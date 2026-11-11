package com.im.oldchat.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

class HttpUtilSupport1 extends HttpUtilSupport0 {
    private static final int MULTIPART_CONNECT_TIMEOUT_MS = 15000;
    private static final int MULTIPART_READ_TIMEOUT_MS = 120000;
    // 网络层小概率抖动（超时/EOF）时多试几次，降低“发送图片失败-1”。
    private static final int MULTIPART_RETRY_TIMES = 3;
    private static final long MULTIPART_RETRY_BASE_DELAY_MS = 800;
    private static final long MULTIPART_RETRY_MAX_DELAY_MS = 4000;

    protected static Result executeMultipart(String path, byte[] data, String fileName, String contentType,
                                             byte[] thumbData, String thumbName, String thumbType,
                                             String token) throws Exception {
        return executeMultipartNamed(path, "file", data, fileName, contentType,
                thumbData, thumbName, thumbType, token);
    }

    protected static Result executeMultipartNamed(String path, String fieldName, byte[] data,
                                                  String fileName, String contentType,
                                                  byte[] thumbData, String thumbName, String thumbType,
                                                  String token) throws Exception {
        String boundary = "----OldChatBoundary" + System.currentTimeMillis();
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        OutputStream os = conn.getOutputStream();
        writePart(os, boundary, fieldName, data, fileName, contentType);
        if (thumbData != null && thumbName != null && thumbType != null) {
            writePart(os, boundary, "thumb", thumbData, thumbName, thumbType);
        }
        os.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        return new Result(code, readResponseBody(conn, code));
    }

    protected static Result executeMultipartWithProgress(String path, byte[] data, String fileName,
                                                         String contentType, byte[] thumbData,
                                                         String thumbName, String thumbType,
                                                         String token,
                                                         HttpUtil.ProgressCallback progress) throws Exception {
        String boundary = "----OldChatBoundary" + System.currentTimeMillis();
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        long totalSize = data.length;
        if (thumbData != null) {
            totalSize += thumbData.length;
        }
        totalSize += 500;
        OutputStream os = conn.getOutputStream();
        long written = 0;
        written += writePartWithProgress(os, boundary, "file", data, fileName, contentType,
                written, totalSize, progress);
        if (thumbData != null && thumbName != null && thumbType != null) {
            written += writePartWithProgress(os, boundary, "thumb", thumbData, thumbName, thumbType,
                    written, totalSize, progress);
        }
        byte[] endBoundary = ("--" + boundary + "--\r\n").getBytes("UTF-8");
        os.write(endBoundary);
        written += endBoundary.length;
        if (progress != null) {
            progress.onProgress(written, totalSize);
        }
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        if (progress != null && code >= 200 && code < 300) {
            progress.onProgress(totalSize, totalSize);
        }
        return new Result(code, readResponseBody(conn, code));
    }

    private static void writePart(OutputStream os, String boundary, String name, byte[] data,
                                  String fileName, String contentType) throws Exception {
        String safeName = sanitizeFileName(fileName);
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        os.write(header.getBytes("UTF-8"));
        os.write(data);
        os.write("\r\n".getBytes("UTF-8"));
    }

    private static long writePartWithProgress(OutputStream os, String boundary, String name, byte[] data,
                                              String fileName, String contentType, long currentWritten,
                                              long totalSize,
                                              HttpUtil.ProgressCallback progress) throws Exception {
        String safeName = sanitizeFileName(fileName);
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        byte[] headerBytes = header.getBytes("UTF-8");
        os.write(headerBytes);
        long written = headerBytes.length;
        int chunkSize = 8192;
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(chunkSize, data.length - offset);
            os.write(data, offset, len);
            offset += len;
            written += len;
            if (progress != null) {
                // 封顶 99%，避免内存写缓冲导致的虚高进度。
                long cap = totalSize - Math.max(1L, totalSize / 100);
                progress.onProgress(Math.max(0L, Math.min(currentWritten + written, cap)), totalSize);
            }
        }
        byte[] endBytes = "\r\n".getBytes("UTF-8");
        os.write(endBytes);
        written += endBytes.length;
        return written;
    }

    protected static Result requestMultipartStream(String path, HttpUtil.StreamProvider provider,
                                                   String fileName, String contentType, String token,
                                                   String fieldName, String fieldValue,
                                                   HttpUtil.ProgressCallback progress) {
        Result result = new Result(-1, "network_error");
        for (int i = 0; i <= MULTIPART_RETRY_TIMES; i++) {
            try {
                result = executeMultipartStream(path, provider, fileName, contentType, token,
                        fieldName, fieldValue, progress);
                if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED && shouldAttemptRefresh(path, token)) {
                    String newToken = HttpAuthHelper.refreshAccessToken();
                    if (newToken != null) {
                        result = executeMultipartStream(path, provider, fileName, contentType, newToken,
                                fieldName, fieldValue, progress);
                        return applyAuthPolicy(result, path, newToken);
                    }
                }
                result = applyAuthPolicy(result, path, token);
            } catch (Exception e) {
                result = new Result(-1, safeErrorMessage(e));
            }
            if (!shouldRetryMultipart(result.code) || i >= MULTIPART_RETRY_TIMES) {
                return result;
            }
            sleepBeforeMultipartRetry(i);
        }
        return result;
    }

    protected static Result requestMultipartStreamWithThumb(String path, HttpUtil.StreamProvider provider,
                                                            String fileName, String contentType,
                                                            byte[] thumbData, String thumbName,
                                                            String thumbType, String token,
                                                            HttpUtil.ProgressCallback progress) {
        return requestMultipartStreamWithLyrics(path, provider, fileName, contentType,
                thumbData, thumbName, thumbType, null, null, null, token, progress);
    }

    protected static Result requestMultipartStreamWithLyrics(String path, HttpUtil.StreamProvider provider,
                                                             String fileName, String contentType,
                                                             byte[] thumbData, String thumbName,
                                                             String thumbType,
                                                             byte[] lyricsData, String lyricsName,
                                                             String lyricsType, String token,
                                                             HttpUtil.ProgressCallback progress) {
        Result result = new Result(-1, "network_error");
        for (int i = 0; i <= MULTIPART_RETRY_TIMES; i++) {
            try {
                result = executeMultipartStreamWithLyrics(path, provider, fileName, contentType,
                        thumbData, thumbName, thumbType, lyricsData, lyricsName, lyricsType,
                        token, progress);
                if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED && shouldAttemptRefresh(path, token)) {
                    String newToken = HttpAuthHelper.refreshAccessToken();
                    if (newToken != null) {
                        result = executeMultipartStreamWithLyrics(path, provider, fileName, contentType,
                                thumbData, thumbName, thumbType, lyricsData, lyricsName, lyricsType,
                                newToken, progress);
                        return applyAuthPolicy(result, path, newToken);
                    }
                }
                result = applyAuthPolicy(result, path, token);
            } catch (Exception e) {
                result = new Result(-1, safeErrorMessage(e));
            }
            if (!shouldRetryMultipart(result.code) || i >= MULTIPART_RETRY_TIMES) {
                return result;
            }
            sleepBeforeMultipartRetry(i);
        }
        return result;
    }

    private static Result executeMultipartStream(String path, HttpUtil.StreamProvider provider,
                                                 String fileName, String contentType, String token,
                                                 String fieldName, String fieldValue,
                                                 HttpUtil.ProgressCallback progress) throws Exception {
        String boundary = "----OldChatBoundary" + System.currentTimeMillis();
        URL url = multipartUrl(path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(MULTIPART_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(MULTIPART_READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setChunkedStreamingMode(0);
        OutputStream os = conn.getOutputStream();
        if (fieldName != null && fieldName.length() > 0 && fieldValue != null) {
            writePartField(os, boundary, fieldName, fieldValue);
        }
        writePartStream(os, boundary, "file", provider, fileName, contentType, progress);
        os.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        reportStreamComplete(progress, provider);
        return new Result(code, readResponseBody(conn, code));
    }

    private static void reportStreamComplete(HttpUtil.ProgressCallback progress,
                                             HttpUtil.StreamProvider provider) {
        if (progress == null || provider == null) {
            return;
        }
        long total = 0L;
        try {
            total = provider.length();
        } catch (Exception ignored) {
        }
        if (total > 0L) {
            progress.onProgress(total, total);
        }
    }

    private static Result executeMultipartStreamWithThumb(String path, HttpUtil.StreamProvider provider,
                                                          String fileName, String contentType,
                                                          byte[] thumbData, String thumbName,
                                                          String thumbType, String token,
                                                          HttpUtil.ProgressCallback progress) throws Exception {
        return executeMultipartStreamWithLyrics(path, provider, fileName, contentType,
                thumbData, thumbName, thumbType, null, null, null, token, progress);
    }

    private static Result executeMultipartStreamWithLyrics(String path, HttpUtil.StreamProvider provider,
                                                           String fileName, String contentType,
                                                           byte[] thumbData, String thumbName,
                                                           String thumbType,
                                                           byte[] lyricsData, String lyricsName,
                                                           String lyricsType, String token,
                                                           HttpUtil.ProgressCallback progress) throws Exception {
        String boundary = "----OldChatBoundary" + System.currentTimeMillis();
        URL url = multipartUrl(path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(MULTIPART_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(MULTIPART_READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setChunkedStreamingMode(0);
        OutputStream os = conn.getOutputStream();
        writePartStream(os, boundary, "file", provider, fileName, contentType, progress);
        if (thumbData != null && thumbData.length > 0 && thumbName != null && thumbType != null) {
            writePart(os, boundary, "thumb", thumbData, thumbName, thumbType);
        }
        if (lyricsData != null && lyricsData.length > 0 && lyricsName != null && lyricsType != null) {
            writePart(os, boundary, "lyrics", lyricsData, lyricsName, lyricsType);
        }
        os.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        reportStreamComplete(progress, provider);
        return new Result(code, readResponseBody(conn, code));
    }

    private static URL multipartUrl(String path) throws Exception {
        if (path != null && (path.startsWith("/v1/") || path.startsWith("/v2/"))) {
            return new URL(BASE_HOST + path);
        }
        return new URL(BASE_URL + path);
    }

    private static void writePartField(OutputStream os, String boundary, String name, String value)
            throws Exception {
        String safeName = name == null ? "" : name;
        String safeValue = value == null ? "" : value;
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + safeName + "\"\r\n\r\n"
                + safeValue + "\r\n";
        os.write(header.getBytes("UTF-8"));
    }

    private static void writePartStream(OutputStream os, String boundary, String name,
                                        HttpUtil.StreamProvider provider, String fileName,
                                        String contentType,
                                        HttpUtil.ProgressCallback progress) throws Exception {
        String safeType = contentType == null || contentType.length() == 0 ? "application/octet-stream" : contentType;
        String safeName = sanitizeFileName(fileName);
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + safeType + "\r\n\r\n";
        os.write(header.getBytes("UTF-8"));
        InputStream is = null;
        try {
            is = provider.open();
            if (is == null) {
                throw new IOException("cannot_open_stream");
            }
            long total = provider.length();
            long written = 0;
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
                written += len;
                if (progress != null && total > 0) {
                    // Chunked 模式下 os.write 只写入内存缓冲，不代表已发送到网络。
                    // 封顶 99%，避免进度瞬间到 100% 后长时间等待服务器确认。
                    long cap = total - Math.max(1L, total / 100);
                    progress.onProgress(Math.max(0L, Math.min(written, cap)), total);
                }
            }
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
        }
        os.write("\r\n".getBytes("UTF-8"));
    }

    private static boolean shouldRetryMultipart(int code) {
        return code <= 0
                || code == HttpURLConnection.HTTP_CLIENT_TIMEOUT
                || code == HttpURLConnection.HTTP_UNAVAILABLE
                || code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT
                || code == HttpURLConnection.HTTP_INTERNAL_ERROR
                || code == HttpURLConnection.HTTP_BAD_GATEWAY
                || code == 429;
    }

    private static void sleepBeforeMultipartRetry(int attempt) {
        long delay = MULTIPART_RETRY_BASE_DELAY_MS * (1L << attempt);
        if (delay > MULTIPART_RETRY_MAX_DELAY_MS) {
            delay = MULTIPART_RETRY_MAX_DELAY_MS;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeErrorMessage(Throwable error) {
        if (error == null) {
            return "network_error";
        }
        String message = error.getMessage();
        if (message == null || message.length() == 0) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private static String sanitizeFileName(String fileName) {
        String safe = fileName == null ? "" : fileName.trim();
        if (safe.length() == 0) {
            return "file";
        }
        safe = safe.replace('\r', '_').replace('\n', '_');
        safe = safe.replace('"', '_').replace('\\', '_');
        if (safe.length() > 120) {
            safe = safe.substring(safe.length() - 120);
        }
        if (safe.length() == 0) {
            return "file";
        }
        return safe;
    }
}
