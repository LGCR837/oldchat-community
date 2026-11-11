package com.im.oldchat.lua;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

public final class CipVibeAgent {
    public interface Events {
        void onStatus(String text);
        void onContent(String delta);
        void onThinking(String delta);
        void onTool(String name, String detail);
        void onTasks(String text);
        void onAsk(String message);
        void onError(String error);
        void onFinished();
    }

    private final VibeConfigStore.Config config;
    private final CipVibeTools tools;
    private final Events events;
    private JSONArray messages = new JSONArray();
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile HttpURLConnection activeConnection;

    public CipVibeAgent(VibeConfigStore.Config config, CipWorkspace workspace, final Events events) throws Exception {
        this(config, workspace, events, null);
    }

    public CipVibeAgent(VibeConfigStore.Config config, CipWorkspace workspace, final Events events,
                        JSONArray restoredMessages) throws Exception {
        this.config = config; this.events = events;
        this.tools = new CipVibeTools(workspace, new CipVibeTools.Events() {
            @Override public void onTool(String name, String detail) { events.onTool(name, detail); }
            @Override public void onTasks(String text) { events.onTasks(text); }
            @Override public void onAsk(String message) { events.onAsk(message); }
        });
        if (restoredMessages != null && restoredMessages.length() > 0) {
            messages = new JSONArray(restoredMessages.toString());
        } else {
            resetConversation();
        }
    }

    public boolean isRunning() { return running; }

    public synchronized void send(final String text) {
        if (running || text == null || text.trim().length() == 0) return;
        try { JSONObject user = new JSONObject(); user.put("role", "user"); user.put("content", text.trim()); messages.put(user); }
        catch (Exception e) { events.onError(e.getMessage()); return; }
        stopRequested = false;
        running = true;
        new Thread(new Runnable() { @Override public void run() { runLoop(); } }, "cip-vibe-agent").start();
    }

    public synchronized void clearConversation() {
        if (running) return;
        resetConversation();
    }

    /** 用户可随时终止网络请求；工具调用次数本身不设上限。 */
    public void requestStop() {
        stopRequested = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try { connection.disconnect(); } catch (Exception ignored) { }
        }
    }

    public synchronized JSONArray snapshotConversation() {
        try { return new JSONArray(messages.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void resetConversation() {
        try {
            messages = new JSONArray();
            JSONObject system = new JSONObject(); system.put("role", "system"); system.put("content", systemPrompt()); messages.put(system);
        } catch (Exception ignored) { }
    }

    private synchronized void trimConversation() {
        if (messages.length() <= 34 && messages.toString().length() <= 120000) return;
        try {
            JSONArray trimmed = new JSONArray();
            JSONObject system = new JSONObject(); system.put("role", "system"); system.put("content", systemPrompt()); trimmed.put(system);
            int start = Math.max(1, messages.length() - 32);
            for (int i = start; i < messages.length(); i++) trimmed.put(messages.get(i));
            messages = trimmed;
        } catch (Exception ignored) { }
    }

    private void runLoop() {
        long round = 0L;
        while (!stopRequested) {
            trimConversation();
            if (!runRound(round)) return;
            round++;
        }
        finishStopped();
    }

    /** @return true 表示模型调用了工具，需要继续请求下一轮。 */
    private boolean runRound(long round) {
        HttpURLConnection connection = null;
        try {
            if (stopRequested) { finishStopped(); return false; }
            events.onStatus(round == 0 ? "AI 正在思考…" : "AI 正在继续处理 · 工具轮次 " + round);
            JSONObject body = new JSONObject(); body.put("model", config.modelId); body.put("stream", true);
            body.put("messages", messages); body.put("tools", CipVibeTools.definitions()); body.put("tool_choice", "auto");
            connection = (HttpURLConnection) new URL(endpoint(config.baseUrl)).openConnection();
            activeConnection = connection;
            connection.setRequestMethod("POST"); connection.setConnectTimeout(12000); connection.setReadTimeout(120000);
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream"); connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            OutputStream output = connection.getOutputStream(); output.write(body.toString().getBytes("UTF-8")); output.close();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readAll(connection.getErrorStream()); throw new Exception("HTTP " + code + ": " + truncate(error, 600));
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder content = new StringBuilder();
            HashMap<Integer, ToolCall> calls = new HashMap<Integer, ToolCall>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim(); if ("[DONE]".equals(data)) break; if (data.length() == 0) continue;
                JSONObject chunk;
                try { chunk = new JSONObject(data); }
                catch (Exception malformedChunk) { continue; }
                JSONArray choices = chunk.optJSONArray("choices"); if (choices == null || choices.length() == 0) continue;
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta"); if (delta == null) continue;
                String contentDelta = delta.optString("content", "");
                if (contentDelta.length() > 0 && !"null".equals(contentDelta)) {
                    if (content.length() < 60000) content.append(contentDelta.substring(0, Math.min(contentDelta.length(), 60000 - content.length())));
                    events.onContent(contentDelta);
                }
                String thought = delta.optString("reasoning_content", delta.optString("reasoning", ""));
                // 思考文本只流向有上限的会话缓冲，不在 Agent 内重复保存一份。
                if (thought.length() > 0 && !"null".equals(thought)) events.onThinking(thought);
                JSONArray toolDeltas = delta.optJSONArray("tool_calls");
                if (toolDeltas != null) for (int i = 0; i < toolDeltas.length(); i++) {
                    JSONObject part = toolDeltas.optJSONObject(i); if (part == null) continue; int index = part.optInt("index", i);
                    ToolCall call = calls.get(Integer.valueOf(index)); if (call == null) { call = new ToolCall(); calls.put(Integer.valueOf(index), call); }
                    String id = part.optString("id", ""); if (id.length() > 0) call.id = id;
                    JSONObject function = part.optJSONObject("function"); if (function != null) {
                        String name = function.optString("name", ""); if (name.length() > 0) call.name += name;
                        String argumentDelta = function.optString("arguments", "");
                        if (call.arguments.length() < 64000) call.arguments.append(argumentDelta.substring(0, Math.min(argumentDelta.length(), 64000 - call.arguments.length())));
                    }
                }
            }
            reader.close();
            JSONObject assistant = new JSONObject(); assistant.put("role", "assistant");
            assistant.put("content", content.length() == 0 ? JSONObject.NULL : content.toString());
            if (calls.isEmpty()) { messages.put(assistant); finish(); return false; }
            JSONArray assistantCalls = new JSONArray(); ArrayList<Integer> indexes = new ArrayList<Integer>(calls.keySet()); java.util.Collections.sort(indexes);
            boolean asked = false;
            for (Integer index : indexes) {
                ToolCall call = calls.get(index); if (call.id.length() == 0) call.id = "call_" + System.currentTimeMillis() + "_" + index;
                JSONObject function = new JSONObject(); function.put("name", call.name); function.put("arguments", call.arguments.toString());
                JSONObject callJson = new JSONObject(); callJson.put("id", call.id); callJson.put("type", "function"); callJson.put("function", function); assistantCalls.put(callJson);
            }
            assistant.put("tool_calls", assistantCalls); messages.put(assistant);
            for (Integer index : indexes) {
                ToolCall call = calls.get(index); String result = tools.execute(call.name, call.arguments.toString());
                boolean toolFailed = result != null && (result.startsWith("工具执行失败") || result.startsWith("未知工具") || result.startsWith("测试失败"));
                events.onTool(call.name, (toolFailed ? "✕ " : "✓ ") + oneLine(result, 180));
                JSONObject toolMessage = new JSONObject(); toolMessage.put("role", "tool"); toolMessage.put("tool_call_id", call.id); toolMessage.put("content", truncate(result, 12000)); messages.put(toolMessage);
                if ("ask".equals(call.name)) asked = true;
            }
            if (asked) { finish(); return false; }
            return true;
        } catch (Exception e) {
            if (stopRequested) finishStopped();
            else finishWithError(friendlyError(e));
            return false;
        } finally {
            if (activeConnection == connection) activeConnection = null;
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void finish() { trimConversation(); running = false; stopRequested = false; events.onStatus("就绪"); events.onFinished(); }
    private synchronized void finishStopped() {
        if (!running) return;
        trimConversation(); running = false; stopRequested = false;
        events.onStatus("已停止"); events.onFinished();
    }
    private synchronized void finishWithError(String error) { trimConversation(); running = false; stopRequested = false; events.onError(error); events.onStatus("请求失败"); events.onFinished(); }

    private static String endpoint(String base) {
        String value = base == null ? "" : base.trim(); while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions";
        return value + "/v1/chat/completions";
    }
    private static String readAll(InputStream input) throws Exception {
        if (input == null) return ""; BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8")); StringBuilder out = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null) out.append(line).append('\n'); reader.close(); return out.toString();
    }
    private static String truncate(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max); }
    private static String oneLine(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return truncate(text, max);
    }
    private static String friendlyError(Exception error) {
        String detail = error == null || error.getMessage() == null ? "" : error.getMessage().trim();
        if (error instanceof java.net.SocketTimeoutException) return "请求超时：模型长时间未响应。可重试或检查接口速度。";
        if (error instanceof java.net.UnknownHostException) return "网络错误：无法解析 AI 服务地址，请检查网络或 Base URL。";
        if (error instanceof javax.net.ssl.SSLException) return "安全连接失败：AI 服务证书或 TLS 配置异常。";
        if (error instanceof java.net.ConnectException) return "连接失败：AI 服务不可达或拒绝连接。";
        if (detail.startsWith("HTTP ")) return "AI 服务返回错误：" + detail;
        if (detail.length() == 0) return "请求失败：" + (error == null ? "未知错误" : error.getClass().getSimpleName());
        return "请求失败：" + truncate(detail, 800);
    }
    private static String systemPrompt() {
        return "你是 Oldchat CIP 应用开发助手。只能操作当前 CIP 工作区，禁止声称使用 Shell、系统文件、Java、聊天数据或未提供的工具。"
                + "项目必须包含根目录 manifest.json 和 main.lua，资源仅放 assets/。先用 tasklist 向用户展示计划，再用 read/grep 理解现状，使用 write/edit 修改，最后必须 run_test。"
                + "UI 可使用 ui.page/text/button/image/input/checkbox/list/column/row/scroll/card/divider/progress/spacer。row/column 支持 children、gravity、padding，card 支持 background/radius/stroke_width/stroke_color，scroll 支持 vertical/horizontal。输入框支持 input_type、max_length、single_line、on_change(value)、on_focus_lost(value)、on_submit(value) 与 app.get_text(id)，可以实现草稿自动保存。"
                + "复选框支持 checked/on_change(checked) 与 get_checked/set_checked。存储支持 storage_get/set/remove/clear/keys/count/get_json/set_json；公网网络支持旧版 http_get(url, callback) 和新版 http_request({url,method,headers,body/json,connect_timeout,read_timeout}, callback)，回调响应含 status/body/url/headers。公网 HTTP/HTTPS 无需权限声明，allowed_hosts 可选且仅用于主动收紧域名。站内 API 仍通过 http_get('/v1/...') 并声明 network。其余 API 包括 toast/json_decode/json_encode/delay/camera/get_text/append_text/set_text/set_hint/focus/get_checked/set_checked/set_image/set_visible/get_visible/set_enabled/asset/back。"
                + "需要用户决定时调用 ask。工具结果失败时修复，不要伪造成功。用中文简洁说明进展。";
    }
    private static final class ToolCall { String id = ""; String name = ""; StringBuilder arguments = new StringBuilder(); }
}
