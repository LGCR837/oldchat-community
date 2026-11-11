package com.im.oldchat.ui;

import com.im.oldchat.BaseActivity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.MainActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.AccountDataCleaner;
import com.im.oldchat.util.DeviceInfoUtil;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.PrivacyPolicyHelper;
import com.im.oldchat.util.ServerBaseUrlManager;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.UpdateManager;
import org.json.JSONObject;

public class LoginActivity extends BaseActivity {

    private EditText etUsername, etPassword;
    private Button btnLogin;
    private Button btnRegister;
    private Button btnRecoverPassword;
    private android.widget.CheckBox cbPrivacyAgree;
    private TextView tvPrivacyPolicy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etUsername = findViewByIdCompat(R.id.etUsername);
        etPassword = findViewByIdCompat(R.id.etPassword);
        btnLogin = findViewByIdCompat(R.id.btnLogin);
        btnRegister = findViewByIdCompat(R.id.btnRegister);
        btnRecoverPassword = findViewByIdCompat(R.id.btnRecoverPassword);
        cbPrivacyAgree = findViewByIdCompat(R.id.cbPrivacyAgree);
        tvPrivacyPolicy = findViewByIdCompat(R.id.tvPrivacyPolicy);
        setupServerBaseUrlEditor();

        if (cbPrivacyAgree != null) {
            cbPrivacyAgree.setChecked(SettingsPrefs.isPrivacyAgreed(this));
            cbPrivacyAgree.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!SettingsPrefs.isPrivacyAgreed(LoginActivity.this)) {
                        cbPrivacyAgree.setChecked(false);
                        showPrivacyPolicy();
                    }
                }
            });
        }
        if (tvPrivacyPolicy != null) {
            tvPrivacyPolicy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPrivacyPolicy();
                }
            });
        }

        UpdateManager.check(this);

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = etUsername.getText().toString();
                String password = etPassword.getText().toString();
                if (!ensurePrivacyAgreed()) {
                    return;
                }
                login(username, password);
            }
        });

        if (btnRegister != null) {
            btnRegister.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openRegisterPage();
                }
            });
        }

        if (btnRecoverPassword != null) {
            btnRecoverPassword.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 找回密码已迁移到网页版（App 内图形验证码已下线）。
                    openForgotPasswordPage();
                }
            });
        }
    }

    /** 打开官网找回密码页（浏览器跳转）。 */
    private void openForgotPasswordPage() {
        try {
            String base = HttpUtil.BASE_URL == null ? "" : HttpUtil.BASE_URL;
            String root = base;
            int idx = base.indexOf("/v1");
            if (idx > 0) {
                root = base.substring(0, idx);
            }
            String url = root + "/forgot-password";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_register, Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开官网注册页（浏览器跳转）。从 BASE_URL 推导官网根地址，拼 /register。 */
    private void openRegisterPage() {
        try {
            String base = HttpUtil.BASE_URL == null ? "" : HttpUtil.BASE_URL;
            String root = base;
            int idx = base.indexOf("/v1");
            if (idx > 0) {
                root = base.substring(0, idx);
            }
            String url = root + "/register";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_register, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupServerBaseUrlEditor() {
        View logo = findViewByIdCompat(R.id.ivLoginLogo);
        if (logo == null) {
            return;
        }
        logo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(LoginActivity.this, "长按图标可设置服务器地址", Toast.LENGTH_SHORT).show();
            }
        });
        logo.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showServerBaseUrlDialog();
                return true;
            }
        });
    }

    private void showServerBaseUrlDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("http://example.com:8080/v1");
        String currentBaseUrl = ServerBaseUrlManager.getCurrentBaseUrl();
        if (currentBaseUrl != null) {
            input.setText(currentBaseUrl);
            input.setSelection(currentBaseUrl.length());
        }

        new AlertDialog.Builder(this)
                .setTitle("设置服务器 Base URL")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText() == null ? "" : input.getText().toString();
                        String saved = ServerBaseUrlManager.saveAndApply(LoginActivity.this, raw);
                        if (saved == null) {
                            Toast.makeText(LoginActivity.this, "地址格式无效，请输入 http(s)://host[:port][/path]",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(LoginActivity.this, "服务器地址已保存: " + saved, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ServerBaseUrlManager.resetToDefault(LoginActivity.this);
                        Toast.makeText(LoginActivity.this, "已恢复默认服务器地址", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void login(String username, String password) {
        final String usernameFinal = username;
        final String passwordFinal = password;
        try {
            JSONObject json = new JSONObject();
            json.put("identifier", username);
            json.put("password", password);
            json.put("device_id", DeviceInfoUtil.getDeviceId(this));
            json.put("imei", DeviceInfoUtil.getImei(this));
            json.put("device_name", DeviceInfoUtil.getDeviceName());
            json.put("platform", "android");
            json.put("app_version", DeviceInfoUtil.getAppVersion(this));

            HttpUtil.post("/auth/login", json, null, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject resp = new JSONObject(response);
                        String accessToken = resp.getString("access_token");
                        String refreshToken = resp.getString("refresh_token");
                        JSONObject user = resp.getJSONObject("user");
                        String userId = user.getString("id");
                        String myUID = user.getString("uid");
                        String myNcuid = user.optString("ncuid", "");
                        // 解析并缓存自己的显示名（发送消息 pending 时渲染用，避免显示 NCUID）。
                        String myDisplayName = user.optString("display_name", "");
                        String myUsername = user.optString("username", "");
                        SharedPreferences authPrefs = getSharedPreferences("auth", MODE_PRIVATE);
                        authPrefs.edit()
                                .putString("display_name", myDisplayName)
                                .putString("my_username", myUsername)
                                .apply();

                        clearCachesOnAccountSwitch(myUID);
                        saveToken(accessToken, refreshToken, userId, myUID, myNcuid);
                        saveCredentials(usernameFinal, passwordFinal);
                        Toast.makeText(LoginActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    } catch (Exception e) {
                        onError(-1, "解析失败: " + e.getMessage());
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (code == 429 || (error != null && error.contains("rate_limited"))) {
                        Toast.makeText(LoginActivity.this, "请求频繁，请稍后再试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (code == 401 || (error != null && error.contains("invalid_credentials"))) {
                        Toast.makeText(LoginActivity.this, "用户名或密码错误，请重试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (code == 403 || (error != null && (error.contains("user_banned") || error.contains("device_banned")))) {
                        Toast.makeText(LoginActivity.this, "已被封禁", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(LoginActivity.this, "登录失败 (" + code + ")", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "构造请求失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToken(String accessToken, String refreshToken, String userId,
                           String myUID, String myNcuid) {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        prefs.edit().putString("access_token", accessToken)
                   .putString("refresh_token", refreshToken)
                   .putString("user_id", userId)
                   .putString("my_uid", myUID)
                   .putString("my_ncuid", myNcuid == null ? "" : myNcuid)
                   .apply();
        MyUidStore.updateCurrentUid(this, myUID);
    }

    private void clearCachesOnAccountSwitch(String newUid) {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String oldUid = prefs.getString("my_uid", "");
        if (!oldUid.isEmpty() && newUid != null && !newUid.isEmpty() && !oldUid.equals(newUid)) {
            AccountDataCleaner.clearAll(this);
            MyUidStore.clearUidAliases(this);
        }
    }

    private void saveCredentials(String username, String password) {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        prefs.edit().putString("saved_username", username)
                   .putString("saved_password", password)
                   .apply();
    }

    private boolean ensurePrivacyAgreed() {
        if (cbPrivacyAgree != null && cbPrivacyAgree.isChecked()) {
            SettingsPrefs.setPrivacyAgreed(this, true);
            return true;
        }
        Toast.makeText(this, R.string.privacy_policy_required, Toast.LENGTH_SHORT).show();
        showPrivacyPolicy();
        return false;
    }

    private void showPrivacyPolicy() {
        PrivacyPolicyHelper.showPolicyDialog(this, new Runnable() {
            @Override
            public void run() {
                if (cbPrivacyAgree != null) {
                    cbPrivacyAgree.setChecked(true);
                }
                SettingsPrefs.setPrivacyAgreed(LoginActivity.this, true);
            }
        });
    }
}
