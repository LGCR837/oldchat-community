package com.im.oldchat.ui;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public final class OldViewVideoActionHelper {
    private static final String API_BASE = "https://api.bilibili.com/";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface BoolCallback { void onDone(boolean ok, String msg, boolean value); }
    public interface SimpleCallback { void onDone(boolean ok, String msg); }

    private OldViewVideoActionHelper() {}

    public static void queryLiked(final String bvid, final String cookie, final BoolCallback callback) {
        if (empty(bvid)) {
            postBool(callback, false, "视频参数错误", false);
            return;
        }
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    String url = API_BASE + "x/web-interface/archive/has/like?bvid=" + enc(bvid.trim());
                    HttpResult result = request("GET", url, "", cookie);
                    if (!result.ok()) {
                        postBool(callback, false, "获取点赞状态失败", false);
                        return;
                    }
                    JSONObject obj = new JSONObject(result.body);
                    if (obj.optInt("code", -1) != 0) {
                        postBool(callback, false, obj.optString("message", "获取点赞状态失败"), false);
                        return;
                    }
                    postBool(callback, true, "", obj.optInt("data", 0) == 1);
                } catch (Exception e) {
                    postBool(callback, false, "获取点赞状态失败", false);
                }
            }
        });
    }

    public static void toggleLike(final String bvid,
                                  final boolean like,
                                  final String accessToken,
                                  final String cookie,
                                  final SimpleCallback callback) {
        final String csrf = extractCsrf(cookie);
        if (empty(bvid)) {
            postSimple(callback, false, "视频参数错误");
            return;
        }
        if (empty(csrf)) {
            postSimple(callback, false, "请先登录B站账号");
            return;
        }
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    StringBuilder body = new StringBuilder();
                    body.append("bvid=").append(enc(bvid.trim()));
                    body.append("&like=").append(like ? "1" : "2");
                    if (!empty(accessToken)) {
                        body.append("&access_key=").append(enc(accessToken));
                    }
                    body.append("&csrf=").append(enc(csrf));
                    body.append("&csrf_token=").append(enc(csrf));
                    HttpResult result = request("POST", API_BASE + "x/web-interface/archive/like", body.toString(), cookie);
                    if (!result.ok()) {
                        postSimple(callback, false, "点赞请求失败");
                        return;
                    }
                    JSONObject obj = new JSONObject(result.body);
                    if (obj.optInt("code", -1) == 0) {
                        postSimple(callback, true, like ? "点赞成功" : "已取消点赞");
                    } else {
                        postSimple(callback, false, obj.optString("message", "点赞失败"));
                    }
                } catch (Exception e) {
                    postSimple(callback, false, "点赞失败");
                }
            }
        });
    }

    public static void favoriteVideo(final long aid,
                                     final String accessToken,
                                     final String cookie,
                                     final SimpleCallback callback) {
        final String csrf = extractCsrf(cookie);
        if (aid <= 0) {
            postSimple(callback, false, "视频参数错误");
            return;
        }
        if (empty(csrf)) {
            postSimple(callback, false, "请先登录B站账号");
            return;
        }
        runAsync(new Runnable() {
            @Override public void run() {
                try {
                    long mid = queryMid(cookie);
                    long favId = mid > 0 ? queryFirstFavId(mid, cookie) : 0L;
                    if (favId <= 0) {
                        postSimple(callback, false, "请先创建收藏夹");
                        return;
                    }
                    StringBuilder body = new StringBuilder();
                    body.append("rid=").append(aid).append("&type=2");
                    body.append("&add_media_ids=").append(favId).append("&del_media_ids=");
                    if (!empty(accessToken)) {
                        body.append("&access_key=").append(enc(accessToken));
                    }
                    body.append("&csrf=").append(enc(csrf)).append("&csrf_token=").append(enc(csrf));
                    HttpResult result = request("POST", API_BASE + "x/v3/fav/resource/deal", body.toString(), cookie);
                    if (!result.ok()) {
                        postSimple(callback, false, "收藏请求失败");
                        return;
                    }
                    JSONObject obj = new JSONObject(result.body);
                    if (obj.optInt("code", -1) == 0) {
                        postSimple(callback, true, "收藏成功");
                    } else {
                        postSimple(callback, false, obj.optString("message", "收藏失败"));
                    }
                } catch (Exception e) {
                    postSimple(callback, false, "收藏失败");
                }
            }
        });
    }

    private static long queryMid(String cookie) {
        try {
            HttpResult result = request("GET", API_BASE + "x/web-interface/nav", "", cookie);
            if (!result.ok()) {
                return 0L;
            }
            JSONObject obj = new JSONObject(result.body);
            JSONObject data = obj.optInt("code", -1) == 0 ? obj.optJSONObject("data") : null;
            return data == null ? 0L : data.optLong("mid", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long queryFirstFavId(long mid, String cookie) {
        try {
            HttpResult result = request("GET", API_BASE + "x/v3/fav/folder/created/list-all?up_mid=" + mid + "&type=2", "", cookie);
            if (!result.ok()) {
                return 0L;
            }
            JSONObject obj = new JSONObject(result.body);
            JSONObject data = obj.optInt("code", -1) == 0 ? obj.optJSONObject("data") : null;
            JSONArray list = data == null ? null : data.optJSONArray("list");
            JSONObject first = list != null && list.length() > 0 ? list.optJSONObject(0) : null;
            if (first == null) {
                return 0L;
            }
            long id = first.optLong("id", 0L);
            return id > 0 ? id : first.optLong("fid", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static HttpResult request(String method, String url, String body, String cookie) {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setUseCaches(false);
            conn.setRequestMethod(method);
            conn.setDoInput(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.0.4) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setRequestProperty("Origin", "https://www.bilibili.com");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (!empty(cookie)) {
                conn.setRequestProperty("Cookie", cookie.trim());
            }
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write((body == null ? "" : body).getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            return new HttpResult(code, readText(is));
        } catch (Exception e) {
            return new HttpResult(-1, e.getMessage() == null ? "" : e.getMessage());
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readText(InputStream is) {
        if (is == null) {
            return "";
        }
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractCsrf(String cookie) {
        if (cookie == null) {
            return "";
        }
        String[] parts = cookie.split(";");
        for (int i = 0; i < parts.length; i++) {
            String one = parts[i] == null ? "" : parts[i].trim();
            if (one.startsWith("bili_jct=")) {
                return one.substring(9).trim();
            }
        }
        return "";
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void runAsync(Runnable runnable) { new Thread(runnable, "oldview-action").start(); }
    private static boolean empty(String text) { return text == null || text.trim().length() == 0; }

    private static void postBool(final BoolCallback callback, final boolean ok, final String msg, final boolean value) {
        if (callback == null) return;
        MAIN.post(new Runnable() { @Override public void run() { callback.onDone(ok, msg, value); } });
    }

    private static void postSimple(final SimpleCallback callback, final boolean ok, final String msg) {
        if (callback == null) return;
        MAIN.post(new Runnable() { @Override public void run() { callback.onDone(ok, msg); } });
    }

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
        boolean ok() { return code >= 200 && code < 300; }
    }
}
