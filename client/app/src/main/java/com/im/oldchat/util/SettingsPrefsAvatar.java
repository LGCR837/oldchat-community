package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsPrefsAvatar {
    private static final String PREFS_SETTINGS = "settings";
    private static final String KEY_HIDE_AVATAR = "hide_avatar_enabled";

    private SettingsPrefsAvatar() {}

    public static boolean isHideAvatarEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return prefs(context).getBoolean(KEY_HIDE_AVATAR, false);
    }

    public static void setHideAvatarEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(KEY_HIDE_AVATAR, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
    }
}
