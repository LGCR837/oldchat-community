package com.im.oldchat.util;
import android.content.Context;
import com.im.oldchat.api.HttpUtil;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
class DownloadUtilStorageSupport {
    private DownloadUtilStorageSupport() {
    }
    static String getAuthToken(Context context) {
        if (context == null) {
            return "";
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
            String token = prefs.getString("access_token", "");
            return token == null ? "" : token.trim();
        } catch (Exception e) {
            return "";
        }
    }
    static DownloadUtil.DownloadAttempt downloadToMediaStoreWithFallback(Context context, String url,
                                                                         String fileName, String ext,
                                                                         String token) {
        String[] candidates = MediaUrlResolver.resolveCandidates(url);
        if (candidates == null || candidates.length == 0) {
            return DownloadUtilMediaStoreApi29.download(context, url, fileName, ext, token);
        }
        String lastError = DownloadUtil.DEFAULT_ERROR_MESSAGE;
        for (int i = 0; i < candidates.length; i++) {
            String one = candidates[i];
            if (one == null || one.length() == 0) {
                continue;
            }
            DownloadUtil.DownloadAttempt oneResult = DownloadUtilMediaStoreApi29.download(
                    context, one, fileName, ext, token);
            if (oneResult.success) {
                return oneResult;
            }
            if (oneResult.message != null && oneResult.message.length() > 0) {
                lastError = oneResult.message;
            }
        }
        return fail(lastError);
    }
    static DownloadUtil.DownloadAttempt downloadToFileWithFallback(String url, File outFile, String token) {
        String[] candidates = MediaUrlResolver.resolveCandidates(url);
        if (candidates == null || candidates.length == 0) {
            return downloadToFile(url, outFile, token);
        }
        String lastError = DownloadUtil.DEFAULT_ERROR_MESSAGE;
        for (int i = 0; i < candidates.length; i++) {
            String one = candidates[i];
            if (one == null || one.length() == 0) {
                continue;
            }
            DownloadUtil.DownloadAttempt oneResult = downloadToFile(one, outFile, token);
            if (oneResult.success) {
                return oneResult;
            }
            if (oneResult.message != null && oneResult.message.length() > 0) {
                lastError = oneResult.message;
            }
        }
        return fail(lastError);
    }
    private static DownloadUtil.DownloadAttempt downloadToFile(String url, File outFile, String token) {
        if (url == null || url.length() == 0) {
            return fail("下载链接为空");
        }
        if (outFile == null) {
            return fail("保存路径无效");
        }
        InputStream is = null;
        FileOutputStream os = null;
        HttpURLConnection conn = null;
        File tempFile = null;
        boolean fileSaved = false;
        try {
            File parent = outFile.getParentFile();
            if (parent == null) {
                return fail("保存路径无效");
            }
            if (!parent.exists() && !parent.mkdirs()) {
                return fail("无法创建下载目录");
            }
            tempFile = new File(outFile.getAbsolutePath() + ".tmp");
            if (tempFile.exists()) {
                tempFile.delete();
            }
            conn = openConnection(url, token);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return fail("服务器返回 " + code);
            }
            is = conn.getInputStream();
            os = new FileOutputStream(tempFile);
            long total = copyStream(is, os);
            if (total <= 0L) {
                return fail("下载内容为空");
            }
            if (outFile.exists()) {
                outFile.delete();
            }
            if (!tempFile.renameTo(outFile)) {
                return fail("写入文件失败");
            }
            fileSaved = true;
            return success();
        } catch (Exception e) {
            return fail(formatDownloadError(e));
        } finally {
            closeQuietly(is);
            closeQuietly(os);
            if (conn != null) {
                conn.disconnect();
            }
            if (!fileSaved && tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
    static HttpURLConnection openConnection(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        if (token != null && token.length() > 0 && shouldAttachAuth(url)) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.connect();
        return conn;
    }
    private static boolean shouldAttachAuth(String rawUrl) {
        // 社区版媒体只从源服务器取，token 只发给源服务器；第三方地址一律不带登录态。
        try {
            URL target = new URL(rawUrl);
            return sameEndpoint(target, new URL(HttpUtil.getBaseOrigin()));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sameEndpoint(URL a, URL b) {
        if (a == null || b == null) {
            return false;
        }
        String ah = a.getHost() == null ? "" : a.getHost();
        String bh = b.getHost() == null ? "" : b.getHost();
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                && ah.equalsIgnoreCase(bh)
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URL url) {
        int port = url.getPort();
        return port >= 0 ? port : url.getDefaultPort();
    }

    static long copyStream(InputStream is, OutputStream os) throws Exception {
        byte[] buffer = new byte[8192];
        int len;
        long total = 0L;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
            total += len;
        }
        os.flush();
        return total;
    }
    static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception e) {
            }
        }
    }
    static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Exception e) {
            }
        }
    }
    static String guessMimeByExt(String ext) {
        if (ext == null) {
            return "application/octet-stream";
        }
        String lower = ext.toLowerCase();
        if (".jpg".equals(lower) || ".jpeg".equals(lower)) {
            return "image/jpeg";
        }
        if (".png".equals(lower)) {
            return "image/png";
        }
        if (".gif".equals(lower)) {
            return "image/gif";
        }
        if (".mp4".equals(lower) || ".m4v".equals(lower)) {
            return "video/mp4";
        }
        if (".3gp".equals(lower) || ".3gpp".equals(lower)) {
            return "video/3gpp";
        }
        if (".zip".equals(lower)) {
            return "application/zip";
        }
        if (".mp3".equals(lower)) {
            return "audio/mpeg";
        }
        return "application/octet-stream";
    }
    static String formatDownloadError(Exception e) {
        if (e == null) {
            return DownloadUtil.DEFAULT_ERROR_MESSAGE;
        }
        if (e instanceof SocketTimeoutException) {
            return "下载超时，请重试";
        }
        if (e instanceof UnknownHostException) {
            return "网络不可用，请检查连接";
        }
        if (e instanceof FileNotFoundException) {
            return "资源不存在或无权限访问";
        }
        if (e instanceof SSLException) {
            return "安全连接失败，请稍后重试";
        }
        String msg = e.getMessage();
        if (msg != null) {
            msg = msg.trim();
            if (msg.length() > 0) {
                return msg;
            }
        }
        return DownloadUtil.DEFAULT_ERROR_MESSAGE;
    }
    static DownloadUtil.DownloadAttempt success() {
        return new DownloadUtil.DownloadAttempt(true, "");
    }
    static DownloadUtil.DownloadAttempt fail(String message) {
        String reason = message;
        if (reason == null || reason.length() == 0) {
            reason = DownloadUtil.DEFAULT_ERROR_MESSAGE;
        }
        return new DownloadUtil.DownloadAttempt(false, reason);
    }
}
