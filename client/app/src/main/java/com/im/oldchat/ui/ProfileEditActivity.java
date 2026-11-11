package com.im.oldchat.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.util.AutofillCompatApi26;
import com.im.oldchat.util.ImageCompressUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MyUidStore;

import org.json.JSONObject;

public class ProfileEditActivity extends BaseActivity {
    private static final int REQ_PICK_AVATAR = 1001;
    private static final int REQ_STORAGE = 1002;
    private static final int MAX_DISPLAY_NAME_LENGTH = 15;

    private ImageView ivAvatar;
    private TextView tvUser;
    private TextView tvEmail;
    private EditText etDisplayName;
    private EditText etUID;
    private View btnSaveProfile;
    private View btnChangeUID;
    private View btnChangeAvatar;
    private String token;
    private String currentAvatarUrl;
    private String currentDisplayName;
    private String currentUid;
    private String currentUsername;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_edit);

        ivAvatar = findViewByIdCompat(R.id.ivAvatar);
        tvUser = findViewByIdCompat(R.id.tvMyUsername);
        tvEmail = findViewByIdCompat(R.id.tvMyEmail);
        etDisplayName = findViewByIdCompat(R.id.etDisplayName);
        etUID = findViewByIdCompat(R.id.etUID);
        AutofillCompatApi26.disableAutofill(etDisplayName);
        AutofillCompatApi26.disableAutofill(etUID);

        if (etDisplayName != null) {
            etDisplayName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_DISPLAY_NAME_LENGTH)});
            etDisplayName.setSingleLine(true);
        }

        btnSaveProfile = findViewByIdCompat(R.id.btnSaveProfile);
        btnChangeUID = findViewByIdCompat(R.id.btnChangeUID);
        btnChangeAvatar = findViewByIdCompat(R.id.btnChangeAvatar);
        View profileCard = (View) findViewByIdCompat(R.id.profile_card);
        View btnBack = (View) findViewByIdCompat(R.id.btnProfileEditBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        if (profileCard != null) {
            profileCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ProfileQrNavigator.openMyQrCard(ProfileEditActivity.this, currentUid, currentDisplayName, tvUser);
                }
            });
        }
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        if (btnChangeAvatar != null) {
            btnChangeAvatar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickAvatar();
                }
            });
        }
        if (btnSaveProfile != null) {
            btnSaveProfile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateProfile();
                }
            });
        }
        if (btnChangeUID != null) {
            btnChangeUID.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateUID();
                }
            });
        }

        loadProfile();
    }

    private void loadProfile() {
        HttpUtil.get("/me", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    String uid = obj.optString("uid", "");
                    String username = obj.optString("username", "");
                    String displayName = normalizeDisplayName(obj.optString("display_name", ""));
                    currentAvatarUrl = obj.optString("avatar_url", "");
                    currentDisplayName = displayName;
                    currentUid = uid;
                    currentUsername = username;
                    String primary = displayName;
                    if (primary == null || primary.isEmpty()) {
                        primary = uid;
                    }
                    if (tvUser != null) {
                        tvUser.setText(primary == null ? "" : primary);
                    }
                    updateSecondaryText(uid, username);
                    if (etDisplayName != null) {
                        etDisplayName.setText(displayName);
                    }
                    if (uid != null && !uid.isEmpty() && etUID != null) {
                        etUID.setText(uid);
                    }
                    if (ivAvatar != null) {
                        ImageLoader.loadAvatar(ivAvatar, currentAvatarUrl);
                    }
                } catch (Exception e) {
                    Toast.makeText(ProfileEditActivity.this, "加载资料失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                String msg = parseErrorMessage(code, error, "加载资料失败: " + code);
                Toast.makeText(ProfileEditActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateProfile() {
        if (etDisplayName == null) return;
        String displayName = normalizeDisplayName(etDisplayName.getText().toString());
        if (displayName.isEmpty()) {
            displayName = normalizeDisplayName(currentDisplayName);
        }
        if (displayName == null || displayName.isEmpty()) {
            Toast.makeText(ProfileEditActivity.this, "昵称不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        final String newDisplayName = displayName;
        try {
            JSONObject json = new JSONObject();
            json.put("display_name", newDisplayName);
            json.put("avatar_url", currentAvatarUrl == null ? "" : currentAvatarUrl);
            HttpUtil.post("/me/profile", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    currentDisplayName = newDisplayName;
                    updatePrimaryText();
                    Toast.makeText(ProfileEditActivity.this, "保存成功", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    String msg = parseErrorMessage(code, error, "保存失败: " + code);
                    Toast.makeText(ProfileEditActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(ProfileEditActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePrimaryText() {
        String primary = currentDisplayName;
        if (primary == null || primary.isEmpty()) {
            primary = currentUid;
        }
        if (tvUser != null) {
            tvUser.setText(primary == null ? "" : primary);
        }
    }

    private void updateSecondaryText(String uid, String username) {
        if (tvEmail == null) return;
        StringBuilder secondary = new StringBuilder();
        if (uid != null && !uid.isEmpty()) {
            secondary.append("旧聊ID: ").append(uid);
        }
        if (username != null && !username.isEmpty()) {
            if (secondary.length() > 0) {
                secondary.append("  ");
            }
            secondary.append("用户名: ").append(username);
        }
        tvEmail.setText(secondary.toString());
    }

    private void updateUID() {
        if (etUID == null) return;
        String newUID = etUID.getText().toString().trim().toUpperCase();
        if (newUID.isEmpty()) {
            Toast.makeText(ProfileEditActivity.this, "请输入新的旧聊ID", Toast.LENGTH_SHORT).show();
            return;
        }
        final String newUidFinal = newUID;
        try {
            JSONObject json = new JSONObject();
            json.put("uid", newUID);
            HttpUtil.post("/me/uid", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
                    String oldUid = prefs.getString("my_uid", "");
                    MyUidStore.recordUidAlias(ProfileEditActivity.this, oldUid, newUidFinal);
                    prefs.edit().putString("my_uid", newUidFinal).apply();
                    MyUidStore.updateCurrentUid(ProfileEditActivity.this, newUidFinal);
                    currentUid = newUidFinal;
                    updatePrimaryText();
                    updateSecondaryText(currentUid, currentUsername);
                    Toast.makeText(ProfileEditActivity.this, "旧聊ID已更新", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    String msg = parseErrorMessage(code, error, "更新失败");
                    Toast.makeText(ProfileEditActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(ProfileEditActivity.this, "更新失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static String parseErrorMessage(int code, String errorJson, String defaultMsg) {
        if (errorJson != null && !errorJson.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(errorJson);
                String msg = obj.optString("error", "");
                String errCode = obj.optString("code", "");
                if ("uid_change_wait".equals(errCode) || "uid_change_wait".equals(msg)) {
                    if (msg.contains("天") || msg.contains("冷却期")) {
                        return msg;
                    }
                    return "正在 UID 修改冷却期（30 天内只能修改一次）";
                }
                if ("rate_limited".equals(errCode) || code == 429) {
                    return "操作过于频繁，请稍后再试";
                }
                if ("uid_taken".equals(errCode)) {
                    return "该旧聊ID已被占用，请更换";
                }
                if ("invalid_uid".equals(errCode)) {
                    return "旧聊ID格式无效（需 3-20 位字母/数字/下划线）";
                }
                if (!msg.isEmpty() && !msg.equals(errCode)) {
                    return msg;
                }
            } catch (Exception ignored) {
                if (errorJson.length() < 60 && !errorJson.startsWith("{")) {
                    return errorJson;
                }
            }
        }
        if (code == 429) {
            return "操作过于频繁，请稍后再试";
        }
        return defaultMsg != null ? defaultMsg : ("操作失败 (" + code + ")");
    }

    private String normalizeDisplayName(String value) {
        return FriendNameResolver.normalizeDisplayName(value, MAX_DISPLAY_NAME_LENGTH);
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "选择头像"), REQ_PICK_AVATAR);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickAvatar();
            } else {
                Toast.makeText(ProfileEditActivity.this, "未授权读取存储", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_AVATAR && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadAvatar(uri);
            }
        }
    }

    private void uploadAvatar(Uri uri) {
        try {
            byte[] data = ImageCompressUtil.compressToBytes(getContentResolver(), uri, 1024, 1024 * 1024);
            HttpUtil.postMultipart("/me/avatar", data, "avatar.jpg", "image/jpeg", token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        currentAvatarUrl = obj.optString("avatar_url", "");
                        if (ivAvatar != null) {
                            ImageLoader.loadAvatar(ivAvatar, currentAvatarUrl);
                        }
                        Toast.makeText(ProfileEditActivity.this, "头像已更新", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ProfileEditActivity.this, "头像更新失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    String msg = parseErrorMessage(code, error, "头像上传失败: " + code);
                    Toast.makeText(ProfileEditActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(ProfileEditActivity.this, "头像上传失败", Toast.LENGTH_SHORT).show();
        }
    }
}
