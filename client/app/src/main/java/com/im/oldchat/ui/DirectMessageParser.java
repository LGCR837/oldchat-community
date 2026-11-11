package com.im.oldchat.ui;

import com.im.oldchat.models.Message;
import com.im.oldchat.util.MessageFieldRepair;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DirectMessageParser {
    public static List<Message> parse(String response) throws Exception {
        JSONObject obj = new JSONObject(response);
        JSONArray arr = obj.getJSONArray("messages");
        List<Message> incoming = new ArrayList<Message>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject mObj = arr.getJSONObject(i);
            Message m = new Message();
            m.id = mObj.optString("id", "");
            m.from_uid = mObj.optString("from_uid", "");
            m.sender_ncuid = mObj.optString("from_ncuid", "");
            m.body = mObj.optString("body", "");
            m.msg_type = mObj.optString("msg_type", "text");
            m.media_url = mObj.optString("media_url");
            m.thumb_url = mObj.optString("thumb_url");
            m.original_url = mObj.optString("original_url");
            m.duration_ms = mObj.optInt("duration_ms", 0);
            m.burn_after_seconds = mObj.optInt("burn_after_seconds", 0);
            m.burn_start_at = ChatMessageUtil.sanitizeTimestamp(mObj.optLong("burn_start_at", 0L));
            m.created_at = ChatMessageUtil.sanitizeTimestamp(mObj.optLong("created_at", 0));
            m.sort_seq = mObj.optLong("sort_seq", 0L);
            m.status = ChatMessageUtil.parseStatus(mObj);
            MessageFieldRepair.repairDirect(m);
            incoming.add(m);
        }
        return incoming;
    }

    public static DirectMessageLoadResult parseLoadResult(String response, int fallbackOffset) {
        try {
            JSONObject obj = new JSONObject(response);
            JSONArray arr = obj.getJSONArray("messages");
            List<Message> incoming = new ArrayList<Message>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mObj = arr.getJSONObject(i);
                Message m = new Message();
                m.id = mObj.optString("id", "");
                m.from_uid = mObj.optString("from_uid", "");
                m.sender_ncuid = mObj.optString("from_ncuid", "");
                m.body = mObj.optString("body", "");
                m.msg_type = mObj.optString("msg_type", "text");
                m.media_url = mObj.optString("media_url");
                m.thumb_url = mObj.optString("thumb_url");
                m.original_url = mObj.optString("original_url");
                m.duration_ms = mObj.optInt("duration_ms", 0);
                m.burn_after_seconds = mObj.optInt("burn_after_seconds", 0);
                m.burn_start_at = ChatMessageUtil.sanitizeTimestamp(mObj.optLong("burn_start_at", 0L));
                m.created_at = ChatMessageUtil.sanitizeTimestamp(mObj.optLong("created_at", 0));
                m.sort_seq = mObj.optLong("sort_seq", 0L);
                m.status = ChatMessageUtil.parseStatus(mObj);
                MessageFieldRepair.repairDirect(m);
                incoming.add(m);
            }
            Collections.reverse(incoming);
            int effectiveOffset = obj.optInt("effective_offset", fallbackOffset);
            if (effectiveOffset < 0) {
                effectiveOffset = 0;
            }
            Boolean hasMore = obj.has("has_more")
                    ? Boolean.valueOf(obj.optBoolean("has_more", false)) : null;
            return new DirectMessageLoadResult(incoming, effectiveOffset, hasMore, null);
        } catch (Exception e) {
            return new DirectMessageLoadResult(null, Math.max(0, fallbackOffset), null, e);
        }
    }

    public static int parseEffectiveOffset(String response, int fallback) {
        try {
            JSONObject obj = new JSONObject(response);
            int value = obj.optInt("effective_offset", fallback);
            if (value < 0) {
                return 0;
            }
            return value;
        } catch (Exception e) {
            if (fallback < 0) {
                return 0;
            }
            return fallback;
        }
    }

}

class DirectMessageLoadResult {
    final List<Message> incoming;
    final int effectiveOffset;
    final Boolean hasMore;
    final Exception error;

    DirectMessageLoadResult(List<Message> incoming, int effectiveOffset,
                            Boolean hasMore, Exception error) {
        this.incoming = incoming;
        this.effectiveOffset = effectiveOffset;
        this.hasMore = hasMore;
        this.error = error;
    }
}
