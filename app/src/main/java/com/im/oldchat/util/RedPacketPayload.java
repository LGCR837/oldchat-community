package com.im.oldchat.util;

import org.json.JSONObject;

public class RedPacketPayload {
    public String packetId;
    public String title;
    public int totalAmount;
    public int totalCount;
    public String coverUrl;
    public String status;
    public int remainingCount;
    public boolean hasRemainingCount;

    public static RedPacketPayload fromBody(String body) {
        RedPacketPayload payload = new RedPacketPayload();
        if (body == null) {
            payload.title = "";
            return payload;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JSONObject obj = new JSONObject(trimmed);
                payload.packetId = obj.optString("packet_id", "");
                String text = obj.optString("text", "");
                if (text == null || text.isEmpty()) {
                    text = obj.optString("title", "");
                }
                payload.title = text == null ? "" : text;
                payload.totalAmount = obj.optInt("total_amount", 0);
                payload.totalCount = obj.optInt("total_count", 0);
                payload.coverUrl = obj.optString("cover_url", "");
                payload.status = obj.optString("status", "");
                payload.hasRemainingCount = obj.has("remaining_count");
                payload.remainingCount = obj.optInt("remaining_count", 0);
                return payload;
            } catch (Exception e) {
                // fall through
            }
        }
        payload.title = body;
        payload.coverUrl = "";
        return payload;
    }

    public boolean isDoneInPayload() {
        if ("done".equalsIgnoreCase(status)) {
            return true;
        }
        return hasRemainingCount && remainingCount <= 0;
    }

    public static String buildBody(String packetId, String title, int totalAmount, int totalCount, String coverUrl) {
        String safeTitle = title == null ? "" : title;
        try {
            JSONObject obj = new JSONObject();
            obj.put("v", MessagePayload.VERSION);
            obj.put("text", safeTitle);
            if (packetId != null && !packetId.isEmpty()) {
                obj.put("packet_id", packetId);
            }
            if (totalAmount > 0) {
                obj.put("total_amount", totalAmount);
            }
            if (totalCount > 0) {
                obj.put("total_count", totalCount);
            }
            String safeCover = coverUrl == null ? "" : coverUrl.trim();
            if (safeCover.length() > 0) {
                obj.put("cover_url", safeCover);
            }
            return obj.toString();
        } catch (Exception e) {
            return safeTitle;
        }
    }
}
