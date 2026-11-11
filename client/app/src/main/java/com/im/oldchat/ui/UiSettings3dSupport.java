package com.im.oldchat.ui;

import android.support.v7.widget.SwitchCompat;
import android.view.View;
import android.widget.CompoundButton;

import com.im.oldchat.ui.widget.SquarePressFeedback;
import com.im.oldchat.util.SettingsPrefs;

final class UiSettings3dSupport {
    private UiSettings3dSupport() {
    }

    static void bind(final UiSettingsActivity activity, final SwitchCompat toggle) {
        if (activity == null || toggle == null) {
            return;
        }
        toggle.setChecked(SettingsPrefs.isButton3dEffectEnabled(activity));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SettingsPrefs.setButton3dEffectEnabled(activity, isChecked);
                View root = activity.findViewById(android.R.id.content);
                if (root == null) {
                    return;
                }
                if (isChecked) {
                    SquarePressFeedback.install(root);
                } else {
                    root.clearAnimation();
                }
            }
        });
    }
}
