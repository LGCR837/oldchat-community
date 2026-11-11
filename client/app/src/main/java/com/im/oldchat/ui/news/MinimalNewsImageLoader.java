package com.im.oldchat.ui.news;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.im.oldchat.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

public final class MinimalNewsImageLoader {
    private static final int MAX_CACHE_BYTES = Math.max(512 * 1024, Math.min(1024 * 1024, (int) (Runtime.getRuntime().maxMemory() / 16)));
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getRowBytes() * value.getHeight();
        }
    };

    public static void clearCache() {
        try {
            CACHE.evictAll();
        } catch (Throwable ignored) {
        }
    }

    private MinimalNewsImageLoader() {
    }

    public static void load(ImageView imageView, String imageUrl) {
        if (imageView == null) {
            return;
        }
        String url = imageUrl == null ? "" : imageUrl.trim();
        imageView.setTag(url);
        if (url.length() == 0) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            return;
        }
        imageView.setVisibility(View.VISIBLE);
        Bitmap cached = CACHE.get(url);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageResource(R.drawable.ic_discover_report);
        LoadTask task = new LoadTask(imageView, url);
        executeTask(task);
    }

    private static void executeTask(AsyncTask<Void, Void, Bitmap> task) {
        com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task, (Void[]) null);
    }

    private static class LoadTask extends AsyncTask<Void, Void, Bitmap> {
        private final WeakReference<ImageView> imageRef;
        private final String url;

        LoadTask(ImageView imageView, String url) {
            this.imageRef = new WeakReference<ImageView>(imageView);
            this.url = url;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            try {
                byte[] data = downloadBytes(url);
                if (data == null || data.length == 0) {
                    return null;
                }
                Bitmap bitmap = decodeSampled(data, 240);
                if (bitmap == null) {
                    return null;
                }
                Bitmap gray = toGrayscale(bitmap);
                if (gray != bitmap && bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                if (gray != null) {
                    CACHE.put(url, gray);
                }
                return gray;
            } catch (Throwable ignore) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            ImageView imageView = imageRef.get();
            if (imageView == null) {
                return;
            }
            Object tag = imageView.getTag();
            if (tag == null || !url.equals(String.valueOf(tag))) {
                return;
            }
            if (bitmap == null || bitmap.isRecycled()) {
                imageView.setImageDrawable(null);
                imageView.setVisibility(View.GONE);
            } else {
                imageView.setVisibility(View.VISIBLE);
                imageView.setImageBitmap(bitmap);
            }
        }
    }

    private static byte[] downloadBytes(String url) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
            conn.setRequestProperty("Accept", "image/*,*/*");
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            in = conn.getInputStream();
            out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                if (out.size() > 4 * 1024 * 1024) {
                    return null;
                }
            }
            return out.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignore) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Bitmap decodeSampled(byte[] data, int maxEdge) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while ((bounds.outWidth / sample) > maxEdge || (bounds.outHeight / sample) > maxEdge) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = sample;
            try {
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            } catch (OutOfMemoryError oom) {
                opts.inSampleSize = sample * 2;
                try {
                    return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                } catch (OutOfMemoryError ignored) {
                    return null;
                }
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap toGrayscale(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        try {
            Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            canvas.drawBitmap(source, 0f, 0f, paint);
            return output;
        } catch (OutOfMemoryError oom) {
            return source;
        } catch (Throwable e) {
            return source;
        }
    }
}
