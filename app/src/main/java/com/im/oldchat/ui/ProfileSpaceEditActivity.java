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
import com.im.oldchat.BaseActivity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.support.v4.content.ContextCompat;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ImageCompressUtil;
import org.json.JSONObject;

public class ProfileSpaceEditActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";
    private static final int REQ_PICK_COVER = 1401;
    private static final int REQ_STORAGE = 1402;

    private ImageView ivCover;
    private EditText etSignature;
    private View btnPick;
    private View btnSave;
    private String token;
    private String currentCoverUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_space_edit);

        ivCover = findViewByIdCompat(R.id.ivEditCover);
        etSignature = findViewByIdCompat(R.id.etSignature);
        btnPick = findViewByIdCompat(R.id.btnPickCover);
        btnSave = findViewByIdCompat(R.id.btnSaveSpace);
        View btnBack = (View) findViewByIdCompat(R.id.btnSpaceEditBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickCover();
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveProfile();
            }
        });

        loadProfile();
    }

    private void loadProfile() {
        HttpUtil.get("/me", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    String signature = obj.optString("signature", "");
                    currentCoverUrl = obj.optString("cover_url", "");
                    etSignature.setText(signature == null ? "" : signature);
                    if (currentCoverUrl == null || currentCoverUrl.isEmpty()) {
                        ivCover.setImageDrawable(null);
                        ivCover.setBackgroundColor(ContextCompat.getColor(ProfileSpaceEditActivity.this, R.color.color_surface));
                    } else {
                        ivCover.setBackgroundColor(0x00000000);
                        ImageLoader.load(ivCover, currentCoverUrl);
                    }
                } catch (Exception e) {
                    Toast.makeText(ProfileSpaceEditActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(ProfileSpaceEditActivity.this, "加载失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveProfile() {
        String signature = etSignature.getText().toString().trim();
        try {
            JSONObject json = new JSONObject();
            json.put("signature", signature);
            json.put("cover_url", currentCoverUrl == null ? "" : currentCoverUrl);
            HttpUtil.post("/me/profile", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(ProfileSpaceEditActivity.this, "保存成功", Toast.LENGTH_SHORT).show();
                    finish();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ProfileSpaceEditActivity.this, "保存失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickCover() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "选择背景"), REQ_PICK_COVER);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickCover();
            } else {
                Toast.makeText(this, "未授权读取存储", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_COVER && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadCover(uri);
            }
        }
    }

    private void uploadCover(Uri uri) {
        try {
            byte[] data = ImageCompressUtil.compressToBytes(getContentResolver(), uri, 1600, 2 * 1024 * 1024);
            HttpUtil.postMultipart("/me/cover", data, "cover.jpg", "image/jpeg", token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        currentCoverUrl = obj.optString("cover_url", "");
                        if (currentCoverUrl == null || currentCoverUrl.isEmpty()) {
                            ivCover.setImageDrawable(null);
                            ivCover.setBackgroundColor(ContextCompat.getColor(ProfileSpaceEditActivity.this, R.color.color_surface));
                        } else {
                            ivCover.setBackgroundColor(0x00000000);
                            ImageLoader.load(ivCover, currentCoverUrl);
                        }
                        Toast.makeText(ProfileSpaceEditActivity.this, "背景已更新", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ProfileSpaceEditActivity.this, "背景更新失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ProfileSpaceEditActivity.this, "背景上传失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "背景上传失败", Toast.LENGTH_SHORT).show();
        }
    }
}
