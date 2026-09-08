package com.im.oldchat.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class QrImageSaver {
    private QrImageSaver() {
    }

    public static boolean needWritePermissionForSave() {
        return Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28;
    }

    public static String saveToPictures(Context context, Bitmap bitmap, String prefix) throws Exception {
        if (context == null || bitmap == null) {
            throw new IllegalArgumentException("context or bitmap is null");
        }
        String fileName = buildFileName(prefix);
        if (Build.VERSION.SDK_INT >= 29) {
            return QrImageSaverApi29.save(context, bitmap, fileName);
        }
        return saveLegacy(context, bitmap, fileName);
    }

    private static String saveLegacy(Context context, Bitmap bitmap, String fileName) throws Exception {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File dir = new File(base, "OldChat");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("mkdirs failed");
        }
        File outFile = new File(dir, fileName);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(outFile);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("compress failed");
            }
            output.flush();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Exception e) {
                }
            }
        }
        MediaScannerConnection.scanFile(context,
                new String[]{outFile.getAbsolutePath()},
                new String[]{"image/png"},
                null);
        return outFile.getAbsolutePath();
    }

    private static String buildFileName(String prefix) {
        String safePrefix = prefix == null || prefix.trim().length() == 0 ? "oldchat_qr" : prefix.trim();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return safePrefix + "_" + timestamp + ".png";
    }
}
