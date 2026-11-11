package com.im.oldchat.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.SwitchCompat;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.AppDpiManager;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.ThemeColorUtil;

public class UiSettingsActivity extends BaseActivity {
    private TextView tvChatBgValue;
    private TextView tvGroupChatBgValue;
    private TextView tvThemeColorValue;
    private TextView tvHomeStyleValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ui_settings);

        View btnBack = findViewByIdCompat(R.id.btnUiSettingsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        final SwitchCompat switchDarkMode = findViewByIdCompat(R.id.switchDarkMode);
        if (switchDarkMode != null) {
            boolean darkSupported = SettingsPrefs.isDarkModeSupported();
            switchDarkMode.setChecked(darkSupported && SettingsPrefs.isDarkModeEnabled(this));
            switchDarkMode.setEnabled(darkSupported);
            ViewCompat.setAlpha(switchDarkMode, darkSupported ? 1f : 0.45f);
            switchDarkMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (!isChecked && ThemeColorUtil.isMinimalDark(UiSettingsActivity.this)) {
                        Toast.makeText(UiSettingsActivity.this,
                                "极简深主题固定为深色，可在主题色中切换其他方案后关闭暗色模式。",
                                Toast.LENGTH_SHORT).show();
                        switchDarkMode.setChecked(true);
                        return;
                    }
                    SettingsPrefs.setDarkModeEnabled(UiSettingsActivity.this, isChecked);
                    int nightMode = isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
                    AppCompatDelegate.setDefaultNightMode(nightMode);
                    getDelegate().setLocalNightMode(nightMode);
                    restartSelf();
                }
            });
        }

        SwitchCompat switchRoundedButtons = findViewByIdCompat(R.id.switchRoundedButtons);
        UiSettingsRoundedSupport.bind(this, switchRoundedButtons);
        UiSettingsRoundedSupport.bindBubble(this, (SwitchCompat) findViewByIdCompat(R.id.switchBubbleRounded));
        UiSettingsRoundedSupport.bindSenderNameOutside(this, (SwitchCompat) findViewByIdCompat(R.id.switchSenderNameOutsideBubble));
        UiSettingsRoundedSupport.bindGroupColoredName(this, (SwitchCompat) findViewByIdCompat(R.id.switchGroupColoredName));
        UiSettingsRoundedSupport.bindBubbleTimeNewLine(this, (SwitchCompat) findViewByIdCompat(R.id.switchBubbleTimeNewLine));
        UiSettingsRoundedSupport.bindHideAvatar(this, (SwitchCompat) findViewByIdCompat(R.id.switchHideAvatar));
        UiSettings3dSupport.bind(this, (SwitchCompat) findViewByIdCompat(R.id.switchButton3dEffect));

        final SwitchCompat switchHomeNews = findViewByIdCompat(R.id.switchHomeNews);
        if (switchHomeNews != null) {
            switchHomeNews.setChecked(SettingsPrefs.isHomeNewsEnabled(this));
            switchHomeNews.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    SettingsPrefs.setHomeNewsEnabled(UiSettingsActivity.this, isChecked);
                }
            });
        }

        final View rowHomeStyle = findViewByIdCompat(R.id.rowHomeStyle);
        tvHomeStyleValue = findViewByIdCompat(R.id.tvHomeStyleValue);
        updateHomeStyleLabel();
        if (rowHomeStyle != null) {
            rowHomeStyle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showHomeStyleDialog();
                }
            });
        }

        final View rowFontSize = findViewByIdCompat(R.id.rowFontSize);
        final TextView tvFontSizeValue = findViewByIdCompat(R.id.tvFontSizeValue);
        updateFontSizeLabel(tvFontSizeValue);
        if (rowFontSize != null) {
            rowFontSize.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFontSizeDialog(tvFontSizeValue);
                }
            });
        }

        AppDpiManager.bindSettingRow(this, findViewByIdCompat(R.id.rowAppDpiScale), (TextView) findViewByIdCompat(R.id.tvAppDpiScaleValue));

        final View rowThemeColor = findViewByIdCompat(R.id.rowThemeColor);
        tvThemeColorValue = findViewByIdCompat(R.id.tvThemeColorValue);
        updateThemeColorLabel(tvThemeColorValue);
        if (rowThemeColor != null) {
            rowThemeColor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showThemeColorDialog(tvThemeColorValue);
                }
            });
        }

        View rowDiscoverSettings = findViewByIdCompat(R.id.rowDiscoverSettings);
        if (rowDiscoverSettings != null) {
            rowDiscoverSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(UiSettingsActivity.this, DiscoverSettingsActivity.class));
                }
            });
        }

        View rowChatBg = findViewByIdCompat(R.id.rowChatBackgroundGlobal);
        View rowGroupChatBg = findViewByIdCompat(R.id.rowGroupChatBackgroundGlobal);
        tvChatBgValue = findViewByIdCompat(R.id.tvChatBackgroundGlobalValue);
        tvGroupChatBgValue = findViewByIdCompat(R.id.tvGroupChatBackgroundGlobalValue);
        UiSettingsBackgroundSupport.bind(this, rowChatBg, rowGroupChatBg, tvChatBgValue, tvGroupChatBgValue);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiSettingsBackgroundSupport.updateLabels(this, tvChatBgValue, tvGroupChatBgValue);
        updateThemeColorLabel(tvThemeColorValue);
        updateHomeStyleLabel();
    }

    private void showHomeStyleDialog() {
        final String[] labels = new String[] { "经典", "Oldchat" };
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("主页样式")
                .setSingleChoiceItems(labels, SettingsPrefs.getHomeStyle(this),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                SettingsPrefs.setHomeStyle(UiSettingsActivity.this, which);
                                updateHomeStyleLabel();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateHomeStyleLabel() {
        if (tvHomeStyleValue != null) {
            tvHomeStyleValue.setText(SettingsPrefs.isClassicHomeStyle(this) ? "经典" : "Oldchat");
        }
    }

    private void showFontSizeDialog(final TextView valueView) {
        final String[] labels = new String[] { "小", "标准", "大", "特大" };
        int currentIndex = SettingsPrefs.getFontSizeIndex(this);
        if (currentIndex < 0 || currentIndex >= labels.length) {
            currentIndex = 1;
        }
        new AlertDialog.Builder(this)
                .setTitle("字体大小")
                .setSingleChoiceItems(labels, currentIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SettingsPrefs.setFontSizeIndex(UiSettingsActivity.this, which);
                        updateFontSizeLabel(valueView);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateFontSizeLabel(TextView valueView) {
        if (valueView == null) return;
        int index = SettingsPrefs.getFontSizeIndex(this);
        String label;
        switch (index) {
            case 0: label = "小"; break;
            case 2: label = "大"; break;
            case 3: label = "特大"; break;
            default: label = "标准"; break;
        }
        valueView.setText(label);
    }

    private void showThemeColorDialog(final TextView valueView) {
        final CharSequence[] labels = ThemeColorUtil.buildThemeColorLabels(this);
        int currentIndex = ThemeColorUtil.normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(this));
        int checkedIndex = currentIndex + 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_theme_color_title)
                .setSingleChoiceItems(labels, checkedIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int targetIndex = ThemeColorUtil.normalizeThemeIndex(which - 1);
                        if (targetIndex == ThemeColorUtil.THEME_MINIMAL_DARK && Build.VERSION.SDK_INT < 14) {
                            Toast.makeText(UiSettingsActivity.this, "Android 4.0 以下设备不支持暗色模式。", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            return;
                        }
                        SettingsPrefs.setThemeColorIndex(UiSettingsActivity.this, targetIndex);
                        if (ThemeColorUtil.isMinimalDark(UiSettingsActivity.this)) {
                            SettingsPrefs.setDarkModeEnabled(UiSettingsActivity.this, true);
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                            getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                        }
                        updateThemeColorLabel(valueView);
                        Toast.makeText(UiSettingsActivity.this, R.string.settings_theme_color_tip_message, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        restartSelf();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateThemeColorLabel(TextView valueView) {
        if (valueView != null) {
            valueView.setText(ThemeColorUtil.getThemeColorLabel(this));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        UiSettingsBackgroundSupport.handleActivityResult(this, requestCode, resultCode, data, tvChatBgValue, tvGroupChatBgValue);
    }

    private void restartSelf() {
        Intent intent = getIntent();
        finish();
        startActivity(intent);
    }

    void restartUiSettingsForTheme() {
        restartSelf();
    }
}
