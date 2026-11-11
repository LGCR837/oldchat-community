package com.im.oldchat.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;

final class ChatFileUploadHttpClient {
    private static final String PREFS_SETTINGS = "settings";
    private static final String PREFS_AUTH = "auth";
    private static final String KEY_FILES_SERVER_BASE_URL = "file_upload_base_url";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 20 * 60 * 1000;
    private static final long MAX_FILE_BYTES = 500L * 1024L * 1024L;

    interface ProgressListener {
        void onProgress(int percent);
    }

    static final class UploadResult {
        final int code;
        final String body;

        UploadResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private ChatFileUploadHttpClient() {
    }

    static String resolveFilesApiBaseUrl(Activity activity) {
        String raw = "";
        if (activity != null) {
            try {
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_SETTINGS, Activity.MODE_PRIVATE);
                raw = prefs.getString(KEY_FILES_SERVER_BASE_URL, "");
            } catch (Exception ignored) {
            }
        }
        String normalized = normalizeServerBase(raw);
        if (normalized.length() == 0) {
            normalized = com.im.oldchat.api.HttpUtil.getBaseOrigin();
        }
        if (!normalized.endsWith("/v2")) {
            normalized = normalized + "/v2";
        }
        return normalized;
    }

    static String buildUploadUrl(String apiBase) {
        String base = trimTrailingSlash(apiBase);
        if (base.endsWith("/files/upload")) {
            return base;
        }
        if (base.endsWith("/v2")) {
            return base + "/files/upload";
        }
        return base + "/v2/files/upload";
    }

    static String normalizeDownloadUrl(String raw, String apiBase) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            return "";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        String origin = com.im.oldchat.api.HttpUtil.getBaseOrigin();
        if (value.startsWith("/")) {
            return origin + value;
        }
        if (value.startsWith("v1/")) {
            return origin + "/" + value;
        }
        if (value.startsWith("files/")) {
            return origin + "/v1/" + value;
        }
        try {
            return origin + "/v1/files/download?name=" + URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return origin + "/v1/files/download?name=" + value;
        }
    }

    static UploadResult upload(Activity activity, String uploadUrl, Uri uri,
                               String fileName, String mimeType, long fileSize, String sha256,
                               ProgressListener progressListener) {
        if (activity == null || uri == null) {
            return new UploadResult(-1, "invalid_input");
        }
        if (fileSize > MAX_FILE_BYTES) {
            return new UploadResult(413, "file_too_large");
        }

        HttpURLConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            String boundary = "----OldChatFileBoundary" + System.currentTimeMillis();
            conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            String token = resolveAccessToken(activity);
            if (token.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (fileSize > 0) {
                conn.setRequestProperty("X-File-Size", String.valueOf(fileSize));
            }
            if (sha256 != null && sha256.length() == 64) {
                conn.setRequestProperty("X-File-SHA256", sha256);
            }
            conn.setChunkedStreamingMode(0);

            os = conn.getOutputStream();
            String safeMime = mimeType == null || mimeType.length() == 0 ? "application/octet-stream" : mimeType;
            String safeName = sanitizeFileName(fileName);
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
                    + "Content-Type: " + safeMime + "\r\n\r\n";
            os.write(header.getBytes("UTF-8"));

            is = activity.getContentResolver().openInputStream(uri);
            if (is == null) {
                return new UploadResult(-1, "cannot_open_stream");
            }

            byte[] buffer = new byte[8192];
            int len;
            long written = 0;
            int lastPercent = -1;
            long lastAt = 0;
            while ((len = is.read(buffer)) != -1) {
                if (written > MAX_FILE_BYTES - len) {
                    return new UploadResult(413, "file_too_large");
                }
                os.write(buffer, 0, len);
                written += len;
                if (fileSize > 0 && progressListener != null) {
                    // Chunked 模式下写入连接缓冲不表示已发送到网络，封顶 99%，
                    // 收到服务器 2xx 响应后再由下方补 100%。
                    int percent = (int) Math.min(99L, (written * 100L) / fileSize);
                    long now = System.currentTimeMillis();
                    if (percent != lastPercent && now - lastAt >= 100) {
                        lastPercent = percent;
                        lastAt = now;
                        progressListener.onProgress(percent);
                    }
                }
            }

            os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
            os.flush();

            int code = conn.getResponseCode();
            String response = readResponseBody(conn, code);
            if (progressListener != null && code >= 200 && code < 300) {
                progressListener.onProgress(100);
            }
            return new UploadResult(code, response);
        } catch (Exception e) {
            return new UploadResult(-1, e.getMessage());
        } finally {
            closeQuietly(is);
            closeQuietly(os);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String normalizeServerBase(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() == 0) {
            return "";
        }
        if (value.indexOf("://") < 0) {
            value = "http://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            if (scheme == null || authority == null || authority.length() == 0) {
                return "";
            }
            String path = uri.getRawPath();
            if (path == null) {
                path = "";
            }
            while (path.endsWith("/") && path.length() > 0) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.endsWith("/v1") || path.endsWith("/v2")) {
                path = path.substring(0, path.length() - 3);
            }
            return scheme.toLowerCase() + "://" + authority + path;
        } catch (Exception e) {
            return "";
        }
    }

    static String resolveAccessToken(Activity activity) {
        if (activity == null) {
            return "";
        }
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_AUTH, Activity.MODE_PRIVATE);
            String token = prefs.getString("access_token", "");
            return token == null ? "" : token.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractOrigin(String fullUrl) {
        try {
            URI uri = new URI(fullUrl);
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            if (scheme == null || authority == null || authority.length() == 0) {
                return "";
            }
            return scheme.toLowerCase() + "://" + authority;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readResponseBody(HttpURLConnection conn, int code) {
        InputStream in = null;
        BufferedReader reader = null;
        try {
            in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                return "";
            }
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            closeQuietly(reader);
            closeQuietly(in);
        }
    }

    private static String sanitizeFileName(String name) {
        String out = name == null ? "" : name.trim();
        if (out.length() == 0) {
            out = "file_" + System.currentTimeMillis();
        }
        out = out.replace('"', '_');
        out = out.replace('\n', '_');
        out = out.replace('\r', '_');
        return out;
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(OutputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
    }
}
