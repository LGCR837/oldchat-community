package com.im.oldchat.ui;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public final class OldViewUpProfileActionHelper {
    private static final String API_BASE = "https://api.bilibili.com/";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface FollowStateCallback {
        void onDone(boolean ok, String msg, boolean followed);
    }

    public interface ToggleFollowCallback {
        void onDone(boolean ok, String msg, boolean followedAfter);
    }

    private OldViewUpProfileActionHelper() {
    }

    public static void queryFollowState(final long upMid,
                                        final String accessToken,
                                        final String cookie,
                                        final FollowStateCallback callback) {
        if (upMid <= 0) {
            postFollowState(callback, false, "UP 参数错误", false);
            return;
        }
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder url = new StringBuilder();
                    url.append(API_BASE).append("x/relation?fid=").append(upMid);
                    if (!empty(accessToken)) {
                        url.append("&access_key=").append(enc(accessToken.trim()));
                    }
                    HttpResult result = request("GET", url.toString(), "", cookie);
                    if (!result.ok()) {
                        postFollowState(callback, false, "获取关注状态失败", false);
                        return;
                    }
                    JSONObject obj = new JSONObject(result.body);
                    if (obj.optInt("code", -1) != 0) {
                        postFollowState(callback, false, obj.optString("message", "获取关注状态失败"), false);
                        return;
                    }
                    JSONObject data = obj.optJSONObject("data");
                    int attribute = readAttribute(data);
                    boolean followed = isFollowed(attribute);
                    postFollowState(callback, true, "", followed);
                } catch (Exception e) {
                    postFollowState(callback, false, "获取关注状态失败", false);
                }
            }
        });
    }

    public static void toggleFollow(final long upMid,
                                    final boolean follow,
                                    final String accessToken,
                                    final String cookie,
                                    final ToggleFollowCallback callback) {
        if (upMid <= 0) {
            postToggle(callback, false, "UP 参数错误", false);
            return;
        }
        final String csrf = extractCsrf(cookie);
        if (empty(csrf)) {
            postToggle(callback, false, "请先登录B站账号", false);
            return;
        }
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder body = new StringBuilder();
                    body.append("fid=").append(upMid);
                    body.append("&act=").append(follow ? 1 : 2);
                    body.append("&re_src=11");
                    if (!empty(accessToken)) {
                        body.append("&access_key=").append(enc(accessToken.trim()));
                    }
                    body.append("&csrf=").append(enc(csrf));
                    body.append("&csrf_token=").append(enc(csrf));

                    HttpResult result = request("POST", API_BASE + "x/relation/modify", body.toString(), cookie);
                    if (!result.ok()) {
                        postToggle(callback, false, "关注操作失败", !follow);
                        return;
                    }
                    JSONObject obj = new JSONObject(result.body);
                    if (obj.optInt("code", -1) == 0) {
                        postToggle(callback, true, follow ? "关注成功" : "已取消关注", follow);
                    } else {
                        postToggle(callback, false, obj.optString("message", "关注操作失败"), !follow);
                    }
                } catch (Exception e) {
                    postToggle(callback, false, "关注操作失败", !follow);
                }
            }
        });
    }

    private static int readAttribute(JSONObject data) {
        if (data == null) {
            return 0;
        }
        if (data.has("attribute")) {
            return data.optInt("attribute", 0);
        }
        JSONObject beRelation = data.optJSONObject("be_relation");
        if (beRelation != null && beRelation.has("attribute")) {
            return beRelation.optInt("attribute", 0);
        }
        return 0;
    }

    private static boolean isFollowed(int attribute) {
        return (attribute & 2) == 2 || attribute == 6;
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
            try {
                if (is != null) {
                    is.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
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

    private static void runAsync(Runnable runnable) {
        new Thread(runnable, "oldview-up-action").start();
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean empty(String text) {
        return text == null || text.trim().length() == 0;
    }

    private static void postFollowState(final FollowStateCallback callback,
                                        final boolean ok,
                                        final String msg,
                                        final boolean followed) {
        if (callback == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onDone(ok, msg, followed);
            }
        });
    }

    private static void postToggle(final ToggleFollowCallback callback,
                                   final boolean ok,
                                   final String msg,
                                   final boolean followedAfter) {
        if (callback == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onDone(ok, msg, followedAfter);
            }
        });
    }

    private static class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }
}
