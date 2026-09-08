package com.im.oldchat.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

class ChatSearchSupport {
    private ChatSearchSupport() {
    }

    static String normalizeKind(String kind) {
        if ("text".equals(kind) || "media".equals(kind)) {
            return kind;
        }
        return "all";
    }

    static String buildSearchPath(String mode, String groupId, String friendUid,
                                  String query, String kind, int limit, int offset) {
        StringBuilder path = new StringBuilder();
        if (ChatSearchActivity.MODE_GROUP.equals(mode)) {
            path.append("/groups/messages/search?group_id=").append(urlEncode(groupId));
        } else {
            path.append("/direct/messages/search?with_uid=").append(urlEncode(friendUid));
        }
        path.append("&q=").append(urlEncode(query));
        path.append("&kind=").append(normalizeKind(kind));
        path.append("&limit=").append(limit);
        path.append("&offset=").append(offset);
        return path.toString();
    }

    static List<ChatSearchResultAdapter.Item> parseItems(String response) throws Exception {
        List<ChatSearchResultAdapter.Item> result = new ArrayList<ChatSearchResultAdapter.Item>();
        JSONObject obj = new JSONObject(response);
        JSONArray arr = obj.optJSONArray("messages");
        if (arr == null) {
            return result;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) {
                continue;
            }
            ChatSearchResultAdapter.Item item = new ChatSearchResultAdapter.Item();
            item.id = m.optString("id", "");
            item.fromUid = m.optString("from_uid", "");
            item.msgType = m.optString("msg_type", "text");
            item.body = m.optString("body", "");
            item.createdAt = ChatMessageUtil.sanitizeTimestamp(m.optLong("created_at", 0));
            if (item.id == null || item.id.length() == 0) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    static String buildSummaryText(String query, String kind, int count) {
        if (query == null || query.length() == 0) {
            return "请输入关键词开始搜索";
        }
        String k = "全部";
        if ("text".equals(kind)) {
            k = "文字";
        } else if ("media".equals(kind)) {
            k = "媒体";
        }
        return "关键词: " + query + " · 类型: " + k + " · 已加载 " + count + " 条";
    }

    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
