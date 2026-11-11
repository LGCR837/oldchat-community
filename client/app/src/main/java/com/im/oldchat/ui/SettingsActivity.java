package com.im.oldchat.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.service.MessageService;
import com.im.oldchat.util.AccountDataCleaner;
import com.im.oldchat.util.SettingsPrefs;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends BaseActivity {
    private static final int REQ_MANUAL_PERMISSION = 6201;

    private final ArrayList<String> pendingPermissions = new ArrayList<String>();
    private boolean manualPermissionRequesting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View btnBack = findViewByIdCompat(R.id.btnSettingsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnUiSettings = findViewByIdCompat(R.id.btnUiSettings);
        if (btnUiSettings != null) {
            btnUiSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(SettingsActivity.this, UiSettingsActivity.class));
                }
            });
        }

        View btnNotificationSettings = findViewByIdCompat(R.id.btnNotificationSettings);
        if (btnNotificationSettings != null) {
            btnNotificationSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(SettingsActivity.this, NotificationSettingsActivity.class));
                }
            });
        }

        final android.support.v7.widget.SwitchCompat switchEnterSendMain =
                findViewByIdCompat(R.id.switchEnterSendMain);
        final View rowEnterSendMain = findViewByIdCompat(R.id.rowEnterSendMain);
        if (switchEnterSendMain != null) {
            switchEnterSendMain.setChecked(SettingsPrefs.isEnterSendEnabled(this));
            switchEnterSendMain.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                     boolean isChecked) {
                            SettingsPrefs.setEnterSendEnabled(SettingsActivity.this, isChecked);
                        }
                    });
        }
        if (rowEnterSendMain != null && switchEnterSendMain != null) {
            rowEnterSendMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchEnterSendMain.setChecked(!switchEnterSendMain.isChecked());
                }
            });
        }

        final android.support.v7.widget.SwitchCompat switchDevMode =
                findViewByIdCompat(R.id.switchDevMode);
        final View rowDevMode = findViewByIdCompat(R.id.rowDevMode);
        ImageView devModeIcon = findViewByIdCompat(R.id.ivDevModeIcon);
        if (devModeIcon != null) {
            // 与本页其他设置图标统一使用次级文字色；运行时着色兼容 Android 2.3+。
            devModeIcon.setColorFilter(getResources().getColor(R.color.color_text_secondary));
        }
        if (switchDevMode != null) {
            switchDevMode.setChecked(SettingsPrefs.isDevModeEnabled(this));
            switchDevMode.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                     boolean isChecked) {
                            SettingsPrefs.setDevModeEnabled(SettingsActivity.this, isChecked);
                            if (isChecked) {
                                Toast.makeText(SettingsActivity.this,
                                        "开发模式已开启，将接收测试版更新", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        }
        if (rowDevMode != null && switchDevMode != null) {
            rowDevMode.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchDevMode.setChecked(!switchDevMode.isChecked());
                }
            });
        }

        View btnRequestPermissions = findViewByIdCompat(R.id.btnRequestPermissions);
        if (btnRequestPermissions != null) {
            btnRequestPermissions.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestMissingPermissionsInOrder();
                }
            });
        }

        final android.widget.CheckBox cbPublicCourtEnabled = findViewByIdCompat(R.id.cbPublicCourtEnabled);
        View btnPublicCourtSetting = findViewByIdCompat(R.id.btnPublicCourtSetting);
        if (cbPublicCourtEnabled != null) {
            cbPublicCourtEnabled.setChecked(SettingsStore.isPublicCourtEnabled(this));
        }
        if (btnPublicCourtSetting != null) {
            btnPublicCourtSetting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !SettingsStore.isPublicCourtEnabled(SettingsActivity.this);
                    SettingsStore.setPublicCourtEnabled(SettingsActivity.this, next);
                    if (cbPublicCourtEnabled != null) {
                        cbPublicCourtEnabled.setChecked(next);
                    }
                }
            });
        }

        View btnAccountManagement = findViewByIdCompat(R.id.btnAccountManagement);
        if (btnAccountManagement != null) {
            btnAccountManagement.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(SettingsActivity.this, AccountManagementActivity.class));
                }
            });
        }

        View btnDataSettings = findViewByIdCompat(R.id.btnDataSettings);
        if (btnDataSettings != null) {
            btnDataSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(SettingsActivity.this, DataSettingsActivity.class));
                }
            });
        }

        View btnSupportSettings = findViewByIdCompat(R.id.btnSupportSettings);
        if (btnSupportSettings != null) {
            btnSupportSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(SettingsActivity.this, SupportSettingsActivity.class));
                }
            });
        }

        View btnLogout = findViewByIdCompat(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
                    prefs.edit().clear().apply();
                    AccountDataCleaner.clearAll(SettingsActivity.this);
                    WSManager.getInstance().stop();
                    MessageService.stop(SettingsActivity.this);
                    com.im.oldchat.util.ActivityTaskCompat.openCleanLogin(SettingsActivity.this);
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MANUAL_PERMISSION) {
            return;
        }
        String permission = permissions != null && permissions.length > 0 ? permissions[0] : "";
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!pendingPermissions.isEmpty()) {
            pendingPermissions.remove(0);
        }
        if (granted) {
            Toast.makeText(this, permissionLabel(permission) + "\u5DF2\u6388\u6743",
                    Toast.LENGTH_SHORT).show();
        } else {
            boolean dontAskAgain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && permission != null
                    && permission.length() > 0
                    && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission);
            if (dontAskAgain) {
                Toast.makeText(this,
                        permissionLabel(permission)
                                + "\u88AB\u6C38\u4E45\u62D2\u7EDD\uFF0C\u8BF7\u5230\u7CFB\u7EDF\u8BBE\u7F6E\u624B\u52A8\u5F00\u542F",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, permissionLabel(permission) + "\u672A\u6388\u6743",
                        Toast.LENGTH_SHORT).show();
            }
        }
        requestNextManualPermission();
    }

    private void requestMissingPermissionsInOrder() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "\u5F53\u524D\u7CFB\u7EDF\u65E0\u9700\u624B\u52A8\u7533\u8BF7\u8FD0\u884C\u65F6\u6743\u9650",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (manualPermissionRequesting) {
            Toast.makeText(this, "\u6B63\u5728\u7533\u8BF7\u6743\u9650\uFF0C\u8BF7\u7A0D\u5019",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pendingPermissions.clear();
        collectMissingRuntimePermissions(pendingPermissions);
        if (pendingPermissions.isEmpty()) {
            Toast.makeText(this, "\u5DF2\u6388\u4E88\u6240\u9700\u6743\u9650", Toast.LENGTH_SHORT).show();
            return;
        }
        manualPermissionRequesting = true;
        Toast.makeText(this,
                "\u5F00\u59CB\u7533\u8BF7\u6743\u9650\uFF08\u5171 " + pendingPermissions.size() + " \u9879\uFF09",
                Toast.LENGTH_SHORT).show();
        requestNextManualPermission();
    }

    private void collectMissingRuntimePermissions(List<String> out) {
        if (out == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addMissingPermission(out, Manifest.permission.READ_MEDIA_IMAGES);
            addMissingPermission(out, Manifest.permission.READ_MEDIA_VIDEO);
            addMissingPermission(out, Manifest.permission.READ_MEDIA_AUDIO);
            addMissingPermission(out, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            addMissingPermission(out, Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 28) {
                addMissingPermission(out, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        addMissingPermission(out, Manifest.permission.RECORD_AUDIO);
        addMissingPermission(out, Manifest.permission.READ_PHONE_STATE);
    }

    private void addMissingPermission(List<String> out, String permission) {
        if (out == null || permission == null || permission.length() == 0) {
            return;
        }
        if (out.contains(permission)) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            out.add(permission);
        }
    }

    private void requestNextManualPermission() {
        if (pendingPermissions.isEmpty()) {
            manualPermissionRequesting = false;
            Toast.makeText(this, "\u6743\u9650\u7533\u8BF7\u6D41\u7A0B\u5DF2\u7ED3\u675F",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String permission = pendingPermissions.get(0);
        ActivityCompat.requestPermissions(this, new String[]{permission}, REQ_MANUAL_PERMISSION);
    }

    private String permissionLabel(String permission) {
        if (permission == null || permission.length() == 0) {
            return "\u6743\u9650";
        }
        if (Manifest.permission.READ_MEDIA_IMAGES.equals(permission)) {
            return "\u56FE\u7247\u8BFB\u53D6\u6743\u9650";
        }
        if (Manifest.permission.READ_MEDIA_VIDEO.equals(permission)) {
            return "\u89C6\u9891\u8BFB\u53D6\u6743\u9650";
        }
        if (Manifest.permission.READ_MEDIA_AUDIO.equals(permission)) {
            return "\u97F3\u9891\u8BFB\u53D6\u6743\u9650";
        }
        if (Manifest.permission.READ_EXTERNAL_STORAGE.equals(permission)) {
            return "\u5A92\u4F53\u8BFB\u53D6\u6743\u9650";
        }
        if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
            return "\u5B58\u50A8\u5199\u5165\u6743\u9650";
        }
        if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
            return "\u901A\u77E5\u6743\u9650";
        }
        if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
            return "\u5F55\u97F3\u6743\u9650";
        }
        if (Manifest.permission.READ_PHONE_STATE.equals(permission)) {
            return "\u8BBE\u5907\u8BC6\u522B\u6743\u9650";
        }
        return permission;
    }
}
