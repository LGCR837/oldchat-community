package com.im.oldchat.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.content.FileProvider;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.lua.CipProjectRunner;
import com.im.oldchat.lua.CipWorkspace;

import java.io.File;
import java.util.List;

public class CipDeveloperActivity extends BaseActivity {
    @Override protected boolean useSquarePressFeedback() { return false; }
    private CipWorkspace workspace;
    private LinearLayout fileList;
    private EditText editor;
    private TextView lineNumbers;
    private TextView currentFileView;
    private TextView statusView;
    private String currentFile = "main.lua";
    private boolean loadingText;
    private boolean dirty;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cip_developer);
        workspace = new CipWorkspace(this);
        fileList = findViewByIdCompat(R.id.cipFileList);
        editor = findViewByIdCompat(R.id.etCipCode);
        lineNumbers = findViewByIdCompat(R.id.tvCipLineNumbers);
        currentFileView = findViewByIdCompat(R.id.tvCipCurrentFile);
        statusView = findViewByIdCompat(R.id.tvCipStatus);
        editor.setTypeface(Typeface.MONOSPACE);
        bindButton(R.id.btnCipBack, new View.OnClickListener() { @Override public void onClick(View v) { finishWithSave(); }});
        bindButton(R.id.btnCipNew, new View.OnClickListener() { @Override public void onClick(View v) { showNewFileDialog(); }});
        bindButton(R.id.btnCipSave, new View.OnClickListener() { @Override public void onClick(View v) { saveCurrent(true); }});
        bindButton(R.id.btnCipDelete, new View.OnClickListener() { @Override public void onClick(View v) { confirmDelete(); }});
        bindButton(R.id.btnCipTest, new View.OnClickListener() { @Override public void onClick(View v) { runTest(false); }});
        bindButton(R.id.btnCipPreview, new View.OnClickListener() { @Override public void onClick(View v) { runTest(true); }});
        bindButton(R.id.btnCipPackage, new View.OnClickListener() { @Override public void onClick(View v) { packageAndShare(); }});
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateLineNumbers(s);
                if (!loadingText) { dirty = true; setStatus("未保存"); }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        try {
            workspace.initialize();
            refreshFiles();
            openFile("main.lua");
        } catch (Exception e) { showError(e.getMessage()); }
    }

    private void bindButton(int id, View.OnClickListener listener) {
        View view = findViewByIdCompat(id); if (view != null) view.setOnClickListener(listener);
    }

    private void refreshFiles() throws Exception {
        fileList.removeAllViews();
        List<String> files = workspace.listFiles();
        for (int i = 0; i < files.size(); i++) {
            final String path = files.get(i);
            Button button = new Button(this);
            button.setText(path);
            button.setTextSize(11f);
            button.setMinHeight(dp(36));
            button.setMinimumHeight(dp(36));
            button.setPadding(dp(10), 0, dp(10), 0);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!saveCurrent(false)) return;
                    try { openFile(path); } catch (Exception e) { showError(e.getMessage()); }
                }
            });
            fileList.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        }
    }

    private void openFile(String path) throws Exception {
        String content = workspace.read(path);
        currentFile = path;
        currentFileView.setText(path);
        loadingText = true;
        editor.setText(content);
        editor.setSelection(0);
        loadingText = false;
        dirty = false;
        updateLineNumbers(content);
        setStatus("已打开");
    }

    private boolean saveCurrent(boolean toast) {
        if (!dirty) { if (toast) Toast.makeText(this, "没有修改", Toast.LENGTH_SHORT).show(); return true; }
        try {
            workspace.write(currentFile, editor.getText().toString());
            dirty = false; setStatus("已保存");
            if (toast) Toast.makeText(this, "已保存 " + currentFile, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) { showError(e.getMessage()); return false; }
    }

    private void showNewFileDialog() {
        final EditText input = new EditText(this);
        input.setHint("assets/data.json");
        input.setSingleLine(true);
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle("新建资源文件")
                .setMessage("文件必须位于 assets/，支持 json、txt、md")
                .setView(input)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        String path = input.getText().toString().trim();
                        try {
                            if (!CipWorkspace.isAllowed(path) || "manifest.json".equals(path) || "main.lua".equals(path))
                                throw new Exception("文件路径或扩展名不合法");
                            workspace.write(path, defaultContent(path));
                            refreshFiles(); openFile(path);
                        } catch (Exception e) { showError(e.getMessage()); }
                    }
                }).setNegativeButton("取消", null).show();
    }

    private String defaultContent(String path) {
        if (path.endsWith(".json")) return "{\n  \"key\": \"value\"\n}\n";
        if (path.endsWith(".md")) return "# CIP 资源文档\n";
        return "";
    }

    private void confirmDelete() {
        if ("manifest.json".equals(currentFile) || "main.lua".equals(currentFile)) {
            showError("不能删除入口文件"); return;
        }
        new android.support.v7.app.AlertDialog.Builder(this).setTitle("删除文件")
                .setMessage("确定删除 " + currentFile + "？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        try { workspace.delete(currentFile); refreshFiles(); openFile("main.lua"); }
                        catch (Exception e) { showError(e.getMessage()); }
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void runTest(final boolean preview) {
        if (!saveCurrent(false)) return;
        setStatus(preview ? "准备预览…" : "测试中…");
        new AsyncTask<Void, Void, CipProjectRunner.Result>() {
            @Override protected CipProjectRunner.Result doInBackground(Void... values) {
                return preview ? CipProjectRunner.installPreview(CipDeveloperActivity.this, workspace)
                        : CipProjectRunner.validate(workspace);
            }
            @Override protected void onPostExecute(CipProjectRunner.Result result) {
                if (!result.success) { setStatus("测试失败"); showError(result.message); return; }
                setStatus("测试通过");
                Toast.makeText(CipDeveloperActivity.this, result.message, Toast.LENGTH_SHORT).show();
                if (preview) {
                    Intent intent = new Intent(CipDeveloperActivity.this, LuaMiniAppActivity.class);
                    intent.putExtra(LuaMiniAppActivity.EXTRA_APP_ID, CipProjectRunner.PREVIEW_ID);
                    startActivity(intent);
                }
            }
        }.execute();
    }

    private void packageAndShare() {
        if (!saveCurrent(false)) return;
        CipProjectRunner.Result test = CipProjectRunner.validate(workspace);
        if (!test.success) { showError("打包前测试失败：" + test.message); return; }
        try {
            File file = workspace.buildCip(this);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/zip");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "保存或分享 ZIP 包"));
            setStatus("ZIP 已生成");
        } catch (Exception e) { showError(e.getMessage()); }
    }

    private void updateLineNumbers(CharSequence text) {
        int lines = 1;
        if (text != null) for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        StringBuilder out = new StringBuilder(lines * 3);
        for (int i = 1; i <= lines; i++) out.append(i).append('\n');
        lineNumbers.setText(out.toString());
    }

    private void finishWithSave() { if (saveCurrent(false)) finish(); }
    @Override public void onBackPressed() { finishWithSave(); }
    private void setStatus(String text) { if (statusView != null) statusView.setText(text == null ? "" : text); }
    private void showError(String error) { setStatus("错误"); Toast.makeText(this, error == null ? "操作失败" : error, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }
}
