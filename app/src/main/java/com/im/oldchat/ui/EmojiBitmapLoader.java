package com.im.oldchat.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.util.LruCache;
import android.widget.ImageView;

import com.im.oldchat.R;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EmojiBitmapLoader {
    private static final Object EMOJI_CACHE_LOCK = new Object();
    private static final ExecutorService LOAD_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static LruCache<String, Bitmap> EMOJI_CACHE;

    private EmojiBitmapLoader() {
    }

    static void load(final Context context, final ImageView target, final String path, final int targetPx) {
        if (target == null) {
            return;
        }
        if (path == null || path.length() == 0) {
            target.setTag(null);
            target.setImageResource(R.drawable.ic_avatar_placeholder);
            return;
        }
        target.setTag(path);
        LruCache<String, Bitmap> cache = getEmojiCache(context);
        Bitmap cached = cache.get(path);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(R.drawable.ic_avatar_placeholder);
        loadAsync(target, path, targetPx, cache);
    }

    private static void loadAsync(ImageView target, String path, int targetPx, LruCache<String, Bitmap> cache) {
        final WeakReference<ImageView> viewRef = new WeakReference<ImageView>(target);
        final String key = path;
        final int sizePx = targetPx;
        final LruCache<String, Bitmap> emojiCache = cache;
        LOAD_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                final Bitmap bitmap = decodeEmojiBitmap(key, sizePx);
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        ImageView view = viewRef.get();
                        if (view == null) {
                            return;
                        }
                        Object tag = view.getTag();
                        if (tag == null || !key.equals(tag)) {
                            return;
                        }
                        if (bitmap != null) {
                            emojiCache.put(key, bitmap);
                            view.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        });
    }

    private static Bitmap decodeEmojiBitmap(String path, int targetPx) {
        if (path == null || path.length() == 0) {
            return null;
        }
        int target = targetPx > 0 ? targetPx : 72;
        BitmapFactory.Options bound = new BitmapFactory.Options();
        bound.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bound);
        int max = Math.max(bound.outWidth, bound.outHeight);
        int sample = 1;
        while (target > 0 && max / sample > target) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static LruCache<String, Bitmap> getEmojiCache(Context context) {
        if (EMOJI_CACHE != null) {
            return EMOJI_CACHE;
        }
        synchronized (EMOJI_CACHE_LOCK) {
            if (EMOJI_CACHE == null) {
                int cacheKb = resolveEmojiCacheKb(context);
                EMOJI_CACHE = new LruCache<String, Bitmap>(cacheKb) {
                    @Override
                    protected int sizeOf(String key, Bitmap value) {
                        return getBitmapSizeKb(value);
                    }
                };
            }
        }
        return EMOJI_CACHE;
    }

    private static int resolveEmojiCacheKb(Context context) {
        int memClass = 0;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                memClass = am.getMemoryClass();
            }
        } catch (Exception e) {
            memClass = 0;
        }
        int sizeKb = memClass > 0 ? memClass * 1024 / 16 : 2048;
        int cap = memClass > 0 && memClass <= 128 ? 2048 : 4096;
        if (sizeKb > cap) {
            sizeKb = cap;
        }
        if (sizeKb < 1024) {
            sizeKb = 1024;
        }
        return sizeKb;
    }

    private static int getBitmapSizeKb(Bitmap value) {
        if (value == null) {
            return 0;
        }
        int bytes;
        if (android.os.Build.VERSION.SDK_INT >= 12) {
            bytes = value.getByteCount();
        } else {
            bytes = value.getRowBytes() * value.getHeight();
        }
        return Math.max(1, bytes / 1024);
    }

    static void clearMemoryCache() {
        synchronized (EMOJI_CACHE_LOCK) {
            if (EMOJI_CACHE != null) {
                EMOJI_CACHE.evictAll();
            }
        }
    }

    static void trimMemory(int level) {
        LruCache<String, Bitmap> cache = EMOJI_CACHE;
        if (cache == null) {
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            cache.evictAll();
            return;
        }
        int maxSize = cache.maxSize();
        if (maxSize <= 0) {
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            cache.trimToSize(Math.max(1, maxSize / 8));
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            cache.trimToSize(Math.max(1, maxSize / 4));
            return;
        }
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            cache.trimToSize(Math.max(1, maxSize / 2));
        }
    }

}
