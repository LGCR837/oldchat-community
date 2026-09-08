package com.im.oldchat.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import com.im.oldchat.BaseActivity;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageCompressUtil;
import org.json.JSONObject;

public class MomentComposeActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";
    private static final int REQ_PICK_IMAGE = 1201;
    private static final int REQ_STORAGE = 1202;
    private static final int MAX_IMAGE = 2560;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGES = 9;

    private EditText etBody;
    private ImageView ivPreview;
    private View rlImagePreview;
    private android.widget.TextView tvImageCount;
    private View btnPick;
    private View btnPublish;
    private View btnRemoveImage;
    private String token;
    private final java.util.List<java.io.File> imageFileList = new java.util.ArrayList<>();
    private final java.util.List<String> imageNameList = new java.util.ArrayList<>();
    private final java.util.List<String> imageTypeList = new java.util.ArrayList<>();
    private Uri previewUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_compose);

        etBody = findViewByIdCompat(R.id.etMomentBody);
        ivPreview = findViewByIdCompat(R.id.ivMomentPreview);
        rlImagePreview = findViewByIdCompat(R.id.rlImagePreview);
        tvImageCount = findViewByIdCompat(R.id.tvMomentImageCount);
        btnPick = findViewByIdCompat(R.id.btnPickMomentImage);
        btnPublish = findViewByIdCompat(R.id.btnPublishMoment);
        btnRemoveImage = findViewByIdCompat(R.id.btnRemoveImage);
        View btnBack = (View) findViewByIdCompat(R.id.btnMomentBack);
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
                pickImage();
            }
        });
        btnPublish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publishMoment();
            }
        });

        if (btnRemoveImage != null) {
            btnRemoveImage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearImages();
                }
            });
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_PICK_IMAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickImage();
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
        if (requestCode == REQ_PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 16) {
                android.content.ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        Uri uri = clip.getItemAt(i).getUri();
                        if (uri != null) {
                            uris.add(uri);
                        }
                    }
                }
            }
            if (uris.isEmpty()) {
                Uri uri = data.getData();
                if (uri != null) {
                    uris.add(uri);
                }
            }
            if (!uris.isEmpty()) {
                if (uris.size() > MAX_IMAGES) {
                    Toast.makeText(this, "最多选择" + MAX_IMAGES + "张图片", Toast.LENGTH_SHORT).show();
                    uris = uris.subList(0, MAX_IMAGES);
                }
                loadImages(uris);
            }
        }
    }

    private void loadImages(java.util.List<Uri> uris) {
        clearImages();
        try {
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                if (uri == null) {
                    continue;
                }
                byte[] data = ImageCompressUtil.compressToBytes(getContentResolver(), uri, MAX_IMAGE, MAX_IMAGE_BYTES);
                if (data == null || data.length == 0) {
                    continue;
                }
                java.io.File tmp = new java.io.File(getCacheDir(), "moment_tmp_" + System.currentTimeMillis() + "_" + i + ".jpg");
                java.io.FileOutputStream fos = null;
                try {
                    fos = new java.io.FileOutputStream(tmp);
                    fos.write(data);
                    fos.flush();
                } finally {
                    if (fos != null) {
                        try { fos.close(); } catch (Exception ignored) {}
                    }
                }
                imageFileList.add(tmp);
                imageNameList.add(queryFileName(uri));
                imageTypeList.add("image/jpeg");
                if (previewUri == null) {
                    previewUri = uri;
                }
                // 仅对第一张图解码微型缩略图（<=320px，RGB_565），极大降低常驻内存
                if (i == 0 && ivPreview != null) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    int sample = 1;
                    int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                    while (maxDim / sample > 320) {
                        sample *= 2;
                    }
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap preview = null;
                    try {
                        preview = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    } catch (OutOfMemoryError ignored) {
                    }
                    if (preview != null) {
                        ivPreview.setImageBitmap(preview);
                    } else if (previewUri != null) {
                        ivPreview.setImageURI(previewUri);
                    }
                }
            }
            if (!imageFileList.isEmpty()) {
                if (rlImagePreview != null) {
                    rlImagePreview.setVisibility(View.VISIBLE);
                }
                if (tvImageCount != null) {
                    tvImageCount.setVisibility(imageFileList.size() > 1 ? View.VISIBLE : View.GONE);
                    tvImageCount.setText(imageFileList.size() + "张");
                }
            }
        } catch (OutOfMemoryError e) {
            clearImages();
            Toast.makeText(this, "图片太大，处理失败", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void publishMoment() {
        String body = etBody.getText().toString().trim();
        if ((body == null || body.isEmpty()) && imageFileList.isEmpty()) {
            Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!imageFileList.isEmpty()) {
            uploadImagesThenPost(body);
        } else {
            createMoment(body, "");
        }
    }

    private void uploadImagesThenPost(String body) {
        final String bodyFinal = body;
        final java.util.ArrayList<String> urls = new java.util.ArrayList<>();
        uploadImageAt(0, bodyFinal, urls);
    }

    private void uploadImageAt(final int index, final String body, final java.util.ArrayList<String> urls) {
        if (index >= imageFileList.size()) {
            String imageUrl = com.im.oldchat.util.MomentImageUtil.encodeUrls(urls);
            createMoment(body, imageUrl);
            return;
        }
        java.io.File file = imageFileList.get(index);
        byte[] data = readFileBytes(file);
        if (data == null || data.length == 0) {
            Toast.makeText(MomentComposeActivity.this, "读取临时图片失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = imageNameList.get(index);
        String type = imageTypeList.get(index);
        HttpUtil.postMultipart("/media", data, name == null ? "moment.jpg" : name, type, token,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        try {
                            JSONObject obj = new JSONObject(response);
                            String url = obj.optString("url", "");
                            if (url == null || url.length() == 0) {
                                Toast.makeText(MomentComposeActivity.this, "上传失败", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            urls.add(url);
                            uploadImageAt(index + 1, body, urls);
                        } catch (Exception e) {
                            Toast.makeText(MomentComposeActivity.this, "上传失败", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(int code, String error) {
                        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                            return;
                        }
                        Toast.makeText(MomentComposeActivity.this, "上传失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private byte[] readFileBytes(java.io.File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        java.io.FileInputStream fis = null;
        java.io.ByteArrayOutputStream bos = null;
        try {
            fis = new java.io.FileInputStream(file);
            bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
            if (bos != null) {
                try { bos.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void createMoment(String body, String imageUrl) {
        try {
            JSONObject json = new JSONObject();
            json.put("body", body == null ? "" : body);
            json.put("image_url", imageUrl == null ? "" : imageUrl);
            HttpUtil.post("/moments", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(MomentComposeActivity.this, "发布成功", Toast.LENGTH_SHORT).show();
                    finish();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MomentComposeActivity.this, "发布失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发布失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String queryFileName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        return cursor.getString(idx);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return "moment.jpg";
    }

    private void clearImages() {
        for (java.io.File file : imageFileList) {
            if (file != null && file.exists()) {
                try { file.delete(); } catch (Exception ignored) {}
            }
        }
        imageFileList.clear();
        imageNameList.clear();
        imageTypeList.clear();
        previewUri = null;
        if (rlImagePreview != null) {
            rlImagePreview.setVisibility(View.GONE);
        }
        if (ivPreview != null) {
            ivPreview.setImageBitmap(null);
        }
        if (tvImageCount != null) {
            tvImageCount.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        clearImages();
        super.onDestroy();
    }
}
