package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/** 隔离 API 29 MediaStore 下载字段，旧系统下载时不会校验这些符号。 */
@TargetApi(29)
final class DownloadUtilMediaStoreApi29 {
    private DownloadUtilMediaStoreApi29() { }

    static DownloadUtil.DownloadAttempt download(Context context, String url,
                                                 String fileName, String ext, String token) {
        if (context == null) return DownloadUtilStorageSupport.fail("无法访问存储目录");
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        Uri itemUri = null;
        boolean saved = false;
        try {
            connection = DownloadUtilStorageSupport.openConnection(url, token);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return DownloadUtilStorageSupport.fail("服务器返回 " + code);
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, DownloadUtilStorageSupport.guessMimeByExt(ext));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            itemUri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (itemUri == null) return DownloadUtilStorageSupport.fail("无法创建下载文件");
            output = context.getContentResolver().openOutputStream(itemUri, "w");
            if (output == null) return DownloadUtilStorageSupport.fail("无法写入下载文件");
            input = connection.getInputStream();
            if (DownloadUtilStorageSupport.copyStream(input, output) <= 0L) {
                return DownloadUtilStorageSupport.fail("下载内容为空");
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(itemUri, done, null, null);
            saved = true;
            return DownloadUtilStorageSupport.success();
        } catch (Exception error) {
            return DownloadUtilStorageSupport.fail(DownloadUtilStorageSupport.formatDownloadError(error));
        } finally {
            DownloadUtilStorageSupport.closeQuietly(input);
            DownloadUtilStorageSupport.closeQuietly(output);
            if (connection != null) connection.disconnect();
            if (!saved && itemUri != null) {
                try { context.getContentResolver().delete(itemUri, null, null); } catch (Exception ignored) { }
            }
        }
    }
}
