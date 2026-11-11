package com.im.oldchat.ui;

import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.UpdateManager;

public class SupportSettingsActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_support_settings);

        View btnBack = findViewByIdCompat(R.id.btnSupportSettingsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnFeedback = findViewByIdCompat(R.id.btnFeedback);
        if (btnFeedback != null) {
            btnFeedback.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new android.content.Intent(
                            SupportSettingsActivity.this, FeedbackActivity.class));
                }
            });
        }

        View btnCheckUpdate = findViewByIdCompat(R.id.btnCheckUpdate);
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(SupportSettingsActivity.this,
                            "\u6B63\u5728\u68C0\u67E5\u66F4\u65B0...", Toast.LENGTH_SHORT).show();
                    UpdateManager.check(SupportSettingsActivity.this, true);
                }
            });
        }

        View btnPrivacyPolicy = findViewByIdCompat(R.id.btnPrivacyPolicy);
        if (btnPrivacyPolicy != null) {
            btnPrivacyPolicy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new android.content.Intent(
                            SupportSettingsActivity.this, PrivacyPolicyActivity.class));
                }
            });
        }

        View btnDonate = findViewByIdCompat(R.id.btnDonate);
        if (btnDonate != null) {
            btnDonate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDonateDialog();
                }
            });
        }

        View btnAboutApp = findViewByIdCompat(R.id.btnAboutApp);
        if (btnAboutApp != null) {
            btnAboutApp.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAboutDialog();
                }
            });
        }
    }

    private void showAboutDialog() {
        String versionName = "\u672A\u77E5";
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            if (info != null && info.versionName != null) {
                versionName = info.versionName;
            }
        } catch (Exception e) {
            // Ignore and keep fallback version string.
        }
        new AlertDialog.Builder(this)
                .setTitle("\u5173\u4E8E\u65E7\u804A")
                .setMessage("\u5F53\u524D\u7248\u672C: " + versionName
                        + "\n\n\u5982\u679C\u4F60\u9047\u5230\u95EE\u9898\uFF0C\u53EF\u5728\u672C\u9875\u8FDB\u5165\u95EE\u9898\u53CD\u9988\u3002")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showDonateDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_support_donate, null);
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("\u6350\u8D60\u652F\u6301")
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
