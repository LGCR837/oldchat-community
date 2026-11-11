package com.im.oldchat.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MessagePayload {
    public static final int VERSION = 2;
    private static final String KEY_FORWARD_V2 = "forward_v2";
    private static final String KEY_FORWARD_ITEM_SOURCE_ID = "sid";

    public static class Quote {
        public String id;
        public String fromUid;
        public String fromNcuid;
        public String fromName;
        public String type;
        public String text;
        public String mediaKind;
        public String thumbUrl;
    }

    public static class Mention {
        public String uid;
        public String ncuid;
        public String name;
    }

    public static class ForwardItem {
        public String sourceMessageId;
        public String fromUid;
        public String fromName;
        public String fromAvatar;
        public String type;
        public String mediaKind;
        public String text;
    }

    public static class ForwardBundle {
        public String title;
        public List<ForwardItem> items;

        public ForwardBundle() {
            items = new ArrayList<ForwardItem>();
        }
    }

    /** 消息内联按钮（机器人/许可用户发送）。 */
    public static class Button {
        public String text;
        public String action; // send_text / open_url / send_image / reply_msg / form
        public String data;
        public int index;     // 消息 buttons 数组中的真实序号，参与 tid 签名
        public long expiresAt; // form 按钮的 Unix 秒级过期时间，0 表示无期限
        public String tid;    // 服务端签名，客户端离线验签
        public String nonce;
    }

    public String text;
    public String mediaKind;
    public String voiceText;
    public Quote quote;
    public List<Mention> mentions;
    public ForwardBundle forward;
    public List<Button> buttons;
    public String replyTid;   // 点击按钮后代发消息时携带的原按钮 tid

    public MessagePayload() {
        mentions = new ArrayList<Mention>();
    }

    public static MessagePayload fromBody(String body) {
        MessagePayload payload = new MessagePayload();
        payload.text = body == null ? "" : body;
        if (body == null) {
            return payload;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return payload;
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            int version = obj.optInt("v", 0);
            if (version != VERSION && version != 3) {
                payload.text = extractFallbackText(obj, payload.text);
                payload.mediaKind = obj.optString("media_kind", "");
                payload.voiceText = safeTrim(obj.optString("voice_text", ""));
                return payload;
            }
            // v3 与 v2 的 text/buttons/media 结构一致（机器人按钮消息用 v3），
            // 走同一解析路径，避免服务端 v3 消息解析不出 buttons。

            payload.text = obj.optString("text", "");
            payload.mediaKind = obj.optString("media_kind", "");
            payload.voiceText = safeTrim(obj.optString("voice_text", ""));
            payload.replyTid = safeTrim(obj.optString("reply_tid", ""));

            // buttons（机器人消息）
            JSONArray buttonsArr = obj.optJSONArray("buttons");
            if (buttonsArr != null && buttonsArr.length() > 0) {
                payload.buttons = new ArrayList<Button>();
                for (int i = 0; i < buttonsArr.length(); i++) {
                    JSONObject bObj = buttonsArr.optJSONObject(i);
                    if (bObj == null) {
                        continue;
                    }
                    Button b = new Button();
                    b.text = bObj.optString("text", "");
                    b.action = bObj.optString("action", "");
                    b.data = bObj.optString("data", "");
                    b.index = bObj.optInt("index", i);
                    b.expiresAt = bObj.optLong("expires_at", 0L);
                    b.tid = bObj.optString("tid", "");
                    b.nonce = bObj.optString("nonce", "");
                    if (b.text != null && !b.text.isEmpty() && b.action != null && !b.action.isEmpty()) {
                        payload.buttons.add(b);
                    }
                }
                if (payload.buttons.isEmpty()) {
                    payload.buttons = null;
                }
            }

            JSONObject qObj = obj.optJSONObject("quote");
            if (qObj != null) {
                Quote q = new Quote();
                q.id = qObj.optString("id", "");
                q.fromUid = qObj.optString("from_uid", "");
                q.fromNcuid = qObj.optString("from_ncuid", "");
                q.fromName = qObj.optString("from_name", "");
                q.type = qObj.optString("type", "");
                q.text = qObj.optString("text", "");
                q.mediaKind = qObj.optString("media_kind", "");
                q.thumbUrl = qObj.optString("thumb_url", "");
                payload.quote = q;
            }

            JSONArray arr = obj.optJSONArray("mentions");
            if (arr != null) {
                payload.mentions.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject mObj = arr.optJSONObject(i);
                    if (mObj == null) {
                        continue;
                    }
                    Mention m = new Mention();
                    m.uid = mObj.optString("uid", "");
                    m.ncuid = mObj.optString("ncuid", "");
                    m.name = mObj.optString("name", "");
                    if ((m.uid != null && !m.uid.isEmpty()) || (m.ncuid != null && !m.ncuid.isEmpty())) {
                        payload.mentions.add(m);
                    }
                }
            }

            JSONObject forwardObj = obj.optJSONObject(KEY_FORWARD_V2);
            if (forwardObj != null) {
                ForwardBundle bundle = new ForwardBundle();
                bundle.title = forwardObj.optString("title", "");
                JSONArray items = forwardObj.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject itemObj = items.optJSONObject(i);
                        if (itemObj == null) {
                            continue;
                        }
                        ForwardItem item = new ForwardItem();
                        item.sourceMessageId = itemObj.optString(KEY_FORWARD_ITEM_SOURCE_ID, "");
                        item.fromUid = itemObj.optString("from_uid", "");
                        item.fromName = itemObj.optString("from_name", "");
                        item.fromAvatar = itemObj.optString("from_avatar", "");
                        item.type = itemObj.optString("type", "");
                        item.mediaKind = itemObj.optString("media_kind", "");
                        item.text = itemObj.optString("text", "");
                        bundle.items.add(item);
                    }
                }
                if (!bundle.items.isEmpty()) {
                    payload.forward = bundle;
                }
            }
        } catch (Exception e) {
            return payload;
        }
        return payload;
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("v", VERSION);
            obj.put("text", text == null ? "" : text);
            if (replyTid != null && !replyTid.isEmpty()) {
                obj.put("reply_tid", replyTid);
            }
            if (buttons != null && !buttons.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Button b : buttons) {
                    if (b == null) {
                        continue;
                    }
                    JSONObject bObj = new JSONObject();
                    bObj.put("text", b.text == null ? "" : b.text);
                    bObj.put("action", b.action == null ? "" : b.action);
                    bObj.put("data", b.data == null ? "" : b.data);
                    bObj.put("index", b.index);
                    if (b.expiresAt > 0L) bObj.put("expires_at", b.expiresAt);
                    bObj.put("tid", b.tid == null ? "" : b.tid);
                    bObj.put("nonce", b.nonce == null ? "" : b.nonce);
                    arr.put(bObj);
                }
                obj.put("buttons", arr);
            }
            if (mediaKind != null && !mediaKind.isEmpty()) {
                obj.put("media_kind", mediaKind);
            }
            if (voiceText != null && !voiceText.isEmpty()) {
                obj.put("voice_text", voiceText);
            }
            if (quote != null) {
                JSONObject qObj = new JSONObject();
                qObj.put("id", quote.id == null ? "" : quote.id);
                qObj.put("from_uid", quote.fromUid == null ? "" : quote.fromUid);
                if (quote.fromNcuid != null && !quote.fromNcuid.isEmpty()) {
                    qObj.put("from_ncuid", quote.fromNcuid);
                }
                qObj.put("from_name", quote.fromName == null ? "" : quote.fromName);
                qObj.put("type", quote.type == null ? "" : quote.type);
                qObj.put("text", quote.text == null ? "" : quote.text);
                if (quote.mediaKind != null && !quote.mediaKind.isEmpty()) {
                    qObj.put("media_kind", quote.mediaKind);
                }
                if (quote.thumbUrl != null && !quote.thumbUrl.isEmpty()) {
                    qObj.put("thumb_url", quote.thumbUrl);
                }
                obj.put("quote", qObj);
            }
            if (mentions != null && !mentions.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Mention m : mentions) {
                    if (m == null) {
                        continue;
                    }
                    boolean hasUid = m.uid != null && !m.uid.isEmpty();
                    boolean hasNcuid = m.ncuid != null && !m.ncuid.isEmpty();
                    if (!hasUid && !hasNcuid) {
                        continue;
                    }
                    JSONObject mObj = new JSONObject();
                    mObj.put("uid", hasUid ? m.uid : "");
                    if (hasNcuid) {
                        mObj.put("ncuid", m.ncuid);
                    }
                    mObj.put("name", m.name == null ? "" : m.name);
                    arr.put(mObj);
                }
                if (arr.length() > 0) {
                    obj.put("mentions", arr);
                }
            }
            if (forward != null && forward.items != null && !forward.items.isEmpty()) {
                JSONObject forwardObj = new JSONObject();
                forwardObj.put("title", forward.title == null ? "" : forward.title);
                JSONArray itemsArr = new JSONArray();
                for (ForwardItem item : forward.items) {
                    if (item == null) {
                        continue;
                    }
                    JSONObject itemObj = new JSONObject();
                    if (item.sourceMessageId != null && !item.sourceMessageId.isEmpty()) {
                        itemObj.put(KEY_FORWARD_ITEM_SOURCE_ID, item.sourceMessageId);
                    }
                    itemObj.put("from_uid", item.fromUid == null ? "" : item.fromUid);
                    itemObj.put("from_name", item.fromName == null ? "" : item.fromName);
                    if (item.fromAvatar != null && !item.fromAvatar.isEmpty()) {
                        itemObj.put("from_avatar", item.fromAvatar);
                    }
                    itemObj.put("type", item.type == null ? "" : item.type);
                    if (item.mediaKind != null && !item.mediaKind.isEmpty()) {
                        itemObj.put("media_kind", item.mediaKind);
                    }
                    itemObj.put("text", item.text == null ? "" : item.text);
                    itemsArr.put(itemObj);
                }
                if (itemsArr.length() > 0) {
                    forwardObj.put("items", itemsArr);
                    obj.put(KEY_FORWARD_V2, forwardObj);
                }
            }
            return obj.toString();
        } catch (Exception e) {
            return text == null ? "" : text;
        }
    }

    public boolean hasMention(String uid) {
        if (uid == null || uid.isEmpty() || mentions == null) {
            return false;
        }
        String norm = uid.trim().toUpperCase(java.util.Locale.US);
        for (Mention m : mentions) {
            if (m == null) {
                continue;
            }
            // 优先按不可变 ncuid 匹配（改 UID 后仍能识别旧 @）。
            if (m.ncuid != null && !m.ncuid.isEmpty() && norm.equals(m.ncuid.trim().toUpperCase(java.util.Locale.US))) {
                return true;
            }
            // 回退按 uid 匹配（历史消息或旧客户端只有 uid）。
            if (m.uid != null && !m.uid.isEmpty() && norm.equals(m.uid.trim().toUpperCase(java.util.Locale.US))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasExtras() {
        return (quote != null) || (mentions != null && !mentions.isEmpty()) ||
                (mediaKind != null && !mediaKind.isEmpty()) ||
                (voiceText != null && !voiceText.isEmpty()) ||
                (forward != null && forward.items != null && !forward.items.isEmpty()) ||
                (buttons != null && !buttons.isEmpty());
    }

    public static String mergeVoiceText(String body, String voiceText) {
        String safeVoice = voiceText == null ? "" : voiceText.trim();
        if (safeVoice.length() == 0) {
            return body == null ? "" : body;
        }
        MessagePayload payload = fromBody(body);
        payload.voiceText = safeVoice;
        return payload.toJson();
    }

    private static String extractFallbackText(JSONObject obj, String fallback) {
        String text = fallback == null ? "" : fallback;
        if (obj == null) {
            return text;
        }
        String fromObj = safeTrim(obj.optString("text", ""));
        if (fromObj.length() > 0) {
            return fromObj;
        }
        return text;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
