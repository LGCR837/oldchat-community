package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import com.im.oldchat.BaseActivity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;

import org.json.JSONObject;

public class FeedbackActivity extends BaseActivity {

    private EditText etContent;
    private Button btnSubmit;
    private TextView tvDeviceInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        etContent = findViewByIdCompat(R.id.etContent);
        btnSubmit = findViewByIdCompat(R.id.btnSubmit);
        tvDeviceInfo = findViewByIdCompat(R.id.tvDeviceInfo);

        View btnBack = (View) findViewByIdCompat(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        String appVersion = getAppVersion();
        String deviceInfo = "设备: " + Build.MANUFACTURER + " " + Build.MODEL +
                " | 系统: Android " + Build.VERSION.RELEASE +
                " | 版本: " + appVersion;
        tvDeviceInfo.setText(deviceInfo);

        btnSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitFeedback();
            }
        });
    }

    private String getAppVersion() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private void submitFeedback() {
        String content = etContent.getText().toString().trim();
        if (content.isEmpty()) {
            Toast.makeText(this, "请输入反馈内容", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        btnSubmit.setText("提交中...");

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        String token = prefs.getString("access_token", "");

        try {
            JSONObject json = new JSONObject();
            json.put("content", content);
            json.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
            json.put("android_version", Build.VERSION.RELEASE);
            json.put("app_version", getAppVersion());

            HttpUtil.post("/feedback", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(FeedbackActivity.this, "感谢您的反馈！", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
                }

                @Override
                public void onError(int code, String error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnSubmit.setEnabled(true);
                            btnSubmit.setText("提交反馈");
                            Toast.makeText(FeedbackActivity.this, "提交失败，请稍后重试", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } catch (Exception e) {
            btnSubmit.setEnabled(true);
            btnSubmit.setText("提交反馈");
            Toast.makeText(this, "提交失败", Toast.LENGTH_SHORT).show();
        }
    }
}
