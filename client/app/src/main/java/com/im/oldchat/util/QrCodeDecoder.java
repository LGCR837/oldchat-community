package com.im.oldchat.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;

public final class QrCodeDecoder {
    private QrCodeDecoder() {
    }

    public static String decode(Bitmap bitmap) {
        if (bitmap == null) {
            return "";
        }
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) {
                return "";
            }
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            Result result = reader.decode(binaryBitmap);
            return result == null ? "" : safe(result.getText());
        } catch (Exception e) {
            return "";
        }
    }

    public static Bitmap decodeBitmapFromUri(ContentResolver resolver, Uri uri, int maxSide) {
        if (resolver == null || uri == null) {
            return null;
        }
        InputStream input = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            input = resolver.openInputStream(uri);
            BitmapFactory.decodeStream(input, null, bounds);
            closeQuietly(input);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeInSampleSize(bounds, Math.max(128, maxSide));
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            input = resolver.openInputStream(uri);
            return BitmapFactory.decodeStream(input, null, opts);
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(input);
        }
    }

    private static int computeInSampleSize(BitmapFactory.Options options, int reqSize) {
        int width = options.outWidth;
        int height = options.outHeight;
        int inSampleSize = 1;
        while (width / inSampleSize > reqSize || height / inSampleSize > reqSize) {
            inSampleSize *= 2;
        }
        return Math.max(1, inSampleSize);
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Exception e) {
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
