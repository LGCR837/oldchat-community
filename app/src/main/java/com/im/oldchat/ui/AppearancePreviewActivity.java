package com.im.oldchat.ui;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDelegate;
import android.view.View;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.util.AppDpiManager;
import com.im.oldchat.util.ThemeColorUtil;
import com.im.oldchat.util.ThemeStyleResolver;

/**
 * 首次启动外观的独立真实界面预览。
 * 所有选项仅作用于本 Activity，不写入用户设置。
 */
public class AppearancePreviewActivity extends AppCompatActivity {
    public static final String EXTRA_DARK = "preview_dark";
    public static final String EXTRA_ROUNDED_BUTTONS = "preview_rounded_buttons";
    public static final String EXTRA_ROUNDED_BUBBLES = "preview_rounded_bubbles";
    public static final String EXTRA_DPI_PERCENT = "preview_dpi_percent";

    private boolean dpiRestored;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        boolean dark = getIntent() != null && getIntent().getBooleanExtra(EXTRA_DARK, false);
        boolean roundedButtons = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_ROUNDED_BUTTONS, false);
        int percent = getIntent() == null ? 100
                : getIntent().getIntExtra(EXTRA_DPI_PERCENT, 100);
        getDelegate().setLocalNightMode(dark
                ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        setTheme(ThemeStyleResolver.resolve(this, roundedButtons));
        AppDpiManager.applyPreviewDpi(this, percent);
        super.onCreate(savedInstanceState);
        // AppCompat 应用日夜配置后再补一次，避免配置切换覆盖预览 DPI。
        AppDpiManager.applyPreviewDpi(this, percent);
        setContentView(R.layout.activity_appearance_preview);

        final View homePanel = findViewById(R.id.panelPreviewHome);
        final View chatPanel = findViewById(R.id.panelPreviewChat);
        View back = findViewById(R.id.btnAppearancePreviewBack);
        View home = findViewById(R.id.btnPreviewHome);
        View chat = findViewById(R.id.btnPreviewChat);
        TextView scale = (TextView) findViewById(R.id.tvAppearancePreviewScale);
        if (scale != null) scale.setText(percent + "%");
        if (back != null) {
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
        }
        if (home != null) {
            home.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (homePanel != null) homePanel.setVisibility(View.VISIBLE);
                    if (chatPanel != null) chatPanel.setVisibility(View.GONE);
                }
            });
        }
        if (chat != null) {
            chat.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (homePanel != null) homePanel.setVisibility(View.GONE);
                    if (chatPanel != null) chatPanel.setVisibility(View.VISIBLE);
                }
            });
        }

        boolean roundedBubbles = getIntent() == null
                || getIntent().getBooleanExtra(EXTRA_ROUNDED_BUBBLES, true);
        View incoming = findViewById(R.id.bubblePreviewIncoming);
        View outgoing = findViewById(R.id.bubblePreviewOutgoing);
        ThemeColorUtil.applyPreviewBubbleBackground(this, incoming, roundedBubbles, false);
        ThemeColorUtil.applyPreviewBubbleBackground(this, outgoing, roundedBubbles, true);
    }

    @Override
    public void finish() {
        restoreDpi();
        super.finish();
    }

    @Override
    protected void onDestroy() {
        restoreDpi();
        super.onDestroy();
    }

    private void restoreDpi() {
        if (dpiRestored) return;
        dpiRestored = true;
        AppDpiManager.applyCurrentDpi(this);
    }
}
