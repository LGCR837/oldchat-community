package com.im.oldchat.util;

import android.content.Context;

public final class UserNameColorUtil {
    private UserNameColorUtil() {}

    // Telegram X 8-color palette (Light Mode)
    private static final int[] TGX_COLORS_LIGHT = new int[]{
            0xFFEF615C, // 0 - Red
            0xFFF29154, // 1 - Orange
            0xFF9388E1, // 2 - Violet
            0xFF7ABB58, // 3 - Green
            0xFF64BACE, // 4 - Cyan
            0xFF5E9CD4, // 5 - Blue
            0xFFD979A3, // 6 - Pink
            0xFFF5AD3D  // 7 - Yellow / Amber
    };

    // Telegram X 8-color palette (Dark Mode, high contrast & vibrance)
    private static final int[] TGX_COLORS_DARK = new int[]{
            0xFFFF8E8A, // 0 - Red
            0xFFFFAE78, // 1 - Orange
            0xFFBCA8F8, // 2 - Violet
            0xFF8CE073, // 3 - Green
            0xFF7ADAD9, // 4 - Cyan
            0xFF6EBBF0, // 5 - Blue
            0xFFFF8EBF, // 6 - Pink
            0xFFF9C84A  // 7 - Yellow / Amber
    };

    private static final java.util.Map<String, Integer> COLOR_CACHE_LIGHT = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private static final java.util.Map<String, Integer> COLOR_CACHE_DARK = new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    public static int getUserNameColor(Context context, String uid) {
        boolean isDark = SettingsPrefs.isDarkModeEnabled(context);
        if (!SettingsPrefs.isGroupColoredNameEnabled(context)) {
            // 关闭彩色名字时，使用默认统一的名称主色调
            return isDark ? 0xFF6EBBF0 : 0xFF5E9CD4;
        }
        return getUserNameColor(uid, isDark);
    }

    public static int getUserNameColor(String uid, boolean isDark) {
        if (uid == null || uid.length() == 0) {
            return isDark ? 0xFF6EBBF0 : 0xFF5E9CD4;
        }
        java.util.Map<String, Integer> cache = isDark ? COLOR_CACHE_DARK : COLOR_CACHE_LIGHT;
        Integer cached = cache.get(uid);
        if (cached != null) {
            return cached.intValue();
        }
        int hash = Math.abs(uid.hashCode());
        int index = hash % TGX_COLORS_LIGHT.length;
        int color = isDark ? TGX_COLORS_DARK[index] : TGX_COLORS_LIGHT[index];
        cache.put(uid, Integer.valueOf(color));
        return color;
    }
}
