package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

public final class SettingsPrefs {
    private static final String PREFS_SETTINGS = "settings";
    private static final String KEY_NOTIFY = "notify_enabled";
    private static final String KEY_DARK_MODE = "dark_mode_enabled";
    private static final String KEY_FONT_SIZE = "font_size_index";
    private static final String KEY_PRIVACY_AGREED = "privacy_agreed";
    private static final String KEY_TYPING_INDICATOR = "typing_indicator_enabled";
    private static final String KEY_ENTER_SEND = "enter_send_enabled";
    private static final String KEY_OLD_VIEW_ENTRY = "old_view_entry_enabled";
    private static final String KEY_DISCOVER_CLASSIC_LAYOUT = "discover_classic_layout_enabled";
    private static final String KEY_DISCOVER_RANDOM_SOLID_COLOR = "discover_random_solid_color_enabled";
    private static final String KEY_HOME_NEWS = "home_news_enabled";
    private static final String KEY_HOME_STYLE = "home_style";
    private static final String KEY_THEME_COLOR_INDEX = "theme_color_index";
    private static final String KEY_ROUNDED_BUTTONS = "rounded_buttons_enabled";
    private static final String KEY_BUBBLE_ROUNDED = "bubble_rounded_enabled";
    private static final String KEY_SENDER_NAME_OUTSIDE_BUBBLE = "sender_name_outside_bubble_enabled";
    private static final String KEY_BUTTON_3D_EFFECT = "button_3d_effect_enabled";
    private static final String KEY_GROUP_COLORED_NAME = "group_colored_name_enabled";
    private static final String KEY_BUBBLE_TIME_NEW_LINE = "bubble_time_new_line_enabled";
    private static final String KEY_APP_DPI_SCALE_INDEX = "app_dpi_scale_index";
    private static final String KEY_APP_DPI_SCALE_PERCENT = "app_dpi_scale_percent";
    private static final String KEY_APP_DPI_FIRST_PROMPT_SHOWN = "app_dpi_first_prompt_shown";
    private static final String KEY_DEV_MODE = "dev_mode_enabled";
    private static final Object APPEARANCE_LOCK = new Object();
    private static boolean appearanceCacheLoaded;
    private static boolean cachedDarkModeEnabled;
    private static int cachedThemeColorIndex = -1;

    private SettingsPrefs() {}

    public static boolean isNotifyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY, true);
    }

    public static void setNotifyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFY, enabled).apply();
    }

    public static boolean isDarkModeSupported() {
        return Build.VERSION.SDK_INT >= 14;
    }

    public static boolean isDarkModeEnabled(Context context) {
        if (!isDarkModeSupported()) return false;
        ensureAppearanceCache(context);
        synchronized (APPEARANCE_LOCK) {
            return cachedDarkModeEnabled;
        }
    }

    public static boolean hasDarkModePreference(Context context) {
        return prefs(context).contains(KEY_DARK_MODE);
    }

    public static void setDarkModeEnabled(Context context, boolean enabled) {
        enabled = enabled && isDarkModeSupported();
        ensureAppearanceCache(context);
        prefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply();
        synchronized (APPEARANCE_LOCK) {
            cachedDarkModeEnabled = enabled;
            appearanceCacheLoaded = true;
        }
    }

    public static int getFontSizeIndex(Context context) {
        return prefs(context).getInt(KEY_FONT_SIZE, 1);
    }

    public static void setFontSizeIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_FONT_SIZE, index).apply();
    }

    public static float getFontScale(Context context) {
        int index = getFontSizeIndex(context);
        switch (index) {
            case 0: return 0.9f;
            case 2: return 1.15f;
            case 3: return 1.3f;
            default: return 1.0f;
        }
    }

    public static boolean isPrivacyAgreed(Context context) {
        return prefs(context).getBoolean(KEY_PRIVACY_AGREED, false);
    }

    public static void setPrivacyAgreed(Context context, boolean agreed) {
        prefs(context).edit().putBoolean(KEY_PRIVACY_AGREED, agreed).apply();
    }

    public static boolean isTypingIndicatorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TYPING_INDICATOR, true);
    }

    public static void setTypingIndicatorEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TYPING_INDICATOR, enabled).apply();
    }

    public static boolean isEnterSendEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENTER_SEND, false);
    }

    public static void setEnterSendEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENTER_SEND, enabled).apply();
    }

    public static boolean isOldViewEntryEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OLD_VIEW_ENTRY, true);
    }

    public static void setOldViewEntryEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OLD_VIEW_ENTRY, enabled).apply();
    }

    public static boolean isDiscoverClassicLayoutEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DISCOVER_CLASSIC_LAYOUT, true);
    }

    public static void setDiscoverClassicLayoutEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DISCOVER_CLASSIC_LAYOUT, enabled).apply();
    }

    public static boolean isDiscoverRandomSolidColorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DISCOVER_RANDOM_SOLID_COLOR, false);
    }

    public static void setDiscoverRandomSolidColorEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DISCOVER_RANDOM_SOLID_COLOR, enabled).apply();
    }

    public static boolean isHomeNewsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HOME_NEWS, true);
    }

    public static void setHomeNewsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HOME_NEWS, enabled).apply();
    }

    public static int getHomeStyle(Context context) {
        int style = prefs(context).getInt(KEY_HOME_STYLE, 1);
        return style == 0 ? 0 : 1;
    }

    public static void setHomeStyle(Context context, int style) {
        prefs(context).edit().putInt(KEY_HOME_STYLE, style == 0 ? 0 : 1).apply();
    }

    public static boolean isClassicHomeStyle(Context context) {
        return getHomeStyle(context) == 0;
    }

    public static int getThemeColorIndex(Context context) {
        ensureAppearanceCache(context);
        synchronized (APPEARANCE_LOCK) {
            return cachedThemeColorIndex;
        }
    }

    public static void setThemeColorIndex(Context context, int index) {
        ensureAppearanceCache(context);
        prefs(context).edit().putInt(KEY_THEME_COLOR_INDEX, index).apply();
        synchronized (APPEARANCE_LOCK) {
            cachedThemeColorIndex = index;
            appearanceCacheLoaded = true;
        }
    }

    public static boolean isRoundedButtonsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ROUNDED_BUTTONS, false);
    }

    public static boolean isBubbleRoundedEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BUBBLE_ROUNDED, true);
    }

    public static void setBubbleRoundedEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_ROUNDED, enabled).apply();
    }

    public static boolean hasBubbleRoundedPreference(Context context) {
        return prefs(context).contains(KEY_BUBBLE_ROUNDED);
    }

    public static boolean hasRoundedButtonsPreference(Context context) {
        return prefs(context).contains(KEY_ROUNDED_BUTTONS);
    }

    public static void setRoundedButtonsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ROUNDED_BUTTONS, enabled).apply();
    }

    public static boolean isButton3dEffectEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BUTTON_3D_EFFECT, true);
    }

    public static void setButton3dEffectEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BUTTON_3D_EFFECT, enabled).apply();
    }

    /** 群聊彩色昵称：true=按成员 UID 分配彩色名字（默认），false=统一名字颜色。 */
    public static boolean isGroupColoredNameEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GROUP_COLORED_NAME, true);
    }

    public static void setGroupColoredNameEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GROUP_COLORED_NAME, enabled).apply();
    }

    /** 气泡时间换行显示：true=时间在消息文本下方另起一行，false=文字末尾内嵌跟随（默认）。 */
    public static boolean isBubbleTimeNewLineEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BUBBLE_TIME_NEW_LINE, false);
    }

    public static void setBubbleTimeNewLineEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_TIME_NEW_LINE, enabled).apply();
    }

    public static int getAppDpiScalePercent(Context context) {
        int percent = prefs(context).getInt(KEY_APP_DPI_SCALE_PERCENT, 100);
        return (percent < 70 || percent > 200) ? 100 : percent;
    }

    public static float getAppDpiScale(Context context) {
        return getAppDpiScalePercent(context) / 100.0f;
    }

    public static void setAppDpiScalePercent(Context context, int percent) {
        if (percent < 70) percent = 70;
        if (percent > 200) percent = 200;
        prefs(context).edit().putInt(KEY_APP_DPI_SCALE_PERCENT, percent).apply();
    }

    public static boolean isAppDpiFirstPromptShown(Context context) {
        return prefs(context).getBoolean(KEY_APP_DPI_FIRST_PROMPT_SHOWN, false);
    }

    public static void setAppDpiFirstPromptShown(Context context, boolean shown) {
        prefs(context).edit().putBoolean(KEY_APP_DPI_FIRST_PROMPT_SHOWN, shown).apply();
    }

    public static boolean isDevModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DEV_MODE, false);
    }

    public static void setDevModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DEV_MODE, enabled).apply();
    }

    public static boolean isSenderNameOutsideBubble(Context context) {
        return prefs(context).getBoolean(KEY_SENDER_NAME_OUTSIDE_BUBBLE, true);
    }

    public static void setSenderNameOutsideBubble(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SENDER_NAME_OUTSIDE_BUBBLE, enabled).apply();
    }

    // 转发音乐大厅设置方法
    public static boolean isLyricBounceEnabled(Context context) { return SettingsPrefsMusic.isLyricBounceEnabled(context); }
    public static void setLyricBounceEnabled(Context context, boolean enabled) { SettingsPrefsMusic.setLyricBounceEnabled(context, enabled); }
    public static boolean isMusicDynamicBgEnabled(Context context) { return SettingsPrefsMusic.isMusicDynamicBgEnabled(context); }
    public static void setMusicDynamicBgEnabled(Context context, boolean enabled) { SettingsPrefsMusic.setMusicDynamicBgEnabled(context, enabled); }
    public static int getLyricColorMode(Context context) { return SettingsPrefsMusic.getLyricColorMode(context); }
    public static void setLyricColorMode(Context context, int mode) { SettingsPrefsMusic.setLyricColorMode(context, mode); }
    public static int getMusicBgStyle(Context context) { return SettingsPrefsMusic.getMusicBgStyle(context); }
    public static void setMusicBgStyle(Context context, int style) { SettingsPrefsMusic.setMusicBgStyle(context, style); }
    public static boolean isHideAvatarEnabled(Context context) { return SettingsPrefsAvatar.isHideAvatarEnabled(context); }
    public static void setHideAvatarEnabled(Context context, boolean enabled) { SettingsPrefsAvatar.setHideAvatarEnabled(context, enabled); }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
    }

    private static void ensureAppearanceCache(Context context) {
        synchronized (APPEARANCE_LOCK) {
            if (appearanceCacheLoaded) return;
        }
        SharedPreferences preferences = prefs(context);
        boolean darkMode = preferences.getBoolean(KEY_DARK_MODE, false);
        int themeIndex = preferences.getInt(KEY_THEME_COLOR_INDEX, -1);
        synchronized (APPEARANCE_LOCK) {
            if (!appearanceCacheLoaded) {
                cachedDarkModeEnabled = darkMode;
                cachedThemeColorIndex = themeIndex;
                appearanceCacheLoaded = true;
            }
        }
    }
}
