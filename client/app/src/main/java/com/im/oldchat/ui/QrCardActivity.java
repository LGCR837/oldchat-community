package com.im.oldchat.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.bili.BiliQrGenerator;
import com.im.oldchat.util.QrImageSaver;
import com.im.oldchat.util.QrPayloadUtil;

public class QrCardActivity extends BaseActivity {
    public static final String EXTRA_MODE = "qr_mode";
    public static final String EXTRA_ID = "qr_id";
    public static final String EXTRA_NAME = "qr_name";

    private static final int REQ_WRITE_STORAGE = 7301;

    private ImageView ivQrCard;
    private TextView tvQrCardTitle;
    private TextView tvQrCardDesc;
    private Bitmap qrBitmap;
    private String qrMode;
    private String qrId;
    private String qrName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_card);

        ivQrCard = findViewByIdCompat(R.id.ivQrCard);
        tvQrCardTitle = findViewByIdCompat(R.id.tvQrCardTitle);
        tvQrCardDesc = findViewByIdCompat(R.id.tvQrCardDesc);

        View btnBack = (View) findViewByIdCompat(R.id.btnQrCardBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnSave = (View) findViewByIdCompat(R.id.btnSaveQrCard);
        if (btnSave != null) {
            btnSave.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveQrToLocal();
                }
            });
        }

        readIntent();
        if (!buildQrBitmap()) {
            Toast.makeText(this, "\u65E0\u6CD5\u751F\u6210\u4E8C\u7EF4\u7801", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void readIntent() {
        Intent intent = getIntent();
        qrMode = intent == null ? "" : safe(intent.getStringExtra(EXTRA_MODE));
        qrId = intent == null ? "" : safe(intent.getStringExtra(EXTRA_ID));
        qrName = intent == null ? "" : safe(intent.getStringExtra(EXTRA_NAME));

        if ((qrId == null || qrId.length() == 0)
                && QrPayloadUtil.TYPE_USER.equals(qrMode)) {
            SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
            qrId = safe(prefs.getString("my_uid", ""));
        }
    }

    private boolean buildQrBitmap() {
        String payload = "";
        String title = "\u4E8C\u7EF4\u7801";
        String desc;
        if (QrPayloadUtil.TYPE_GROUP.equals(qrMode)) {
            payload = QrPayloadUtil.buildGroupPayload(qrId);
            title = "\u7FA4\u4E8C\u7EF4\u7801";
            String name = qrName.length() == 0 ? "\u7FA4\u804A" : qrName;
            desc = name + " (" + qrId + ")";
        } else if (QrPayloadUtil.TYPE_USER.equals(qrMode)) {
            payload = QrPayloadUtil.buildUserPayload(qrId);
            title = "\u6211\u7684\u540D\u7247\u4E8C\u7EF4\u7801";
            String name = qrName.length() == 0 ? qrId : qrName;
            desc = name + " (UID: " + qrId + ")";
        } else {
            return false;
        }
        if (payload.length() == 0) {
            return false;
        }

        if (tvQrCardTitle != null) {
            tvQrCardTitle.setText(title);
        }
        if (tvQrCardDesc != null) {
            tvQrCardDesc.setText(desc);
        }

        int size = Math.max(480, getResources().getDisplayMetrics().widthPixels - dp(88));
        try {
            qrBitmap = BiliQrGenerator.generate(payload, size);
            if (ivQrCard != null && qrBitmap != null) {
                ivQrCard.setImageBitmap(qrBitmap);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private void saveQrToLocal() {
        if (qrBitmap == null) {
            Toast.makeText(this, "\u4E8C\u7EF4\u7801\u5C1A\u672A\u751F\u6210", Toast.LENGTH_SHORT).show();
            return;
        }
        if (QrImageSaver.needWritePermissionForSave()) {
            if (Build.VERSION.SDK_INT >= 23
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_WRITE_STORAGE);
                return;
            }
        }
        doSave();
    }

    private void doSave() {
        try {
            String prefix = QrPayloadUtil.TYPE_GROUP.equals(qrMode) ? "group_qr" : "user_qr";
            String path = QrImageSaver.saveToPictures(this, qrBitmap, prefix);
            Toast.makeText(this, "\u5DF2\u4FDD\u5B58\u5230: " + path, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "\u4FDD\u5B58\u4E8C\u7EF4\u7801\u5931\u8D25", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doSave();
            } else {
                Toast.makeText(this, "\u672A\u6388\u6743\u5B58\u50A8\u6743\u9650", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
