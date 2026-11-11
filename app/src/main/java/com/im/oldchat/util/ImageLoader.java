package com.im.oldchat.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.util.LruCache;
import android.widget.ImageView;
import com.im.oldchat.R;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class ImageLoader {
    public interface ImageLoadListener {
        void onComplete(String url);
    }

    public interface ImageLoadResultListener extends ImageLoadListener {
        void onResult(String url, boolean success);
    }

    private static final String CACHE_DIR = "img_cache";
    private static final int AVATAR_MAX_BYTES = 16 * 1024;

    // 使用统一的线程池管理器替代独立的线程池
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object AVATAR_ONCE_GUARD = new Object();
    private static final Object AVATAR_NETWORK_GUARD = new Object();
    private static final Object AVATAR_REQUEST_GUARD = new Object();
    // 图片设置交错：避免同一帧内多张图片同时触发 HWUI 解码
    private static final HashSet<String> AVATAR_FETCHED_ONCE = new HashSet<String>();
    private static final HashMap<String, ArrayList<PendingAvatarTarget>> PENDING_AVATAR_TARGETS =
            new HashMap<String, ArrayList<PendingAvatarTarget>>();
    private static long avatarNetworkBlockedUntilMs;
    private static volatile boolean inlineLoadsPaused;
    private static volatile boolean globalScrollPause = false;

    private static class PendingAvatarTarget {
        final WeakReference<ImageView> viewRef;
        final ImageLoadListener listener;
        final String expectedTag;

        PendingAvatarTarget(ImageView view, ImageLoadListener listener, String expectedTag) {
            this.viewRef = new WeakReference<ImageView>(view);
            this.listener = listener;
            this.expectedTag = expectedTag;
        }
    }

    public static void setInlineLoadsPaused(boolean paused) {
        inlineLoadsPaused = paused;
    }

    public static void setGlobalPauseForScroll(boolean paused) {
        globalScrollPause = paused;
    }

    public static boolean isGlobalScrollPaused() {
        return globalScrollPause;
    }

    public static boolean isInlineLoadsPaused() {
        return inlineLoadsPaused || globalScrollPause;
    }

    public static void blockAvatarNetworkFor(long durationMs) {
        if (durationMs <= 0L) {
            return;
        }
        long until = System.currentTimeMillis() + durationMs;
        synchronized (AVATAR_NETWORK_GUARD) {
            if (until > avatarNetworkBlockedUntilMs) {
                avatarNetworkBlockedUntilMs = until;
            }
        }
    }

    public static boolean isAvatarNetworkBlockedNow() {
        return isAvatarNetworkBlocked();
    }

    public static void load(ImageView view, String url) {
        load(view, url, null);
    }

    public static void loadCachedOnly(ImageView view, String url) {
        loadCachedOnly(view, url, null);
    }

    public static void loadCachedOnly(ImageView view, String url, ImageLoadListener listener) {
        loadInternal(view, url, listener, true);
    }

    public static void load(ImageView view, String url, ImageLoadListener listener) {
        loadInternal(view, url, listener, false);
    }

    private static void loadInternal(final ImageView view, final String url, final ImageLoadListener listener,
                                     boolean cacheOnly) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageResource(R.drawable.bg_image_placeholder);
            notifyComplete(listener, null);
            return;
        }
        String resolved = resolveUrl(url);
        String cacheKey = memoryCacheKey(ImageCacheUtil.buildCacheKey(resolved), "normal");

        Object currentTag = view.getTag();
        boolean sameTag = resolved.equals(currentTag);

        view.setTag(resolved);
        Context ctx = view.getContext();
        Context appContext = ctx != null ? ctx.getApplicationContext() : null;

        LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(appContext);
        if (sameTag) {
            Bitmap cached = cache.get(cacheKey);
            if (cached != null) {
                view.setImageBitmap(cached);
                notifyComplete(listener, resolved);
                return;
            }
        }

        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            view.setImageBitmap(cached);
            notifyComplete(listener, resolved);
            return;
        }

        int maxSize = ImageLoaderBitmapSupport.resolveMaxBitmapSize(appContext);

        if (!sameTag) {
            view.setImageResource(R.drawable.bg_image_placeholder);
        }
        if (cacheOnly || inlineLoadsPaused) {
            loadCachedAsync(view, appContext, resolved, cacheKey, listener, maxSize, 0,
                    true, false, null);
            return;
        }
        loadAsync(view, appContext, resolved, cacheKey, listener, maxSize, 0, true, null);
    }
    public static void loadLarge(ImageView view, String url, ImageLoadListener listener) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageResource(R.drawable.bg_image_placeholder);
            notifyComplete(listener, null);
            return;
        }
        String resolved = resolveUrl(url);
        String cacheKey = memoryCacheKey(ImageCacheUtil.buildCacheKey(resolved), "large");

        Object currentTag = view.getTag();
        boolean sameTag = resolved.equals(currentTag);
        view.setTag(resolved);
        Context ctx = view.getContext();
        Context appContext = ctx != null ? ctx.getApplicationContext() : null;

        if (!sameTag) {
            view.setImageResource(R.drawable.bg_image_placeholder);
        }

        int maxSize = ImageLoaderBitmapSupport.resolveMaxBitmapSize(appContext);
        loadAsync(view, appContext, resolved, cacheKey, listener, maxSize, 0, false, null);
    }

    public static void loadOriginal(ImageView view, String url, ImageLoadListener listener) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageResource(R.drawable.bg_image_placeholder);
            notifyComplete(listener, null);
            return;
        }
        String resolved = resolveUrl(url);
        Object currentTag = view.getTag();
        boolean sameTag = resolved.equals(currentTag);
        view.setTag(resolved);
        Context ctx = view.getContext();
        Context appContext = ctx != null ? ctx.getApplicationContext() : null;
        String cacheKey = memoryCacheKey(ImageCacheUtil.buildCacheKey(resolved), "original");

        if (!sameTag) {
            view.setImageResource(R.drawable.bg_image_placeholder);
        }
        int maxSize = 2048;
        loadAsync(view, appContext, resolved, cacheKey, listener, maxSize, 0, false, null);
    }

    /**
     * 加载本地文件（下载完成的原图缓存等）。本地路径不会被 resolveCandidates 改写，
     * 直接在后台线程按最大 2048px 采样解码，避免大图 OOM。
     */
    public static void loadLocalFile(final ImageView view, final String path, final ImageLoadListener listener) {
        if (view == null || path == null || path.length() == 0) {
            notifyComplete(listener, null, false);
            return;
        }
        final WeakReference<ImageView> viewRef = new WeakReference<ImageView>(view);
        view.setTag(path);
        ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override
            public void run() {
                ImageView targetView = viewRef.get();
                if (targetView == null || !path.equals(targetView.getTag())) {
                    return;
                }
                Context c = targetView.getContext();
                if (c instanceof android.app.Activity && ((android.app.Activity) c).isFinishing()) {
                    return;
                }
                final Bitmap bitmap = ImageLoaderBitmapSupport.decodeSampledBitmap(path, 2048, 0);
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        ImageView v = viewRef.get();
                        if (v == null) {
                            notifyComplete(listener, path, false);
                            return;
                        }
                        Object tag = v.getTag();
                        if (bitmap != null && !bitmap.isRecycled() && path.equals(tag)) {
                            v.setImageBitmap(bitmap);
                        }
                        notifyComplete(listener, path, bitmap != null && !bitmap.isRecycled());
                    }
                });
            }
        });
    }

    public static void loadAvatar(ImageView view, String url) {
        loadAvatar(view, url, null);
    }

    public static void loadAvatar(ImageView view, String url, ImageLoadListener listener) {
        loadAvatarInternal(view, url, listener, false);
    }

    public static void loadAvatarCachedOnly(ImageView view, String url) {
        loadAvatarCachedOnly(view, url, null);
    }

    public static void loadAvatarCachedOnly(ImageView view, String url, ImageLoadListener listener) {
        loadAvatarInternal(view, url, listener, true);
    }

    public static void loadAvatarCacheFirstTryOnce(ImageView view, String url) {
        loadAvatarCacheFirstTryOnce(view, url, null);
    }

    public static void loadAvatarCacheFirstTryOnce(ImageView view, String url, ImageLoadListener listener) {
        loadAvatarInternal(view, url, listener, false, true);
    }

    private static void loadAvatarInternal(ImageView view, String url, ImageLoadListener listener,
                                           boolean cacheOnly) {
        loadAvatarInternal(view, url, listener, cacheOnly, false);
    }

    private static void loadAvatarInternal(ImageView view, String url, ImageLoadListener listener,
                                           boolean cacheOnly, boolean tryNetworkOnce) {
        if (view != null && view.getContext() != null && SettingsPrefs.isHideAvatarEnabled(view.getContext())) {
            view.setTag(null);
            view.setVisibility(android.view.View.GONE);
            notifyComplete(listener, null);
            return;
        }
        if (view != null) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (view instanceof com.im.oldchat.ui.widget.RoundedImageView) {
                // 所有通过头像入口加载的网络/缓存图片统一使用圆形遮罩。
                ((com.im.oldchat.ui.widget.RoundedImageView) view).setCircleCrop(true);
            }
        }
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageResource(R.drawable.ic_avatar_placeholder);
            notifyComplete(listener, null);
            return;
        }
        String resolved = resolveUrl(url);
        String cacheKey = memoryCacheKey(ImageCacheUtil.buildCacheKey(resolved), "avatar");

        Object currentTag = view.getTag();
        boolean sameTag = resolved.equals(currentTag);
        view.setTag(resolved);
        Context ctx = view.getContext();
        Context appContext = ctx != null ? ctx.getApplicationContext() : null;
        LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(appContext);
        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            view.setImageBitmap(cached);
            notifyComplete(listener, resolved);
            return;
        }

        int maxSize = ImageLoaderBitmapSupport.resolveAvatarMaxSize(appContext);

        boolean hasDiskCacheHint = !sameTag && ImageDiskCacheHint.isKnownPresent(cacheKey);
        if (!sameTag && !hasDiskCacheHint) {
            view.setImageResource(R.drawable.ic_avatar_placeholder);
        }
        String avatarRequestKey = buildAvatarRequestKey(cacheKey, cacheOnly,
                tryNetworkOnce && !isAvatarNetworkBlocked());
        if (!enqueueAvatarTarget(avatarRequestKey, view, listener, resolved)) {
            return;
        }
        if (cacheOnly) {
            loadCachedAsync(view, appContext, resolved, cacheKey, listener, maxSize, AVATAR_MAX_BYTES,
                    true, false, avatarRequestKey);
            return;
        }
        if (tryNetworkOnce) {
            boolean allowNetwork = !isAvatarNetworkBlocked();
            loadCachedAsync(view, appContext, resolved, cacheKey, listener, maxSize, AVATAR_MAX_BYTES,
                    true, allowNetwork, avatarRequestKey);
            if (!allowNetwork) {
                scheduleAvatarRetryAfterScroll(view, resolved);
            }
            return;
        }
        loadAsync(view, appContext, resolved, cacheKey, listener, maxSize, AVATAR_MAX_BYTES, true,
                avatarRequestKey);
    }

    /**
     * 滚动期间只查缓存；滚动保护结束后，若 ImageView 尚未被复用，则自动补一次网络加载。
     * 否则首次绑定时写入的 URL tag 会让渲染层误以为头像已经加载完成，永久停留占位图。
     */
    private static void scheduleAvatarRetryAfterScroll(ImageView view, final String resolvedUrl) {
        if (view == null || resolvedUrl == null || resolvedUrl.isEmpty()) {
            return;
        }
        final WeakReference<ImageView> viewRef = new WeakReference<ImageView>(view);
        long delayMs;
        synchronized (AVATAR_NETWORK_GUARD) {
            delayMs = avatarNetworkBlockedUntilMs - System.currentTimeMillis() + 80L;
        }
        if (delayMs < 80L) {
            delayMs = 80L;
        }
        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                ImageView target = viewRef.get();
                if (target == null) {
                    return;
                }
                Object tag = target.getTag();
                if (!resolvedUrl.equals(tag)) {
                    return;
                }
                if (isAvatarNetworkBlocked()) {
                    scheduleAvatarRetryAfterScroll(target, resolvedUrl);
                    return;
                }
                loadAvatarCacheFirstTryOnce(target, resolvedUrl, null);
            }
        }, delayMs);
    }

    public static void prefetch(Context context, String url) {
        if (context == null || url == null || url.isEmpty() || SettingsPrefs.isHideAvatarEnabled(context)) {
            return;
        }
        String resolved = resolveUrl(url);
        String cacheKey = memoryCacheKey(ImageCacheUtil.buildCacheKey(resolved), "avatar");
        if (isCached(context, resolved)) {
            return;
        }
        prefetchAsync(context.getApplicationContext(), resolved, cacheKey,
                ImageLoaderBitmapSupport.resolveAvatarMaxSize(context), AVATAR_MAX_BYTES);
    }

    public static void prefetchInline(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return;
        }
        String resolved = resolveUrl(url);
        Context appContext = context.getApplicationContext();
        String cacheKey = memoryCacheKey(ImageCacheUtil.buildCacheKey(resolved), "normal");
        LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(appContext);
        if (cache.get(cacheKey) != null) {
            return;
        }
        prefetchAsync(appContext, resolved, cacheKey,
                ImageLoaderBitmapSupport.resolveMaxBitmapSize(appContext), 0);
    }

    /**
     * 检查是否已缓存 - 优化版：仅检查内存缓存，不执行主线程IO
     */
    public static boolean isCached(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return false;
        }
        String resolved = resolveUrl(url);
        String sourceKey = ImageCacheUtil.buildCacheKey(resolved);
        Context appContext = context.getApplicationContext();
        LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(appContext);
        if (hasAnyMemoryVariant(cache, sourceKey)) {
            return true;
        }
        if (ImageDiskCacheHint.isKnownPresent(memoryCacheKey(sourceKey, "normal"))
                || ImageDiskCacheHint.isKnownPresent(memoryCacheKey(sourceKey, "avatar"))
                || ImageDiskCacheHint.isKnownPresent(memoryCacheKey(sourceKey, "large"))
                || ImageDiskCacheHint.isKnownPresent(memoryCacheKey(sourceKey, "original"))) {
            return true;
        }
        // 不执行文件系统检查，避免主线程IO阻塞
        return false;
    }

    public static boolean hasMemoryCache(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return false;
        }
        String resolved = resolveUrl(url);
        String sourceKey = ImageCacheUtil.buildCacheKey(resolved);
        Context appContext = context.getApplicationContext();
        return hasAnyMemoryVariant(ImageLoaderBitmapSupport.getCache(appContext), sourceKey);
    }

    private static boolean hasAnyMemoryVariant(LruCache<String, Bitmap> cache, String sourceKey) {
        return cache.get(memoryCacheKey(sourceKey, "normal")) != null
                || cache.get(memoryCacheKey(sourceKey, "avatar")) != null
                || cache.get(memoryCacheKey(sourceKey, "large")) != null
                || cache.get(memoryCacheKey(sourceKey, "original")) != null;
    }

    private static String memoryCacheKey(String sourceKey, String purpose) {
        return (sourceKey == null ? "" : sourceKey) + "|" + purpose;
    }

    private static String resolveUrl(String url) {
        return MediaUrlResolver.resolve(url);
    }

    private static void loadAsync(final ImageView view, final Context context,
                                   final String url, final String cacheKey,
                                   final ImageLoadListener listener,
                                   final int maxSize, final int maxBytes,
                                   final boolean cacheInMemory,
                                   final String avatarRequestKey) {
        final WeakReference<ImageView> viewRef = new WeakReference<ImageView>(view);
        // 首先检查是否已经缓存
        Bitmap cached = ImageLoaderBitmapSupport.getCache(context.getApplicationContext()).get(cacheKey);
        if (cached != null) {
            final Bitmap finalBitmap = cached;
            MAIN_HANDLER.post(new Runnable() {

                public void run() {
                    ImageView targetView = viewRef.get();
                    if (avatarRequestKey != null) {
                        deliverPendingAvatarTargets(avatarRequestKey, finalBitmap, url);
                    } else {
                        if (targetView != null && finalBitmap != null && url.equals(targetView.getTag()) &&
                                !targetView.getTag().toString().isEmpty()) {
                            targetView.setImageBitmap(finalBitmap);
                        }
                        notifyComplete(listener, url, finalBitmap != null);
                    }
                }
            });
            return;
        }



        // 暂停时不做忙等待，由 setInlineLoadsPaused(false) 触发恢复

        // 使用统一线程池执行
        ThreadPoolManager.getInstance().executeIo(new Runnable() {

            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                String[] candidates = MediaUrlResolver.resolveCandidates(url);
                Bitmap loaded = null;
                String loadedFrom = url;
                for (int i = 0; i < candidates.length; i++) {
                    String candidate = candidates[i];
                    if (candidate == null || candidate.length() == 0) {
                        continue;
                    }
                    if (globalScrollPause) {
                        break;
                    }
                    ImageView targetView = viewRef.get();
                    if (avatarRequestKey == null) {
                        if (targetView == null || !url.equals(targetView.getTag())) {
                            break;
                        }
                        Context c = targetView.getContext();
                        if (c instanceof android.app.Activity && ((android.app.Activity) c).isFinishing()) {
                            break;
                        }
                    }
                    loaded = ImageLoaderBitmapSupport.downloadBitmap(context, CACHE_DIR, candidate, maxSize, maxBytes);
                    if (loaded != null) {
                        loadedFrom = candidate;
                        break;
                    }
                }
                final Bitmap bitmap = loaded;
                // 延迟一帧设置图片，避免当前帧内多张图片同时触发 HWUI 纹理解码
                MAIN_HANDLER.post(new Runnable() {

                    public void run() {
                        ImageView targetView = viewRef.get();
                        if (bitmap != null) {
                            if (cacheInMemory) {
                                LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(context);
                                cache.put(cacheKey, bitmap);
                            }
                            ImageDiskCacheHint.markPresent(cacheKey);
                            if (avatarRequestKey != null) {
                                // 即使发起请求的 View 已被复用，也必须分发并清理合并队列；
                                // 否则同 URL 后续目标会永久停在 pending，进而反复触发上层刷新。
                                deliverPendingAvatarTargets(avatarRequestKey, bitmap, url);
                            } else if (targetView != null && url.equals(targetView.getTag())
                                    && !targetView.getTag().toString().isEmpty()) {
                                targetView.setImageBitmap(bitmap);
                            }
                        } else if (avatarRequestKey != null) {
                            // 下载失败同样释放等待队列，允许后续正常重试。
                            deliverPendingAvatarTargets(avatarRequestKey, null, url);
                        }
                        if (avatarRequestKey == null) {
                            notifyComplete(listener, url, bitmap != null);
                        }
                    }
                });
            }
        });
    }

    private static void prefetchAsync(final Context context, final String url,
                                      final String cacheKey,
                                      final int maxSize, final int maxBytes) {
        // 使用统一线程池的低优先级任务
        ThreadPoolManager.getInstance().execute(new Runnable() {

            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                String[] candidates = MediaUrlResolver.resolveCandidates(url);
                Bitmap loaded = null;
                for (int i = 0; i < candidates.length; i++) {
                    String candidate = candidates[i];
                    if (candidate == null || candidate.length() == 0) {
                        continue;
                    }
                    loaded = ImageLoaderBitmapSupport.downloadBitmap(context, CACHE_DIR, candidate, maxSize, maxBytes);
                    if (loaded != null) {
                        break;
                    }
                }
                final Bitmap bitmap = loaded;
                if (bitmap != null) {
                    MAIN_HANDLER.post(new Runnable() {

                        public void run() {
                            LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(context);
                            cache.put(cacheKey, bitmap);
                            ImageDiskCacheHint.markPresent(cacheKey);
                        }
                    });
                }
            }
        });
    }

    private static void loadCachedAsync(final ImageView view, final Context context,
                                        final String url, final String cacheKey,
                                        final ImageLoadListener listener,
                                        final int maxSize, final int maxBytes,
                                        final boolean cacheInMemory,
                                        final boolean allowNetworkOnMiss,
                                        final String avatarRequestKey) {
        if (context == null) {
            if (avatarRequestKey != null) {
                deliverPendingAvatarTargets(avatarRequestKey, null, url);
                return;
            }
            notifyComplete(listener, url, false);
            return;
        }

        // 使用统一线程池执行
        ThreadPoolManager.getInstance().executeIo(new Runnable() {

            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                Bitmap loaded = ImageLoaderBitmapSupport.loadCachedBitmap(context, CACHE_DIR, url, maxSize, maxBytes);
                if (loaded == null && allowNetworkOnMiss && markAvatarFetchAttemptedIfNeeded(cacheKey)) {
                    String[] candidates = MediaUrlResolver.resolveCandidates(url);
                    for (int i = 0; i < candidates.length; i++) {
                        String candidate = candidates[i];
                        if (candidate == null || candidate.length() == 0) {
                            continue;
                        }
                        loaded = ImageLoaderBitmapSupport.downloadBitmap(context, CACHE_DIR, candidate, maxSize, maxBytes);
                        if (loaded != null) {
                            break;
                        }
                    }
                }
                final Bitmap bitmap = loaded;
                MAIN_HANDLER.post(new Runnable() {

                    public void run() {
                        if (bitmap != null) {
                            if (cacheInMemory) {
                                LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(context);
                                cache.put(cacheKey, bitmap);
                            }
                            ImageDiskCacheHint.markPresent(cacheKey);
                        }
                        if (avatarRequestKey != null) {
                            deliverPendingAvatarTargets(avatarRequestKey, bitmap, url);
                            return;
                        }
                        if (bitmap != null && url.equals(view.getTag())) {
                            view.setImageBitmap(bitmap);
                        }
                        notifyComplete(listener, url, bitmap != null);
                    }
                });
            }
        });
    }

    private static String buildAvatarRequestKey(String cacheKey, boolean cacheOnly, boolean allowNetworkOnMiss) {
        StringBuilder builder = new StringBuilder();
        builder.append(cacheKey == null ? "" : cacheKey);
        builder.append('|');
        builder.append(cacheOnly ? 'C' : 'A');
        builder.append('|');
        builder.append(allowNetworkOnMiss ? 'N' : 'M');
        return builder.toString();
    }

    private static boolean enqueueAvatarTarget(String requestKey, ImageView view,
                                               ImageLoadListener listener, String expectedTag) {
        synchronized (AVATAR_REQUEST_GUARD) {
            ArrayList<PendingAvatarTarget> pending = PENDING_AVATAR_TARGETS.get(requestKey);
            if (pending == null) {
                pending = new ArrayList<PendingAvatarTarget>();
                PENDING_AVATAR_TARGETS.put(requestKey, pending);
                pending.add(new PendingAvatarTarget(view, listener, expectedTag));
                return true;
            }
            pending.add(new PendingAvatarTarget(view, listener, expectedTag));
            return false;
        }
    }

    private static void deliverPendingAvatarTargets(String requestKey, Bitmap bitmap, String resolvedUrl) {
        ArrayList<PendingAvatarTarget> pending;
        synchronized (AVATAR_REQUEST_GUARD) {
            pending = PENDING_AVATAR_TARGETS.remove(requestKey);
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            PendingAvatarTarget target = pending.get(i);
            ImageView imageView = target == null ? null : target.viewRef.get();
            if (imageView != null && target.expectedTag.equals(imageView.getTag()) && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
            if (target != null) {
                notifyComplete(target.listener, resolvedUrl, bitmap != null);
            }
        }
    }
    private static void notifyComplete(ImageLoadListener listener, String url) {
        notifyComplete(listener, url, url != null && url.length() > 0);
    }

    private static void notifyComplete(ImageLoadListener listener, String url, boolean success) {
        if (listener != null) {
            listener.onComplete(url);
            if (listener instanceof ImageLoadResultListener) {
                ((ImageLoadResultListener) listener).onResult(url, success);
            }
        }
    }

    public static void clearDiskCache(Context context) {
        if (context == null) {
            return;
        }
        File base = new File(context.getCacheDir(), CACHE_DIR);
        ImageCacheUtil.deleteRecursive(base);
        ImageDiskCacheHint.clear();
        LruCache<String, Bitmap> cache = ImageLoaderBitmapSupport.getCache(context.getApplicationContext());
        if (cache != null) {
            cache.evictAll();
        }
        synchronized (AVATAR_ONCE_GUARD) {
            AVATAR_FETCHED_ONCE.clear();
        }
    }

    public static void clearMemoryCache() {
        ImageLoaderBitmapSupport.clearMemoryCache();
        synchronized (AVATAR_ONCE_GUARD) {
            AVATAR_FETCHED_ONCE.clear();
        }
    }

    public static void trimMemory(int level) {
        ImageLoaderBitmapSupport.trimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            synchronized (AVATAR_ONCE_GUARD) {
                AVATAR_FETCHED_ONCE.clear();
            }
        }
    }

    private static boolean markAvatarFetchAttemptedIfNeeded(String cacheKey) {
        synchronized (AVATAR_ONCE_GUARD) {
            if (AVATAR_FETCHED_ONCE.contains(cacheKey)) {
                return false;
            }
            AVATAR_FETCHED_ONCE.add(cacheKey);
            return true;
        }
    }
    
    // 滚动时强制尝试网络加载头像（不检查 AVATAR_FETCHED_ONCE）
    public static void retryAvatarLoad(ImageView view, String url) {
        if (view == null || url == null || url.isEmpty()) return;
        String resolved = resolveUrl(url);
        Context ctx = view.getContext();
        if (ctx == null) return;
        Context appContext = ctx.getApplicationContext();
        String cacheKey = memoryCacheKey(ImageCacheUtil.buildCacheKey(resolved), "avatar");
        loadAsync(view, appContext, resolved, cacheKey, null,
                ImageLoaderBitmapSupport.resolveAvatarMaxSize(appContext),
                AVATAR_MAX_BYTES, true, null);
    }

    private static boolean isAvatarNetworkBlocked() {
        synchronized (AVATAR_NETWORK_GUARD) {
            return avatarNetworkBlockedUntilMs > System.currentTimeMillis();
        }
    }
}
