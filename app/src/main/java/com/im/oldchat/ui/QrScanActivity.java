package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.QrCodeDecoder;

public class QrScanActivity extends BaseActivity {
    public static final String EXTRA_QR_RESULT = "qr_result";

    private static final int REQ_PICK_IMAGE = 7401;
    private static final int REQ_CAPTURE_IMAGE = 7402;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        View btnBack = (View) findViewByIdCompat(R.id.btnQrScanBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnScanFromCamera = (View) findViewByIdCompat(R.id.btnScanFromCamera);
        if (btnScanFromCamera != null) {
            btnScanFromCamera.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startCameraScan();
                }
            });
        }

        View btnScanFromGallery = (View) findViewByIdCompat(R.id.btnScanFromGallery);
        if (btnScanFromGallery != null) {
            btnScanFromGallery.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startGalleryScan();
                }
            });
        }
    }

    private void startCameraScan() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "\u65E0\u53EF\u7528\u76F8\u673A", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivityForResult(intent, REQ_CAPTURE_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "\u65E0\u6CD5\u6253\u5F00\u76F8\u673A", Toast.LENGTH_SHORT).show();
        }
    }

    private void startGalleryScan() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "\u9009\u62E9\u4E8C\u7EF4\u7801\u56FE\u7247"), REQ_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "\u65E0\u6CD5\u6253\u5F00\u76F8\u518C", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        Bitmap bitmap = null;
        if (requestCode == REQ_CAPTURE_IMAGE) {
            bitmap = extractCameraBitmap(data);
        } else if (requestCode == REQ_PICK_IMAGE) {
            bitmap = extractGalleryBitmap(data);
        }
        if (bitmap == null) {
            Toast.makeText(this, "\u65E0\u6CD5\u8BFB\u53D6\u56FE\u7247", Toast.LENGTH_SHORT).show();
            return;
        }

        String text = QrCodeDecoder.decode(bitmap);
        if (text.length() == 0) {
            Toast.makeText(this, "\u672A\u8BC6\u522B\u5230\u4E8C\u7EF4\u7801", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent out = new Intent();
        out.putExtra(EXTRA_QR_RESULT, text);
        setResult(Activity.RESULT_OK, out);
        finish();
    }

    private Bitmap extractGalleryBitmap(Intent data) {
        if (data == null) {
            return null;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return null;
        }
        return QrCodeDecoder.decodeBitmapFromUri(getContentResolver(), uri, 1280);
    }

    private Bitmap extractCameraBitmap(Intent data) {
        if (data == null) {
            return null;
        }
        Object thumb = data.getParcelableExtra("data");
        if (thumb instanceof Bitmap) {
            return (Bitmap) thumb;
        }
        Uri uri = data.getData();
        if (uri != null) {
            return QrCodeDecoder.decodeBitmapFromUri(getContentResolver(), uri, 1280);
        }
        return null;
    }
}
