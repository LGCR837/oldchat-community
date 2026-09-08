package com.im.oldchat.ui;

import android.widget.CompoundButton;
import android.widget.Toast;

import android.support.v7.widget.SwitchCompat;

import com.im.oldchat.api.HttpUtil;

import org.json.JSONObject;

/** 账号级“默认拒绝群聊邀请”设置，服务端保存、默认关闭。 */
final class GroupInvitePreferenceController {
    private final AccountManagementActivity activity;
    private final SwitchCompat toggle;
    private final String token;
    private boolean applying;

    private GroupInvitePreferenceController(AccountManagementActivity activity,
                                            SwitchCompat toggle, String token) {
        this.activity = activity;
        this.toggle = toggle;
        this.token = token == null ? "" : token;
    }

    static void bind(AccountManagementActivity activity, SwitchCompat toggle, String token) {
        if (activity == null || toggle == null) {
            return;
        }
        new GroupInvitePreferenceController(activity, toggle, token).load();
    }

    private void load() {
        toggle.setEnabled(false);
        HttpUtil.get("/me/group-invite-preference", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                boolean reject = false;
                try {
                    reject = new JSONObject(response).optBoolean("reject", false);
                } catch (Exception e) {
                }
                applying = true;
                toggle.setChecked(reject);
                applying = false;
                toggle.setEnabled(true);
                toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (!applying) {
                            save(isChecked);
                        }
                    }
                });
            }

            @Override
            public void onError(int code, String error) {
                toggle.setEnabled(true);
                Toast.makeText(activity, "加载群邀请设置失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void save(final boolean reject) {
        toggle.setEnabled(false);
        try {
            JSONObject json = new JSONObject();
            json.put("reject", reject);
            HttpUtil.post("/me/group-invite-preference", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    toggle.setEnabled(true);
                    Toast.makeText(activity, reject ? "已默认拒绝群聊邀请" : "群聊邀请将进入请求页面",
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    applying = true;
                    toggle.setChecked(!reject);
                    applying = false;
                    toggle.setEnabled(true);
                    Toast.makeText(activity, "保存失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            applying = true;
            toggle.setChecked(!reject);
            applying = false;
            toggle.setEnabled(true);
        }
    }
}
