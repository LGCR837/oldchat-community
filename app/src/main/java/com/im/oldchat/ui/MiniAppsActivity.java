package com.im.oldchat.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
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
import com.im.oldchat.lua.LuaAppSyncManager;
import com.im.oldchat.lua.LuaMiniApp;
import com.im.oldchat.util.ImageLoader;

import java.util.List;

public class MiniAppsActivity extends BaseActivity {
    private static final int REQUEST_IMPORT = 6411;
    private LinearLayout container;
    private TextView emptyView;
    private ProgressBar syncing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_mini_apps);
        container = findViewByIdCompat(R.id.miniAppsContainer);
        emptyView = findViewByIdCompat(R.id.tvMiniAppsEmpty);
        syncing = findViewByIdCompat(R.id.pbMiniAppsSync);
        View back = findViewByIdCompat(R.id.btnMiniAppsBack);
        View storeButton = findViewByIdCompat(R.id.btnMiniAppsStore);
        View importButton = findViewByIdCompat(R.id.btnMiniAppsImport);
        View developButton = findViewByIdCompat(R.id.btnMiniAppsDevelop);
        View docsButton = findViewByIdCompat(R.id.btnMiniAppsDocs);
        if (back != null) back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); }});
        if (storeButton != null) storeButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(MiniAppsActivity.this, CipStoreActivity.class)); }});
        if (importButton != null) importButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseZip(); }});
        if (developButton != null) developButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(MiniAppsActivity.this, CipDevelopmentModeActivity.class)); }});
        if (docsButton != null) docsButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openDocs(); }});
        render(LuaAppStore.getApps(this));
    }

    @Override protected void onResume() {
        super.onResume();
        render(LuaAppStore.getApps(this));
        if (syncing != null) syncing.setVisibility(View.VISIBLE);
        LuaAppSyncManager.sync(this, new LuaAppSyncManager.Callback() {
            @Override public void onComplete(List<LuaMiniApp> apps) {
                if (syncing != null) syncing.setVisibility(View.GONE);
                if (!isFinishing()) render(apps);
            }
        });
    }

    private void chooseZip() {
        Intent intent = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // .cip 经常被文件管理器标为 application/octet-stream，使用 */* 才不会被隐藏。
        intent.setType("*/*");
        try { startActivityForResult(intent, REQUEST_IMPORT); }
        catch (Exception e) { Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (syncing != null) syncing.setVisibility(View.VISIBLE);
        new AsyncTask<Void, Void, LuaMiniApp>() {
            String error;
            @Override protected LuaMiniApp doInBackground(Void... values) {
                try { return LocalCipImporter.importPackage(MiniAppsActivity.this, uri); }
                catch (Exception e) { error = e.getMessage(); return null; }
            }
            @Override protected void onPostExecute(LuaMiniApp app) {
                if (syncing != null) syncing.setVisibility(View.GONE);
                if (app == null) Toast.makeText(MiniAppsActivity.this, "导入失败：" + (error == null ? "未知错误" : error), Toast.LENGTH_LONG).show();
                else {
                    Toast.makeText(MiniAppsActivity.this, "已导入：" + app.name, Toast.LENGTH_SHORT).show();
                    render(LuaAppStore.getApps(MiniAppsActivity.this));
                }
            }
        }.execute();
    }

    private void render(List<LuaMiniApp> apps) {
        if (container == null) return;
        container.removeAllViews();
        int count = 0;
        if (apps != null) for (int i = 0; i < apps.size(); i++) {
            final LuaMiniApp app = apps.get(i);
            if (app == null || !app.enabled || app.id == null) continue;
            View row = LayoutInflater.from(this).inflate(R.layout.item_discover_lua_app, container, false);
            TextView name = (TextView) row.findViewById(R.id.tvLuaAppName);
            TextView description = (TextView) row.findViewById(R.id.tvLuaAppDescription);
            ImageView icon = (ImageView) row.findViewById(R.id.ivLuaAppIcon);
            name.setText(app.name);
            description.setText(app.description);
            description.setVisibility(app.description == null || app.description.length() == 0 ? View.GONE : View.VISIBLE);
            if (app.icon_url != null && app.icon_url.length() > 0) ImageLoader.load(icon, app.icon_url);
            View.OnClickListener clickListener = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent intent = new Intent(MiniAppsActivity.this, LuaMiniAppActivity.class);
                    intent.putExtra(LuaMiniAppActivity.EXTRA_APP_ID, app.id);
                    startActivity(intent);
                }
            };
            View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showLuaAppOptionsDialog(app);
                    return true;
                }
            };
            row.setOnClickListener(clickListener);
            row.setOnLongClickListener(longClickListener);
            if (row instanceof android.view.ViewGroup && ((android.view.ViewGroup) row).getChildCount() > 0) {
                View inner = ((android.view.ViewGroup) row).getChildAt(0);
                inner.setOnClickListener(clickListener);
                inner.setOnLongClickListener(longClickListener);
            }
            container.addView(row); count++;
        }
        if (emptyView != null) emptyView.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    private void showLuaAppOptionsDialog(final LuaMiniApp app) {
        if (app == null || app.id == null) return;
        final boolean pinned = isCipAppPinned(app.id);
        final boolean isLocal = app.id.startsWith("local_");

        java.util.List<String> options = new java.util.ArrayList<String>();
        options.add(pinned ? "从发现页磁贴移除" : "固定到发现页磁贴");
        if (isLocal) {
            options.add("删除本地小程序");
        }

        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(app.name != null ? app.name : app.id)
                .setItems(options.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            if (pinned) {
                                unpinCipAppTile(app.id);
                                Toast.makeText(MiniAppsActivity.this, "已从发现页磁贴移除", Toast.LENGTH_SHORT).show();
                            } else {
                                pinCipAppTile(app.id);
                                Toast.makeText(MiniAppsActivity.this, "已添加到发现页磁贴", Toast.LENGTH_SHORT).show();
                            }
                            render(LuaAppStore.getApps(MiniAppsActivity.this));
                        } else if (which == 1 && isLocal) {
                            confirmDelete(app);
                        }
                    }
                })
                .show();
    }

    private boolean isCipAppPinned(String appId) {
        if (appId == null) return false;
        android.content.SharedPreferences prefs = getSharedPreferences("discover_tile_layout", MODE_PRIVATE);
        String raw = prefs.getString("pinned_cip_app_ids", "");
        if (raw == null || raw.length() == 0) return false;
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (appId.equals(part.trim())) return true;
        }
        return false;
    }

    private void pinCipAppTile(String appId) {
        if (appId == null || isCipAppPinned(appId)) return;
        android.content.SharedPreferences prefs = getSharedPreferences("discover_tile_layout", MODE_PRIVATE);
        String raw = prefs.getString("pinned_cip_app_ids", "");
        String updated = (raw == null || raw.length() == 0) ? appId : (raw + "," + appId);
        prefs.edit().putString("pinned_cip_app_ids", updated).apply();
        int tileId = 0x70000000 | (Math.abs(appId.hashCode()) & 0x0FFFFFFF);
        String order = prefs.getString("tile_order", "");
        if (order != null && order.length() > 0 && !order.contains(String.valueOf(tileId))) {
            prefs.edit().putString("tile_order", order + "," + tileId).apply();
        }
    }

    private void unpinCipAppTile(String appId) {
        if (appId == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("discover_tile_layout", MODE_PRIVATE);
        String raw = prefs.getString("pinned_cip_app_ids", "");
        if (raw == null || raw.length() == 0) return;
        String[] parts = raw.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() > 0 && !trimmed.equals(appId)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(trimmed);
            }
        }
        prefs.edit().putString("pinned_cip_app_ids", sb.toString()).apply();
        int tileId = 0x70000000 | (Math.abs(appId.hashCode()) & 0x0FFFFFFF);
        String order = prefs.getString("tile_order", "");
        if (order != null && order.length() > 0) {
            String[] oParts = order.split(",");
            StringBuilder oSb = new StringBuilder();
            for (String p : oParts) {
                if (!p.trim().equals(String.valueOf(tileId)) && p.trim().length() > 0) {
                    if (oSb.length() > 0) oSb.append(",");
                    oSb.append(p.trim());
                }
            }
            prefs.edit().putString("tile_order", oSb.toString()).apply();
        }
    }

    private void confirmDelete(final LuaMiniApp app) {
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle("删除本地小程序")
                .setMessage("确定删除“" + app.name + "”及其脚本和资源吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        boolean ok = LuaAppStore.deleteLocalApp(MiniAppsActivity.this, app.id);
                        Toast.makeText(MiniAppsActivity.this, ok ? "已删除" : "删除失败", Toast.LENGTH_SHORT).show();
                        render(LuaAppStore.getApps(MiniAppsActivity.this));
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void openDocs() {
        String base = HttpUtil.BASE_URL == null ? "" : HttpUtil.BASE_URL;
        int index = base.indexOf("/v1");
        String origin = index > 0 ? base.substring(0, index) : base;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(origin + "/lua-cip.md"))); }
        catch (Exception e) { Toast.makeText(this, "无法打开开发文档", Toast.LENGTH_SHORT).show(); }
    }
}
