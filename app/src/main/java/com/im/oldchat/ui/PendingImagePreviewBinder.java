package com.im.oldchat.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.util.LruCache;
import android.widget.ImageView;

import com.im.oldchat.R;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PendingImagePreviewBinder {
    private static final int MAX_PREVIEW_PX = 720;
    private static final int MAX_CACHE_BYTES = 3 * 1024 * 1024;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object REQUEST_LOCK = new Object();
    private static final HashMap<String, ArrayList<WeakReference<ImageView>>> WAITERS =
            new HashMap<String, ArrayList<WeakReference<ImageView>>>();
    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return bitmapSize(value);
        }
    };

    private PendingImagePreviewBinder() {
    }

    static boolean bind(ImageView imageView, String previewUri) {
        if (imageView == null || previewUri == null || previewUri.length() == 0) {
            return false;
        }
        Context context = imageView.getContext();
        Context appContext = context == null ? null : context.getApplicationContext();
        ContentResolver resolver = appContext == null ? null : appContext.getContentResolver();
        if (resolver == null) {
            return false;
        }
        String requestKey = buildRequestKey(previewUri);
        Object currentTag = imageView.getTag();
        boolean sameTag = requestKey.equals(currentTag);
        imageView.setTag(requestKey);
        Bitmap bitmap = BITMAP_CACHE.get(requestKey);
        if (bitmap != null && !bitmap.isRecycled()) {
            imageView.setImageBitmap(bitmap);
            return true;
        }
        if (!sameTag) {
            imageView.setImageResource(R.drawable.bg_image_placeholder);
        }
        enqueueDecode(imageView, resolver, previewUri, requestKey);
        return true;
    }

    private static void enqueueDecode(ImageView imageView, final ContentResolver resolver,
                                      final String previewUri, final String requestKey) {
        boolean shouldStart = false;
        synchronized (REQUEST_LOCK) {
            ArrayList<WeakReference<ImageView>> waiters = WAITERS.get(requestKey);
            if (waiters == null) {
                waiters = new ArrayList<WeakReference<ImageView>>();
                WAITERS.put(requestKey, waiters);
                shouldStart = true;
            }
            addWaiter(waiters, imageView);
        }
        if (!shouldStart) {
            return;
        }
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeCompute(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                final Bitmap bitmap = decodeSampledBitmap(resolver, previewUri, MAX_PREVIEW_PX);
                if (bitmap != null) {
                    BITMAP_CACHE.put(requestKey, bitmap);
                }
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        deliverResult(requestKey, bitmap);
                    }
                });
            }
        });
    }

    private static void addWaiter(ArrayList<WeakReference<ImageView>> waiters, ImageView imageView) {
        for (int i = waiters.size() - 1; i >= 0; i--) {
            ImageView existing = waiters.get(i).get();
            if (existing == null) {
                waiters.remove(i);
            } else if (existing == imageView) {
                return;
            }
        }
        waiters.add(new WeakReference<ImageView>(imageView));
    }

    private static void deliverResult(String requestKey, Bitmap bitmap) {
        ArrayList<WeakReference<ImageView>> waiters;
        synchronized (REQUEST_LOCK) {
            waiters = WAITERS.remove(requestKey);
        }
        if (waiters == null) {
            return;
        }
        for (int i = 0; i < waiters.size(); i++) {
            ImageView imageView = waiters.get(i).get();
            if (imageView == null) {
                continue;
            }
            Object currentTag = imageView.getTag();
            if (!requestKey.equals(currentTag)) {
                continue;
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                imageView.setImageBitmap(bitmap);
                applyAdaptiveSize(imageView, bitmap.getWidth(), bitmap.getHeight());
            } else {
                imageView.setImageResource(R.drawable.bg_image_placeholder);
            }
        }
    }

    /** 本地预览图自适应：横图撑宽、竖图撑高，保持原始宽高比（与网络图一致）。 */
    private static void applyAdaptiveSize(ImageView imageView, int w, int h) {
        if (imageView == null || w <= 0 || h <= 0) {
            return;
        }
        float density = imageView.getResources().getDisplayMetrics().density;
        int base = Math.round(180f * density);
        int maxW = Math.round(240f * density);
        int maxH = Math.round(300f * density);
        int min = Math.round(120f * density);
        float ratio = w / (float) h;
        int targetW;
        int targetH;
        if (ratio >= 1.0f) {
            targetW = ratio > 1.4f ? maxW : base;
            targetH = Math.round(targetW / ratio);
        } else {
            targetH = ratio < 0.7f ? maxH : base;
            targetW = Math.round(targetH * ratio);
        }
        if (targetW < min) targetW = min;
        if (targetH < min) targetH = min;
        if (targetW > maxW) targetW = maxW;
        if (targetH > maxH) targetH = maxH;
        android.view.ViewGroup.LayoutParams lp = imageView.getLayoutParams();
        if (lp == null || (lp.width == targetW && lp.height == targetH)) {
            return;
        }
        lp.width = targetW;
        lp.height = targetH;
        imageView.setLayoutParams(lp);
    }

    private static String buildRequestKey(String previewUri) {
        return "local:" + previewUri;
    }

    private static Bitmap decodeSampledBitmap(ContentResolver resolver, String uriText, int maxSize) {
        InputStream boundsStream = null;
        InputStream decodeStream = null;
        try {
            Uri uri = Uri.parse(uriText);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            boundsStream = resolver.openInputStream(uri);
            if (boundsStream == null) {
                return null;
            }
            BitmapFactory.decodeStream(boundsStream, null, bounds);
            closeQuietly(boundsStream);
            boundsStream = null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(bounds, maxSize);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            decodeStream = resolver.openInputStream(uri);
            if (decodeStream == null) {
                return null;
            }
            return BitmapFactory.decodeStream(decodeStream, null, options);
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(boundsStream);
            closeQuietly(decodeStream);
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int maxSize) {
        int width = options.outWidth;
        int height = options.outHeight;
        int sample = 1;
        while ((width / sample) > maxSize || (height / sample) > maxSize) {
            sample *= 2;
        }
        return sample < 1 ? 1 : sample;
    }

    private static int bitmapSize(Bitmap bitmap) {
        if (bitmap == null) {
            return 0;
        }
        return bitmap.getRowBytes() * bitmap.getHeight();
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }

    static void clearMemoryCache() {
        synchronized (REQUEST_LOCK) {
            WAITERS.clear();
        }
        BITMAP_CACHE.evictAll();
    }

    static void trimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            clearMemoryCache();
            return;
        }
        int maxSize = BITMAP_CACHE.maxSize();
        if (maxSize <= 0) {
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            BITMAP_CACHE.trimToSize(Math.max(1, maxSize / 8));
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            BITMAP_CACHE.trimToSize(Math.max(1, maxSize / 4));
            return;
        }
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            BITMAP_CACHE.trimToSize(Math.max(1, maxSize / 2));
        }
    }
}
