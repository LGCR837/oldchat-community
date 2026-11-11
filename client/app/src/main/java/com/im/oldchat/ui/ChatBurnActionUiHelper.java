package com.im.oldchat.ui;

import android.app.Activity;
import android.support.v4.view.ViewCompat;
import android.view.View;
import android.widget.ImageView;
import com.im.oldchat.R;
import com.im.oldchat.util.SettingsPrefs;

final class ChatBurnActionUiHelper {
    private ChatBurnActionUiHelper() {
    }

    static void bind(final Activity activity) {
        bind(activity, null);
    }

    static void bind(final Activity activity, final View actionPanel) {
        if (activity == null) {
            return;
        }
        final View btnActionBurn = actionPanel != null
                ? actionPanel.findViewById(R.id.btnActionBurn)
                : activity.findViewById(R.id.btnActionBurn);
        if (btnActionBurn == null) {
            return;
        }
        btnActionBurn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatBurnModeQuickActionHelper.showBurnModePicker(activity,
                        new ChatBurnModeQuickActionHelper.ModeChangeCallback() {
                            @Override
                            public void onModeChanged(boolean enabled) {
                                applyState(activity, btnActionBurn, enabled);
                            }
                        });
            }
        });
        applyState(activity, btnActionBurn, ChatBurnModeQuickActionHelper.isEnabled(activity));
    }

    static void sync(Activity activity) {
        if (activity == null) {
            return;
        }
        View btnActionBurn = activity.findViewById(R.id.btnActionBurn);
        if (btnActionBurn != null) {
            applyState(activity, btnActionBurn, ChatBurnModeQuickActionHelper.isEnabled(activity));
        }
        ChatBurnModeQuickActionHelper.syncHint(activity);
    }

    private static void applyState(Activity activity, View actionButton, boolean enabled) {
        if (actionButton == null) {
            return;
        }
        actionButton.setSelected(false);
        ViewCompat.setAlpha(actionButton, 1f);
        View tile = actionButton.findViewById(R.id.layoutActionBurnTile);
        if (tile != null) {
            tile.setSelected(enabled);
            ViewCompat.setAlpha(tile, 1f);
        }
        View icon = actionButton.findViewById(R.id.ivActionBurnIcon);
        if (icon instanceof ImageView) {
            ImageView imageView = (ImageView) icon;
            if (activity != null && SettingsPrefs.isDarkModeEnabled(activity)) {
                imageView.setColorFilter(activity.getResources().getColor(R.color.colorTextPrimary));
            } else {
                imageView.clearColorFilter();
            }
            ViewCompat.setAlpha(icon, 1f);
        }
        View label = actionButton.findViewById(R.id.tvActionBurnLabel);
        if (label instanceof android.widget.TextView) {
            ((android.widget.TextView) label).setText(enabled ? "阅后即焚(开)" : "阅后即焚");
        }
    }
}
