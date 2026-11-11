package com.im.oldchat.bili;

import android.os.AsyncTask;
import android.util.Log;

import com.google.gson.Gson;
import com.im.oldchat.util.AsyncTaskExecutionCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

class BiliApiSupport0 {
    protected static final String PASSPORT_BASE_URL = "https://passport.bilibili.com/";
    protected static final String APP_BASE_URL = "https://app.bilibili.com/";
    protected static final String API_BASE_URL = "https://api.bilibili.com/";
    protected static final String LOG_PREFIX = "【bilibili 测试报错！！！！！】";
    protected static final int CONNECT_TIMEOUT_MS = 15000;
    protected static final int READ_TIMEOUT_MS = 15000;
    protected static final Gson GSON = new Gson();
    protected static volatile String WBI_IMG_KEY;
    protected static volatile String WBI_SUB_KEY;
    protected static volatile long WBI_FETCH_AT;

    protected static void logError(String detail, Throwable t) {
        String msg = LOG_PREFIX + " " + (detail != null ? detail : "");
        if (t != null) {
            Log.e("BiliApi", msg, t);
        } else {
            Log.e("BiliApi", msg);
        }
    }

    protected static String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300) + "...";
        }
        return text;
    }

    public static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://")) {
            return url.replace("http://", "https://");
        }
        return url;
    }

    protected static Result postForm(String url, Map<String, String> params, String cookie) {
        String body = buildQuery(params);
        HttpURLConnection conn = null;
        try {
            URL target = new URL(url);
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            applyHeaders(conn, cookie);
            if (body != null && body.length() > 0) {
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            String response = readResponse(conn, code);
            return new Result(code, response);
        } catch (Exception e) {
            return new Result(-1, e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    protected static Result get(String url, Map<String, String> params, String cookie) {
        String query = buildQuery(params);
        String finalUrl = url;
        if (query != null && query.length() > 0) {
            finalUrl = url + (url.contains("?") ? "&" : "?") + query;
        }
        HttpURLConnection conn = null;
        try {
            URL target = new URL(finalUrl);
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            applyHeaders(conn, cookie);
            int code = conn.getResponseCode();
            String response = readResponse(conn, code);
            return new Result(code, response);
        } catch (Exception e) {
            return new Result(-1, e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void applyHeaders(HttpURLConnection conn, String cookie) {
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.0.4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36");
        conn.setRequestProperty("Referer", "https://www.bilibili.com/");
        conn.setRequestProperty("Origin", "https://www.bilibili.com");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        String cookieHeader = buildCookieHeader(cookie);
        if (cookieHeader != null && cookieHeader.length() > 0) {
            conn.setRequestProperty("Cookie", cookieHeader);
        }
    }

    private static String buildCookieHeader(String cookie) {
        String cookieHeader = cookie != null ? cookie.trim() : "";
        if (cookieHeader.length() == 0 || cookieHeader.indexOf("buvid3") < 0) {
            String dummyBuvid = "buvid3=FE" + UUID.randomUUID().toString().toUpperCase(Locale.US) + "infoc";
            if (cookieHeader.length() == 0) {
                cookieHeader = dummyBuvid;
            } else {
                cookieHeader = cookieHeader + "; " + dummyBuvid;
            }
        }
        return cookieHeader;
    }

    protected static String extractCsrf(String cookie) {
        if (cookie == null) {
            return "";
        }
        String[] parts = cookie.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] != null ? parts[i].trim() : "";
            if (part.startsWith("bili_jct=")) {
                return part.substring("bili_jct=".length());
            }
        }
        return "";
    }

    private static String buildQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                value = "";
            }
            sb.append(urlEncode(key)).append('=').append(urlEncode(value));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String readResponse(HttpURLConnection conn, int code) {
        InputStream is = null;
        try {
            if (code >= 200 && code < 400) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
            }
            if (is == null) {
                return "";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
        }
    }

    protected static <P, Pr, R> void executeTask(AsyncTask<P, Pr, R> task, P... params) {
        AsyncTaskExecutionCompat.execute(task, params);
    }

    protected static final class Result {
        final int code;
        final String body;

        Result(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean isOk() {
            return code >= 200 && code < 300;
        }

        String errorMessage() {
            if (body != null && body.length() > 0) {
                return body;
            }
            return "网络错误";
        }
    }
}
