package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import com.im.oldchat.BaseActivity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import org.json.JSONObject;

public class ChangePasswordActivity extends BaseActivity {
    private EditText etOldPassword, etNewPassword, etConfirmPassword;
    private Button btnSubmit;
    private String token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        etOldPassword = findViewByIdCompat(R.id.etOldPassword);
        etNewPassword = findViewByIdCompat(R.id.etNewPassword);
        etConfirmPassword = findViewByIdCompat(R.id.etConfirmPassword);
        btnSubmit = findViewByIdCompat(R.id.btnSubmit);
        View btnBack = (View) findViewByIdCompat(R.id.btnChangePasswordBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        btnSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changePassword();
            }
        });
    }

    private void changePassword() {
        String oldPwd = etOldPassword.getText().toString().trim();
        String newPwd = etNewPassword.getText().toString().trim();
        String confirmPwd = etConfirmPassword.getText().toString().trim();

        if (oldPwd.isEmpty()) {
            Toast.makeText(this, "请输入旧密码", Toast.LENGTH_SHORT).show();
            return;
        }
        if (newPwd.isEmpty()) {
            Toast.makeText(this, "请输入新密码", Toast.LENGTH_SHORT).show();
            return;
        }
        if (newPwd.length() < 6) {
            Toast.makeText(this, "新密码至少6位", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!newPwd.equals(confirmPwd)) {
            Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        final String newPwdFinal = newPwd;
        try {
            JSONObject json = new JSONObject();
            json.put("old_password", oldPwd);
            json.put("new_password", newPwd);
            HttpUtil.post("/me/password", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
                    prefs.edit().putString("saved_password", newPwdFinal).apply();
                    Toast.makeText(ChangePasswordActivity.this, "密码修改成功", Toast.LENGTH_SHORT).show();
                    finish();
                }

                @Override
                public void onError(int code, String error) {
                    btnSubmit.setEnabled(true);
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (code == 400 || code == 401) {
                        Toast.makeText(ChangePasswordActivity.this, "旧密码错误", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(ChangePasswordActivity.this, "修改失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            btnSubmit.setEnabled(true);
            Toast.makeText(this, "修改失败", Toast.LENGTH_SHORT).show();
        }
    }
}
