package com.im.oldchat.ui;

import android.support.v7.widget.SwitchCompat;
import android.widget.CompoundButton;

import com.im.oldchat.util.SettingsPrefs;

final class UiSettingsRoundedSupport {
    private UiSettingsRoundedSupport() {
    }

    static void bind(final UiSettingsActivity activity, final SwitchCompat toggle) {
        if (activity == null || toggle == null) {
            return;
        }
        toggle.setChecked(SettingsPrefs.isRoundedButtonsEnabled(activity));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsPrefs.setRoundedButtonsEnabled(activity, isChecked);
                activity.restartUiSettingsForTheme();
            }
        });
    }

    /** 聊天气泡形状开关：独立于按钮圆角，默认圆角（头像侧直角+其余圆角）。 */
    static void bindBubble(final UiSettingsActivity activity, final SwitchCompat toggle) {
        if (activity == null || toggle == null) {
            return;
        }
        toggle.setChecked(SettingsPrefs.isBubbleRoundedEnabled(activity));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsPrefs.setBubbleRoundedEnabled(activity, isChecked);
                activity.restartUiSettingsForTheme();
            }
        });
    }

    /** 群聊名字显示在气泡外开关。 */
    static void bindSenderNameOutside(final UiSettingsActivity activity, final SwitchCompat toggle) {
        if (activity == null || toggle == null) {
            return;
        }
        toggle.setChecked(SettingsPrefs.isSenderNameOutsideBubble(activity));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsPrefs.setSenderNameOutsideBubble(activity, isChecked);
            }
        });
    }

    /** 群聊彩色昵称开关。 */
    static void bindGroupColoredName(final UiSettingsActivity activity, final SwitchCompat toggle) {
        if (activity == null || toggle == null) {
            return;
        }
        toggle.setChecked(SettingsPrefs.isGroupColoredNameEnabled(activity));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsPrefs.setGroupColoredNameEnabled(activity, isChecked);
            }
        });
    }

    /** 气泡时间换行显示开关。 */
    static void bindBubbleTimeNewLine(final UiSettingsActivity activity, final SwitchCompat toggle) {
        if (activity == null || toggle == null) {
            return;
        }
        toggle.setChecked(SettingsPrefs.isBubbleTimeNewLineEnabled(activity));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsPrefs.setBubbleTimeNewLineEnabled(activity, isChecked);
            }
        });
    }

    /** 不显示头像开关。 */
    static void bindHideAvatar(final UiSettingsActivity activity, final SwitchCompat toggle) {
        if (activity == null || toggle == null) {
            return;
        }
        toggle.setChecked(SettingsPrefs.isHideAvatarEnabled(activity));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsPrefs.setHideAvatarEnabled(activity, isChecked);
            }
        });
    }
}
