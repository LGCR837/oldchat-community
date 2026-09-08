package com.im.oldchat.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

public class ImageCompressor {
    public static final long DEFAULT_MAX_BYTES = 1024 * 1024;

    public static File compress(Context context, Uri uri, int maxSize, int quality, boolean usePng) {
        if (context == null || uri == null) {
            return null;
        }
        File dir = new File(context.getCacheDir(), "upload_cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String ext = usePng ? "png" : "jpg";
        File out = new File(dir, "img_" + System.currentTimeMillis() + "." + ext);
        if (compressToFile(context, uri, out, maxSize, quality, usePng)) {
            return out;
        }
        return null;
    }

    public static File compressToTarget(Context context, Uri uri, int maxSize, int quality,
                                        boolean usePng, long maxBytes) {
        if (context == null || uri == null) {
            return null;
        }
        File dir = new File(context.getCacheDir(), "upload_cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String ext = usePng ? "png" : "jpg";
        File out = new File(dir, "img_" + System.currentTimeMillis() + "." + ext);
        if (!compressToTargetFile(context, uri, out, maxSize, quality, usePng, maxBytes)) {
            return null;
        }
        return out;
    }

    public static boolean compressToFile(Context context, Uri uri, File outFile, int maxSize,
                                         int quality, boolean usePng) {
        if (context == null || uri == null || outFile == null) {
            return false;
        }
        Bitmap bitmap = decodeBitmap(context, uri, maxSize, usePng);
        if (bitmap == null) {
            return false;
        }
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(outFile);
            Bitmap.CompressFormat format = usePng ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            int safeQuality = Math.max(75, Math.min(quality, 100));
            boolean ok = bitmap.compress(format, safeQuality, output);
            output.flush();
            bitmap.recycle();
            return ok;
        } catch (Exception e) {
            bitmap.recycle();
            return false;
        } finally {
            if (output != null) {
                try { output.close(); } catch (Exception e) {}
            }
        }
    }

    public static boolean compressToTargetFile(Context context, Uri uri, File outFile, int maxSize,
                                               int quality, boolean usePng, long maxBytes) {
        if (!compressToFile(context, uri, outFile, maxSize, quality, usePng)) {
            return false;
        }
        if (maxBytes <= 0) {
            return true;
        }
        long size = outFile.length();
        if (size <= maxBytes) {
            return true;
        }
        int targetQuality = Math.max(75, Math.min(quality, 100));
        int targetSize = maxSize;
        int minSize = usePng ? 128 : 256;
        for (int i = 0; i < 8 && size > maxBytes; i++) {
            if (!usePng && targetQuality > 75) {
                targetQuality = Math.max(75, targetQuality - 5);
            } else if (targetSize > minSize) {
                targetSize = Math.max(minSize, (int) (targetSize * 0.90f));
            } else {
                break;
            }
            if (!compressToFile(context, uri, outFile, targetSize, targetQuality, usePng)) {
                break;
            }
            size = outFile.length();
        }
        return outFile.length() > 0;
    }

    private static Bitmap decodeBitmap(Context context, Uri uri, int maxSize, boolean usePng) {
        BitmapFactory.Options bound = new BitmapFactory.Options();
        bound.inJustDecodeBounds = true;
        decodeBounds(context, uri, bound);
        int sample = calculateInSampleSize(bound, maxSize);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = usePng ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        InputStream input = null;
        try {
            input = openStream(context, uri);
            if (input == null) {
                return null;
            }
            Bitmap bitmap = null;
            try {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            } catch (OutOfMemoryError oom) {
                options.inSampleSize = sample * 2;
                try { input.close(); } catch (Exception ignored) {}
                input = openStream(context, uri);
                if (input == null) {
                    return null;
                }
                try {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                } catch (OutOfMemoryError oom2) {
                    return null;
                }
            }
            if (bitmap == null) {
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int max = Math.max(width, height);
            if (maxSize > 0 && max > maxSize) {
                float ratio = maxSize / (float) max;
                int targetW = Math.max(1, Math.round(width * ratio));
                int targetH = Math.max(1, Math.round(height * ratio));
                try {
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
                    if (scaled != bitmap) {
                        bitmap.recycle();
                    }
                    return scaled;
                } catch (OutOfMemoryError oom) {
                    return bitmap;
                }
            }
            return bitmap;
        } catch (Throwable e) {
            return null;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Exception e) {}
            }
        }
    }

    private static void decodeBounds(Context context, Uri uri, BitmapFactory.Options options) {
        InputStream input = null;
        try {
            input = openStream(context, uri);
            if (input != null) {
                BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) {
        } finally {
            if (input != null) {
                try { input.close(); } catch (Exception e) {}
            }
        }
    }

    private static InputStream openStream(Context context, Uri uri) {
        try {
            ContentResolver resolver = context.getContentResolver();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return new BufferedInputStream(new FileInputStream(new File(uri.getPath())));
            }
            return new BufferedInputStream(resolver.openInputStream(uri));
        } catch (Exception e) {
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int maxSize) {
        int height = options.outHeight;
        int width = options.outWidth;
        int max = Math.max(width, height);
        int sample = 1;
        while (maxSize > 0 && max / sample > maxSize) {
            sample *= 2;
        }
        return sample;
    }
}
