package com.im.oldchat.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.SwitchCompat;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.service.MessageService;
import com.im.oldchat.util.AccountDataCleaner;

import org.json.JSONObject;

public class AccountManagementActivity extends BaseActivity {
    private String token;
    private View btnDeleteAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_management);

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        View btnBack = findViewByIdCompat(R.id.btnAccountBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnChangePassword = findViewByIdCompat(R.id.btnChangePassword);
        if (btnChangePassword != null) {
            btnChangePassword.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(AccountManagementActivity.this,
                            ChangePasswordActivity.class));
                }
            });
        }

        View btnDeviceManagement = findViewByIdCompat(R.id.btnDeviceManagement);
        if (btnDeviceManagement != null) {
            btnDeviceManagement.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(AccountManagementActivity.this,
                            DeviceManagementActivity.class));
                }
            });
        }

        btnDeleteAccount = findViewByIdCompat(R.id.btnDeleteAccount);
        if (btnDeleteAccount != null) {
            btnDeleteAccount.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDeleteConfirmDialog();
                }
            });
        }
        SwitchCompat rejectInvites = findViewByIdCompat(R.id.switchRejectGroupInvites);
        GroupInvitePreferenceController.bind(this, rejectInvites, token);
    }

    private void showDeleteConfirmDialog() {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("\u6CE8\u9500\u8D26\u53F7")
                .setMessage("\u6CE8\u9500\u540E\u8D26\u53F7\u4E0E\u804A\u5929\u6570\u636E\u5C06\u88AB\u5220\u9664"
                        + "\u4E14\u4E0D\u53EF\u6062\u590D\uFF0C\u662F\u5426\u7EE7\u7EED\uFF1F")
                .setPositiveButton("\u7EE7\u7EED", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        sendDeleteEmailCode();
                    }
                })
                .setNegativeButton("\u53D6\u6D88", null)
                .show();
    }

    private void sendDeleteEmailCode() {
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "\u767B\u5F55\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (btnDeleteAccount != null) {
            btnDeleteAccount.setEnabled(false);
        }
        HttpUtil.post("/me/delete/email/send", new JSONObject(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                if (btnDeleteAccount != null) {
                    btnDeleteAccount.setEnabled(true);
                }
                String hint = "\u7ED1\u5B9A\u90AE\u7BB1";
                try {
                    hint = new JSONObject(response).optString("email_hint", hint);
                } catch (Exception e) {
                }
                showDeleteVerificationDialog(hint);
            }

            @Override
            public void onError(int code, String error) {
                if (btnDeleteAccount != null) {
                    btnDeleteAccount.setEnabled(true);
                }
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                String message = code == 429
                        ? "\u53D1\u9001\u8FC7\u4E8E\u9891\u7E41\uFF0C\u8BF7\u7A0D\u540E\u518D\u8BD5"
                        : "\u9A8C\u8BC1\u7801\u53D1\u9001\u5931\u8D25(" + code + ")";
                Toast.makeText(AccountManagementActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showDeleteVerificationDialog(String emailHint) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, dp(8), pad, 0);

        TextView tip = new TextView(this);
        tip.setText("\u9A8C\u8BC1\u7801\u5DF2\u53D1\u9001\u81F3 " + emailHint
                + "\uFF0C10 \u5206\u949F\u5185\u6709\u6548\u3002");
        tip.setTextColor(getResources().getColor(R.color.color_text_secondary));
        tip.setTextSize(13f);
        content.addView(tip);

        final EditText codeInput = new EditText(this);
        codeInput.setHint("\u8BF7\u8F93\u5165 6 \u4F4D\u90AE\u7BB1\u9A8C\u8BC1\u7801");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        content.addView(codeInput);

        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("\u8BF7\u8F93\u5165\u5F53\u524D\u5BC6\u7801");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(passwordInput);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("\u9A8C\u8BC1\u90AE\u7BB1\u5E76\u6CE8\u9500")
                .setView(content)
                .setPositiveButton("\u786E\u8BA4\u6CE8\u9500", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteAccount(passwordInput.getText().toString().trim(),
                                codeInput.getText().toString().trim());
                    }
                })
                .setNegativeButton("\u53D6\u6D88", null)
                .show();
    }

    private void deleteAccount(String password, String emailCode) {
        if (password == null || password.length() == 0) {
            Toast.makeText(this, "\u8BF7\u8F93\u5165\u5F53\u524D\u5BC6\u7801", Toast.LENGTH_SHORT).show();
            return;
        }
        if (emailCode == null || emailCode.length() != 6) {
            Toast.makeText(this, "\u8BF7\u8F93\u5165 6 \u4F4D\u90AE\u7BB1\u9A8C\u8BC1\u7801",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "\u767B\u5F55\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (btnDeleteAccount != null) {
            btnDeleteAccount.setEnabled(false);
        }
        try {
            JSONObject json = new JSONObject();
            json.put("password", password);
            json.put("email_code", emailCode);
            HttpUtil.post("/me/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(AccountManagementActivity.this, "\u8D26\u53F7\u5DF2\u6CE8\u9500",
                            Toast.LENGTH_SHORT).show();
                    forceLogout();
                }

                @Override
                public void onError(int code, String error) {
                    if (btnDeleteAccount != null) {
                        btnDeleteAccount.setEnabled(true);
                    }
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (error != null && error.contains("invalid_email_code")) {
                        Toast.makeText(AccountManagementActivity.this,
                                "\u90AE\u7BB1\u9A8C\u8BC1\u7801\u9519\u8BEF\u6216\u5DF2\u8FC7\u671F",
                                Toast.LENGTH_SHORT).show();
                    } else if (code == 401) {
                        Toast.makeText(AccountManagementActivity.this, "\u5BC6\u7801\u9519\u8BEF",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(AccountManagementActivity.this,
                                "\u6CE8\u9500\u5931\u8D25(" + code + ")",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            if (btnDeleteAccount != null) {
                btnDeleteAccount.setEnabled(true);
            }
            Toast.makeText(this, "\u6CE8\u9500\u5931\u8D25", Toast.LENGTH_SHORT).show();
        }
    }

    private void forceLogout() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        prefs.edit().clear().apply();
        AccountDataCleaner.clearAll(this);
        WSManager.getInstance().stop();
        MessageService.stop(this);
        com.im.oldchat.util.ActivityTaskCompat.openCleanLogin(this);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
