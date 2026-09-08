package com.im.oldchat.util;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

public class DownloadUtil {
    public interface Callback {
        void onResult(boolean success, String message, File file);
    }

    static final String DEFAULT_ERROR_MESSAGE = "保存失败";

    public static void saveUrlToDownloadsAsync(final Context context, final String url,
                                               final String prefix, final String fallbackExt,
                                               final Callback callback) {
        if (context == null || url == null || url.isEmpty()) {
            if (callback != null) {
                callback.onResult(false, "无效的下载链接", null);
            }
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File outFile = null;
                DownloadAttempt attempt;
                try {
                    String resolved = resolveUrl(url);
                    if (resolved == null || resolved.length() == 0) {
                        postResult(callback, false, "下载链接无效", null);
                        return;
                    }
                    String ext = guessExtension(resolved, fallbackExt);
                    String safePrefix = (prefix == null || prefix.isEmpty()) ? "oldchat_" : prefix;
                    String fileName = safePrefix + System.currentTimeMillis() + ext;
                    String token = DownloadUtilStorageSupport.getAuthToken(appContext);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        attempt = DownloadUtilStorageSupport.downloadToMediaStoreWithFallback(
                                appContext, resolved, fileName, ext, token);
                        if (attempt.success) {
                            postResult(callback, true, "已保存到下载: " + fileName, null);
                        } else {
                            postResult(callback, false, safeMessage(attempt), null);
                        }
                        return;
                    }

                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                        dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    }
                    if (dir == null) {
                        dir = appContext.getExternalFilesDir(null);
                    }
                    if (dir == null) {
                        postResult(callback, false, "无法访问存储目录", null);
                        return;
                    }
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    outFile = new File(dir, fileName);
                    attempt = DownloadUtilStorageSupport.downloadToFileWithFallback(resolved, outFile, token);
                } catch (Exception e) {
                    attempt = DownloadUtilStorageSupport.fail(DownloadUtilStorageSupport.formatDownloadError(e));
                }

                if (attempt.success && outFile != null) {
                    scanFile(appContext, outFile);
                    postResult(callback, true, "已保存到下载: " + outFile.getAbsolutePath(), outFile);
                } else {
                    if (outFile != null && outFile.exists()) {
                        outFile.delete();
                    }
                    postResult(callback, false, safeMessage(attempt), null);
                }
            }
        }).start();
    }

    private static String safeMessage(DownloadAttempt attempt) {
        String message = attempt == null ? DEFAULT_ERROR_MESSAGE : attempt.message;
        if (message == null || message.length() == 0) {
            message = DEFAULT_ERROR_MESSAGE;
        }
        return message;
    }

    private static void postResult(final Callback callback, final boolean success,
                                   final String message, final File file) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(success, message, file);
            }
        });
    }

    private static void scanFile(Context context, File file) {
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()},
                    null,
                    null);
        } catch (Throwable e) {
            // ignore
        }
    }

    private static String resolveUrl(String url) {
        return MediaUrlResolver.resolve(url);
    }

    private static String guessExtension(String url, String fallbackExt) {
        if (url != null) {
            int q = url.indexOf('?');
            if (q >= 0) {
                url = url.substring(0, q);
            }
            int h = url.indexOf('#');
            if (h >= 0) {
                url = url.substring(0, h);
            }
            int idx = url.lastIndexOf('.');
            if (idx >= 0 && idx < url.length() - 1) {
                String ext = url.substring(idx).toLowerCase();
                if (ext.length() <= 8) {
                    return ext;
                }
            }
        }
        if (fallbackExt == null || fallbackExt.isEmpty()) {
            return ".jpg";
        }
        return fallbackExt.startsWith(".") ? fallbackExt : ("." + fallbackExt);
    }

    static class DownloadAttempt {
        final boolean success;
        final String message;

        DownloadAttempt(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
