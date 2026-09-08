package com.im.oldchat.lua;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 保存和恢复 VibeCoding 聊天记录。
 * 使用 SharedPreferences 存储 JSON 格式的消息数组，避免大文件 IO。
 * 限制最大保存条数以控制内存。
 */
public final class VibeChatStore {
    private static final String PREFS = "cip_vibe_chat";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_TRANSCRIPT = "transcript";
    private static final int MAX_SAVED_TRANSCRIPT = 60000;

    private VibeChatStore() { }

    public static JSONArray loadMessages(Context context) {
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MESSAGES, "");
            if (raw == null || raw.length() == 0) return null;
            return new JSONArray(raw);
        } catch (Exception e) { return null; }
    }

    public static String loadTranscript(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TRANSCRIPT, "");
    }

    public static void save(Context context, JSONArray messages, String transcript) {
        try {
            String trimmed = transcript;
            if (trimmed != null && trimmed.length() > MAX_SAVED_TRANSCRIPT) {
                trimmed = "…（已截断）…\n" + trimmed.substring(trimmed.length() - MAX_SAVED_TRANSCRIPT);
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_MESSAGES, messages != null ? messages.toString() : "")
                    .putString(KEY_TRANSCRIPT, trimmed != null ? trimmed : "")
                    .apply();
        } catch (Exception ignored) { }
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
