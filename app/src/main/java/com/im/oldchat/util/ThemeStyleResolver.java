package com.im.oldchat.util;

import android.content.Context;

import com.im.oldchat.R;

public final class ThemeStyleResolver {
    private static final int[] SQUARE_STYLE_RES = new int[]{
            R.style.AppTheme,
            R.style.AppTheme_Green,
            R.style.AppTheme_Blue,
            R.style.AppTheme_Orange,
            R.style.AppTheme_Red,
            R.style.AppTheme_Teal,
            R.style.AppTheme_Brown,
            R.style.AppTheme_Dark
    };
    private static final int[] ROUND_STYLE_RES = new int[]{
            R.style.AppTheme_Round,
            R.style.AppTheme_Green_Round,
            R.style.AppTheme_Blue_Round,
            R.style.AppTheme_Orange_Round,
            R.style.AppTheme_Red_Round,
            R.style.AppTheme_Teal_Round,
            R.style.AppTheme_Brown_Round,
            R.style.AppTheme_Dark_Round
    };

    private ThemeStyleResolver() {
    }

    public static int resolve(Context context) {
        if (context == null) {
            return R.style.AppTheme;
        }
        int themeIndex = ThemeColorUtil.normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context));
        int slot = themeIndex + 1;
        if (slot < 0 || slot >= SQUARE_STYLE_RES.length) {
            slot = 0;
        }
        // 主题选择只由"圆角按钮"开关决定：关闭时按钮/卡片/列表行全部直角。
        // 聊天气泡形状由代码层独立读取 isBubbleRoundedEnabled，不受此主题影响。
        boolean rounded = SettingsPrefs.isRoundedButtonsEnabled(context);
        return rounded ? ROUND_STYLE_RES[slot] : SQUARE_STYLE_RES[slot];
    }

    /** 外观预览专用：不写设置，直接按指定的按钮形状解析真实 Activity 主题。 */
    public static int resolve(Context context, boolean rounded) {
        if (context == null) return R.style.AppTheme;
        int themeIndex = ThemeColorUtil.normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context));
        int slot = themeIndex + 1;
        if (slot < 0 || slot >= SQUARE_STYLE_RES.length) slot = 0;
        return rounded ? ROUND_STYLE_RES[slot] : SQUARE_STYLE_RES[slot];
    }
}
