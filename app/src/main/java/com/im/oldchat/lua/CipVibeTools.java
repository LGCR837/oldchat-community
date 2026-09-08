package com.im.oldchat.lua;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CipVibeTools {
    public interface Events {
        void onTool(String name, String detail);
        void onTasks(String text);
        void onAsk(String message);
    }

    private final CipWorkspace workspace;
    private final Events events;

    public CipVibeTools(CipWorkspace workspace, Events events) {
        this.workspace = workspace; this.events = events;
    }

    public String execute(String name, String arguments) {
        try {
            JSONObject args = arguments == null || arguments.trim().length() == 0 ? new JSONObject() : new JSONObject(arguments);
            if (events != null) events.onTool(name, summarize(args));
            if ("read".equals(name)) return workspace.read(args.optString("path", ""));
            if ("write".equals(name)) {
                String path = args.optString("path", ""); workspace.write(path, args.optString("content", "")); return "已写入 " + path;
            }
            if ("edit".equals(name)) return edit(args);
            if ("grep".equals(name)) return grep(args);
            if ("run_test".equals(name) || "test".equals(name) || "run".equals(name)) {
                CipProjectRunner.Result result = CipProjectRunner.validate(workspace);
                return result.success ? "测试通过：" + result.message : "测试失败：" + result.message;
            }
            if ("tasklist".equals(name)) {
                String text = formatTasks(args.optJSONArray("tasks")); if (events != null) events.onTasks(text); return "任务列表已展示给用户";
            }
            if ("ask".equals(name)) {
                String message = args.optString("message", "请补充需求"); if (events != null) events.onAsk(message); return "已向用户提问，等待用户回复";
            }
            return "未知工具：" + name;
        } catch (Exception e) { return "工具执行失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    }

    private String edit(JSONObject args) throws Exception {
        String path = args.optString("path", ""); String oldText = args.optString("old_text", ""); String newText = args.optString("new_text", "");
        if (oldText.length() == 0) throw new Exception("old_text 不能为空");
        String content = workspace.read(path); int first = content.indexOf(oldText);
        if (first < 0) throw new Exception("old_text 未找到");
        if (content.indexOf(oldText, first + oldText.length()) >= 0) throw new Exception("old_text 不唯一，请提供更多上下文");
        workspace.write(path, content.substring(0, first) + newText + content.substring(first + oldText.length()));
        return "已编辑 " + path;
    }

    private String grep(JSONObject args) throws Exception {
        String query = args.optString("pattern", ""); String pathFilter = args.optString("path", "");
        if (query.length() == 0 || query.length() > 200) throw new Exception("pattern 不合法");
        Pattern pattern = Pattern.compile(query); StringBuilder out = new StringBuilder(); int matches = 0;
        List<String> files = workspace.listFiles();
        for (String path : files) {
            if (pathFilter.length() > 0 && !path.startsWith(pathFilter)) continue;
            String[] lines = workspace.read(path).split("\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = pattern.matcher(lines[i]);
                if (matcher.find()) { out.append(path).append(':').append(i + 1).append(':').append(lines[i]).append('\n'); if (++matches >= 100) return out.toString(); }
            }
        }
        return matches == 0 ? "没有匹配结果" : out.toString();
    }

    private String formatTasks(JSONArray tasks) {
        if (tasks == null || tasks.length() == 0) return "任务列表为空";
        StringBuilder out = new StringBuilder("任务列表\n");
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject item = tasks.optJSONObject(i); if (item == null) continue;
            String status = item.optString("status", "pending");
            out.append("completed".equals(status) ? "✓ " : "in_progress".equals(status) ? "→ " : "○ ")
                    .append(item.optString("title", "未命名任务")).append('\n');
        }
        return out.toString().trim();
    }

    private String summarize(JSONObject args) {
        String path = args.optString("path", ""); return path.length() > 0 ? path : "";
    }

    public static JSONArray definitions() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("grep", "在 CIP 工作区文本文件中搜索正则表达式", props("pattern", "string", "path", "string"), new String[]{"pattern"}));
        tools.put(tool("read", "读取 CIP 工作区文件", props("path", "string"), new String[]{"path"}));
        tools.put(tool("write", "创建或完整覆盖工作区文件", props("path", "string", "content", "string"), new String[]{"path", "content"}));
        tools.put(tool("edit", "用唯一文本片段精确编辑文件", props("path", "string", "old_text", "string", "new_text", "string"), new String[]{"path", "old_text", "new_text"}));
        tools.put(tool("run_test", "校验 manifest 并编译 main.lua", new JSONObject(), new String[]{}));
        tools.put(tool("run", "运行 CIP 项目安全测试（不会执行 Shell）", new JSONObject(), new String[]{}));
        tools.put(tool("test", "执行 manifest 和 Lua 编译测试", new JSONObject(), new String[]{}));
        tools.put(tool("tasklist", "向用户展示开发任务和状态", taskProps(), new String[]{"tasks"}));
        tools.put(tool("ask", "向用户提出必须回答的问题", props("message", "string"), new String[]{"message"}));
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String[] required) throws Exception {
        JSONObject parameters = new JSONObject(); parameters.put("type", "object"); parameters.put("properties", properties);
        JSONArray req = new JSONArray(); for (String value : required) req.put(value); parameters.put("required", req);
        JSONObject function = new JSONObject(); function.put("name", name); function.put("description", description); function.put("parameters", parameters);
        JSONObject tool = new JSONObject(); tool.put("type", "function"); tool.put("function", function); return tool;
    }
    private static JSONObject props(String... pairs) throws Exception {
        JSONObject out = new JSONObject(); for (int i = 0; i + 1 < pairs.length; i += 2) { JSONObject value = new JSONObject(); value.put("type", pairs[i + 1]); out.put(pairs[i], value); } return out;
    }
    private static JSONObject taskProps() throws Exception {
        JSONObject itemProps = props("title", "string", "status", "string"); JSONObject item = new JSONObject(); item.put("type", "object"); item.put("properties", itemProps);
        JSONObject array = new JSONObject(); array.put("type", "array"); array.put("items", item); JSONObject out = new JSONObject(); out.put("tasks", array); return out;
    }
}
