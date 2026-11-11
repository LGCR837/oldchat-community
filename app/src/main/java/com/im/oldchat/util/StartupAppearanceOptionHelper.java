package com.im.oldchat.util;

import android.content.Context;
import android.view.View;
import android.widget.RadioButton;

import com.im.oldchat.R;

public final class StartupAppearanceOptionHelper {
    private StartupAppearanceOptionHelper() {
    }

    public static void bind(Context context, View content) {
        if (context == null || content == null) {
            return;
        }
        setChecked(content, R.id.rbStartupThemeDark, resolveDarkMode(context));
        setChecked(content, R.id.rbStartupThemeLight, !resolveDarkMode(context));
        setChecked(content, R.id.rbStartupCornersRound, resolveRoundedButtons(context));
        setChecked(content, R.id.rbStartupCornersSquare, !resolveRoundedButtons(context));
        setChecked(content, R.id.rbStartupBubbleRound,
                SettingsPrefs.isBubbleRoundedEnabled(context));
        setChecked(content, R.id.rbStartupBubbleSquare,
                !SettingsPrefs.isBubbleRoundedEnabled(context));
    }

    public static void applySelection(Context context, View content) {
        if (context == null || content == null) {
            return;
        }
        SettingsPrefs.setDarkModeEnabled(context,
                isChecked(content, R.id.rbStartupThemeDark, resolveDarkMode(context)));
        SettingsPrefs.setRoundedButtonsEnabled(context,
                isChecked(content, R.id.rbStartupCornersRound, resolveRoundedButtons(context)));
        View bubbleRound = content.findViewById(R.id.rbStartupBubbleRound);
        if (bubbleRound instanceof RadioButton) {
            SettingsPrefs.setBubbleRoundedEnabled(context,
                    ((RadioButton) bubbleRound).isChecked());
        }
    }

    public static void applyDefaultsIfMissing(Context context) {
        if (context == null) {
            return;
        }
        if (!SettingsPrefs.hasDarkModePreference(context)) {
            SettingsPrefs.setDarkModeEnabled(context, false);
        }
        if (!SettingsPrefs.hasRoundedButtonsPreference(context)) {
            // 默认直角（方形按钮），与官网几何风一致。
            SettingsPrefs.setRoundedButtonsEnabled(context, false);
        }
        if (!SettingsPrefs.hasBubbleRoundedPreference(context)) {
            SettingsPrefs.setBubbleRoundedEnabled(context, true);
        }
    }

    private static boolean resolveDarkMode(Context context) {
        if (SettingsPrefs.hasDarkModePreference(context)) {
            return SettingsPrefs.isDarkModeEnabled(context);
        }
        return false;
    }

    private static boolean resolveRoundedButtons(Context context) {
        if (SettingsPrefs.hasRoundedButtonsPreference(context)) {
            return SettingsPrefs.isRoundedButtonsEnabled(context);
        }
        // 未设置时默认直角。
        return false;
    }

    private static boolean isChecked(View content, int id, boolean fallback) {
        View view = content.findViewById(id);
        if (view instanceof RadioButton) {
            return ((RadioButton) view).isChecked();
        }
        return fallback;
    }

    private static void setChecked(View content, int id, boolean checked) {
        View view = content.findViewById(id);
        if (view instanceof RadioButton) {
            ((RadioButton) view).setChecked(checked);
        }
    }
}
