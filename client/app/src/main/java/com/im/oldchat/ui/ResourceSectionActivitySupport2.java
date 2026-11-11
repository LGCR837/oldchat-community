package com.im.oldchat.ui;

import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.ResourceItem;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

abstract class ResourceSectionActivitySupport2 extends ResourceSectionActivitySupport3 {
    protected void performSearch() {
        if (etSearch != null) {
            currentQuery = etSearch.getText().toString().trim();
        } else {
            currentQuery = "";
        }
        hasMore = true;
        currentOffset = 0;
        loadItems(false);
    }

    @Override
    protected void loadQuota() {
        if (tvQuota == null) {
            return;
        }
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            tvQuota.setVisibility(View.GONE);
            return;
        }
        HttpUtil.get("/me/resources/quota", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    long limit = obj.optLong("limit_bytes", 0);
                    long used = obj.optLong("used_bytes", 0);
                    long remain = obj.optLong("remaining_bytes", 0);
                    if (limit <= 0) {
                        limit = DEFAULT_QUOTA_BYTES;
                        if (used > 0 && used < limit) {
                            remain = limit - used;
                        } else {
                            remain = limit;
                        }
                    }
                    final String text = "空间: 已用 " + formatBytesQuota(used) + " / " + formatBytesQuota(limit)
                            + "  |  剩余 " + formatBytesQuota(remain);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvQuota.setText(text);
                            tvQuota.setVisibility(View.VISIBLE);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvQuota.setVisibility(View.GONE);
                        }
                    });
                }
            }

            @Override
            public void onError(int code, String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvQuota.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    protected void setupLoadMoreFooter() {
        loadMoreFooter = getLayoutInflater().inflate(com.im.oldchat.R.layout.list_load_more, lvResources, false);
        btnLoadMore = loadMoreFooter.findViewById(com.im.oldchat.R.id.btnLoadMore);
        btnLoadMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isLoadingMore && hasMore) {
                    loadMoreItems();
                }
            }
        });
        if (lvResources.getFooterViewsCount() == 0) {
            lvResources.addFooterView(loadMoreFooter);
        }
        updateLoadMoreButton();
    }

    protected void loadItems(final boolean append) {
        if (token == null || token.isEmpty() || sectionId == null || sectionId.isEmpty()) {
            showLoading(false);
            return;
        }
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            showLoading(false);
            return;
        }
        if (append) {
            isLoadingMore = true;
        } else {
            showLoading(true);
        }
        String encodedSection = sectionId;
        try {
            encodedSection = URLEncoder.encode(sectionId, "UTF-8");
        } catch (Exception e) {
            encodedSection = sectionId;
        }
        String url;
        if (currentQuery != null && !currentQuery.isEmpty()) {
            String encodedQuery;
            try {
                encodedQuery = URLEncoder.encode(currentQuery, "UTF-8");
            } catch (Exception e) {
                encodedQuery = currentQuery;
            }
            url = "/resources/search?section_id=" + encodedSection + "&q=" + encodedQuery
                    + "&limit=" + PAGE_SIZE + "&offset=" + currentOffset;
        } else {
            url = "/resources/items?section_id=" + encodedSection + "&limit=" + PAGE_SIZE + "&offset=" + currentOffset;
        }
        HttpUtil.get(url, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("items");
                    if (!append) {
                        items.clear();
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject itemObj = arr.getJSONObject(i);
                        ResourceItem item = new ResourceItem();
                        item.id = itemObj.optString("id");
                        item.section_id = itemObj.optString("section_id");
                        item.name = itemObj.optString("name");
                        item.url = itemObj.optString("url");
                        item.size_bytes = itemObj.optLong("size_bytes", 0);
                        item.uploader_uid = itemObj.optString("uploader_uid");
                        item.uploader_name = itemObj.optString("uploader_name");
                        item.uploader_title = itemObj.optString("uploader_title");
                        item.uploader_avatar = itemObj.optString("uploader_avatar");
                        item.created_at = itemObj.optLong("created_at");
                        item.likes = itemObj.optInt("likes", 0);
                        item.comments = itemObj.optInt("comments", 0);
                        item.liked = itemObj.optBoolean("liked", false);
                        item.can_delete = canDeleteItem(item);
                        items.add(item);
                    }
                    hasMore = arr.length() >= PAGE_SIZE;
                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    Toast.makeText(ResourceSectionActivitySupport2.this, "加载资源失败", Toast.LENGTH_SHORT).show();
                }
                isLoadingMore = false;
                showLoading(false);
                updateLoadMoreButton();
            }

            @Override
            public void onError(int code, String error) {
                isLoadingMore = false;
                showLoading(false);
                updateLoadMoreButton();
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(ResourceSectionActivitySupport2.this, "加载资源失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected void loadMoreItems() {
        if (isLoadingMore || !hasMore) {
            return;
        }
        currentOffset = items.size();
        updateLoadMoreButton();
        loadItems(true);
    }

    @Override
    protected void updateLoadMoreButton() {
        if (lvResources == null || btnLoadMore == null) {
            return;
        }
        boolean hasData = items != null && !items.isEmpty();
        if (loadMoreFooter != null && lvResources.getFooterViewsCount() == 0) {
            lvResources.addFooterView(loadMoreFooter);
        }
        if (!hasData) {
            if (loadMoreFooter != null) {
                loadMoreFooter.setVisibility(View.GONE);
            }
            return;
        }
        if (loadMoreFooter != null) {
            loadMoreFooter.setVisibility(View.VISIBLE);
        }
        if (isLoadingMore) {
            btnLoadMore.setEnabled(false);
            ((TextView) btnLoadMore).setText("加载中...");
        } else if (!hasMore) {
            btnLoadMore.setEnabled(false);
            ((TextView) btnLoadMore).setText("没有更多了");
        } else {
            btnLoadMore.setEnabled(true);
            ((TextView) btnLoadMore).setText("加载更多");
        }
    }
}
