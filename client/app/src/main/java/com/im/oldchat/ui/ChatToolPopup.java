package com.im.oldchat.ui;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.im.oldchat.R;

/** 加号上方的横向工具菜单，兼容 API 9 / Java 7。 */
final class ChatToolPopup {
    private static final int[] ACTION_IDS = new int[]{
            R.id.btnActionImage, R.id.btnActionVideo,
            R.id.btnActionEmoji, R.id.btnActionRedPacket, R.id.btnActionBurn
    };
    private static final int[] ICON_IDS = new int[]{
            R.drawable.ic_action_image, R.drawable.ic_action_video,
            R.drawable.ic_action_emoji, R.drawable.ic_action_red_packet, R.drawable.ic_action_burn
    };
    private static final String[] LABELS = new String[]{
            "图片", "视频", "表情", "红包", "阅后即焚"
    };

    private final Activity activity;
    private PopupWindow popup;

    ChatToolPopup(Activity activity) {
        this.activity = activity;
    }

    void toggle(final View anchor) {
        if (popup != null && popup.isShowing()) {
            dismiss();
            return;
        }
        if (activity == null || anchor == null) return;
        anchor.postDelayed(new Runnable() {
            @Override
            public void run() {
                show(anchor);
            }
        }, 80L);
    }

    void dismiss() {
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
            }
            popup = null;
        }
    }

    private void show(View anchor) {
        if (activity == null || activity.isFinishing()) return;
        LinearLayout menu = new LinearLayout(activity);
        menu.setOrientation(LinearLayout.HORIZONTAL);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(6), dp(7), dp(6), dp(7));
        menu.setBackgroundColor(0xFF151719);
        for (int i = 0; i < ACTION_IDS.length; i++) {
            menu.addView(createItem(i), new LinearLayout.LayoutParams(0, dp(58), 1f));
        }
        int width = activity.getResources().getDisplayMetrics().widthPixels - dp(12);
        popup = new PopupWindow(menu, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(0xFF151719));
        popup.setOutsideTouchable(true);
        popup.setTouchable(true);
        View inputArea = activity.findViewById(R.id.input_area);
        int bottomOffset = (inputArea == null ? anchor.getHeight() : inputArea.getHeight()) + dp(6);
        popup.showAtLocation(anchor, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, bottomOffset);
    }

    private View createItem(final int index) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setBackgroundResource(R.drawable.bg_transparent_row);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(ICON_IDS[index]);
        // 原工具图标包含为浅色卡片设计的黑色 PNG；弹窗为深色底，统一着浅灰前景色。
        icon.setColorFilter(0xFFCBD5E1, PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView label = new TextView(activity);
        label.setText(LABELS[index]);
        label.setTextColor(0xFFCBD5E1);
        label.setTextSize(9f);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(3);
        item.addView(label, labelParams);

        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View original = activity.findViewById(ACTION_IDS[index]);
                dismiss();
                if (original != null) original.performClick();
            }
        });
        return item;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
