package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 音乐大厅相关偏好设置存储 (Java 7 / minSdk 9)
 */
public final class SettingsPrefsMusic {
    private static final String PREFS_SETTINGS = "settings";
    private static final String KEY_LYRIC_BOUNCE = "music_lyric_bounce_enabled";
    private static final String KEY_MUSIC_DYNAMIC_BG = "music_dynamic_bg_enabled";
    private static final String KEY_LYRIC_COLOR_MODE = "music_lyric_color_mode";
    private static final String KEY_MUSIC_BG_STYLE = "music_bg_style";

    private SettingsPrefsMusic() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
    }

    public static boolean isLyricBounceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LYRIC_BOUNCE, true);
    }

    public static void setLyricBounceEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_LYRIC_BOUNCE, enabled).apply();
    }

    public static boolean isMusicDynamicBgEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MUSIC_DYNAMIC_BG, true);
    }

    public static void setMusicDynamicBgEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MUSIC_DYNAMIC_BG, enabled).apply();
    }

    public static int getLyricColorMode(Context context) {
        return prefs(context).getInt(KEY_LYRIC_COLOR_MODE, 0);
    }

    public static void setLyricColorMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_LYRIC_COLOR_MODE, mode).apply();
    }

    public static int getMusicBgStyle(Context context) {
        return prefs(context).getInt(KEY_MUSIC_BG_STYLE, 0);
    }

    public static void setMusicBgStyle(Context context, int style) {
        prefs(context).edit().putInt(KEY_MUSIC_BG_STYLE, style).apply();
    }
}
