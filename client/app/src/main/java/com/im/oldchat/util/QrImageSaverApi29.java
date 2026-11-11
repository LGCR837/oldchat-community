package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.OutputStream;

/** 隔离 API 29 的二维码 MediaStore 保存逻辑。 */
@TargetApi(29)
final class QrImageSaverApi29 {
    private QrImageSaverApi29() { }

    static String save(Context context, Bitmap bitmap, String fileName) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OldChat");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("insert failed");
        OutputStream output = null;
        try {
            output = context.getContentResolver().openOutputStream(uri);
            if (output == null) throw new IllegalStateException("open output failed");
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("compress failed");
            }
            output.flush();
            return uri.toString();
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
        }
    }
}
