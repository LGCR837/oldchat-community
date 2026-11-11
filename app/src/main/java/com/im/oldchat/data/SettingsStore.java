package com.im.oldchat.data;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsStore {
    private static final String PREFS = "oldchat_settings";
    private static final String KEY_NOTIFY_ENABLED = "notify_enabled";
    private static final String KEY_PUBLIC_COURT_ENABLED = "public_court_enabled";
    private static final String KEY_MUTE_PREFIX = "mute_";

    public static boolean isNotifyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY_ENABLED, true);
    }

    public static void setNotifyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ENABLED, enabled).apply();
    }

    public static boolean isPublicCourtEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PUBLIC_COURT_ENABLED, true);
    }

    public static void setPublicCourtEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PUBLIC_COURT_ENABLED, enabled).apply();
    }

    public static boolean isConversationMuted(Context context, String conversationId, boolean isGroup) {
        return isConversationMuted(context, conversationId, conversationId, isGroup);
    }

    public static boolean isConversationMuted(Context context, String conversationId,
                                              String legacyConversationId, boolean isGroup) {
        if (context == null || conversationId == null || conversationId.trim().length() == 0) {
            return false;
        }
        SharedPreferences settingsPrefs = prefs(context);
        String accountKey = resolveConversationAccountKey(context);
        String legacyAccountKey = resolveLegacyAccountKey(context);
        if (isConversationMuted(settingsPrefs, accountKey, conversationId, isGroup)) return true;
        if (legacyConversationId != null && legacyConversationId.length() > 0
                && isConversationMuted(settingsPrefs, accountKey, legacyConversationId, isGroup)) return true;
        if (!legacyAccountKey.equals(accountKey)) {
            if (isConversationMuted(settingsPrefs, legacyAccountKey, conversationId, isGroup)) return true;
            return legacyConversationId != null && legacyConversationId.length() > 0
                    && isConversationMuted(settingsPrefs, legacyAccountKey, legacyConversationId, isGroup);
        }
        return false;
    }

    public static void setConversationMuted(Context context, String conversationId, boolean isGroup, boolean muted) {
        setConversationMuted(context, conversationId, conversationId, isGroup, muted);
    }

    public static void setConversationMuted(Context context, String conversationId,
                                            String legacyConversationId, boolean isGroup, boolean muted) {
        if (context == null || conversationId == null || conversationId.trim().length() == 0) {
            return;
        }
        SharedPreferences settingsPrefs = prefs(context);
        String accountKey = resolveConversationAccountKey(context);
        String legacyAccountKey = resolveLegacyAccountKey(context);
        SharedPreferences.Editor editor = settingsPrefs.edit();
        editor.remove(buildMuteKey(accountKey, conversationId, isGroup));
        if (legacyConversationId != null && legacyConversationId.length() > 0) {
            editor.remove(buildMuteKey(accountKey, legacyConversationId, isGroup));
        }
        if (!legacyAccountKey.equals(accountKey)) {
            editor.remove(buildMuteKey(legacyAccountKey, conversationId, isGroup));
            if (legacyConversationId != null && legacyConversationId.length() > 0) {
                editor.remove(buildMuteKey(legacyAccountKey, legacyConversationId, isGroup));
            }
        }
        editor.putBoolean(buildMuteKey(accountKey, conversationId, isGroup), muted).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isConversationMuted(SharedPreferences prefs, String accountKey,
                                              String conversationId, boolean isGroup) {
        if (prefs == null || conversationId == null || conversationId.trim().length() == 0) {
            return false;
        }
        return prefs.getBoolean(buildMuteKey(accountKey, conversationId, isGroup), false);
    }

    public static String resolveConversationAccountKey(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences authPrefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
        return resolveConversationAccountKey(authPrefs);
    }

    public static String resolveConversationAccountKey(SharedPreferences authPrefs) {
        if (authPrefs == null) {
            return "";
        }
        String ncuid = authPrefs.getString("my_ncuid", "");
        if (ncuid != null && ncuid.length() > 0) {
            return sanitizeKey(ncuid);
        }
        String uid = authPrefs.getString("my_uid", "");
        return uid == null ? "" : sanitizeKey(uid);
    }

    private static String resolveLegacyAccountKey(Context context) {
        if (context == null) return "";
        SharedPreferences authPrefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
        String uid = authPrefs.getString("my_uid", "");
        return uid == null ? "" : sanitizeKey(uid);
    }

    private static String buildMuteKey(Context context, String conversationId, boolean isGroup) {
        String account = resolveConversationAccountKey(context);
        return buildMuteKey(account, conversationId, isGroup);
    }

    private static String buildMuteKey(String account, String conversationId, boolean isGroup) {
        String safeConversation = sanitizeKey(conversationId);
        String type = isGroup ? "g" : "u";
        return KEY_MUTE_PREFIX + account + "_" + type + "_" + safeConversation;
    }

    private static String sanitizeKey(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z') || c == '_') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
