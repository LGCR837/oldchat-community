package com.im.oldchat.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.ResourceItem;
import com.im.oldchat.service.ResourceUploadService;

import org.json.JSONObject;

abstract class ResourceSectionActivitySupport3 extends ResourceSectionActivitySupport4 {
    protected final BroadcastReceiver uploadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            String targetSection = intent.getStringExtra(ResourceUploadService.EXTRA_SECTION_ID);
            if (sectionId == null || targetSection == null || !sectionId.equals(targetSection)) {
                return;
            }
            if (ResourceUploadService.ACTION_PROGRESS.equals(action)) {
                String fileName = intent.getStringExtra(ResourceUploadService.EXTRA_FILE_NAME);
                int progress = intent.getIntExtra(ResourceUploadService.EXTRA_PROGRESS, 0);
                boolean indeterminate = intent.getBooleanExtra(ResourceUploadService.EXTRA_INDETERMINATE, false);
                long written = intent.getLongExtra(ResourceUploadService.EXTRA_UPLOADED_BYTES, 0L);
                long total = intent.getLongExtra(ResourceUploadService.EXTRA_TOTAL_BYTES, 0L);
                long speedBps = intent.getLongExtra(ResourceUploadService.EXTRA_SPEED_BPS, 0L);
                uploading = true;
                updateUploadUi(fileName, progress, indeterminate, written, total, speedBps);
            } else if (ResourceUploadService.ACTION_DONE.equals(action)) {
                handleUploadSuccess(intent.getStringExtra(ResourceUploadService.EXTRA_RESPONSE));
            } else if (ResourceUploadService.ACTION_ERROR.equals(action)) {
                int code = intent.getIntExtra(ResourceUploadService.EXTRA_ERROR_CODE, -1);
                String error = intent.getStringExtra(ResourceUploadService.EXTRA_ERROR_MESSAGE);
                handleUploadError(code, error);
            }
        }
    };

    protected void syncUploadStateFromService() {
        ResourceUploadService.UploadState state = ResourceUploadService.getCurrentUpload();
        if (state != null && state.running && sectionId != null && sectionId.equals(state.sectionId)) {
            uploading = true;
            updateUploadUi(state.fileName, state.progress, state.indeterminate,
                    state.uploadedBytes, state.totalBytes, state.speedBps);
        } else {
            uploading = false;
            hideUploadUi();
        }
    }

    protected void updateUploadUi(String fileName, int progress, boolean indeterminate,
                                  long written, long total, long speedBps) {
        if (uploadProgressLayout == null) {
            return;
        }
        uploadProgressLayout.setVisibility(android.view.View.VISIBLE);
        if (pbUpload != null) {
            pbUpload.setIndeterminate(indeterminate);
            if (!indeterminate) {
                pbUpload.setProgress(progress);
            }
        }
        if (tvUploadStatus != null) {
            String safeName = (fileName == null || fileName.length() == 0) ? "资源" : fileName;
            if (indeterminate) {
                tvUploadStatus.setText("后台上传中：" + safeName + " " + formatSpeed(speedBps));
            } else {
                String sizeText = formatSize(written, total);
                String speedText = formatSpeed(speedBps);
                tvUploadStatus.setText("后台上传中：" + safeName + " " + progress + "% " + sizeText + " " + speedText);
            }
        }
    }

    protected void hideUploadUi() {
        if (uploadProgressLayout != null) {
            uploadProgressLayout.setVisibility(android.view.View.GONE);
        }
    }

    protected void handleUploadSuccess(String response) {
        uploading = false;
        hideUploadUi();
        if (response == null) {
            Toast.makeText(this, "上传失败", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject obj = new JSONObject(response);
            ResourceItem item = new ResourceItem();
            item.id = obj.optString("id");
            item.section_id = obj.optString("section_id");
            item.name = obj.optString("name");
            item.url = obj.optString("url");
            item.size_bytes = obj.optLong("size_bytes", 0);
            item.uploader_uid = obj.optString("uploader_uid");
            item.uploader_name = obj.optString("uploader_name");
            item.uploader_title = obj.optString("uploader_title");
            item.uploader_avatar = obj.optString("uploader_avatar");
            item.created_at = obj.optLong("created_at");
            item.likes = obj.optInt("likes", 0);
            item.comments = obj.optInt("comments", 0);
            item.liked = obj.optBoolean("liked", false);
            item.can_delete = canDeleteItem(item);
            items.add(0, item);
            adapter.notifyDataSetChanged();
            Toast.makeText(this, "上传成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "上传失败", Toast.LENGTH_SHORT).show();
        }
        updateLoadMoreButton();
        loadQuota();
    }

    protected void handleUploadError(int code, String error) {
        uploading = false;
        hideUploadUi();
        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
            return;
        }
        if (code == -1 && "network_unavailable".equals(error)) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code == -2) {
            Toast.makeText(this, "已有上传任务", Toast.LENGTH_SHORT).show();
            return;
        }
        if (error != null && error.contains("quota_exceeded")) {
            Toast.makeText(this, "空间不足：单个账号资源总容量上限为10GB", Toast.LENGTH_LONG).show();
            loadQuota();
            return;
        }
        Toast.makeText(this, "上传失败: " + code, Toast.LENGTH_SHORT).show();
        loadQuota();
    }

    protected abstract void loadQuota();

    protected abstract void updateLoadMoreButton();
}
