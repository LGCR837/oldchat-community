package com.im.oldchat.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.widget.ImageView;
import android.widget.Toast;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageCompressUtil;
import com.im.oldchat.util.ImageLoader;
import org.json.JSONObject;

public class GroupAvatarManager {
    private final Activity activity;
    private final ImageView avatarView;
    private final String token;
    private final String groupId;

    public GroupAvatarManager(Activity activity, ImageView avatarView, String token, String groupId) {
        this.activity = activity;
        this.avatarView = avatarView;
        this.token = token;
        this.groupId = groupId;
    }

    public void pickAvatar(int pickRequestCode, int permissionRequestCode) {
        if (activity == null) {
            return;
        }
        startPicker(pickRequestCode);
    }

    public boolean handlePermissionResult(int requestCode, int[] grantResults, int permissionRequestCode,
                                          int pickRequestCode) {
        if (requestCode != permissionRequestCode) {
            return false;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPicker(pickRequestCode);
        } else {
            Toast.makeText(activity, "未授权读取存储", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data, int pickRequestCode) {
        if (requestCode != pickRequestCode || resultCode != Activity.RESULT_OK || data == null) {
            return false;
        }
        Uri uri = data.getData();
        if (uri != null) {
            uploadAvatar(uri);
        }
        return true;
    }

    private void startPicker(int pickRequestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        try {
            activity.startActivityForResult(Intent.createChooser(intent, "选择群头像"), pickRequestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "无法选择图片", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadAvatar(Uri uri) {
        try {
            byte[] data = ImageCompressUtil.compressToBytes(activity.getContentResolver(), uri, 1024, 1024 * 1024);
            HttpUtil.postMultipart("/media", data, "group_avatar.jpg", "image/jpeg", token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        String url = obj.optString("url");
                        updateAvatarUrl(url);
                    } catch (Exception e) {
                        Toast.makeText(activity, "头像更新失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(activity, "头像上传失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "头像上传失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateAvatarUrl(final String url) {
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("avatar_url", url);
            HttpUtil.post("/groups/avatar", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    ImageLoader.loadAvatar(avatarView, url);
                    Toast.makeText(activity, "头像已更新", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(activity, "头像更新失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "头像更新失败", Toast.LENGTH_SHORT).show();
        }
    }
}
