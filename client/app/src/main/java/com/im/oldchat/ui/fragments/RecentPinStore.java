package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

final class RecentPinStore {
    private static final String PREFS = "recent_pin";
    private static final String NCUID_PREFIX = "nc:";

    private RecentPinStore() {
    }

    static boolean isPinned(Context context, boolean isGroup, String id) {
        return isPinned(context, isGroup, id, null);
    }

    static boolean isPinned(Context context, boolean isGroup, String id, String ncuid) {
        if (context == null || id == null || id.isEmpty()) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(buildKey(isGroup, id), false)) {
            return true;
        }
        // 私聊：好友改过 UID 后，用不可变 NCUID 兜底匹配，避免置顶状态丢失。
        if (!isGroup && ncuid != null && !ncuid.isEmpty()) {
            return prefs.getBoolean(NCUID_PREFIX + ncuid.trim().toUpperCase(Locale.US), false);
        }
        return false;
    }

    static void setPinned(Context context, boolean isGroup, String id, boolean pinned) {
        setPinned(context, isGroup, id, null, pinned);
    }

    static void setPinned(Context context, boolean isGroup, String id, String ncuid, boolean pinned) {
        if (context == null || id == null || id.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putBoolean(buildKey(isGroup, id), pinned);
        if (!isGroup && ncuid != null && !ncuid.isEmpty()) {
            // 同时维护 NCUID 键：UID 变化后仍能保持置顶状态。
            editor.putBoolean(NCUID_PREFIX + ncuid.trim().toUpperCase(Locale.US), pinned);
        }
        editor.apply();
    }

    private static String buildKey(boolean isGroup, String id) {
        return (isGroup ? "g:" : "u:") + id;
    }
}
