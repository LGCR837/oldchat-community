package com.im.oldchat.api;

import android.content.Context;
import android.content.SharedPreferences;
import com.im.oldchat.OldChatApplication;
import com.im.oldchat.util.CryptoUtil;
import com.im.oldchat.util.DeviceInfoUtil;
import com.im.oldchat.util.MyUidStore;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

class HttpAuthHelper {
    private static final String AUTH_PREFS = "auth";
    private static final Object SESSION_LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();
    private static boolean sessionHandshakeInProgress;
    private static boolean refreshInProgress;
    private static String lastRefreshResult;
    private static int authGeneration;

    static String getSavedAccessToken() {
        Context context = OldChatApplication.getAppContext();
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        return prefs.getString("access_token", "");
    }

    static boolean ensureSession() {
        if (!CryptoUtil.isEcdhSupported()) return false;
        if (CryptoUtil.hasSession()) return true;
        synchronized (SESSION_LOCK) {
            if (sessionHandshakeInProgress) {
                long deadline = android.os.SystemClock.elapsedRealtime() + 25000L;
                while (sessionHandshakeInProgress && !CryptoUtil.hasSession()) {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0L) return false;
                    try {
                        SESSION_LOCK.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return CryptoUtil.hasSession();
            }
            if (CryptoUtil.hasSession()) return true;
            sessionHandshakeInProgress = true;
        }
        boolean success = false;
        try {
            success = performSessionHandshake();
            return success;
        } finally {
            synchronized (SESSION_LOCK) {
                sessionHandshakeInProgress = false;
                SESSION_LOCK.notifyAll();
            }
            if (!success) CryptoUtil.clearSession();
        }
    }

    /** WS 收到 400（服务端会话丢失）后重置握手闸门，允许立刻重新握手。 */
    static void resetHandshakeGate() {
        synchronized (SESSION_LOCK) {
            sessionHandshakeInProgress = false;
            SESSION_LOCK.notifyAll();
        }
    }

    private static boolean performSessionHandshake() {
        try {
            CryptoUtil.Handshake handshake = CryptoUtil.beginHandshake();
            JSONObject json = new JSONObject();
            json.put("client_pub", handshake.getPublicKey());
            HttpUtil.Result result = executePlainRequest("POST", "/auth/handshake", json, null);
            if (result.code < 200 || result.code >= 300) return false;
            JSONObject obj = new JSONObject(result.data);
            String sessionId = obj.optString("session_id", "");
            String serverPub = obj.optString("server_pub", "");
            if (sessionId.length() == 0 || serverPub.length() == 0) return false;
            CryptoUtil.SessionKeys keys = handshake.finish(serverPub);
            CryptoUtil.setSession(sessionId, keys.encKey, keys.macKey);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String refreshAccessToken() {
        synchronized (REFRESH_LOCK) {
            if (refreshInProgress) {
                long deadline = android.os.SystemClock.elapsedRealtime() + 25000L;
                while (refreshInProgress) {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0L) return null;
                    try {
                        REFRESH_LOCK.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return lastRefreshResult;
            }
            refreshInProgress = true;
            lastRefreshResult = null;
        }
        final int operationGeneration;
        synchronized (REFRESH_LOCK) {
            operationGeneration = authGeneration;
        }
        String result = null;
        try {
            result = performRefreshAccessToken(operationGeneration);
            return result;
        } finally {
            synchronized (REFRESH_LOCK) {
                lastRefreshResult = result;
                refreshInProgress = false;
                REFRESH_LOCK.notifyAll();
            }
        }
    }

    static void invalidateAuthOperations() {
        synchronized (REFRESH_LOCK) {
            authGeneration++;
            lastRefreshResult = null;
            REFRESH_LOCK.notifyAll();
        }
    }

    private static String performRefreshAccessToken(int operationGeneration) {
        try {
            Context context = OldChatApplication.getAppContext();
            if (context == null) {
                return null;
            }
            SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
            String oldUid = prefs.getString("my_uid", "");
            String expectedAccessToken = prefs.getString("access_token", "");
            String refreshToken = prefs.getString("refresh_token", "");
            if (refreshToken != null && refreshToken.length() > 0) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("refresh_token", refreshToken);
                    HttpUtil.Result result = HttpUtil.executeRequest("POST", "/auth/refresh", json, null);
                    if (result.code >= 200 && result.code < 300) {
                        return applyAuthResponse(prefs, context, oldUid, expectedAccessToken,
                                refreshToken, operationGeneration, result.data);
                    }
                    if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED || result.code == HttpURLConnection.HTTP_FORBIDDEN) {
                        String data = result.data != null ? result.data : "";
                        boolean isExplicitAuthFailure = data.contains("invalid_credentials")
                                || data.contains("token_revoked")
                                || data.contains("user_banned")
                                || data.contains("account_disabled");
                        boolean isTransientSessionIssue = data.contains("invalid_session")
                                || data.contains("missing_session")
                                || data.contains("bad_gateway")
                                || data.contains("502");
                        if (isExplicitAuthFailure && !isTransientSessionIssue) {
                            if (refreshToken.equals(prefs.getString("refresh_token", ""))) {
                                prefs.edit().remove("refresh_token").apply();
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore and fall back to password login.
                }
            }
            String savedUsername = prefs.getString("saved_username", null);
            String savedPassword = prefs.getString("saved_password", null);
            if (savedUsername == null || savedUsername.length() == 0 ||
                    savedPassword == null || savedPassword.length() == 0) {
                return null;
            }
            JSONObject json = new JSONObject();
            json.put("identifier", savedUsername);
            json.put("password", savedPassword);
            json.put("device_id", DeviceInfoUtil.getDeviceId(context));
            json.put("imei", DeviceInfoUtil.getImei(context));
            json.put("device_name", DeviceInfoUtil.getDeviceName());
            json.put("platform", "android");
            json.put("app_version", DeviceInfoUtil.getAppVersion(context));
            String passwordLoginExpectedAccess = prefs.getString("access_token", "");
            HttpUtil.Result result = HttpUtil.executeRequest("POST", "/auth/login", json, null);
            if (result.code < 200 || result.code >= 300) {
                return null;
            }
            return applyAuthResponse(prefs, context, oldUid, passwordLoginExpectedAccess,
                    null, operationGeneration, result.data);
        } catch (Exception e) {
            return null;
        }
    }

    private static String applyAuthResponse(SharedPreferences prefs, Context context, String oldUid,
                                            String expectedAccessToken, String expectedRefreshToken,
                                            int operationGeneration, String response) throws Exception {
        synchronized (REFRESH_LOCK) {
        if (operationGeneration != authGeneration) return null;
        String currentAccessToken = prefs.getString("access_token", "");
        if (expectedAccessToken == null || !expectedAccessToken.equals(currentAccessToken)) return null;
        if (expectedRefreshToken != null
                && !expectedRefreshToken.equals(prefs.getString("refresh_token", ""))) return null;
        JSONObject obj = new JSONObject(response);
        String accessToken = obj.optString("access_token", "");
        String newRefreshToken = obj.optString("refresh_token", "");
        JSONObject user = obj.optJSONObject("user");
        if (accessToken.length() == 0) {
            return null;
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("access_token", accessToken);
        if (newRefreshToken != null && newRefreshToken.length() > 0) {
            editor.putString("refresh_token", newRefreshToken);
        }
        if (user != null) {
            String userId = user.optString("id", "");
            String myUID = user.optString("uid", "");
            String myNcuid = user.optString("ncuid", "");
            if (userId.length() > 0) {
                editor.putString("user_id", userId);
            }
            if (myNcuid.length() > 0) {
                editor.putString("my_ncuid", myNcuid);
            }
            if (myUID.length() > 0) {
                if (oldUid.length() > 0 && !oldUid.equals(myUID)) {
                    MyUidStore.recordUidAlias(context, oldUid, myUID);
                }
                editor.putString("my_uid", myUID);
                MyUidStore.updateCurrentUid(context, myUID);
            }
        }
        editor.apply();
        return accessToken;
        }
    }

    private static HttpUtil.Result executePlainRequest(String method, String path, JSONObject json, String token) throws Exception {
        URL url = new URL(HttpUtil.BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod(method);
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (json != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
        }
        int code = conn.getResponseCode();
        return new HttpUtil.Result(code, HttpUtil.readResponseBody(conn, code));
    }
}
