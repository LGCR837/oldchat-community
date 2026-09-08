package com.im.oldchat.util;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.util.LruCache;
import android.widget.ImageView;

import com.im.oldchat.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatInlineWarmCache {
    private static final String CACHE_DIR = "img_cache";
    private static final int BASE_INLINE_DP = 180;
    private static final int MIN_INLINE_PX = 160;
    private static final int MAX_RECENT_PREFETCH = 96;
    private static final long PREFETCH_WINDOW_MS = 2500L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object RECENT_LOCK = new Object();
    private static final Object PENDING_LOCK = new Object();
    private static final Map<String, PendingLoad> PENDING_LOADS = new HashMap<String, PendingLoad>();
    private static final Map<String, Long> RECENT_PREFETCH = new LinkedHashMap<String, Long>(MAX_RECENT_PREFETCH, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_RECENT_PREFETCH;
        }
    };

    private ChatInlineWarmCache() {
    }

    public static boolean hasWarmMemory(Context context, String url) {
        CacheSpec spec = buildSpec(context, url);
        if (spec == null || spec.appContext == null) {
            return false;
        }
        LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(spec.appContext);
        return getCachedBitmap(cache, spec) != null;
    }

    public static boolean bindIfReady(Context context, ImageView view, String url) {
        if (view == null) {
            return false;
        }
        CacheSpec spec = buildSpec(resolveContext(context, view), url);
        if (spec == null || spec.appContext == null) {
            return false;
        }
        LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(spec.appContext);
        Bitmap bitmap = getCachedBitmap(cache, spec);
        if (bitmap == null) {
            return false;
        }
        view.setTag(spec.resolvedUrl);
        view.setImageBitmap(bitmap);
        return true;
    }

    public static void load(Context context, final ImageView view, String url, final ImageLoader.ImageLoadListener listener) {
        if (view == null) {
            notifyComplete(listener, null);
            return;
        }
        final CacheSpec spec = buildSpec(resolveContext(context, view), url);
        if (spec == null || spec.appContext == null) {
            view.setTag(null);
            view.setImageResource(R.drawable.bg_image_placeholder);
            notifyComplete(listener, null);
            return;
        }
        final LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(spec.appContext);
        Object currentTag = view.getTag();
        boolean sameTag = spec.resolvedUrl.equals(currentTag);
        view.setTag(spec.resolvedUrl);

        Bitmap cached = getCachedBitmap(cache, spec);
        if (cached != null) {
            view.setImageBitmap(cached);
            notifyComplete(listener, spec.resolvedUrl);
            return;
        }

        if (!sameTag) view.setImageResource(R.drawable.bg_image_placeholder);
        if (!beginPendingLoad(spec, view, listener)) {
            return;
        }

        com.im.oldchat.util.ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                final Bitmap bitmap = loadBitmap(spec);
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        deliverPendingLoad(spec, cache, bitmap);
                    }
                });
            }
        });
    }

    public static void prefetch(Context context, String url) {
        final CacheSpec spec = buildSpec(context, url);
        if (spec == null || spec.appContext == null) {
            return;
        }
        final LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(spec.appContext);
        if (cache.get(spec.inlineKey) != null || cache.get(spec.baseKey) != null) {
            return;
        }
        if (isPending(spec.inlineKey)) {
            return;
        }
        if (!shouldSchedule(spec.baseKey)) {
            return;
        }
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                final Bitmap bitmap = loadBitmap(spec);
                if (bitmap == null) {
                    return;
                }
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        cache.put(spec.inlineKey, bitmap);
                        ImageDiskCacheHint.markPresent(spec.baseKey);
                    }
                });
            }
        });
    }

    private static boolean shouldSchedule(String key) {
        if (key == null || key.length() == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (RECENT_LOCK) {
            Long last = RECENT_PREFETCH.get(key);
            if (last != null && now - last.longValue() < PREFETCH_WINDOW_MS) {
                return false;
            }
            RECENT_PREFETCH.put(key, Long.valueOf(now));
            return true;
        }
    }

    private static CacheSpec buildSpec(Context context, String url) {
        if (context == null || url == null || url.length() == 0) {
            return null;
        }
        Context appContext = context.getApplicationContext();
        String resolved = MediaUrlResolver.resolve(url);
        if (resolved == null || resolved.length() == 0) {
            return null;
        }
        int inlinePx = resolveInlineCachePx(appContext);
        String baseKey = ImageCacheUtil.buildCacheKey(resolved);
        String inlineKey = buildInlineKey(baseKey, inlinePx);
        return new CacheSpec(appContext, resolved, baseKey, inlineKey, inlinePx);
    }

    private static Bitmap loadBitmap(CacheSpec spec) {
        String[] candidates = MediaUrlResolver.resolveCandidates(spec.resolvedUrl);
        for (int i = 0; i < candidates.length; i++) {
            String candidate = candidates[i];
            if (candidate == null || candidate.length() == 0) {
                continue;
            }
            Bitmap loaded = ImageLoaderBitmapSupport.downloadBitmap(spec.appContext, CACHE_DIR, candidate, spec.inlinePx, 0);
            if (loaded != null) {
                return loaded;
            }
        }
        return null;
    }

    private static Bitmap getCachedBitmap(LruCache<String, Bitmap> cache, CacheSpec spec) {
        if (cache == null || spec == null) return null;
        Bitmap bitmap = cache.get(spec.inlineKey);
        if (bitmap != null) return bitmap;
        return cache.get(spec.baseKey);
    }

    private static boolean beginPendingLoad(CacheSpec spec, ImageView view,
                                            ImageLoader.ImageLoadListener listener) {
        if (spec == null) {
            return false;
        }
        synchronized (PENDING_LOCK) {
            PendingLoad pending = PENDING_LOADS.get(spec.inlineKey);
            PendingTarget target = new PendingTarget(view, listener);
            if (pending != null) {
                pending.targets.add(target);
                return false;
            }
            pending = new PendingLoad();
            pending.targets.add(target);
            PENDING_LOADS.put(spec.inlineKey, pending);
            return true;
        }
    }

    private static boolean isPending(String inlineKey) {
        if (inlineKey == null || inlineKey.length() == 0) {
            return false;
        }
        synchronized (PENDING_LOCK) {
            return PENDING_LOADS.containsKey(inlineKey);
        }
    }

    private static void deliverPendingLoad(CacheSpec spec, LruCache<String, Bitmap> cache, Bitmap bitmap) {
        List<PendingTarget> targets = null;
        synchronized (PENDING_LOCK) {
            PendingLoad pending = PENDING_LOADS.remove(spec.inlineKey);
            if (pending != null && !pending.targets.isEmpty()) {
                targets = new ArrayList<PendingTarget>(pending.targets);
            }
        }
        if (bitmap != null) {
            cache.put(spec.inlineKey, bitmap);
            ImageDiskCacheHint.markPresent(spec.baseKey);
        }
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            PendingTarget target = targets.get(i);
            if (target == null) {
                continue;
            }
            if (bitmap != null && target.view != null
                    && spec.resolvedUrl.equals(target.view.getTag())) {
                target.view.setImageBitmap(bitmap);
            }
            notifyComplete(target.listener, spec.resolvedUrl);
        }
    }

    private static void notifyComplete(ImageLoader.ImageLoadListener listener, String url) {
        if (listener != null) listener.onComplete(url);
    }

    private static Context resolveContext(Context context, ImageView view) {
        if (context != null) return context;
        return view != null ? view.getContext() : null;
    }

    private static String buildInlineKey(String baseKey, int inlinePx) {
        return baseKey + "#chat_inline#" + inlinePx;
    }

    private static int resolveInlineCachePx(Context context) {
        int basePx = dpToPx(context, BASE_INLINE_DP);
        int targetPx = basePx + basePx / 3;
        int bucketPx = bucketSize(targetPx);
        int capPx = resolveCapPx(context);
        if (bucketPx > capPx) {
            bucketPx = capPx;
        }
        if (bucketPx < MIN_INLINE_PX) {
            bucketPx = MIN_INLINE_PX;
        }
        return bucketPx;
    }

    private static int bucketSize(int px) {
        if (px <= 192) {
            return 192;
        }
        if (px <= 256) {
            return 256;
        }
        if (px <= 320) {
            return 320;
        }
        if (px <= 384) {
            return 384;
        }
        return 480;
    }

    private static int resolveCapPx(Context context) {
        int memClass = getMemoryClassMb(context);
        if (android.os.Build.VERSION.SDK_INT <= 10) {
            if (memClass > 0 && memClass <= 32) {
                return 256;
            }
            return 320;
        }
        if (memClass > 0 && memClass <= 64) {
            return 320;
        }
        if (memClass > 0 && memClass <= 128) {
            return 384;
        }
        return 480;
    }

    private static int getMemoryClassMb(Context context) {
        if (context == null) return 0;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) return am.getMemoryClass();
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    private static int dpToPx(Context context, int dp) {
        if (context == null) return dp;
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(dp, (int) (dp * density + 0.5f));
    }

    private static final class CacheSpec {
        final Context appContext;
        final String resolvedUrl;
        final String baseKey;
        final String inlineKey;
        final int inlinePx;

        CacheSpec(Context appContext, String resolvedUrl, String baseKey, String inlineKey, int inlinePx) {
            this.appContext = appContext;
            this.resolvedUrl = resolvedUrl;
            this.baseKey = baseKey;
            this.inlineKey = inlineKey;
            this.inlinePx = inlinePx;
        }
    }

    private static final class PendingLoad {
        final List<PendingTarget> targets = new ArrayList<PendingTarget>();
    }

    private static final class PendingTarget {
        final ImageView view;
        final ImageLoader.ImageLoadListener listener;

        PendingTarget(ImageView view, ImageLoader.ImageLoadListener listener) {
            this.view = view;
            this.listener = listener;
        }
    }
}
