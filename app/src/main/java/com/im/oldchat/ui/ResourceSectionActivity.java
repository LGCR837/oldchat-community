package com.im.oldchat.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.ResourceItem;
import com.im.oldchat.service.ResourceUploadService;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONObject;

public class ResourceSectionActivity extends ResourceSectionActivitySupport0 {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resource_section);

        lvResources = findViewByIdCompat(R.id.lvResources);
        pbLoading = findViewByIdCompat(R.id.pbResourcesLoading);
        tvQuota = findViewByIdCompat(R.id.tvResourceQuota);
        etSearch = findViewByIdCompat(R.id.etResourceSearch);
        btnSearch = findViewByIdCompat(R.id.btnResourceSearch);
        uploadProgressLayout = findViewByIdCompat(R.id.layoutResourceUploadProgress);
        pbUpload = findViewByIdCompat(R.id.pbResourceUpload);
        tvUploadStatus = findViewByIdCompat(R.id.tvResourceUploadStatus);

        sectionId = getIntent().getStringExtra("section_id");
        sectionName = getIntent().getStringExtra("section_name");
        sectionOwnerUid = getIntent().getStringExtra("section_owner_uid");

        TextView title = findViewByIdCompat(R.id.tvResourceSectionTitle);
        if (title != null && sectionName != null) {
            title.setText(sectionName);
        }

        View btnBack = findViewByIdCompat(R.id.btnResourceSectionBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnUpload = findViewByIdCompat(R.id.btnResourceUpload);
        if (btnUpload != null) {
            btnUpload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (uploading || ResourceUploadService.isUploading()) {
                        if (ResourceUploadService.isUploadingSection(sectionId)) {
                            syncUploadStateFromService();
                        } else {
                            Toast.makeText(ResourceSectionActivity.this, "正在上传其他资源", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    pickResource();
                }
            });
        }

        if (btnSearch != null) {
            btnSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performSearch();
                }
            });
        }

        setupLoadMoreFooter();
        adapter = new ResourceItemAdapter(this, items, this);
        lvResources.setAdapter(adapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ResourceUploadService.ACTION_PROGRESS);
        filter.addAction(ResourceUploadService.ACTION_DONE);
        filter.addAction(ResourceUploadService.ACTION_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(uploadReceiver, filter);
        syncUploadStateFromService();
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(uploadReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        myUid = prefs.getString("my_uid", "");
        currentQuery = "";
        if (etSearch != null) {
            etSearch.setText("");
        }
        loadQuota();
        currentOffset = 0;
        hasMore = true;
        items.clear();
        adapter.notifyDataSetChanged();
        updateLoadMoreButton();
        loadItems(false);
    }

    @Override
    public void onLike(final ResourceItem item) {
        if (item == null || item.id == null || item.id.isEmpty()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            String path = item.liked ? "/resources/unlike" : "/resources/like";
            HttpUtil.post(path, json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        item.liked = obj.optBoolean("liked", item.liked);
                        item.likes = obj.optInt("likes", item.likes);
                    } catch (Exception e) {
                    }
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ResourceSectionActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onComment(ResourceItem item) {
        if (item == null || item.id == null || item.id.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, ResourceCommentsActivity.class);
        intent.putExtra("item_id", item.id);
        intent.putExtra("section_owner_uid", sectionOwnerUid);
        startActivity(intent);
    }

    @Override
    public void onReport(ResourceItem item) {
        if (item == null || item.id == null || item.id.isEmpty()) {
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String myUid = prefs.getString("uid", "");
        if (myUid == null || myUid.length() == 0) {
            myUid = prefs.getString("user_uid", "");
        }
        String uploaderUid = item.uploader_uid != null ? item.uploader_uid.trim() : "";
        if (uploaderUid.length() == 0) {
            Toast.makeText(this, "无法举报：缺少上传者信息", Toast.LENGTH_SHORT).show();
            return;
        }
        if (myUid.length() > 0 && myUid.equalsIgnoreCase(uploaderUid)) {
            Toast.makeText(this, "不能举报自己上传的资源", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder evidence = new StringBuilder();
        evidence.append("[资源广场举报]\n");
        evidence.append("资源ID: ").append(item.id).append("\n");
        evidence.append("资源名称: ").append(item.name != null ? item.name : "").append("\n");
        if (item.url != null && !item.url.isEmpty()) {
            evidence.append("资源链接: ").append(item.url).append("\n");
        }
        if (item.uploader_name != null && !item.uploader_name.isEmpty()) {
            evidence.append("上传者: ").append(item.uploader_name).append("\n");
        }
        if (sectionId != null && !sectionId.isEmpty()) {
            evidence.append("所属分区: ").append(sectionId);
        }

        ReportActivity.start(this, uploaderUid, "", item.uploader_name, item.uploader_avatar,
                evidence.toString(), "来源：资源广场 (" + (item.name != null ? item.name : "") + ")");
    }

    @Override
    public void onFavorite(ResourceItem item) {
        if (item == null) {
            return;
        }
        FavoriteHelper.addResourceFavorite(this, item);
    }

    @Override
    public void onDelete(final ResourceItem item) {
        if (item == null || item.id == null || item.id.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除资源")
                .setMessage("确定删除该资源吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteItem(item);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    protected void showReportDialog(final ResourceItem item) {
        final EditText input = new EditText(this);
        input.setHint("请输入举报理由");
        input.setMinLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});

        FrameLayout container = new FrameLayout(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        container.setPadding(pad, pad / 2, pad, pad / 2);
        container.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("举报资源")
                .setView(container)
                .setPositiveButton("提交", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String reason = input.getText().toString().trim();
                        if (reason.length() == 0) {
                            Toast.makeText(ResourceSectionActivity.this, "请输入举报理由", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        submitResourceReport(item, reason);
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
    }

    protected void submitResourceReport(final ResourceItem item, String reason) {
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "未登录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            json.put("reason", reason);
            HttpUtil.post("/resources/report", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(ResourceSectionActivity.this, "已提交举报", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ResourceSectionActivity.this, "举报失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "举报失败", Toast.LENGTH_SHORT).show();
        }
    }

    protected void deleteItem(final ResourceItem item) {
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            HttpUtil.post("/resources/items/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    items.remove(item);
                    adapter.notifyDataSetChanged();
                    updateLoadMoreButton();
                    Toast.makeText(ResourceSectionActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    loadQuota();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ResourceSectionActivity.this, "删除失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }
}
