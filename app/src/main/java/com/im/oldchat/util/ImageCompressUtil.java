package com.im.oldchat.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 图片压缩工具：解码时先采样降尺寸，再循环压缩，最后必要时二次缩放到目标体积。
 */
public class ImageCompressUtil {
    private ImageCompressUtil() {
    }

    public static byte[] compressToBytes(ContentResolver resolver, Uri uri, int maxSizePx, int maxBytes) throws Exception {
        Bitmap bitmap = decodeSampledBitmap(resolver, uri, maxSizePx, maxSizePx);
        if (bitmap == null) {
            throw new Exception("decode failed");
        }
        try {
            return compressBitmap(bitmap, maxBytes, maxSizePx);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    /**
     * 压缩位图到指定体积上限。
     * @param maxSizePx 位图最大边长（解码/缩放后不应超过），用于首轮缩放到目标尺寸。
     */
    private static byte[] compressBitmap(Bitmap bitmap, int maxBytes, int maxSizePx) {
        // 解码后仍可能超目标（inSampleSize 是 2 的幂），先缩放到目标边长内，并回收原图。
        Bitmap working = ensureMaxSize(bitmap, maxSizePx);
        if (working == null) {
            return new byte[0];
        }
        try {
            int quality = 95;
            byte[] data = compressOnce(working, quality);
            for (int i = 0; i < 6 && data.length > maxBytes && quality > 85; i++) {
                quality -= 2;
                data = compressOnce(working, quality);
            }

            int scaleAttempts = 0;
            while (data.length > maxBytes && scaleAttempts < 6) {
                int w = Math.max(1, (int) (working.getWidth() * 0.90f));
                int h = Math.max(1, (int) (working.getHeight() * 0.90f));
                // 只保留新缩放位图，旧位图立即回收，避免大图叠加 OOM。
                Bitmap scaled = null;
                try {
                    scaled = Bitmap.createScaledBitmap(working, w, h, true);
                } catch (OutOfMemoryError oom) {
                    break;
                }
                if (scaled == null) {
                    break;
                }
                if (working != bitmap) {
                    working.recycle();
                }
                working = scaled;
                quality = 90;
                data = compressOnce(working, quality);
                for (int i = 0; i < 4 && data.length > maxBytes && quality > 80; i++) {
                    quality -= 2;
                    data = compressOnce(working, quality);
                }
                scaleAttempts++;
            }
            return data;
        } finally {
            if (working != bitmap && working != null && !working.isRecycled()) {
                working.recycle();
            }
        }
    }

    /** 若位图任一长边超过 maxSizePx，缩放到目标内并回收原图；否则原样返回。 */
    private static Bitmap ensureMaxSize(Bitmap bitmap, int maxSizePx) {
        if (bitmap == null || maxSizePx <= 0) {
            return bitmap;
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int maxDim = Math.max(w, h);
        if (maxDim <= maxSizePx) {
            return bitmap;
        }
        float scale = (float) maxSizePx / (float) maxDim;
        int targetW = Math.max(1, (int) (w * scale));
        int targetH = Math.max(1, (int) (h * scale));
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

    private static byte[] compressOnce(Bitmap bitmap, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        return baos.toByteArray();
    }

    private static Bitmap decodeSampledBitmap(ContentResolver resolver, Uri uri, int reqWidth, int reqHeight) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream is = resolver.openInputStream(uri);
        if (is != null) {
            try {
                BitmapFactory.decodeStream(is, null, options);
            } finally {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565; // RGB_565 内存占用减半，极大降低低端机 OOM 风险
        options.inDither = true;

        InputStream is2 = resolver.openInputStream(uri);
        if (is2 == null) {
            return null;
        }
        try {
            try {
                return BitmapFactory.decodeStream(is2, null, options);
            } catch (OutOfMemoryError oom) {
                options.inSampleSize *= 2;
                try { is2.close(); } catch (Exception ignored) {}
                is2 = resolver.openInputStream(uri);
                if (is2 == null) {
                    return null;
                }
                try {
                    return BitmapFactory.decodeStream(is2, null, options);
                } catch (OutOfMemoryError oom2) {
                    return null;
                }
            }
        } finally {
            if (is2 != null) {
                try { is2.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 计算 inSampleSize：确保解码后的尺寸 <= reqWidth/reqHeight。
     * 使用 || 条件，任一边超过目标即继续放大采样率，绝不解码出超大位图。
     */
    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight || (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
