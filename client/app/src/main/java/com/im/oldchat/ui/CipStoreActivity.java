package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.lua.LocalCipImporter;
import com.im.oldchat.lua.LuaAppStore;
import com.im.oldchat.lua.LuaMiniApp;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ThreadPoolManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class CipStoreActivity extends BaseActivity {
    private LinearLayout container;
    private TextView emptyView;
    private ProgressBar pbSync;
    private final List<CipStoreItem> storeItems = new ArrayList<CipStoreItem>();

    public static class CipStoreItem {
        public String id;
        public String name;
        public String description;
        public int version;
        public String authorName;
        public String iconUrl;
        public String cipFileUrl;
        public long fileSize;
        public String sha256;
        public List<String> permissions = new ArrayList<String>();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cip_store);

        container = findViewByIdCompat(R.id.cipStoreContainer);
        emptyView = findViewByIdCompat(R.id.tvCipStoreEmpty);
        pbSync = findViewByIdCompat(R.id.pbCipStoreSync);

        View btnBack = findViewByIdCompat(R.id.btnCipStoreBack);
        View btnRefresh = findViewByIdCompat(R.id.btnCipStoreRefresh);
        View btnUploadWeb = findViewByIdCompat(R.id.btnCipStoreUploadWeb);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadStoreData();
                }
            });
        }

        if (btnUploadWeb != null) {
            btnUploadWeb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        String uploadUrl = HttpUtil.getBaseOrigin() + "/cip-upload";
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uploadUrl));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(CipStoreActivity.this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        loadStoreData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!storeItems.isEmpty()) {
            renderList();
        }
    }

    private void loadStoreData() {
        if (pbSync != null) pbSync.setVisibility(View.VISIBLE);
        String token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
        HttpUtil.get("/cip/store", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String data) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (pbSync != null) pbSync.setVisibility(View.GONE);
                        parseAndRender(data);
                    }
                });
            }

            @Override
            public void onError(int code, final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (pbSync != null) pbSync.setVisibility(View.GONE);
                        Toast.makeText(CipStoreActivity.this, "加载商店失败：" + error, Toast.LENGTH_SHORT).show();
                        if (storeItems.isEmpty() && emptyView != null) {
                            emptyView.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private void parseAndRender(String json) {
        storeItems.clear();
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject itemObj = arr.getJSONObject(i);
                    CipStoreItem item = new CipStoreItem();
                    item.id = itemObj.optString("id", "");
                    item.name = itemObj.optString("name", "");
                    item.description = itemObj.optString("description", "");
                    item.version = itemObj.optInt("version", 1);
                    item.authorName = itemObj.optString("author_name", "官方精选");
                    item.iconUrl = itemObj.optString("icon_url", "");
                    item.cipFileUrl = itemObj.optString("cip_file_url", "");
                    item.fileSize = itemObj.optLong("file_size", 0);
                    item.sha256 = itemObj.optString("sha256", "");

                    String permsRaw = itemObj.optString("permissions", "[]");
                    try {
                        JSONArray pArr = new JSONArray(permsRaw);
                        for (int j = 0; j < pArr.length(); j++) {
                            item.permissions.add(pArr.getString(j));
                        }
                    } catch (Exception ignored) { }

                    if (item.id != null && item.id.length() > 0) {
                        storeItems.add(item);
                    }
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "解析数据异常", Toast.LENGTH_SHORT).show();
        }
        renderList();
    }

    private void renderList() {
        if (container == null) return;
        container.removeAllViews();

        if (storeItems.isEmpty()) {
            if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
            return;
        }

        if (emptyView != null) emptyView.setVisibility(View.GONE);

        List<LuaMiniApp> localApps = LuaAppStore.getApps(this);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < storeItems.size(); i++) {
            final CipStoreItem item = storeItems.get(i);
            View row = inflater.inflate(R.layout.item_cip_store_row, container, false);

            ImageView ivIcon = (ImageView) row.findViewById(R.id.ivCipStoreIcon);
            TextView tvName = (TextView) row.findViewById(R.id.tvCipStoreName);
            TextView tvVersion = (TextView) row.findViewById(R.id.tvCipStoreVersion);
            TextView tvAuthor = (TextView) row.findViewById(R.id.tvCipStoreAuthor);
            TextView tvDesc = (TextView) row.findViewById(R.id.tvCipStoreDescription);
            TextView tvSize = (TextView) row.findViewById(R.id.tvCipStoreSize);
            TextView tvPerms = (TextView) row.findViewById(R.id.tvCipStorePermissions);
            final Button btnAction = (Button) row.findViewById(R.id.btnCipStoreAction);

            tvName.setText(item.name);
            tvVersion.setText("v" + item.version);
            tvAuthor.setText("开发者: " + item.authorName);
            tvDesc.setText(item.description != null && item.description.length() > 0 ? item.description : "暂无功能描述");

            if (item.fileSize > 0) {
                long kb = item.fileSize / 1024;
                tvSize.setText(kb > 0 ? kb + " KB" : item.fileSize + " B");
            } else {
                tvSize.setText("");
            }

            if (!item.permissions.isEmpty()) {
                StringBuilder sb = new StringBuilder("权限: ");
                for (int p = 0; p < item.permissions.size(); p++) {
                    String perm = item.permissions.get(p);
                    if (p > 0) sb.append(", ");
                    if ("storage".equals(perm)) sb.append("本地存储");
                    else if ("network_external".equals(perm)) sb.append("外网访问");
                    else if ("network".equals(perm)) sb.append("内网访问");
                    else if ("camera".equals(perm)) sb.append("相机权限");
                    else sb.append(perm);
                }
                tvPerms.setText(sb.toString());
            } else {
                tvPerms.setText("权限: 纯本地运行");
            }

            if (item.iconUrl != null && item.iconUrl.length() > 0) {
                ImageLoader.load(ivIcon, item.iconUrl);
            }

            // Check local install state
            LuaMiniApp matchedLocal = null;
            if (localApps != null) {
                for (LuaMiniApp app : localApps) {
                    if (app != null && (item.id.equals(app.id) || ("local_" + item.id).equals(app.id))) {
                        matchedLocal = app;
                        break;
                    }
                }
            }

            final LuaMiniApp installedApp = matchedLocal;
            if (installedApp == null) {
                btnAction.setText("获取");
                btnAction.setEnabled(true);
            } else if (installedApp.version < item.version) {
                btnAction.setText("更新");
                btnAction.setEnabled(true);
            } else {
                btnAction.setText("打开");
                btnAction.setEnabled(true);
            }

            btnAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (installedApp != null && installedApp.version >= item.version) {
                        Intent intent = new Intent(CipStoreActivity.this, LuaMiniAppActivity.class);
                        intent.putExtra(LuaMiniAppActivity.EXTRA_APP_ID, installedApp.id);
                        startActivity(intent);
                    } else {
                        downloadAndInstallCip(item, btnAction);
                    }
                }
            });

            container.addView(row);
        }
    }

    private void downloadAndInstallCip(final CipStoreItem item, final Button btnAction) {
        btnAction.setEnabled(false);
        btnAction.setText("下载中…");

        ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override
            public void run() {
                String downloadUrl = item.cipFileUrl;
                if (!downloadUrl.startsWith("http://") && !downloadUrl.startsWith("https://")) {
                    String base = HttpUtil.getBaseOrigin();
                    downloadUrl = base + (downloadUrl.startsWith("/") ? downloadUrl : "/" + downloadUrl);
                }

                try {
                    URL url = new URL(downloadUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "OldChat-Android-Client");
                    String token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
                    if (token != null && token.length() > 0) {
                        conn.setRequestProperty("Authorization", "Bearer " + token);
                    }

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new Exception("下载失败 HTTP " + code);
                    }

                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        bos.write(buf, 0, len);
                    }
                    is.close();
                    conn.disconnect();

                    final byte[] archive = bos.toByteArray();
                    final LuaMiniApp app = LocalCipImporter.importPackageFromBytes(CipStoreActivity.this, archive);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(CipStoreActivity.this, "已安装小程序：" + app.name, Toast.LENGTH_SHORT).show();
                            renderList();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnAction.setEnabled(true);
                            btnAction.setText("重试");
                            Toast.makeText(CipStoreActivity.this, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }
}
