package com.im.oldchat.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

public final class FileDownloadStorage {
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    public static final class Result {
        public boolean success;
        public String path = "";
        public String uri = "";
        public String error = "";
    }

    private FileDownloadStorage() { }

    public static Result download(Context context, String url, String fileName, String mimeType,
                                  String token, ProgressListener listener) {
        String[] candidates = MediaUrlResolver.resolveCandidates(url);
        if (candidates == null || candidates.length == 0) {
            candidates = new String[]{url};
        }
        String lastError = "下载失败";
        for (int i = 0; i < candidates.length; i++) {
            String candidate = candidates[i];
            if (candidate == null || candidate.length() == 0) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                Result mediaResult = FileDownloadMediaStoreApi29.download(
                        context, candidate, fileName, mimeType, token, listener);
                if (mediaResult.success) {
                    MediaUrlResolver.reportLineSuccess(candidate);
                    return mediaResult;
                }
                if (mediaResult.error != null && mediaResult.error.length() > 0) {
                    lastError = mediaResult.error;
                }
            }
            File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            Result publicResult = downloadToFile(candidate, uniqueFile(publicDir, fileName), token, listener);
            if (publicResult.success) {
                MediaUrlResolver.reportLineSuccess(candidate);
                return publicResult;
            }
            if (publicResult.error != null && publicResult.error.length() > 0) {
                lastError = publicResult.error;
            }
            MediaUrlResolver.reportLineFailure(candidate);
        }
        // 全部候选失败后，最后回退一次应用内部目录（使用原始 URL 也可，直接用最后一个候选）。
        File internalDir = new File(context.getFilesDir(), "downloads");
        return downloadToFile(candidates[candidates.length - 1], uniqueFile(internalDir, fileName), token, listener);
    }

    public static Result downloadToFile(String url, File target, String token, ProgressListener listener) {
        String[] candidates = MediaUrlResolver.resolveCandidates(url);
        if (candidates == null || candidates.length == 0) {
            candidates = new String[]{url};
        }
        String lastError = "下载失败";
        for (int i = 0; i < candidates.length; i++) {
            String candidate = candidates[i];
            if (candidate == null || candidate.length() == 0) {
                continue;
            }
            Result r = downloadSingleFile(candidate, target, token, listener);
            if (r.success) {
                MediaUrlResolver.reportLineSuccess(candidate);
                return r;
            }
            if (r.error != null && r.error.length() > 0) {
                lastError = r.error;
            }
            MediaUrlResolver.reportLineFailure(candidate);
        }
        Result finalResult = new Result();
        finalResult.error = lastError;
        return finalResult;
    }

    private static Result downloadSingleFile(String url, File target, String token, ProgressListener listener) {
        Result result = new Result();
        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;
        File temp = null;
        try {
            File parent = target.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new Exception("无法创建下载目录");
            temp = new File(target.getAbsolutePath() + ".part");
            if (temp.exists()) temp.delete();
            connection = DownloadUtilStorageSupport.openConnection(url, token);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("服务器返回 " + code);
            long total = connection.getContentLength();
            input = connection.getInputStream();
            output = new FileOutputStream(temp);
            byte[] buffer = new byte[32 * 1024];
            long downloaded = 0L;
            long lastAt = 0L;
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
            if (target.exists()) target.delete();
            if (!temp.renameTo(target)) throw new Exception("写入文件失败");
            result.success = true;
            result.path = target.getAbsolutePath();
            return result;
        } catch (Exception e) {
            result.error = e.getMessage() == null ? "下载失败" : e.getMessage();
            if (temp != null && temp.exists()) temp.delete();
            return result;
        } finally {
            DownloadUtilStorageSupport.closeQuietly(input);
            DownloadUtilStorageSupport.closeQuietly(output);
            if (connection != null) connection.disconnect();
        }
    }

    static File uniqueFile(File dir, String rawName) {
        String name = sanitizeName(rawName);
        File target = new File(dir, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    public static String guessMimeType(String fileName, String fallback) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (mime != null && mime.length() > 0) return mime;
        }
        return fallback == null || fallback.length() == 0 ? "application/octet-stream" : fallback;
    }

    public static String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        name = name.replace('/', '_').replace('\\', '_').replace(':', '_');
        if (name.length() == 0) name = "oldchat-file-" + System.currentTimeMillis();
        if (name.length() > 160) name = name.substring(0, 160);
        return name;
    }
}
