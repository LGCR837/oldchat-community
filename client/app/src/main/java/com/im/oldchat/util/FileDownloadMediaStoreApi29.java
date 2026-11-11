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

@TargetApi(29)
final class FileDownloadMediaStoreApi29 {
    private FileDownloadMediaStoreApi29() { }

    static FileDownloadStorage.Result download(Context context, String url, String fileName, String mimeType,
                                               String token, FileDownloadStorage.ProgressListener listener) {
        FileDownloadStorage.Result result = new FileDownloadStorage.Result();
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        Uri uri = null;
        boolean saved = false;
        try {
            connection = DownloadUtilStorageSupport.openConnection(url, token);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("服务器返回 " + code);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, FileDownloadStorage.sanitizeName(fileName));
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType == null ? "application/octet-stream" : mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("无法创建下载文件");
            output = context.getContentResolver().openOutputStream(uri, "w");
            if (output == null) throw new Exception("无法写入下载文件");
            input = connection.getInputStream();
            long total = connection.getContentLength();
            long downloaded = 0L;
            long lastAt = 0L;
            byte[] buffer = new byte[32 * 1024];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
                downloaded += len;
                long now = System.currentTimeMillis();
                if (listener != null && (now - lastAt >= 200 || (total > 0 && downloaded >= total))) {
                    lastAt = now;
                    listener.onProgress(downloaded, total);
                }
            }
            output.flush();
            if (downloaded <= 0) throw new Exception("下载内容为空");
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            saved = true;
            result.success = true;
            result.uri = uri.toString();
            return result;
        } catch (Exception e) {
            result.error = e.getMessage() == null ? "下载失败" : e.getMessage();
            return result;
        } finally {
            DownloadUtilStorageSupport.closeQuietly(input);
            DownloadUtilStorageSupport.closeQuietly(output);
            if (connection != null) connection.disconnect();
            if (!saved && uri != null) {
                try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
        }
    }
}
