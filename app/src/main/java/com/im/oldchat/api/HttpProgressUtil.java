package com.im.oldchat.api;

import android.os.Handler;
import android.os.Looper;

import com.im.oldchat.util.CryptoUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * 带进度的 HTTP JSON 请求（上传/下载字节进度）。
 * 与 HttpUtil 保持同一协议：支持 ECDH 加密、gzip 响应、Bearer token。
 * 进度回调回主线程；percent 为 -1 表示无法确定总量（chunked 响应）。
 */
public final class HttpProgressUtil {

    public interface ProgressListener {
        /** percent ∈ [0,100]，-1 表示不确定（无 Content-Length）。回调在主线程。 */
        void onProgress(int percent);
    }

    public interface ResultCallback {
        void onSuccess(String body);
        void onError(int code, String body);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private HttpProgressUtil() {
    }

    /** 上传 JSON（带写进度）。 */
    public static void postJSON(String path, String jsonBody, String token,
                                final ProgressListener progress, final ResultCallback callback) {
        byte[] bytes = new byte[0];
        try {
            bytes = jsonBody.getBytes("UTF-8");
        } catch (Exception ignored) {
        }
        doRequest("POST", path, bytes, token, progress, callback);
    }

    /** 下载 JSON（带读进度）。 */
    public static void getJSON(String path, String token,
                               final ProgressListener progress, final ResultCallback callback) {
        doRequest("GET", path, null, token, progress, callback);
    }

    private static void doRequest(final String method, final String path, final byte[] jsonBytes,
                                  final String token, final ProgressListener progress,
                                  final ResultCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(HttpUtil.BASE_URL + path);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setUseCaches("GET".equals(method));
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestMethod(method);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("Accept-Encoding", "gzip");
                    conn.setRequestProperty("X-Burn-Secure", "1");
                    if (token != null && token.length() > 0) {
                        conn.setRequestProperty("Authorization", "Bearer " + token);
                    }

                    boolean encrypt = shouldEncrypt(path);
                    if (encrypt && !HttpAuthHelper.ensureSession()) {
                        // ECDH 握手失败则降级为明文（服务端兼容）
                        encrypt = false;
                    }
                    if (jsonBytes != null && jsonBytes.length > 0) {
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);
                        byte[] out = jsonBytes;
                        if (encrypt) {
                            String cipher = CryptoUtil.encrypt(new String(jsonBytes, "UTF-8"));
                            out = cipher.getBytes("UTF-8");
                            conn.setRequestProperty("X-Enc", "1");
                            conn.setRequestProperty("X-Enc-Compression", "gzip");
                            String sid = CryptoUtil.getSessionId();
                            if (sid != null && sid.length() > 0) {
                                conn.setRequestProperty("X-Session", sid);
                            }
                        }
                        conn.setFixedLengthStreamingMode(out.length);
                        OutputStream os = conn.getOutputStream();
                        int total = out.length;
                        int written = 0;
                        while (written < total) {
                            int n = Math.min(4096, total - written);
                            os.write(out, written, n);
                            written += n;
                            report(progress, total > 0 ? (int) (written * 100L / total) : -1);
                        }
                        os.flush();
                        try {
                            os.close();
                        } catch (Exception ignored) {
                        }
                    }

                    int code = conn.getResponseCode();
                    String body = readBody(conn, code, progress);
                    if (encrypt) {
                        boolean compressed = "gzip".equalsIgnoreCase(conn.getHeaderField("X-Enc-Compression"));
                        String dec = CryptoUtil.decryptIfNeeded(body, compressed);
                        if (dec != null) {
                            body = dec;
                        }
                    }
                    report(progress, 100);
                    if (code >= 200 && code < 300) {
                        callSuccess(callback, body);
                    } else {
                        callError(callback, code, body);
                    }
                } catch (Exception e) {
                    callError(callback, -1, "");
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }).start();
    }

    private static boolean shouldEncrypt(String path) {
        return path != null && !path.startsWith("/auth/handshake") && CryptoUtil.isEcdhSupported();
    }

    /** 读响应体（按 wire 字节算下载进度，再解 gzip）。 */
    private static String readBody(HttpURLConnection conn, int code, ProgressListener progress) throws Exception {
        InputStream is = null;
        try {
            if (code >= 200 && code < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
                if (is == null) {
                    is = conn.getInputStream();
                }
            }
        } catch (Exception e) {
            return "";
        }
        if (is == null) {
            return "";
        }
        long total = -1;
        try {
            total = conn.getContentLength();
        } catch (Exception ignored) {
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long read = 0L;
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
            read += n;
            report(progress, total > 0 ? (int) (read * 100L / total) : -1);
        }
        try {
            is.close();
        } catch (Exception ignored) {
        }
        byte[] all = bos.toByteArray();
        String encoding = null;
        try {
            encoding = conn.getContentEncoding();
        } catch (Exception ignored) {
        }
        if (encoding != null && encoding.toLowerCase().indexOf("gzip") >= 0) {
            all = gunzip(all);
        }
        return new String(all, "UTF-8");
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = gz.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        gz.close();
        return bos.toByteArray();
    }

    private static void report(final ProgressListener listener, final int percent) {
        if (listener == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                listener.onProgress(percent);
            }
        });
    }

    private static void callSuccess(final ResultCallback cb, final String body) {
        if (cb == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onSuccess(body);
            }
        });
    }

    private static void callError(final ResultCallback cb, final int code, final String body) {
        if (cb == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(code, body);
            }
        });
    }
}
