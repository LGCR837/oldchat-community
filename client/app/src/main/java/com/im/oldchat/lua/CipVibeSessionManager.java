package com.im.oldchat.lua;

import android.content.Context;
import android.content.Intent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 跨 Activity 保存 VibeCoding 会话，并让 AI 任务在页面退出后继续运行。 */
public final class CipVibeSessionManager implements CipVibeAgent.Events {
    private static final int MAX_TRANSCRIPT_CHARS = 60000;
    private static final int MAX_THINKING_CHARS = 20000;
    private static final CipVibeSessionManager INSTANCE = new CipVibeSessionManager();

    private final CopyOnWriteArrayList<CipVibeAgent.Events> listeners = new CopyOnWriteArrayList<CipVibeAgent.Events>();
    private final StringBuilder transcript = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder toolTimeline = new StringBuilder();
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private Context appContext;
    private CipWorkspace workspace;
    private CipVibeAgent agent;
    private String status = "就绪";
    private String tasks = "";
    private String latestTool = "";
    private String lastError = "";
    private long toolCount;
    private long thinkingSegmentCount;
    private boolean assistantStarted;
    private boolean thinkingSegmentOpen;
    private long lastPersistAt;

    private CipVibeSessionManager() { }
    public static CipVibeSessionManager get() { return INSTANCE; }

    public synchronized boolean initialize(Context context) {
        if (context == null) return false;
        appContext = context.getApplicationContext();
        VibeConfigStore.Config config = VibeConfigStore.load(appContext);
        if (!config.isValid()) return false;
        if (workspace == null) { workspace = new CipWorkspace(appContext); try { workspace.initialize(); } catch (Exception ignored) { } }
        if (agent == null) {
            try {
                String saved = VibeChatStore.loadTranscript(appContext);
                if (saved != null && saved.length() > 0) transcript.append(saved);
                agent = new CipVibeAgent(config, workspace, this, VibeChatStore.loadMessages(appContext));
            } catch (Exception e) { onError(e.getMessage()); return false; }
        }
        return true;
    }

    public synchronized boolean reconfigure(Context context) {
        if (agent != null && agent.isRunning()) return false;
        agent = null; workspace = null;
        return initialize(context);
    }

    public void attach(CipVibeAgent.Events listener) { if (listener != null && !listeners.contains(listener)) listeners.add(listener); }
    public void detach(CipVibeAgent.Events listener) { listeners.remove(listener); }
    public synchronized boolean isRunning() { return agent != null && agent.isRunning(); }
    public synchronized String getTranscript() { return transcript.toString(); }
    public synchronized String getThinking() { return thinking.toString(); }
    public synchronized String getTasks() { return tasks; }
    public synchronized String getStatus() { return status; }
    public synchronized String getLatestTool() { return latestTool; }
    public synchronized String getLastError() { return lastError; }
    public synchronized String getToolTimeline() { return toolTimeline.toString(); }
    public synchronized long getToolCount() { return toolCount; }
    public synchronized CipWorkspace getWorkspace() { return workspace; }

    public synchronized boolean send(Context context, String text) {
        if (!initialize(context) || agent == null || agent.isRunning() || text == null || text.trim().length() == 0) return false;
        appendBounded(transcript, "\n你：" + text.trim() + "\n", MAX_TRANSCRIPT_CHARS);
        thinking.setLength(0); toolTimeline.setLength(0); latestTool = ""; lastError = ""; toolCount = 0L; thinkingSegmentCount = 0L;
        assistantStarted = false; thinkingSegmentOpen = false;
        agent.send(text.trim());
        startWorkerService();
        persistAsync(false);
        return true;
    }

    public synchronized boolean stop() {
        if (agent == null || !agent.isRunning()) return false;
        status = "正在停止…";
        agent.requestStop();
        return true;
    }

    public synchronized boolean clear(Context context) {
        if (isRunning()) return false;
        if (agent != null) agent.clearConversation();
        transcript.setLength(0); thinking.setLength(0); toolTimeline.setLength(0); tasks = ""; latestTool = ""; lastError = ""; toolCount = 0L; thinkingSegmentCount = 0L;
        assistantStarted = false; thinkingSegmentOpen = false;
        if (context != null) VibeChatStore.clear(context.getApplicationContext());
        return true;
    }

    @Override public synchronized void onContent(String delta) {
        if (!assistantStarted) {
            if (thinkingSegmentOpen) appendBounded(transcript, "\n", MAX_TRANSCRIPT_CHARS);
            appendBounded(transcript, "AI：", MAX_TRANSCRIPT_CHARS);
            assistantStarted = true;
        }
        thinkingSegmentOpen = false;
        appendBounded(transcript, delta, MAX_TRANSCRIPT_CHARS); notifyContent(delta); persistAsync(false);
    }
    @Override public synchronized void onThinking(String delta) {
        // reasoning 必须出现在对应正文之前。若正文或工具已开始，下一批 reasoning
        // 是新一轮思考，另起一段，不能继续写入页面底部的旧思考框。
        if (!thinkingSegmentOpen) {
            thinkingSegmentCount++;
            thinking.setLength(0);
            appendBounded(transcript, "\n\n▌思考 " + thinkingSegmentCount + "\n", MAX_TRANSCRIPT_CHARS);
            thinkingSegmentOpen = true;
            assistantStarted = false;
        }
        appendBounded(thinking, delta, MAX_THINKING_CHARS);
        appendBounded(transcript, delta, MAX_TRANSCRIPT_CHARS);
        for (CipVibeAgent.Events e : listeners) e.onThinking(delta);
        persistAsync(false);
    }
    @Override public synchronized void onTool(String name, String detail) {
        thinkingSegmentOpen = false;
        assistantStarted = false;
        String safeName = name == null || name.length() == 0 ? "工具" : name;
        String safeDetail = detail == null ? "" : detail;
        boolean result = safeDetail.startsWith("✓ ") || safeDetail.startsWith("✕ ");
        if (result) {
            latestTool = safeName + " · " + safeDetail;
            appendToolTimeline("    " + safeDetail);
            appendBounded(transcript, "[结果] " + safeDetail + "\n", MAX_TRANSCRIPT_CHARS);
        } else {
            toolCount++;
            latestTool = safeName + (safeDetail.length() > 0 ? " · " + safeDetail : "");
            appendToolTimeline("#" + toolCount + "  " + latestTool + "  …");
            appendBounded(transcript, "\n[工具 #" + toolCount + "] " + latestTool + "\n", MAX_TRANSCRIPT_CHARS);
        }
        for (CipVibeAgent.Events e : listeners) e.onTool(name, safeDetail);
        persistAsync(false);
    }
    @Override public synchronized void onTasks(String text) { tasks = text == null ? "" : text; for (CipVibeAgent.Events e : listeners) e.onTasks(tasks); }
    @Override public synchronized void onAsk(String message) { appendBounded(transcript, "\nAI 提问：" + message + "\n", MAX_TRANSCRIPT_CHARS); for (CipVibeAgent.Events e : listeners) e.onAsk(message); }
    @Override public synchronized void onStatus(String text) { status = text == null ? "" : text; for (CipVibeAgent.Events e : listeners) e.onStatus(status); }
    @Override public synchronized void onError(String error) {
        lastError = error == null || error.trim().length() == 0 ? "未知错误" : error.trim();
        appendBounded(transcript, "\n[错误] " + lastError + "\n", MAX_TRANSCRIPT_CHARS);
        for (CipVibeAgent.Events e : listeners) e.onError(lastError);
    }
    @Override public synchronized void onFinished() { appendBounded(transcript, "\n", MAX_TRANSCRIPT_CHARS); persistAsync(true); stopWorkerService(); for (CipVibeAgent.Events e : listeners) e.onFinished(); }

    private void notifyContent(String delta) { for (CipVibeAgent.Events e : listeners) e.onContent(delta); }
    private void appendToolTimeline(String line) {
        toolTimeline.append(line == null ? "" : line).append('\n');
        String[] lines = toolTimeline.toString().split("\\n", -1);
        if (lines.length > 13) {
            toolTimeline.setLength(0);
            for (int i = Math.max(0, lines.length - 13); i < lines.length; i++) {
                if (lines[i].length() > 0) toolTimeline.append(lines[i]).append('\n');
            }
        }
    }
    private static void appendBounded(StringBuilder target, String value, int max) { if (value != null) target.append(value); if (target.length() > max) target.delete(0, target.length() - max); }
    private void persistAsync(boolean force) {
        final long now = System.currentTimeMillis(); if (!force && now - lastPersistAt < 1200L) return; lastPersistAt = now;
        if (appContext == null || agent == null) return;
        final Context context = appContext; final org.json.JSONArray messages = agent.snapshotConversation(); final String text = transcript.toString();
        disk.execute(new Runnable() { @Override public void run() { VibeChatStore.save(context, messages, text); }});
    }
    private void startWorkerService() { if (appContext != null) try { appContext.startService(new Intent(appContext, CipVibeBackgroundService.class)); } catch (Exception ignored) { } }
    private void stopWorkerService() { if (appContext != null) try { appContext.stopService(new Intent(appContext, CipVibeBackgroundService.class)); } catch (Exception ignored) { } }
}
