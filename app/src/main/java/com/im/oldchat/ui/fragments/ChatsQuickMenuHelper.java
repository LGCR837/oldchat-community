package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.support.v4.app.Fragment;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.ui.AddFriendActivity;
import com.im.oldchat.ui.GroupCreateActivity;
import com.im.oldchat.ui.widget.TiltFrameLayout;

final class ChatsQuickMenuHelper {
    interface CreateCustomGroupCallback {
        void onCreateCustomGroup();
        void onDiscoverChannel();
    }

    private ChatsQuickMenuHelper() {
    }

    static PopupWindow show(final Fragment fragment, View anchor,
                            final CreateCustomGroupCallback customGroupCallback) {
        if (fragment == null || fragment.getActivity() == null || anchor == null) {
            return null;
        }
        final Context context = fragment.getActivity();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TiltFrameLayout addFriend = createQuickMenuButton(context, "\u6DFB\u52A0\u8054\u7CFB\u4EBA");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.bottomMargin = dp(context, 8);
        content.addView(addFriend, addLp);

        TiltFrameLayout createGroup = createQuickMenuButton(context, "\u521B\u5EFA\u7FA4\u804A");
        LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        groupLp.bottomMargin = dp(context, 8);
        content.addView(createGroup, groupLp);

        TiltFrameLayout createCustomGroup = createQuickMenuButton(context, "\u65B0\u5EFA\u4F1A\u8BDD\u5206\u7EC4");
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        customLp.bottomMargin = dp(context, 8);
        content.addView(createCustomGroup, customLp);
        TiltFrameLayout discoverChannel = createQuickMenuButton(context, "\u53D1\u73B0\u9891\u9053");
        content.addView(discoverChannel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final PopupWindow popup = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(0x00000000));

        addFriend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss(popup);
                if (fragment.getActivity() != null) {
                    fragment.startActivity(new Intent(fragment.getActivity(), AddFriendActivity.class));
                }
            }
        });
        createGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss(popup);
                if (fragment.getActivity() != null) {
                    fragment.startActivity(new Intent(fragment.getActivity(), GroupCreateActivity.class));
                }
            }
        });
        createCustomGroup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss(popup);
                if (customGroupCallback != null) customGroupCallback.onCreateCustomGroup();
            }
        });
        discoverChannel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss(popup);
                if (customGroupCallback != null) customGroupCallback.onDiscoverChannel();
            }
        });

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int xoff = anchor.getWidth() - content.getMeasuredWidth();
        popup.showAsDropDown(anchor, xoff, dp(context, 6));
        return popup;
    }

    static void dismiss(PopupWindow popup) {
        if (popup != null) {
            popup.dismiss();
        }
    }

    private static TiltFrameLayout createQuickMenuButton(Context context, String text) {
        TiltFrameLayout tile = new TiltFrameLayout(context);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setBackgroundResource(R.drawable.fab_menu_bg);

        TextView label = new TextView(context);
        label.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        label.setText(text);
        label.setTextColor(context.getResources().getColor(R.color.color_text_primary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tile.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tile;
    }

    private static int dp(Context context, int value) {
        return (int) (context.getResources().getDisplayMetrics().density * value + 0.5f);
    }
}
