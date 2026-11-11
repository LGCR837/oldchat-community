package com.im.oldchat.lua;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CIP 公网 HTTP/HTTPS 客户端。
 * 外部公网访问无需额外权限声明；allowed_hosts 非空时仍作为开发者主动收紧的白名单。
 * 始终阻断本机、私网和链路本地地址，避免 SSRF。
 */
public final class LuaExternalHttp {
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private static final int MAX_REQUEST_BODY = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private LuaExternalHttp() { }

    public interface Callback {
        void complete(Response response, String error);
    }

    public static final class Response {
        public int status;
        public String body = "";
        public String url = "";
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    /** 兼容旧版 app.http_get。 */
    public static boolean get(final LuaMiniApp app, final String rawUrl,
                              final LegacyCallback callback) {
        return request(app, rawUrl, "GET", null, null, 10000, 25000,
                new Callback() {
                    @Override public void complete(Response response, String error) {
                        callback.complete(response == null ? null : response.body, error);
                    }
                });
    }

    public interface LegacyCallback {
        void complete(String body, String error);
    }

    public static boolean request(final LuaMiniApp app, final String rawUrl,
                                  final String method, final Map<String, String> headers,
                                  final byte[] body, final int connectTimeout,
                                  final int readTimeout, final Callback callback) {
        final URL initial;
        final String safeMethod;
        try {
            initial = validateUrl(app, rawUrl);
            safeMethod = normalizeMethod(method);
            if (body != null && body.length > MAX_REQUEST_BODY) {
                throw new Exception("请求体超过 2 MiB");
            }
        } catch (Exception e) {
            if (callback != null) callback.complete(null, message(e, "外部 API 地址不合法"));
            return false;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                perform(app, initial, safeMethod, headers, body,
                        clamp(connectTimeout, 1000, 60000),
                        clamp(readTimeout, 1000, 120000), callback);
            }
        }, "cip-http").start();
        return true;
    }

    private static void perform(LuaMiniApp app, URL initial, String method,
                                Map<String, String> headers, byte[] body,
                                int connectTimeout, int readTimeout, Callback callback) {
        URL current = initial;
        String currentMethod = method;
        byte[] currentBody = body;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = null;
            try {
                current = validateUrl(app, current.toString());
                ensurePublicHost(current.getHost());
                connection = (HttpURLConnection) current.openConnection();
                connection.setRequestMethod(currentMethod);
                connection.setConnectTimeout(connectTimeout);
                connection.setReadTimeout(readTimeout);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json, text/plain, */*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "Oldchat-CIP/2");
                applyHeaders(connection, headers);
                if (currentBody != null && currentBody.length > 0 && allowsBody(currentMethod)) {
                    connection.setDoOutput(true);
                    if (connection.getRequestProperty("Content-Type") == null) {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    }
                    connection.setFixedLengthStreamingMode(currentBody.length);
                    OutputStream output = connection.getOutputStream();
                    try {
                        output.write(currentBody);
                        output.flush();
                    } finally {
                        output.close();
                    }
                }
                int code = connection.getResponseCode();
                if (isRedirect(code)) {
                    if (redirect >= MAX_REDIRECTS) throw new Exception("重定向次数超过 5 次");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() == 0) throw new Exception("重定向缺少 Location");
                    current = new URL(current, location);
                    // RFC 常见行为：303 与 POST 的 301/302 转 GET；307/308 保留方法和请求体。
                    if (code == 303 || ((code == 301 || code == 302) && "POST".equals(currentMethod))) {
                        currentMethod = "GET";
                        currentBody = null;
                    }
                    continue;
                }
                Response response = new Response();
                response.status = code;
                response.url = current.toString();
                copyResponseHeaders(connection, response.headers);
                InputStream input = code >= 200 && code < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                response.body = readLimited(input);
                if (callback != null) callback.complete(response, null);
                return;
            } catch (Exception e) {
                if (callback != null) callback.complete(null, message(e, "外部 API 请求失败"));
                return;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    private static URL validateUrl(LuaMiniApp app, String rawUrl) throws Exception {
        URL url = new URL(rawUrl == null ? "" : rawUrl.trim());
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new Exception("仅支持 HTTP 或 HTTPS");
        }
        if (url.getUserInfo() != null) throw new Exception("URL 不允许包含用户名或密码");
        int port = url.getPort();
        if (port == 0 || port < -1 || port > 65535) throw new Exception("端口不合法");
        String host = url.getHost();
        if (host == null || host.length() == 0) throw new Exception("域名为空");
        if (app != null && !app.allowsHost(host)) throw new Exception("域名不在 allowed_hosts 范围内");
        return url;
    }

    private static void applyHeaders(HttpURLConnection connection,
                                     Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!isSafeHeaderName(name) || containsNewline(value)) continue;
            if ("host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name)
                    || "connection".equalsIgnoreCase(name)) continue;
            connection.setRequestProperty(name, value);
        }
    }

    private static void copyResponseHeaders(HttpURLConnection connection,
                                            Map<String, String> out) {
        for (int i = 1; i < 100; i++) {
            String name = connection.getHeaderFieldKey(i);
            String value = connection.getHeaderField(i);
            if (name == null && value == null) break;
            if (name != null && value != null) out.put(name, value);
        }
    }

    private static String normalizeMethod(String method) throws Exception {
        String value = method == null ? "GET" : method.trim().toUpperCase(java.util.Locale.US);
        if (!"GET".equals(value) && !"POST".equals(value) && !"PUT".equals(value)
                && !"PATCH".equals(value) && !"DELETE".equals(value)
                && !"HEAD".equals(value) && !"OPTIONS".equals(value)) {
            throw new Exception("不支持的 HTTP 方法：" + value);
        }
        return value;
    }

    private static boolean allowsBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method);
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static void ensurePublicHost(String host) throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new Exception("域名解析失败");
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new Exception("禁止访问本机或内网地址");
            }
            byte[] raw = address.getAddress();
            if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) {
                throw new Exception("禁止访问内网 IPv6 地址");
            }
        }
    }

    private static String readLimited(InputStream input) throws Exception {
        if (input == null) return "";
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_RESPONSE) throw new Exception("响应超过 4 MiB");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), "UTF-8");
        } finally {
            try { input.close(); } catch (Exception ignored) { }
        }
    }

    private static boolean isSafeHeaderName(String value) {
        if (value.length() < 1 || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static boolean containsNewline(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static String message(Exception e, String fallback) {
        String value = e.getMessage();
        return value == null || value.length() == 0 ? fallback : value;
    }
}
