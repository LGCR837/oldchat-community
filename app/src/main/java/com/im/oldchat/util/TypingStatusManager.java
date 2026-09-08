package com.im.oldchat.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.models.TypingStatus;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TypingStatusManager {
    private static TypingStatusManager instance;
    private final Map<String, TypingStatus> typingUsers = new ConcurrentHashMap<>();
    private final Map<String, TypingListener> listeners = new ConcurrentHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> sendTasks = new ConcurrentHashMap<>();
    private final Map<String, Runnable> checkTasks = new ConcurrentHashMap<>();
    private final Map<String, Runnable> stopTasks = new ConcurrentHashMap<>();
    private static final long TYPING_TIMEOUT = 3000;
    // 服务端 TTL 为 8 秒；5 秒续租可保持相同显示效果，同时减少约 40% typing 上报。
    private static final long SEND_INTERVAL = 5000;
    // 仅在 WebSocket 断开时作为兜底轮询，降低后台 HTTP 唤醒与电量消耗。
    private static final long CHECK_INTERVAL = 2500;
    private static final long STOP_GRACE_MS = 3000;

    public interface TypingListener {
        void onTypingStatusChanged(String uid, boolean isTyping);
    }

    private TypingStatusManager() {}

    public static synchronized TypingStatusManager getInstance() {
        if (instance == null) {
            instance = new TypingStatusManager();
        }
        return instance;
    }

    public void registerListener(String chatId, TypingListener listener) {
        listeners.put(chatId, listener);
    }

    public void unregisterListener(String chatId) {
        listeners.remove(chatId);
        stopCheckingTyping(chatId);
    }

    public void startTyping(final Context context, final String token, final String chatId, final boolean isGroup) {
        String key = typingTaskKey(chatId, isGroup, "send");
        if (sendTasks.containsKey(key)) {
            return;
        }

        Runnable task = new Runnable() {
            @Override
            public void run() {
                sendTypingStatus(context, token, chatId, true, isGroup);
                handler.postDelayed(this, SEND_INTERVAL);
            }
        };
        sendTasks.put(key, task);
        handler.post(task);
    }

    public void stopTyping(final Context context, final String token, final String chatId, final boolean isGroup) {
        String key = typingTaskKey(chatId, isGroup, "send");
        Runnable task = sendTasks.remove(key);
        if (task != null) {
            handler.removeCallbacks(task);
        }
        sendTypingStatus(context, token, chatId, false, isGroup);
    }

    public void startCheckingTyping(final Context context, final String token, final String chatId, final boolean isGroup) {
        String key = typingTaskKey(chatId, isGroup, "check");
        if (checkTasks.containsKey(key)) {
            return;
        }

        Runnable task = new Runnable() {
            @Override
            public void run() {
                checkTypingStatus(context, token, chatId, isGroup);
                handler.postDelayed(this, CHECK_INTERVAL);
            }
        };
        checkTasks.put(key, task);
        handler.post(task);
    }

    public void stopCheckingTyping(String chatId) {
        stopCheckingTask(chatId, false);
        stopCheckingTask(chatId, true);
        clearTypingUsers(chatId);
    }

    private void stopCheckingTask(String chatId, boolean isGroup) {
        String key = typingTaskKey(chatId, isGroup, "check");
        Runnable task = checkTasks.remove(key);
        if (task != null) {
            handler.removeCallbacks(task);
        }
    }

    private static String typingTaskKey(String chatId, boolean isGroup, String kind) {
        return (isGroup ? "g:" : "d:") + chatId + ":" + kind;
    }

    public boolean isUserTyping(String chatId, String uid) {
        String normalizedUid = normalizeUid(uid);
        if (normalizedUid.isEmpty()) {
            return false;
        }
        String key = chatId + "_" + normalizedUid;
        TypingStatus status = typingUsers.get(key);
        if (status == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - status.timestamp > TYPING_TIMEOUT) {
            typingUsers.remove(key);
            notifyListener(chatId, normalizedUid, false);
            return false;
        }
        return status.isTyping;
    }

    private void sendTypingStatus(Context context, String token, String chatId, boolean isTyping, boolean isGroup) {
        if (context == null || token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) {
            return;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("is_typing", isTyping);
            body.put("is_group", isGroup);

            String endpoint = isGroup ? "/groups/typing" : "/chats/typing";
            HttpUtil.post(endpoint, body, token, new HttpUtil.Callback() {
                @Override public void onSuccess(String data) {}
                @Override public void onError(int code, String message) {}
            });
        } catch (Exception e) {}
    }

    private void checkTypingStatus(Context context, String token, String chatId, boolean isGroup) {
        // 在线时 typing 由 WebSocket 推送；HTTP 只作为断线和旧链路失败时的兜底。
        if (WSManager.getInstance().isConnected()) {
            return;
        }
        if (context == null || token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) {
            return;
        }

        String endpoint = isGroup
            ? "/groups/" + chatId + "/typing"
            : "/chats/" + chatId + "/typing";

        HttpUtil.get(endpoint, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    if (response == null || response.isEmpty()) {
                        return;
                    }

                    JSONObject json = new JSONObject(response);
                    if (json.has("users")) {
                        org.json.JSONArray users = json.getJSONArray("users");
                        for (int i = 0; i < users.length(); i++) {
                            JSONObject user = users.getJSONObject(i);
                            String uid = normalizeUid(user.optString("uid"));
                            String avatarUrl = user.optString("avatar_url");
                            boolean isTyping = user.optBoolean("is_typing", false);

                            if (uid != null && !uid.isEmpty()) {
                                updateTypingStatus(context, chatId, uid, isTyping, avatarUrl);
                            }
                        }
                    }
                } catch (Exception e) {}
            }
            @Override public void onError(int code, String message) {}
        });
    }

    private void updateTypingStatus(Context context, String chatId, String uid, boolean isTyping,
                                    String avatarUrl) {
        String normalizedUid = normalizeUid(uid);
        if (normalizedUid.isEmpty()) {
            return;
        }
        String key = chatId + "_" + normalizedUid;
        String resolvedAvatarUrl = avatarUrl == null ? "" : avatarUrl.trim();
        if (isTyping) {
            cancelStopTask(key);
            TypingStatus status = typingUsers.get(key);
            if (status == null) {
                status = new TypingStatus();
                status.uid = normalizedUid;
                status.chatId = chatId;
            }
            if (!resolvedAvatarUrl.isEmpty()) {
                status.avatarUrl = resolvedAvatarUrl;
                TypingAvatarCache.seed(context, normalizedUid, resolvedAvatarUrl);
            }
            status.isTyping = true;
            status.timestamp = System.currentTimeMillis();
            typingUsers.put(key, status);
            notifyListener(chatId, normalizedUid, true);
            return;
        }
        TypingStatus existing = typingUsers.get(key);
        if (existing == null) {
            notifyListener(chatId, normalizedUid, false);
            return;
        }
        scheduleStop(chatId, normalizedUid, key);
    }

    public void handleRemoteTyping(Context context, String chatId, String uid, boolean isTyping,
                                   String avatarUrl) {
        if (chatId == null || chatId.isEmpty() || uid == null || uid.isEmpty()) {
            return;
        }
        updateTypingStatus(context, chatId, uid, isTyping, avatarUrl);
    }

    public void clearTypingOnMessage(String chatId, String uid) {
        if (chatId == null || chatId.isEmpty() || uid == null || uid.isEmpty()) {
            return;
        }
        String normalizedUid = normalizeUid(uid);
        if (normalizedUid.isEmpty()) {
            return;
        }
        String key = chatId + "_" + normalizedUid;
        cancelStopTask(key);
        typingUsers.remove(key);
        notifyListener(chatId, normalizedUid, false);
    }

    private void notifyListener(final String chatId, final String uid, final boolean isTyping) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                TypingListener listener = listeners.get(chatId);
                if (listener != null) listener.onTypingStatusChanged(uid, isTyping);
            }
        });
    }

    private void clearTypingUsers(String chatId) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>(typingUsers.keySet());
        for (String key : keys) {
            if (key.startsWith(chatId + "_")) {
                typingUsers.remove(key);
            }
        }
        java.util.ArrayList<String> stopKeys = new java.util.ArrayList<>(stopTasks.keySet());
        for (String key : stopKeys) {
            if (key.startsWith(chatId + "_")) {
                Runnable task = stopTasks.remove(key);
                if (task != null) {
                    handler.removeCallbacks(task);
                }
            }
        }
    }

    private void scheduleStop(final String chatId, final String uid, final String key) {
        cancelStopTask(key);
        TypingStatus status = typingUsers.get(key);
        if (status == null) {
            status = new TypingStatus();
            status.uid = uid;
            status.chatId = chatId;
        }
        status.isTyping = true;
        status.timestamp = System.currentTimeMillis();
        typingUsers.put(key, status);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                Runnable current = stopTasks.get(key);
                if (current != this) {
                    return;
                }
                stopTasks.remove(key);
                typingUsers.remove(key);
                notifyListener(chatId, uid, false);
            }
        };
        stopTasks.put(key, task);
        handler.postDelayed(task, STOP_GRACE_MS);
    }

    private void cancelStopTask(String key) {
        Runnable task = stopTasks.remove(key);
        if (task != null) handler.removeCallbacks(task);
    }

    private String normalizeUid(String uid) {
        if (uid == null) return "";
        String out = uid.trim();
        while (out.startsWith("@")) {
            out = out.substring(1).trim();
        }
        return out.length() == 0 ? "" : out.toUpperCase(Locale.US);
    }
}
