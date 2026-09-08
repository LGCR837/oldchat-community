package com.im.oldchat.ui;

import android.content.Intent;
import android.support.v7.app.AlertDialog;
import com.im.oldchat.BaseActivity;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.ResourceSection;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

abstract class ResourceSectionsActivitySupport extends BaseActivity {
    protected static final String AUTH_PREFS = "auth";
    protected static final long DEFAULT_QUOTA_BYTES = 10L * 1024 * 1024 * 1024;
    protected static final int SECTION_PAGE_SIZE = 50;

    protected ListView lvSections;
    protected ProgressBar pbLoading;
    protected TextView tvQuota;
    protected ResourceSectionAdapter adapter;
    protected final List<ResourceSection> sections = new ArrayList<>();
    protected String token;
    protected String myUid;
    protected boolean loadingSections = false;

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
                    final String text = "空间: 已用 " + formatBytes(used) + " / " + formatBytes(limit) + "  |  剩余 " + formatBytes(remain);
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

    protected static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        double b = (double) bytes;
        double kb = b / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        if (gb >= 1.0) {
            return String.format(java.util.Locale.US, "%.2fGB", gb);
        }
        if (mb >= 1.0) {
            return String.format(java.util.Locale.US, "%.2fMB", mb);
        }
        if (kb >= 1.0) {
            return String.format(java.util.Locale.US, "%.2fKB", kb);
        }
        return String.format(java.util.Locale.US, "%dB", bytes);
    }

    protected void loadSections() {
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            showLoading(false);
            return;
        }
        if (loadingSections) {
            return;
        }
        loadingSections = true;
        showLoading(true);
        sections.clear();
        adapter.notifyDataSetChanged();
        loadSectionsPage(0);
    }

    protected void loadSectionsPage(final int offset) {
        if (token == null || token.isEmpty()) {
            loadingSections = false;
            showLoading(false);
            return;
        }
        String url = "/resources/sections?limit=" + SECTION_PAGE_SIZE + "&offset=" + offset;
        HttpUtil.get(url, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                int count = 0;
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("sections");
                    count = arr.length();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject sObj = arr.getJSONObject(i);
                        ResourceSection section = new ResourceSection();
                        section.id = sObj.optString("id");
                        section.name = sObj.optString("name");
                        section.owner_uid = sObj.optString("owner_uid");
                        section.owner_name = sObj.optString("owner_name");
                        section.owner_title = sObj.optString("owner_title");
                        section.owner_avatar = sObj.optString("owner_avatar");
                        section.created_at = sObj.optLong("created_at");
                        section.resource_count = sObj.optInt("resource_count", 0);
                        section.is_owner = sObj.optBoolean("is_owner", false);
                        sections.add(section);
                    }
                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    loadingSections = false;
                    showLoading(false);
                    Toast.makeText(ResourceSectionsActivitySupport.this, "加载失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (count >= SECTION_PAGE_SIZE) {
                    loadSectionsPage(offset + count);
                } else {
                    loadingSections = false;
                    showLoading(false);
                }
            }

            @Override
            public void onError(int code, String error) {
                loadingSections = false;
                showLoading(false);
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(ResourceSectionsActivitySupport.this, "加载失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected void openSection(ResourceSection section) {
        if (section == null || section.id == null || section.id.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, ResourceSectionActivity.class);
        intent.putExtra("section_id", section.id);
        intent.putExtra("section_name", section.name);
        intent.putExtra("section_owner_uid", section.owner_uid);
        intent.putExtra("section_owner_name", section.owner_name);
        startActivity(intent);
    }

    protected void showQuotaCreateSectionDialog() {
        final EditText input = new EditText(this);
        input.setHint("分区名称");
        final TextView tvInfo = new TextView(this);
        tvInfo.setTextColor(getResources().getColor(R.color.color_text_secondary));
        tvInfo.setTextSize(12);
        tvInfo.setText("空间: 加载中...\n分区: " + countMySections() + "/5");

        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        wrap.setPadding(pad, pad / 2, pad, pad / 2);
        wrap.addView(tvInfo);

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (getResources().getDisplayMetrics().density * 10);
        input.setLayoutParams(lp);
        wrap.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("创建资源分区")
                .setView(wrap)
                .setPositiveButton("创建", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(ResourceSectionsActivitySupport.this, "请输入分区名称", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (countMySections() >= 5) {
                            Toast.makeText(ResourceSectionsActivitySupport.this, "最多创建5个分区", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        createSection(name);
                        dialog.dismiss();
                    }
                });
            }
        });

        dialog.show();
        loadQuotaInto(tvInfo);
    }

    protected int countMySections() {
        if (sections == null || sections.isEmpty()) {
            return 0;
        }
        int cnt = 0;
        for (int i = 0; i < sections.size(); i++) {
            ResourceSection s = sections.get(i);
            if (s != null && s.is_owner) {
                cnt++;
            }
        }
        return cnt;
    }

    protected void loadQuotaInto(final TextView tv) {
        if (tv == null) {
            return;
        }
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            tv.setText("空间: 未知\n分区: " + countMySections() + "/5");
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
                    final String text = "空间: 已用 " + formatBytes(used) + " / " + formatBytes(limit) + "\n剩余: " + formatBytes(remain)
                            + "\n分区: " + countMySections() + "/5";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tv.setText(text);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tv.setText("空间: 未知\n分区: " + countMySections() + "/5");
                        }
                    });
                }
            }

            @Override
            public void onError(int code, String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText("空间: 未知\n分区: " + countMySections() + "/5");
                    }
                });
            }
        });
    }

    protected void createSection(String name) {
        try {
            JSONObject json = new JSONObject();
            json.put("name", name);
            HttpUtil.post("/resources/sections", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        ResourceSection section = new ResourceSection();
                        section.id = obj.optString("id");
                        section.name = obj.optString("name");
                        section.owner_uid = obj.optString("owner_uid");
                        section.owner_name = obj.optString("owner_name");
                        section.owner_title = obj.optString("owner_title");
                        section.owner_avatar = obj.optString("owner_avatar");
                        section.created_at = obj.optLong("created_at");
                        section.resource_count = obj.optInt("resource_count", 0);
                        section.is_owner = true;
                        sections.add(0, section);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(ResourceSectionsActivitySupport.this, "创建成功", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ResourceSectionsActivitySupport.this, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (code == 409) {
                        Toast.makeText(ResourceSectionsActivitySupport.this, "分区名已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (code == 400 && error != null && error.contains("limit")) {
                        Toast.makeText(ResourceSectionsActivitySupport.this, "最多创建5个分区", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(ResourceSectionsActivitySupport.this, "创建失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
        }
    }

    protected void showLoading(boolean loading) {
        if (pbLoading != null) {
            pbLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }
}
