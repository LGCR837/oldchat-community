package com.im.oldchat.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;

import com.im.oldchat.service.ResourceUploadService;
import com.im.oldchat.util.NetworkStateManager;

abstract class ResourceSectionActivitySupport1 extends ResourceSectionActivitySupport2 {
    protected void pickResource() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(Intent.createChooser(intent, "选择资源"), REQ_PICK_RESOURCE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_RESOURCE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadResource(uri);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickResource();
            } else {
                Toast.makeText(this, "未授权读取存储", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    protected void uploadResource(final Uri uri) {
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ResourceUploadService.isUploading()) {
            if (!ResourceUploadService.isUploadingSection(sectionId)) {
                Toast.makeText(this, "正在上传其他资源", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        long size = querySize(uri);
        if (size > MAX_RESOURCE_BYTES) {
            Toast.makeText(this, "资源大小不能超过500MB", Toast.LENGTH_SHORT).show();
            return;
        }
        String fileName = queryDisplayName(uri);
        String contentType = getContentResolver().getType(uri);
        if (sectionId == null || sectionId.isEmpty()) {
            return;
        }
        uploading = true;
        updateUploadUi(fileName, 0, size <= 0, 0, size, 0);
        ResourceUploadService.startUpload(this, sectionId, uri, fileName, contentType, size);
    }

    protected long querySize(Uri uri) {
        if (uri == null) {
            return 0;
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                }
            }
        }
        return 0;
    }

    protected String queryDisplayName(Uri uri) {
        if (uri == null) {
            return "resource";
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    return name == null || name.isEmpty() ? "resource" : name;
                }
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                }
            }
        }
        return "resource";
    }
}
