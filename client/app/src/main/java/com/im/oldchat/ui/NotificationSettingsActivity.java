package com.im.oldchat.ui;

import android.os.Bundle;
import com.im.oldchat.BaseActivity;
import android.support.v7.widget.SwitchCompat;
import android.view.View;

import com.im.oldchat.R;
import com.im.oldchat.util.SettingsPrefs;

public class NotificationSettingsActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_settings);

        View btnBack = (View) findViewByIdCompat(R.id.btnNotificationSettingsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        final SwitchCompat switchNotifications = (SwitchCompat) findViewByIdCompat(R.id.switchNotifications);
        final SwitchCompat switchTypingIndicator = (SwitchCompat) findViewByIdCompat(R.id.switchTypingIndicator);
        final SwitchCompat switchEnterSend = (SwitchCompat) findViewByIdCompat(R.id.switchEnterSend);

        if (switchNotifications != null) {
            switchNotifications.setChecked(SettingsPrefs.isNotifyEnabled(this));
            switchNotifications.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    SettingsPrefs.setNotifyEnabled(NotificationSettingsActivity.this, isChecked);
                }
            });
        }

        if (switchTypingIndicator != null) {
            switchTypingIndicator.setChecked(SettingsPrefs.isTypingIndicatorEnabled(this));
            switchTypingIndicator.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    SettingsPrefs.setTypingIndicatorEnabled(NotificationSettingsActivity.this, isChecked);
                }
            });
        }

        if (switchEnterSend != null) {
            switchEnterSend.setChecked(SettingsPrefs.isEnterSendEnabled(this));
            switchEnterSend.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    SettingsPrefs.setEnterSendEnabled(NotificationSettingsActivity.this, isChecked);
                }
            });
        }

        View rowNotifications = (View) findViewByIdCompat(R.id.rowNotifications);
        if (rowNotifications != null && switchNotifications != null) {
            rowNotifications.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchNotifications.setChecked(!switchNotifications.isChecked());
                }
            });
        }

        View rowTypingIndicator = (View) findViewByIdCompat(R.id.rowTypingIndicator);
        if (rowTypingIndicator != null && switchTypingIndicator != null) {
            rowTypingIndicator.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTypingIndicator.setChecked(!switchTypingIndicator.isChecked());
                }
            });
        }

        View rowEnterSend = (View) findViewByIdCompat(R.id.rowEnterSend);
        if (rowEnterSend != null && switchEnterSend != null) {
            rowEnterSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchEnterSend.setChecked(!switchEnterSend.isChecked());
                }
            });
        }
    }
}
