package com.im.oldchat.api;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.im.oldchat.OldChatApplication;
import com.im.oldchat.ui.LoginActivity;
import com.im.oldchat.util.CryptoUtil;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;

class HttpUtilSupport0 {
    protected static final String TAG = "HttpUtil";
    /** 无版本前缀的主机（/v2 等新协议使用）。 */
    public static final String DEFAULT_BASE_HOST = "http://110.42.36.103:8082";
    /** API 版本前缀：用户配置与对外展示一律不带，仅在内部拼 URL 时附加。 */
    public static final String API_PREFIX = "/v1";
    public static final String DEFAULT_BASE_URL = DEFAULT_BASE_HOST + API_PREFIX;
    /** 站点根地址（无版本前缀），媒体、官网页、更新清单等非 API 资源都以它为源。 */
    public static volatile String BASE_HOST = DEFAULT_BASE_HOST;
    /** API 根地址 = BASE_HOST + API_PREFIX，由 BASE_HOST 单向派生，不允许单独赋值。 */
    public static volatile String BASE_URL = DEFAULT_BASE_URL;
    protected static final String AUTH_SILENT = "auth_silent";
    protected static final String AUTH_WARNING = "auth_warning";
    protected static final int CONNECT_TIMEOUT_MS = 8000;
    protected static final int READ_TIMEOUT_MS = 15000;
    // 文本响应安全上限（字符数）：超过视为异常响应并丢弃，防止 OOM。
    // 正常业务 JSON 响应（消息/动态/列表）通常在几十 KB 以内。
    protected static final int MAX_RESPONSE_CHARS = 4 * 1024 * 1024;
    protected static final int GET_RETRY_TIMES = 2;
    protected static final long GET_RETRY_BASE_DELAY_MS = 350;
    protected static final long GET_RETRY_MAX_DELAY_MS = 1200;
    protected static final long ENCRYPT_BACKOFF_MS = 60 * 1000;
    protected static final long AUTH_REDIRECT_COOLDOWN_MS = 15000;
    protected static volatile long encryptDisabledUntil = 0;
    protected static int authFailStreak = 0;
    protected static boolean authWarned = false;
    protected static volatile long lastAuthRedirectAt = 0;
    protected static final Object refreshLock = new Object();
    protected static volatile boolean isRefreshing = false;
    private static final long GET_CACHE_SUCCESS_TTL_MS = 1500;
    private static final long GET_CACHE_ERROR_TTL_MS = 300;
    private static final long GET_IN_FLIGHT_WAIT_MS = 4000;
    private static final int GET_CACHE_MAX_SIZE = 180;
    private static final Object getCacheLock = new Object();
    private static final HashMap<String, GetCacheEntry> getCacheEntries = new HashMap<String, GetCacheEntry>();

    protected static class Result {
        int code;
        String data;

        Result(int c, String d) {
            code = c;
            data = d;
        }
    }

    private static class GetCacheEntry {
        boolean inFlight;
        long finishedAt;
        Result result;
    }

    protected static Result requestWithRefresh(String method, String path, JSONObject json, String token) {
        try {
            Result result = executeRequestWithRetry(method, path, json, token);
            if ((result.code == HttpURLConnection.HTTP_BAD_REQUEST
                    || result.code == HttpURLConnection.HTTP_UNAUTHORIZED)
                    && isInvalidSession(result.data)) {
                // 服务端返回 400/401 invalid_session 或 missing_session（如服务端重启清空加密会话）时，
                // 问题不是 token 而是 ECDH 会话失效：清会话后重试会重新握手。
                // 若走 refresh token 分支则永远无法修复，且会误触登录失效。
                CryptoUtil.clearSession();
                HttpAuthHelper.resetHandshakeGate();
                resetAuthStreak();
                result = executeRequestWithRetry(method, path, json, token);
            }
            if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED && shouldAttemptRefresh(path, token)
                    && !isInvalidSession(result.data)) {
                boolean refreshFailed = false;
                synchronized (refreshLock) {
                    if (!isRefreshing) {
                        isRefreshing = true;
                        try {
                            String newToken = HttpAuthHelper.refreshAccessToken();
                            if (newToken != null) {
                                resetAuthStreak();
                                Result refreshed = executeRequestWithRetry(method, path, json, newToken);
                                return applyAuthPolicy(refreshed, path, newToken);
                            }
                            refreshFailed = true;
                        } finally {
                            isRefreshing = false;
                        }
                    } else {
                        Thread.sleep(500);
                        String newToken = HttpAuthHelper.getSavedAccessToken();
                        if (newToken != null && !newToken.isEmpty()) {
                            Result refreshed = executeRequestWithRetry(method, path, json, newToken);
                            if (refreshed.code >= 200 && refreshed.code < 300) {
                                resetAuthStreak();
                            }
                            return applyAuthPolicy(refreshed, path, newToken);
                        }
                        refreshFailed = true;
                    }
                }
                if (refreshFailed) {
                    // 单次 refresh 失败不立即弹登录失效：交给 applyAuthPolicy 的
                    // authFailStreak 累计（连续 5 次才提示），避免偶发网络抖动误判掉登录。
                    authFailStreak++;
                }
            }
            return applyAuthPolicy(result, path, token);
        } catch (Exception e) {
            NetworkStateManager.getInstance().recordRequestFailure(-1);
            return new Result(-1, e.getMessage());
        }
    }

    private static Result executeRequestWithRetry(String method, String path, JSONObject json, String token) {
        if (!"GET".equals(method)) {
            try {
                return executeRequest(method, path, json, token);
            } catch (Exception e) {
                return new Result(-1, e.getMessage());
            }
        }

        if (!isGetCacheEligible(path)) {
            return executeGetRetryLoop(method, path, json, token);
        }

        String key = buildGetCacheKey(path, token);
        Result cached = readCachedGetResult(key);
        if (cached != null) {
            return cached;
        }

        boolean owner = beginGetInFlight(key);
        if (!owner) {
            Result shared = waitForGetInFlightResult(key);
            if (shared != null) {
                return shared;
            }
            owner = beginGetInFlight(key);
            if (!owner) {
                shared = waitForGetInFlightResult(key);
                if (shared != null) {
                    return shared;
                }
                owner = true;
            }
        }

        Result finalResult = executeGetRetryLoop(method, path, json, token);
        if (owner) {
            finishGetInFlight(key, finalResult);
        }
        return finalResult;
    }

    private static Result executeGetRetryLoop(String method, String path, JSONObject json, String token) {
        // 聊天历史首屏优先快速失败，避免弱网下 3×15 秒阻塞页面。
        int attempts = isGroupMessageHistoryPath(path) ? 2 : GET_RETRY_TIMES + 1;
        Result last = new Result(-1, "network_error");
        for (int i = 0; i < attempts; i++) {
            try {
                last = executeRequest(method, path, json, token);
            } catch (Exception e) {
                last = new Result(-1, e.getMessage());
            }
            if (!shouldRetryGet(last.code) || i >= attempts - 1) {
                return last;
            }
            sleepBeforeGetRetry(i);
        }
        return last;
    }

    private static boolean isGroupMessageHistoryPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.startsWith("/groups/messages") || lower.startsWith("/v1/groups/messages");
    }

    /**
     * v1 业务接口 → v2 路径映射（走 gateway 加密）。
     * 覆盖：写接口（发消息/已读/typing/红包/好友/群管理）+ 读列表（好友/群）。
     * 返回 null 表示无映射（保留原路径）。处理 /xxx 和 /v1/xxx 两种前缀。
     * 注意：已映射的接口强制走 v2，不再回落 v1（v2-only 测试版）。
     */
    private static String mapV1ToV2(String path) {
        if (path == null || android.os.Build.VERSION.SDK_INT <= 10) {
            return null; // Android 2.3 直接走原生稳定兼容的 v1 接口
        }
        String p = path;
        if (p.startsWith("/v1/")) {
            p = p.substring(4);
        }
        // 带 query 的路径：先剥离 query 判断，保留 query 拼到映射结果
        String query = "";
        int qIdx = p.indexOf('?');
        if (qIdx >= 0) {
            query = p.substring(qIdx);
            p = p.substring(0, qIdx);
        }
        String mapped = null;
        // 写接口（发消息暂时不映射 v2——gateway body 透传有兼容问题，先回 v1 保证可用）
        if (p.equals("/groups/read")) {
            mapped = "/v2/groups/read";
        } else if (p.equals("/groups/burn/open")) {
            mapped = "/v2/groups/burn/open";
        } else if (p.equals("/groups/typing")) {
            mapped = "/v2/groups/typing";
        } else if (p.equals("/direct/send")) {
            mapped = "/v2/direct/send";
        } else if (p.equals("/direct/read")) {
            mapped = "/v2/direct/read";
        } else if (p.equals("/direct/burn/open")) {
            mapped = "/v2/direct/burn/open";
        } else if (p.equals("/chats/typing")) {
            mapped = "/v2/chats/typing";
        } else if (p.equals("/redpackets/send")) {
            mapped = "/v2/redpackets/send";
        } else if (p.equals("/redpackets/claim")) {
            mapped = "/v2/redpackets/claim";
        } else if (p.equals("/friends/request")) {
            mapped = "/v2/friends/request";
        } else if (p.equals("/friends/respond")) {
            mapped = "/v2/friends/respond";
        } else if (p.equals("/friends/remark")) {
            mapped = "/v2/friends/remark";
        } else if (p.equals("/friends/delete")) {
            mapped = "/v2/friends/delete";
        } else if (p.equals("/groups/create")) {
            mapped = "/v2/groups/create";
        } else if (p.equals("/groups/join")) {
            mapped = "/v2/groups/join";
        } else if (p.equals("/groups/leave")) {
            mapped = "/v2/groups/leave";
        } else if (p.equals("/groups/approve")) {
            mapped = "/v2/groups/approve";
        } else if (p.equals("/groups/invite")) {
            mapped = "/v2/groups/invite";
        } else if (p.equals("/groups/invitations")) {
            mapped = "/v2/groups/invitations";
        } else if (p.equals("/groups/invitations/respond")) {
            mapped = "/v2/groups/invitations/respond";
        } else if (p.equals("/groups/admin")) {
            mapped = "/v2/groups/admin";
        } else if (p.equals("/groups/avatar")) {
            mapped = "/v2/groups/avatar";
        } else if (p.equals("/groups/kick")) {
            mapped = "/v2/groups/kick";
        } else if (p.equals("/groups/name")) {
            mapped = "/v2/groups/name";
        } else if (p.equals("/groups/settings")) {
            mapped = "/v2/groups/settings";
        } else if (p.equals("/groups/announcement")) {
            mapped = "/v2/groups/announcement";
        } else if (p.equals("/groups/announcement/read")) {
            mapped = "/v2/groups/announcement/read";
        } else if (p.equals("/groups/dissolve")) {
            mapped = "/v2/groups/dissolve";
        } else if (p.equals("/me/uid")) {
            mapped = "/v2/me/uid";
        } else if (p.equals("/me/profile")) {
            mapped = "/v2/me/profile";
        } else if (p.equals("/me/password")) {
            mapped = "/v2/me/password";
        } else if (p.equals("/me/delete/email/send")) {
            mapped = "/v2/me/delete/email/send";
        } else if (p.equals("/me/delete")) {
            mapped = "/v2/me/delete";
        } else if (p.equals("/me/group-invite-preference")) {
            mapped = "/v2/me/group-invite-preference";
        } else if (p.equals("/me/avatar")) {
            mapped = "/v2/me/avatar";
        } else if (p.equals("/me/cover")) {
            mapped = "/v2/me/cover";
        } else if (p.equals("/me/checkin")) {
            mapped = "/v2/me/checkin";
        } else if (p.equals("/me/scratch")) {
            mapped = "/v2/me/scratch";
        } else if (p.equals("/me/presence")) {
            mapped = "/v2/me/presence";
        } else if (p.equals("/me/devices")) {
            mapped = "/v2/me/devices";
        } else if (p.equals("/me/devices/cleanup")) {
            mapped = "/v2/me/devices/cleanup";
        } else if (p.equals("/me/bug-reports")) {
            mapped = "/v2/me/bug-reports";
        } else if (p.equals("/me/user-reports")) {
            mapped = "/v2/me/user-reports";
        } else if (p.equals("/me/group-reports")) {
            mapped = "/v2/me/group-reports";
        } else if (p.equals("/moments/like")) {
            mapped = "/v2/moments/like";
        } else if (p.equals("/moments/unlike")) {
            mapped = "/v2/moments/unlike";
        } else if (p.equals("/moments/delete")) {
            mapped = "/v2/moments/delete";
        } else if (p.equals("/moments/comment")) {
            mapped = "/v2/moments/comment";
        } else if (p.equals("/moments/comment/delete")) {
            mapped = "/v2/moments/comment/delete";
        } else if (p.equals("/moments")) {
            mapped = "/v2/moments";
        }
        // 读列表接口（GET）
        else if (p.equals("/friends/requests")) {
            mapped = "/v2/friends/requests";
        } else if (p.equals("/groups/list")) {
            mapped = "/v2/groups/list";
        } else if (p.equals("/groups/members")) {
            mapped = "/v2/groups/members";
        } else if (p.equals("/groups/requests")) {
            mapped = "/v2/groups/requests";
        } else if (p.equals("/users/profile")) {
            mapped = "/v2/users/profile";
        } else if (p.equals("/friends")) {
            mapped = "/v2/friends";
        } else if (p.equals("/moments/comments")) {
            mapped = "/v2/moments/comments";
        } else if (p.equals("/moments/user")) {
            mapped = "/v2/moments/user";
        } else if (p.equals("/moments/feed")) {
            mapped = "/v2/moments/feed";
        } else if (p.equals("/cip/store")) {
            mapped = "/v2/cip/store";
        }
        if (mapped != null) {
            return mapped + query;
        }
        return null;
    }

    private static boolean isGetCacheEligible(String path) {        if (path == null || path.length() == 0) {
            return false;
        }
        if (path.startsWith("/auth/") || path.startsWith("/ws")) {
            return false;
        }
        String lower = path.toLowerCase();
        if (lower.indexOf("/messages") >= 0
                || lower.indexOf("/typing") >= 0
                || lower.indexOf("/redpackets") >= 0
                || lower.indexOf("timestamp=") >= 0
                || lower.indexOf("nonce=") >= 0) {
            return false;
        }
        return true;
    }

    private static String buildGetCacheKey(String path, String token) {
        String safePath = path == null ? "" : path;
        int hash = token == null ? 0 : token.hashCode();
        return safePath + "#" + hash;
    }

    private static Result readCachedGetResult(String key) {
        long now = System.currentTimeMillis();
        synchronized (getCacheLock) {
            GetCacheEntry entry = getCacheEntries.get(key);
            if (entry == null || entry.inFlight || entry.result == null) {
                return null;
            }
            if (entry.result.code < 200 || entry.result.code >= 300) {
                if (now - entry.finishedAt > GET_CACHE_ERROR_TTL_MS) {
                    getCacheEntries.remove(key);
                }
                return null;
            }
            if (now - entry.finishedAt > GET_CACHE_SUCCESS_TTL_MS) {
                getCacheEntries.remove(key);
                return null;
            }
            return copyResult(entry.result);
        }
    }

    private static boolean beginGetInFlight(String key) {
        synchronized (getCacheLock) {
            GetCacheEntry entry = getCacheEntries.get(key);
            if (entry == null) {
                entry = new GetCacheEntry();
                getCacheEntries.put(key, entry);
            }
            if (entry.inFlight) {
                return false;
            }
            entry.inFlight = true;
            entry.result = null;
            entry.finishedAt = 0;
            return true;
        }
    }

    private static void finishGetInFlight(String key, Result result) {
        synchronized (getCacheLock) {
            GetCacheEntry entry = getCacheEntries.get(key);
            if (entry == null) {
                entry = new GetCacheEntry();
                getCacheEntries.put(key, entry);
            }
            entry.inFlight = false;
            entry.finishedAt = System.currentTimeMillis();
            entry.result = copyResult(result);
            pruneGetCacheLocked();
            getCacheLock.notifyAll();
        }
    }

    private static Result waitForGetInFlightResult(String key) {
        long deadline = System.currentTimeMillis() + GET_IN_FLIGHT_WAIT_MS;
        synchronized (getCacheLock) {
            while (true) {
                GetCacheEntry entry = getCacheEntries.get(key);
                if (entry == null) {
                    return null;
                }
                if (!entry.inFlight) {
                    if (entry.result == null) {
                        return null;
                    }
                    long age = System.currentTimeMillis() - entry.finishedAt;
                    if (entry.result.code >= 200 && entry.result.code < 300) {
                        if (age <= GET_CACHE_SUCCESS_TTL_MS) {
                            return copyResult(entry.result);
                        }
                    } else if (age <= GET_CACHE_ERROR_TTL_MS) {
                        return copyResult(entry.result);
                    }
                    return null;
                }
                long waitMs = deadline - System.currentTimeMillis();
                if (waitMs <= 0) {
                    return null;
                }
                try {
                    getCacheLock.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private static void pruneGetCacheLocked() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, GetCacheEntry>> iterator = getCacheEntries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, GetCacheEntry> item = iterator.next();
            GetCacheEntry entry = item.getValue();
            if (entry == null || entry.inFlight || entry.result == null) {
                continue;
            }
            long ttl = entry.result.code >= 200 && entry.result.code < 300
                    ? GET_CACHE_SUCCESS_TTL_MS
                    : GET_CACHE_ERROR_TTL_MS;
            if (now - entry.finishedAt > ttl) {
                iterator.remove();
            }
        }
        if (getCacheEntries.size() <= GET_CACHE_MAX_SIZE) {
            return;
        }
        iterator = getCacheEntries.entrySet().iterator();
        while (getCacheEntries.size() > GET_CACHE_MAX_SIZE && iterator.hasNext()) {
            Map.Entry<String, GetCacheEntry> item = iterator.next();
            GetCacheEntry entry = item.getValue();
            if (entry == null || entry.inFlight) {
                continue;
            }
            iterator.remove();
        }
    }

    private static Result copyResult(Result source) {
        if (source == null) {
            return null;
        }
        return new Result(source.code, source.data);
    }

    private static boolean shouldRetryGet(int code) {
        return code <= 0
                || code == HttpURLConnection.HTTP_CLIENT_TIMEOUT
                || code == HttpURLConnection.HTTP_UNAVAILABLE
                || code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT
                || code == 429
                || code == HttpURLConnection.HTTP_INTERNAL_ERROR
                || code == HttpURLConnection.HTTP_BAD_GATEWAY;
    }

    private static void sleepBeforeGetRetry(int attempt) {
        long delay = GET_RETRY_BASE_DELAY_MS * (1L << attempt);
        if (delay > GET_RETRY_MAX_DELAY_MS) {
            delay = GET_RETRY_MAX_DELAY_MS;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected static Result executeRequest(String method, String path, JSONObject json, String token) throws Exception {
        Result result = executeRequestOnce(method, path, json, token);
        if (result != null && (result.code == HttpURLConnection.HTTP_BAD_REQUEST
                || result.code == HttpURLConnection.HTTP_UNAUTHORIZED)
                && isInvalidSession(result.data)) {
            // 服务端重启后内存 ECDH 会话表清空：客户端带旧 sid 的加密请求返回
            // 400/401 invalid_session/missing_session。/auth/refresh、/auth/login 等直接入口没有
            // requestWithRefresh 的恢复逻辑，这里统一清会话重握手后重试一次。
            CryptoUtil.clearSession();
            HttpAuthHelper.resetHandshakeGate();
            result = executeRequestOnce(method, path, json, token);
        }
        return result;
    }

    private static Result executeRequestOnce(String method, String path, JSONObject json, String token) throws Exception {
        String originalPath = path;
        // v2 写接口映射：把 v1 业务写接口自动改走 v2（gateway 加密）。
        // 覆盖发消息/已读/正在输入/红包/好友/群管理/列表；无 v2 对应或映射失败的走原路径。
        String mappedPath = mapV1ToV2(path);
        if (mappedPath != null) {
            path = mappedPath;
        }
        // 网关模式：/v2 请求且已有加密会话时，折叠为 POST /v2/gateway（真实语义进密文）。
        // 无会话时先尝试主动建立会话；若仍无会话且该路径由 v1 映射而来，回退原 v1 路径（服务端免 sign 放行）。
        if (path != null && path.startsWith("/v2/") && !path.startsWith("/v2/gateway")) {
            if (!CryptoUtil.hasSession()) {
                HttpAuthHelper.ensureSession();
            }
            if (CryptoUtil.hasSession()) {
                return executeV2Gateway(method, path, json, token);
            } else if (mappedPath != null) {
                // 回退到未映射前的原 v1 路径，防止被 v2SignMiddleware missing_session 拦截
                path = originalPath;
            }
        }
        // 路径已含版本前缀（/v1/ 或 /v2/）时用无前缀主机，避免拼成 /v1/v2/...。
        URL url;
        if (path != null && (path.startsWith("/v1/") || path.startsWith("/v2/"))) {
            url = new URL(BASE_HOST + path);
        } else {
            url = new URL(BASE_URL + path);
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        boolean fastGroupHistory = "GET".equals(method) && isGroupMessageHistoryPath(path);
        conn.setConnectTimeout(fastGroupHistory ? 6000 : CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(fastGroupHistory ? 10000 : READ_TIMEOUT_MS);
        conn.setUseCaches("GET".equals(method));
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        // Android 2.3 的 HttpURLConnection 连接池在 keep-alive 下复用死连接会抛出 EOFException / Connection reset。
        // API <= 10 使用 close，高版本使用 keep-alive 复用。
        if (android.os.Build.VERSION.SDK_INT <= 10) {
            conn.setRequestProperty("Connection", "close");
        } else {
            conn.setRequestProperty("Connection", "keep-alive");
        }
        conn.setRequestProperty("X-Burn-Secure", "1");
        boolean encrypt = shouldEncrypt(path);
        if (encrypt) {
            if (!HttpAuthHelper.ensureSession()) {
                encryptDisabledUntil = System.currentTimeMillis() + ENCRYPT_BACKOFF_MS;
                CryptoUtil.clearSession();
                encrypt = false;
            } else {
                conn.setRequestProperty("X-Enc", "1");
                // 请求服务端先压缩 JSON 再加密，避免对随机密文做无效 gzip。
                conn.setRequestProperty("X-Enc-Compression", "gzip");
                String sessionId = CryptoUtil.getSessionId();
                if (sessionId != null && sessionId.length() > 0) {
                    conn.setRequestProperty("X-Session", sessionId);
                }
            }
        }
        if (token != null && token.length() > 0) {
            // 有会话时：token 加密进 X-Auth 头（抓包看不到），不再发明文 Authorization。
            // 无会话（老设备）回退明文 Authorization（服务端兼容）。
            if (CryptoUtil.hasSession()) {
                String encAuth = null;
                try {
                    encAuth = CryptoUtil.encrypt("Bearer " + token);
                } catch (Throwable ignored) {
                    encAuth = null;
                }
                if (encAuth != null) {
                    conn.setRequestProperty("X-Auth", encAuth);
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
        }
        // /v2 路由防护：请求签名（X-Ts/X-Nonce/X-Sign）+ 设备绑定（X-Device-Id）。
        // 服务端灰度期有签名则校验、无签名放行；强制期缺签名拒绝。
        // 有会话时始终附加签名，保证新客户端覆盖后平滑切强制。
        addV2SignatureHeaders(conn, method, path);
        if (json != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                String payload = json.toString();
                if (encrypt) {
                    payload = CryptoUtil.encrypt(payload);
                }
                os.write(payload.getBytes("UTF-8"));
                os.flush();
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        try {
            int code = conn.getResponseCode();
            String body = readResponseBody(conn, code);
            if (encrypt) {
                boolean compressed = "gzip".equalsIgnoreCase(
                        conn.getHeaderField("X-Enc-Compression"));
                String decrypted = CryptoUtil.decryptIfNeeded(body, compressed);
                if (decrypted != null) {
                    body = decrypted;
                }
            }
            return new Result(code, body);
        } finally {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    protected static boolean shouldAttemptRefresh(String path, String token) {
        if (token == null || token.length() == 0) {
            return false;
        }
        return !(path.startsWith("/auth/login") || path.startsWith("/auth/refresh"));
    }

    private static boolean shouldEncrypt(String path) {
        if (System.currentTimeMillis() < encryptDisabledUntil) {
            return false;
        }
        return path != null && !path.startsWith("/auth/handshake") && CryptoUtil.isEcdhSupported();
    }

    public static boolean shouldSuppressAuthToast(int code, String error) {
        if (code != HttpURLConnection.HTTP_UNAUTHORIZED || error == null) {
            return false;
        }
        return AUTH_SILENT.equals(error) || AUTH_WARNING.equals(error);
    }

    protected static Result applyAuthPolicy(Result result, String path, String token) {
        if (result == null) {
            return result;
        }
        if (!shouldAttemptRefresh(path, token)) {
            if (result.code >= 200 && result.code < 300) {
                resetAuthStreak();
            }
            return result;
        }
        if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            if (!isRefreshing) {
                authFailStreak++;
                if (authFailStreak >= 5 && !authWarned) {
                    authWarned = true;
                    result.data = AUTH_WARNING;
                } else {
                    result.data = AUTH_SILENT;
                }
            } else {
                result.data = AUTH_SILENT;
            }
            return result;
        }
        if (result.code >= 200 && result.code < 300) {
            NetworkStateManager.getInstance().recordRequestSuccess();
            resetAuthStreak();
        } else {
            NetworkStateManager.getInstance().recordRequestFailure(result.code);
            resetAuthStreak();
        }
        return result;
    }

    private static void resetAuthStreak() {
        authFailStreak = 0;
        authWarned = false;
    }

    public static void showAuthWarning() {
        final Context context = OldChatApplication.getAppContext();
        if (context == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, "登录已过期，请重新登录", Toast.LENGTH_LONG).show();
                maybeRedirectToLogin(context);
            }
        });
    }

    private static void maybeRedirectToLogin(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastAuthRedirectAt < AUTH_REDIRECT_COOLDOWN_MS) {
            return;
        }
        lastAuthRedirectAt = now;
        com.im.oldchat.util.ActivityTaskCompat.openCleanLogin(context);
    }

    private static boolean isInvalidSession(String body) {
        if (body == null) {
            return false;
        }
        return body.contains("\"code\":\"invalid_session\"")
                || body.contains("invalid_session")
                || body.contains("\"code\":\"missing_session\"")
                || body.contains("missing_session");
    }

    protected static String readResponseBody(HttpURLConnection conn, int code) {
        if (conn == null) {
            return "";
        }
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
            is = null;
        }
        if (is == null) {
            return "";
        }
        String encoding = null;
        try {
            encoding = conn.getContentEncoding();
        } catch (Exception ignored) {
        }
        if (encoding != null && encoding.toLowerCase().indexOf("gzip") >= 0) {
            try {
                is = new GZIPInputStream(is);
            } catch (Exception ignored) {
            }
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                // 防御服务端异常返回超大响应导致内存暴涨 OOM：
                // 正常业务 JSON 响应远小于 4MB，超过即丢弃本次响应。
                if (sb.length() > MAX_RESPONSE_CHARS) {
                    return "";
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            } else {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static boolean ensureSession() {
        return HttpAuthHelper.ensureSession();
    }

    /**
     * 网关模式：把 /v2 请求打包进加密 body，POST 到 /v2/gateway。
     * body 明文：{"m":"GET","p":"/v2/groups/messages/v2","q":"group_id=..."}
     * 由既有 X-Enc 机制（ECDH 会话密钥）整体加密。
     * 响应：{"code":200,"body":"真实 handler JSON"}（同样加密）。
     */
    private static Result executeV2Gateway(String method, String path, JSONObject json, String token) throws Exception {
        // 拆分 path 为 p（路径）与 q（query）
        String p = path;
        String q = "";
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            p = path.substring(0, qIdx);
            q = path.substring(qIdx + 1);
        }
        JSONObject inner = new JSONObject();
        inner.put("m", method);
        inner.put("p", p);
        inner.put("q", q);
        // 携带原始业务 body（发消息/已读等 POST 参数），服务端透传给内部 handler。
        if (json != null) {
            inner.put("b", json);
        }

        // 走 /v2/gateway（executeRequest 会递归？不会——gateway 路径被排除在网关分支外）
        Result result = executeRequest("POST", "/v2/gateway", inner, token);
        if (result.code != 200) {
            return result;
        }
        // 解析包装响应
        try {
            JSONObject wrapper = new JSONObject(result.data);
            int innerCode = wrapper.optInt("code", 200);
            Object bodyObj = wrapper.opt("body");
            String innerBody = "";
            if (bodyObj instanceof JSONObject || bodyObj instanceof JSONArray) {
                innerBody = bodyObj.toString();
            } else if (bodyObj != null) {
                innerBody = String.valueOf(bodyObj);
            }
            return new Result(innerCode, innerBody);
        } catch (Exception e) {
            return new Result(-1, "bad_gateway_response");
        }
    }

    /**
     * 为 /v2 请求附加签名与设备头。
     * 签名串：HMAC-SHA256(sessionMacKey, METHOD\nPATH\nTS\nNONCE)，与服务端一致。
     * 仅当会话存在时签名；无会话时跳过（服务端灰度期放行、强制期拒绝）。
     */
    private static void addV2SignatureHeaders(HttpURLConnection conn, String method, String path) {
        try {
            if (path == null || !(path.startsWith("/v2/") || path.startsWith("/v1/v2/"))) {
                return;
            }
            // 签名用不含 query 的路径（服务端 r.URL.Path 一致）。
            String cleanPath = path;
            int q = path.indexOf('?');
            if (q >= 0) {
                cleanPath = path.substring(0, q);
            }
            if (!CryptoUtil.hasSession()) {
                return;
            }
            String ts = String.valueOf(System.currentTimeMillis() / 1000L);
            String nonce = CryptoUtil.randomNonce();
            String sign = CryptoUtil.signRequest(method, cleanPath, ts, nonce);
            if (sign == null) {
                return;
            }
            conn.setRequestProperty("X-Ts", ts);
            conn.setRequestProperty("X-Nonce", nonce);
            conn.setRequestProperty("X-Sign", sign);
            // 设备绑定：登录上报的稳定设备 ID（ANDROID_ID）。
            String deviceId = null;
            android.content.Context ctx = OldChatApplication.getAppContext();
            if (ctx != null) {
                deviceId = com.im.oldchat.util.DeviceInfoUtil.getDeviceId(ctx);
            }
            if (deviceId != null && deviceId.length() > 0) {
                conn.setRequestProperty("X-Device-Id", deviceId);
            }
        } catch (Throwable ignored) {
            // 签名失败不阻断请求（灰度期服务端放行）。
        }
    }
}
