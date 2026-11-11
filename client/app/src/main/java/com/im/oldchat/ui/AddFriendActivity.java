package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.content.Intent;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.QrPayloadUtil;
import org.json.JSONObject;

public class AddFriendActivity extends BaseActivity {
    private static final int REQ_SCAN_QR = 5210;

    private EditText etFriendUsername;
    private View btnSubmit;
    private EditText etGroupId;
    private View btnJoinGroup;
    private View btnScanQr;
    private String token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_friend);

        etFriendUsername = findViewByIdCompat(R.id.etFriendUsername);
        btnSubmit = findViewByIdCompat(R.id.btnSubmit);
        etGroupId = findViewByIdCompat(R.id.etGroupId);
        btnJoinGroup = findViewByIdCompat(R.id.btnJoinGroup);
        btnScanQr = findViewByIdCompat(R.id.btnScanQr);
        View btnBack = (View) findViewByIdCompat(R.id.btnAddFriendBack);
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
                String username = etFriendUsername.getText().toString().trim();
                if (username.isEmpty()) {
                    Toast.makeText(AddFriendActivity.this, R.string.friend_request_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                submitFriendRequest(username, true);
            }
        });

        btnJoinGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String groupId = etGroupId.getText().toString().trim().toUpperCase();
                if (groupId.isEmpty()) {
                    Toast.makeText(AddFriendActivity.this, R.string.group_id_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                joinGroup(groupId, true);
            }
        });

        if (btnScanQr != null) {
            btnScanQr.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivityForResult(new Intent(AddFriendActivity.this, QrScanActivity.class), REQ_SCAN_QR);
                }
            }
            );
        }
    }

    private void submitFriendRequest(String uid, final boolean finishOnSuccess) {
        btnSubmit.setEnabled(false);
        try {
            JSONObject json = new JSONObject();
            json.put("to_uid", uid);
            HttpUtil.post("/friends/request", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    btnSubmit.setEnabled(true);
                    Toast.makeText(AddFriendActivity.this, R.string.friend_request_sent, Toast.LENGTH_SHORT).show();
                    if (finishOnSuccess) {
                        finish();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    btnSubmit.setEnabled(true);
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (code == 404) {
                        Toast.makeText(AddFriendActivity.this, R.string.friend_request_not_found, Toast.LENGTH_SHORT).show();
                    } else if (code == 409) {
                        if (FriendRequestErrorHelper.isPending(error)) {
                            Toast.makeText(AddFriendActivity.this, R.string.friend_request_pending_processing, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(AddFriendActivity.this, R.string.friend_request_conflict, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(AddFriendActivity.this, R.string.friend_request_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            btnSubmit.setEnabled(true);
            Toast.makeText(AddFriendActivity.this, R.string.friend_request_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void joinGroup(String groupId, final boolean finishOnSuccess) {
        btnJoinGroup.setEnabled(false);
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            HttpUtil.post("/groups/join", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    btnJoinGroup.setEnabled(true);
                    try {
                        JSONObject obj = new JSONObject(response);
                        String status = obj.optString("status", "");
                        if ("joined".equalsIgnoreCase(status)) {
                            Toast.makeText(AddFriendActivity.this, R.string.group_joined, Toast.LENGTH_SHORT).show();
                        } else if ("pending".equalsIgnoreCase(status)) {
                            Toast.makeText(AddFriendActivity.this, R.string.group_join_pending, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(AddFriendActivity.this, R.string.group_join_failed, Toast.LENGTH_SHORT).show();
                        }
                        if (finishOnSuccess) {
                            finish();
                        }
                    } catch (Exception e) {
                        Toast.makeText(AddFriendActivity.this, R.string.group_join_failed, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    btnJoinGroup.setEnabled(true);
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (code == 404) {
                        Toast.makeText(AddFriendActivity.this, R.string.group_not_found, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(AddFriendActivity.this, R.string.group_join_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            btnJoinGroup.setEnabled(true);
            Toast.makeText(AddFriendActivity.this, R.string.group_join_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN_QR || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        String raw = data.getStringExtra(QrScanActivity.EXTRA_QR_RESULT);
        QrPayloadUtil.QrPayload payload = QrPayloadUtil.parse(raw);
        if (payload == null || payload.id.length() == 0) {
            Toast.makeText(this, "\u4E8C\u7EF4\u7801\u5185\u5BB9\u65E0\u6548", Toast.LENGTH_SHORT).show();
            return;
        }
        if (QrPayloadUtil.TYPE_GROUP.equals(payload.type)) {
            etGroupId.setText(payload.id);
            joinGroup(payload.id, true);
            return;
        }
        if (QrPayloadUtil.TYPE_USER.equals(payload.type)) {
            SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
            String myUid = prefs.getString("my_uid", "");
            if (myUid != null && myUid.equalsIgnoreCase(payload.id)) {
                Toast.makeText(this, "\u8FD9\u662F\u4F60\u81EA\u5DF1\u7684\u540D\u7247", Toast.LENGTH_SHORT).show();
                return;
            }
            etFriendUsername.setText(payload.id);
            submitFriendRequest(payload.id, true);
            return;
        }
        Toast.makeText(this, "\u6682\u4E0D\u652F\u6301\u7684\u4E8C\u7EF4\u7801", Toast.LENGTH_SHORT).show();
    }
}
