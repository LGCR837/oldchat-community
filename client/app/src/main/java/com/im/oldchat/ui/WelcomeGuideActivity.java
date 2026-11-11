package com.im.oldchat.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.MainActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.AppDpiManager;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.StartupAppearanceOptionHelper;

/**
 * 首次启动引导页：在登录页之前展示，用于选择 UI 外观（DPI 缩放、主题模式、按钮形状）。
 * 兼容 Android 2.3+（API 9），仅首次启动显示一次，之后直接进入登录/主界面。
 */
public class WelcomeGuideActivity extends BaseActivity {
    private static final int MIN_PERCENT = 30;
    private static final int MAX_PERCENT = 160;

    private SeekBar seekBar;
    private EditText input;
    private TextView titleValue;
    private View contentRoot;
    private View previewSurface;
    private TextView previewTitle;
    private TextView previewScale;
    private TextView previewIncoming;
    private TextView previewOutgoing;
    private Button previewPrimary;
    private Button previewSecondary;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome_guide);

        contentRoot = findViewByIdCompat(android.R.id.content);
        seekBar = findViewByIdCompat(R.id.seekDpiScale);
        input = findViewByIdCompat(R.id.etDpiScalePercent);
        titleValue = findViewByIdCompat(R.id.tvDpiScalePercentValue);
        previewSurface = findViewByIdCompat(R.id.layoutGuidePreview);
        previewTitle = findViewByIdCompat(R.id.tvGuidePreviewTitle);
        previewScale = findViewByIdCompat(R.id.tvGuidePreviewScale);
        previewIncoming = findViewByIdCompat(R.id.tvGuidePreviewIncoming);
        previewOutgoing = findViewByIdCompat(R.id.tvGuidePreviewOutgoing);
        previewPrimary = findViewByIdCompat(R.id.btnGuidePreviewPrimary);
        previewSecondary = findViewByIdCompat(R.id.btnGuidePreviewSecondary);
        Button btnPreview = findViewByIdCompat(R.id.btnGuideRealPreview);
        Button btnStart = findViewByIdCompat(R.id.btnGuideStart);
        Button btnSkip = findViewByIdCompat(R.id.btnGuideSkip);

        initDpiControls();
        StartupAppearanceOptionHelper.bind(this, contentRoot);
        initPreviewControls();
        updateAppearancePreview();

        if (btnPreview != null) {
            btnPreview.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openRealAppearancePreview();
                }
            });
        }
        if (btnStart != null) {
            btnStart.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onFinishGuide(false);
                }
            });
        }
        if (btnSkip != null) {
            btnSkip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onFinishGuide(true);
                }
            });
        }
    }

    private void initDpiControls() {
        if (seekBar == null || input == null || titleValue == null) {
            return;
        }
        int currentPercent = clampPercent(SettingsPrefs.getAppDpiScalePercent(this));
        seekBar.setMax(MAX_PERCENT - MIN_PERCENT);
        seekBar.setProgress(currentPercent - MIN_PERCENT);
        input.setText(String.valueOf(currentPercent));
        titleValue.setText(formatPercent(currentPercent));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = clampPercent(MIN_PERCENT + progress);
                input.setText(String.valueOf(percent));
                if (input.getText() != null) {
                    input.setSelection(input.getText().length());
                }
                titleValue.setText(formatPercent(percent));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int percent = parsePercentOrDefault(s, MIN_PERCENT);
                percent = clampPercent(percent);
                seekBar.setProgress(percent - MIN_PERCENT);
                titleValue.setText(formatPercent(percent));
                updateAppearancePreview();
            }
        });
    }

    private void initPreviewControls() {
        RadioButton themeLight = findViewByIdCompat(R.id.rbStartupThemeLight);
        RadioButton themeDark = findViewByIdCompat(R.id.rbStartupThemeDark);
        RadioButton cornersRound = findViewByIdCompat(R.id.rbStartupCornersRound);
        RadioButton cornersSquare = findViewByIdCompat(R.id.rbStartupCornersSquare);
        CompoundButton.OnCheckedChangeListener listener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (isChecked) {
                            updateAppearancePreview();
                        }
                    }
                };
        if (themeLight != null) themeLight.setOnCheckedChangeListener(listener);
        if (themeDark != null) themeDark.setOnCheckedChangeListener(listener);
        if (cornersRound != null) cornersRound.setOnCheckedChangeListener(listener);
        if (cornersSquare != null) cornersSquare.setOnCheckedChangeListener(listener);
    }

    /** 根据当前选择实时绘制预览，不提前写入设置。 */
    private void updateAppearancePreview() {
        if (previewSurface == null) {
            return;
        }
        RadioButton darkButton = findViewByIdCompat(R.id.rbStartupThemeDark);
        RadioButton roundButton = findViewByIdCompat(R.id.rbStartupCornersRound);
        boolean dark = darkButton != null && darkButton.isChecked();
        boolean rounded = roundButton != null && roundButton.isChecked();
        int percent = clampPercent(parsePercentOrDefault(
                input != null ? input.getText() : null, 100));

        int surface = dark ? 0xFF1F2937 : 0xFFFFFFFF;
        int border = dark ? 0xFF475569 : 0xFFD1D5DB;
        int primaryText = dark ? 0xFFF9FAFB : 0xFF111827;
        int secondaryText = dark ? 0xFF9CA3AF : 0xFF6B7280;
        int incoming = dark ? 0xFF2A313A : 0xFFF3F4F6;
        int outgoing = dark ? 0xFFE5E7EB : 0xFF111827;
        int outgoingText = dark ? 0xFF111827 : Color.WHITE;
        float radius = rounded ? dp(14) : 0f;

        previewSurface.setBackgroundDrawable(shape(surface, border, radius));
        setTextColor(previewTitle, primaryText);
        setTextColor(previewScale, secondaryText);
        setTextColor(previewIncoming, primaryText);
        setTextColor(previewOutgoing, outgoingText);
        if (previewIncoming != null) {
            previewIncoming.setBackgroundDrawable(shape(incoming, border, radius));
        }
        if (previewOutgoing != null) {
            previewOutgoing.setBackgroundDrawable(shape(outgoing, outgoing, radius));
        }
        if (previewPrimary != null) {
            previewPrimary.setTextColor(outgoingText);
            previewPrimary.setBackgroundDrawable(shape(outgoing, outgoing, radius));
        }
        if (previewSecondary != null) {
            previewSecondary.setTextColor(primaryText);
            previewSecondary.setBackgroundDrawable(shape(surface, border, radius));
        }

        float scale = Math.max(0.70f, Math.min(1.30f, percent / 100f));
        if (previewTitle != null) {
            previewTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * scale);
        }
        if (previewScale != null) {
            previewScale.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f * scale);
            previewScale.setText(getString(R.string.welcome_guide_preview_scale,
                    Integer.valueOf(percent)));
        }
        if (previewIncoming != null) {
            previewIncoming.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * scale);
        }
        if (previewOutgoing != null) {
            previewOutgoing.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * scale);
        }
        if (previewPrimary != null) {
            previewPrimary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f * scale);
        }
        if (previewSecondary != null) {
            previewSecondary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f * scale);
        }
    }

    private GradientDrawable shape(int fill, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void setTextColor(TextView view, int color) {
        if (view != null) {
            view.setTextColor(color);
        }
    }

    private int dp(int value) {
        return Math.max(1, (int) (value
                * getResources().getDisplayMetrics().density + 0.5f));
    }

    private void openRealAppearancePreview() {
        RadioButton dark = findViewByIdCompat(R.id.rbStartupThemeDark);
        RadioButton roundedButtons = findViewByIdCompat(R.id.rbStartupCornersRound);
        RadioButton roundedBubbles = findViewByIdCompat(R.id.rbStartupBubbleRound);
        int percent = clampPercent(parsePercentOrDefault(
                input != null ? input.getText() : null, 100));
        Intent intent = new Intent(this, AppearancePreviewActivity.class);
        intent.putExtra(AppearancePreviewActivity.EXTRA_DARK,
                dark != null && dark.isChecked());
        intent.putExtra(AppearancePreviewActivity.EXTRA_ROUNDED_BUTTONS,
                roundedButtons != null && roundedButtons.isChecked());
        intent.putExtra(AppearancePreviewActivity.EXTRA_ROUNDED_BUBBLES,
                roundedBubbles == null || roundedBubbles.isChecked());
        intent.putExtra(AppearancePreviewActivity.EXTRA_DPI_PERCENT, percent);
        startActivity(intent);
    }

    /**
     * @param skipped true 表示用户点了“跳过”，使用默认外观；false 表示“开始使用”，应用用户选择。
     */
    private void onFinishGuide(boolean skipped) {
        if (skipped) {
            StartupAppearanceOptionHelper.applyDefaultsIfMissing(this);
        } else {
            StartupAppearanceOptionHelper.applySelection(this, contentRoot);
            int percent = clampPercent(parsePercentOrDefault(input != null ? input.getText() : null, 100));
            SettingsPrefs.setAppDpiScalePercent(this, percent);
            AppDpiManager.applyCurrentDpi(this);
        }
        SettingsPrefs.setAppDpiFirstPromptShown(this, true);
        startActivity(new Intent(this, tokenExists() ? MainActivity.class : LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private boolean tokenExists() {
        android.content.SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String value = prefs.getString("access_token", null);
        if (value == null || value.length() == 0) {
            value = prefs.getString("token", null);
        }
        return value != null && value.length() > 0;
    }

    private int clampPercent(int percent) {
        if (percent < MIN_PERCENT) {
            return MIN_PERCENT;
        }
        if (percent > MAX_PERCENT) {
            return MAX_PERCENT;
        }
        return percent;
    }

    private int parsePercentOrDefault(CharSequence value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        if (text.length() == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String formatPercent(int percent) {
        return getString(R.string.settings_dpi_percent_format, Integer.valueOf(percent));
    }
}
