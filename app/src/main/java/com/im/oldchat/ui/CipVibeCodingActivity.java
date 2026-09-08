package com.im.oldchat.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.content.FileProvider;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.lua.CipProjectRunner;
import com.im.oldchat.lua.CipVibeAgent;
import com.im.oldchat.lua.CipVibeSessionManager;
import com.im.oldchat.lua.CipWorkspace;
import com.im.oldchat.lua.VibeConfigStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class CipVibeCodingActivity extends BaseActivity implements CipVibeAgent.Events {
    private CipWorkspace workspace;
    private CipVibeSessionManager session;
    private EditText input;
    private TextView transcript;
    private TextView thinking;
    private TextView tasks;
    private TextView status;
    private TextView toolStatus;
    private TextView errorView;
    private TextView sendButton;
    private ScrollView scroll;
    private boolean thinkingExpanded;
    private boolean renderScheduled;
    private String lastRenderedTranscript = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cip_vibe);
        session = CipVibeSessionManager.get();
        input = findViewByIdCompat(R.id.etVibeInput); transcript = findViewByIdCompat(R.id.tvVibeTranscript);
        thinking = findViewByIdCompat(R.id.tvVibeThinking); tasks = findViewByIdCompat(R.id.tvVibeTasks);
        status = findViewByIdCompat(R.id.tvVibeStatus); toolStatus = findViewByIdCompat(R.id.tvVibeToolStatus);
        errorView = findViewByIdCompat(R.id.tvVibeError); sendButton = findViewByIdCompat(R.id.btnVibeSend); scroll = findViewByIdCompat(R.id.scrollVibe);
        transcript.setTypeface(Typeface.MONOSPACE);
        bind(R.id.btnVibeBack, new View.OnClickListener() { @Override public void onClick(View v) { finish(); }});
        bind(R.id.btnVibeConfig, new View.OnClickListener() { @Override public void onClick(View v) { showConfigDialog(); }});
        bind(R.id.btnVibeSend, new View.OnClickListener() { @Override public void onClick(View v) {
            if (session != null && session.isRunning()) stopRequest(); else sendRequest();
        }});
        bind(R.id.btnVibeTest, new View.OnClickListener() { @Override public void onClick(View v) { runProject(false); }});
        bind(R.id.btnVibePreview, new View.OnClickListener() { @Override public void onClick(View v) { runProject(true); }});
        bind(R.id.btnVibeExport, new View.OnClickListener() { @Override public void onClick(View v) { exportZip(); }});
        bind(R.id.btnVibeClear, new View.OnClickListener() { @Override public void onClick(View v) { clearChat(); }});
        bind(R.id.btnVibeThinkingToggle, new View.OnClickListener() { @Override public void onClick(View v) { thinkingExpanded = !thinkingExpanded; renderSession(); }});
        bind(R.id.tvVibeError, new View.OnClickListener() { @Override public void onClick(View v) { copyLastError(); }});
        if (!initializeAgent()) showConfigDialog();
        renderSession();
    }

    private void bind(int id, View.OnClickListener listener) { View view = findViewByIdCompat(id); if (view != null) view.setOnClickListener(listener); }

    private boolean initializeAgent() {
        VibeConfigStore.Config config = VibeConfigStore.load(this); if (!config.isValid()) return false;
        if (!session.initialize(this)) return false;
        workspace = session.getWorkspace();
        setStatus(config.managed ? "Oldchat 免费额度 · 查询中…" : session.getStatus() + " · " + config.modelId);
        if (config.managed) refreshQuota(config);
        return true;
    }

    private void showConfigDialog() {
        VibeConfigStore.Config current = VibeConfigStore.load(this);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); int pad = dp(18); form.setPadding(pad, 0, pad, 0);
        final CheckBox managed = new CheckBox(this); managed.setText("使用 Oldchat 免费额度（每个账号 1 亿 token）"); managed.setChecked(current.managed); form.addView(managed);
        final EditText base = new EditText(this); base.setHint("自定义 Base URL，例如 https://api.example.com/v1"); base.setSingleLine(true); base.setText(current.managed ? "" : current.baseUrl);
        final EditText key = new EditText(this); key.setHint("自定义 API Key"); key.setSingleLine(true); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); key.setText(current.managed ? "" : current.apiKey);
        final EditText model = new EditText(this); model.setHint("自定义 Model ID"); model.setSingleLine(true); model.setText(current.managed ? "" : current.modelId);
        form.addView(base); form.addView(key); form.addView(model);
        View.OnClickListener toggle = new View.OnClickListener() { @Override public void onClick(View v) { boolean enabled = !managed.isChecked(); base.setEnabled(enabled); key.setEnabled(enabled); model.setEnabled(enabled); }}; managed.setOnClickListener(toggle); toggle.onClick(managed);
        new android.support.v7.app.AlertDialog.Builder(this).setTitle("VibeCoding 模型配置")
                .setMessage("推荐使用 Oldchat 免费额度，无需填写 Key；也可以切换到自定义 OpenAI 兼容接口。")
                .setView(form).setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (managed.isChecked()) {
                            if (VibeConfigStore.useOldchat(CipVibeCodingActivity.this)) { session.reconfigure(CipVibeCodingActivity.this); initializeAgent(); Toast.makeText(CipVibeCodingActivity.this, "已使用 Oldchat 免费额度", Toast.LENGTH_SHORT).show(); }
                            return;
                        }
                        String baseValue = base.getText().toString().trim(); String keyValue = key.getText().toString().trim(); String modelValue = model.getText().toString().trim();
                        if (!baseValue.startsWith("http") || keyValue.length() == 0 || modelValue.length() == 0) { Toast.makeText(CipVibeCodingActivity.this, "请完整填写 Base URL、Key 和 Model ID", Toast.LENGTH_LONG).show(); return; }
                        if (VibeConfigStore.save(CipVibeCodingActivity.this, baseValue, keyValue, modelValue)) { session.reconfigure(CipVibeCodingActivity.this); initializeAgent(); Toast.makeText(CipVibeCodingActivity.this, "自定义配置已保存", Toast.LENGTH_SHORT).show(); }
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void copyLastError() {
        String error = session == null ? "" : session.getLastError();
        if (error.length() == 0) return;
        try {
            android.text.ClipboardManager clipboard = (android.text.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setText(error);
            Toast.makeText(this, "错误信息已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show(); }
    }

    private void stopRequest() {
        if (session != null && session.stop()) {
            setStatus("正在停止…");
            if (sendButton != null) { sendButton.setText("停止中"); sendButton.setEnabled(false); }
        }
    }

    private void sendRequest() {
        String text = input.getText().toString().trim(); if (text.length() == 0) return;
        if (!initializeAgent()) { showConfigDialog(); return; }
        if (session.isRunning()) { Toast.makeText(this, "AI 正在后台处理上一项任务", Toast.LENGTH_SHORT).show(); return; }
        input.setText(""); thinkingExpanded = false;
        if (!session.send(this, text)) { Toast.makeText(this, "无法启动 AI 任务", Toast.LENGTH_SHORT).show(); return; }
        renderSession();
    }

    @Override public void onContent(final String delta) { scheduleRender(); }
    @Override public void onThinking(final String delta) { scheduleRender(); }
    @Override public void onTool(final String name, final String detail) { scheduleRender(); }
    @Override public void onTasks(final String text) { scheduleRender(); }
    @Override public void onAsk(final String message) { scheduleRender(); }
    @Override public void onStatus(final String text) { runOnUiThread(new Runnable() { @Override public void run() { setStatus(text); }}); }
    @Override public void onError(final String error) { runOnUiThread(new Runnable() { @Override public void run() {
        scheduleRender(); Toast.makeText(CipVibeCodingActivity.this, "执行失败，点按错误卡片可复制", Toast.LENGTH_LONG).show();
    }}); }
    @Override public void onFinished() { runOnUiThread(new Runnable() { @Override public void run() { renderSession(); VibeConfigStore.Config config = VibeConfigStore.load(CipVibeCodingActivity.this); if (config.managed) refreshQuota(config); }}); }

    private void scheduleRender() {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (renderScheduled) return; renderScheduled = true;
            transcript.postDelayed(new Runnable() { @Override public void run() { renderScheduled = false; renderSession(); }}, 400L);
        }});
    }

    private void renderSession() {
        if (session == null) return;
        String text = session.getTranscript();
        if (text.length() == 0) text = "VibeCoding 已连接到 CIP 工作区。描述你想制作的应用，AI 会先列任务，再读取、编辑和测试项目。\n";
        if (text.length() > 30000) text = "…较早记录已隐藏，完整记录仍已保存…\n" + text.substring(text.length() - 30000);
        boolean keepAtBottom = isNearBottom();
        boolean transcriptChanged = !text.equals(lastRenderedTranscript);
        if (transcriptChanged) {
            transcript.setText(text);
            lastRenderedTranscript = text;
        }
        // 思考内容现在按真实发生顺序插入对话时间线；旧的底部聚合框会造成位置错误和布局跳动。
        View toggle = findViewByIdCompat(R.id.btnVibeThinkingToggle);
        thinking.setVisibility(View.GONE); if (toggle != null) toggle.setVisibility(View.GONE);
        String taskText = session.getTasks(); tasks.setText(taskText); tasks.setVisibility(taskText.length() == 0 ? View.GONE : View.VISIBLE);
        long toolCount = session.getToolCount(); String toolTimeline = session.getToolTimeline();
        if (toolStatus != null) {
            toolStatus.setText(toolCount > 0 ? "工具调用 " + toolCount + " 次（不限制轮次）\n" + toolTimeline : "");
            toolStatus.setVisibility(toolCount > 0 ? View.VISIBLE : View.GONE);
        }
        String lastError = session.getLastError();
        if (errorView != null) {
            errorView.setText(lastError.length() > 0 ? "执行错误 · 点按复制\n" + lastError : "");
            errorView.setVisibility(lastError.length() > 0 ? View.VISIBLE : View.GONE);
        }
        boolean running = session.isRunning();
        if (sendButton != null) { sendButton.setText(running ? "停止" : "发送"); sendButton.setEnabled(true); }
        setStatus(session.getStatus());
        if (transcriptChanged && keepAtBottom) scrollBottom();
    }

    private void refreshQuota(final VibeConfigStore.Config config) {
        new Thread(new Runnable() { @Override public void run() {
            HttpURLConnection connection = null;
            try {
                String endpoint = config.baseUrl.substring(0, config.baseUrl.length() - "/chat/completions".length()) + "/quota";
                connection = (HttpURLConnection) new URL(endpoint).openConnection(); connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey); connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != 200) return;
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8")); StringBuilder raw = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) raw.append(line); reader.close(); final JSONObject quota = new JSONObject(raw.toString());
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (session != null && session.isRunning()) return;
                    long remaining = quota.optLong("remaining_tokens", 0); setStatus("Oldchat 免费额度 · 剩余 " + formatTokens(remaining));
                }});
            } catch (Exception ignored) { } finally { if (connection != null) connection.disconnect(); }
        }}, "cip-vibe-quota").start();
    }

    private String formatTokens(long value) { if (value >= 100000000L) return "1 亿 token"; if (value >= 10000L) return String.format(java.util.Locale.CHINA, "%.1f 万 token", value / 10000.0); return value + " token"; }

    private void runProject(final boolean preview) {
        if (workspace == null && !initializeAgent()) { Toast.makeText(this, "工作区尚未初始化", Toast.LENGTH_SHORT).show(); return; }
        setStatus(preview ? "准备预览…" : "测试中…");
        new AsyncTask<Void, Void, CipProjectRunner.Result>() {
            @Override protected CipProjectRunner.Result doInBackground(Void... values) { return preview ? CipProjectRunner.installPreview(CipVibeCodingActivity.this, workspace) : CipProjectRunner.validate(workspace); }
            @Override protected void onPostExecute(CipProjectRunner.Result result) {
                Toast.makeText(CipVibeCodingActivity.this, result.message, Toast.LENGTH_SHORT).show(); setStatus(result.success ? "测试通过" : "测试失败");
                if (preview && result.success) { Intent intent = new Intent(CipVibeCodingActivity.this, LuaMiniAppActivity.class); intent.putExtra(LuaMiniAppActivity.EXTRA_APP_ID, CipProjectRunner.PREVIEW_ID); startActivity(intent); }
            }
        }.execute();
    }

    private void exportZip() {
        CipProjectRunner.Result test = CipProjectRunner.validate(workspace); if (!test.success) { onError("导出前测试失败：" + test.message); return; }
        try {
            File file = workspace.buildCip(this); Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND); share.setType("application/zip"); share.putExtra(Intent.EXTRA_STREAM, uri); share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "导出 CIP ZIP"));
        } catch (Exception e) { onError(e.getMessage()); }
    }

    private void clearChat() { if (session.isRunning()) { Toast.makeText(this, "后台任务运行中，暂不能清空", Toast.LENGTH_SHORT).show(); return; } session.clear(this); renderSession(); }
    @Override protected void onStart() { super.onStart(); if (session != null) { session.attach(this); renderSession(); } }
    @Override protected void onStop() { if (session != null) session.detach(this); super.onStop(); }
    private boolean isNearBottom() {
        if (scroll == null || scroll.getChildCount() == 0) return true;
        View child = scroll.getChildAt(0);
        return child == null || child.getHeight() - (scroll.getScrollY() + scroll.getHeight()) <= dp(96);
    }
    private void scrollBottom() { if (scroll != null) scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }}); }
    private void setStatus(String text) { if (status != null) status.setText(text == null ? "" : text); }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }
}
