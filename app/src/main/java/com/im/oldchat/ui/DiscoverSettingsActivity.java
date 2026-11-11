package com.im.oldchat.ui;

import android.os.Bundle;
import android.support.v7.widget.SwitchCompat;
import android.view.View;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.util.SettingsPrefs;

public class DiscoverSettingsActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_discover_settings);

        View btnBack = findViewByIdCompat(R.id.btnDiscoverSettingsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        final SwitchCompat switchClassicLayout =
                findViewByIdCompat(R.id.switchDiscoverClassicLayout);
        final SwitchCompat switchRandomSolidColor =
                findViewByIdCompat(R.id.switchDiscoverRandomSolidColor);
        final SwitchCompat switchOldViewEntry = findViewByIdCompat(R.id.switchOldViewEntry);
        final SwitchCompat switchPublicCourt = findViewByIdCompat(R.id.switchPublicCourtEntry);

        if (switchClassicLayout != null) {
            switchClassicLayout.setChecked(SettingsPrefs.isDiscoverClassicLayoutEnabled(this));
            switchClassicLayout.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(
                        android.widget.CompoundButton buttonView, boolean isChecked) {
                    SettingsPrefs.setDiscoverClassicLayoutEnabled(
                            DiscoverSettingsActivity.this, isChecked);
                }
            });
        }

        if (switchRandomSolidColor != null) {
            switchRandomSolidColor.setChecked(SettingsPrefs.isDiscoverRandomSolidColorEnabled(this));
            switchRandomSolidColor.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(
                        android.widget.CompoundButton buttonView, boolean isChecked) {
                    SettingsPrefs.setDiscoverRandomSolidColorEnabled(
                            DiscoverSettingsActivity.this, isChecked);
                }
            });
        }

        if (switchOldViewEntry != null) {
            switchOldViewEntry.setChecked(SettingsPrefs.isOldViewEntryEnabled(this));
            switchOldViewEntry.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    SettingsPrefs.setOldViewEntryEnabled(DiscoverSettingsActivity.this, isChecked);
                }
            });
        }

        if (switchPublicCourt != null) {
            switchPublicCourt.setChecked(SettingsStore.isPublicCourtEnabled(this));
            switchPublicCourt.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    SettingsStore.setPublicCourtEnabled(DiscoverSettingsActivity.this, isChecked);
                }
            });
        }

        View rowClassicLayout = findViewByIdCompat(R.id.rowDiscoverClassicLayout);
        if (rowClassicLayout != null && switchClassicLayout != null) {
            rowClassicLayout.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    switchClassicLayout.setChecked(!switchClassicLayout.isChecked());
                }
            });
        }

        View rowRandomSolidColor = findViewByIdCompat(R.id.rowDiscoverRandomSolidColor);
        if (rowRandomSolidColor != null && switchRandomSolidColor != null) {
            rowRandomSolidColor.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    switchRandomSolidColor.setChecked(!switchRandomSolidColor.isChecked());
                }
            });
        }

        View rowOldView = findViewByIdCompat(R.id.rowOldViewEntry);
        if (rowOldView != null && switchOldViewEntry != null) {
            rowOldView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchOldViewEntry.setChecked(!switchOldViewEntry.isChecked());
                }
            });
        }

        View rowPublicCourt = findViewByIdCompat(R.id.rowPublicCourtEntry);
        if (rowPublicCourt != null && switchPublicCourt != null) {
            rowPublicCourt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchPublicCourt.setChecked(!switchPublicCourt.isChecked());
                }
            });
        }
    }
}

