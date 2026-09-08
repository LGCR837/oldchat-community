package com.im.oldchat.ui;

import android.content.Context;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class ChatMessageUtil {
    private static final String BURN_PREVIEW_TEXT = "(阅后即焚)";

    public static void applyMessageStatus(List<Message> messages, String myUID) {
        applyMessageStatus(null, messages, myUID);
    }

    public static void applyMessageStatus(Context context, List<Message> messages, String myUID) {
        applyMessageStatusAndCollectChanged(context, messages, myUID);
    }

    public static ArrayList<Integer> applyMessageStatusAndCollectChanged(Context context, List<Message> messages,
                                                                         String myUID) {
        ArrayList<Integer> changedPositions = new ArrayList<Integer>();
        long lastIncoming = -1;
        long lastOutgoing = -1;
        for (Message msg : messages) {
            if (msg.from_uid == null || msg.localPending || msg.localFailed) {
                continue;
            }
            if (MyUidStore.isMyUid(context, msg.from_uid, myUID)) {
                if (msg.created_at > lastOutgoing) {
                    lastOutgoing = msg.created_at;
                }
            } else {
                if (msg.created_at > lastIncoming) {
                    lastIncoming = msg.created_at;
                }
            }
        }

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            int previousStatus = msg.status;
            if (msg.from_uid == null || !MyUidStore.isMyUid(context, msg.from_uid, myUID)) {
                msg.status = Message.STATUS_NONE;
                if (previousStatus != msg.status) {
                    changedPositions.add(Integer.valueOf(i));
                }
                continue;
            }
            if (msg.localPending || msg.localFailed) {
                msg.status = Message.STATUS_NONE;
                if (previousStatus != msg.status) {
                    changedPositions.add(Integer.valueOf(i));
                }
                continue;
            }
            if (msg.status == Message.STATUS_READ || msg.status == Message.STATUS_DELIVERED) {
                continue;
            }
            if (lastIncoming > msg.created_at) {
                msg.status = Message.STATUS_READ;
            } else if (lastOutgoing > msg.created_at) {
                msg.status = Message.STATUS_DELIVERED;
            } else {
                msg.status = Message.STATUS_SENT;
            }
            if (previousStatus != msg.status) {
                changedPositions.add(Integer.valueOf(i));
            }
        }
        return changedPositions;
    }

    public static int parseStatus(JSONObject mObj) {
        long readAt = mObj.optLong("read_at", 0);
        if (readAt > 0) {
            return Message.STATUS_READ;
        }
        long deliveredAt = mObj.optLong("delivered_at", 0);
        if (deliveredAt > 0) {
            return Message.STATUS_DELIVERED;
        }
        return Message.STATUS_SENT;
    }

    public static String previewForMessage(Message msg) {
        if (msg == null) {
            return "";
        }
        if (isBurnPreview(msg.msg_type, msg.burn_after_seconds)) {
            return BURN_PREVIEW_TEXT;
        }
        String type = msg.msg_type == null ? "" : msg.msg_type.toLowerCase();
        MessagePayload payload = MessagePayload.fromBody(msg.body);
        if ("image".equals(type)) {
            if ("emoji".equals(payload.mediaKind)) {
                return "[表情]";
            }
            return "[图片]";
        }
        if ("voice".equals(type)) {
            return "[语音]";
        }
        if ("video".equals(type)) {
            return "[视频]";
        }
        if ("resource".equals(type)) {
            if ("music".equals(payload.mediaKind)) {
                return "[音乐]";
            }
            return "[资源]";
        }
        if ("music".equals(type)) {
            return "[音乐]";
        }
        if ("red_packet".equals(type)) {
            return "[红包]";
        }
        return payload.text != null ? payload.text : "";
    }

    public static String mediaPreview(String type) {
        if ("image".equals(type)) {
            return "[图片]";
        }
        if ("voice".equals(type)) {
            return "[语音]";
        }
        if ("video".equals(type)) {
            return "[视频]";
        }
        if ("resource".equals(type)) {
            return "[资源]";
        }
        if ("music".equals(type)) {
            return "[音乐]";
        }
        if ("red_packet".equals(type)) {
            return "[红包]";
        }
        return "";
    }

    public static String previewForType(String type, String body) {
        return previewForType(type, body, 0);
    }

    public static String previewForType(String type, String body, int burnAfterSeconds) {
        if (isBurnPreview(type, burnAfterSeconds)) {
            return BURN_PREVIEW_TEXT;
        }
        String normalized = type == null ? "" : type.toLowerCase();
        MessagePayload payload = MessagePayload.fromBody(body);
        if ("image".equals(normalized)) {
            if ("emoji".equals(payload.mediaKind)) {
                return "[表情]";
            }
            return "[图片]";
        }
        if ("voice".equals(normalized)) {
            return "[语音]";
        }
        if ("video".equals(normalized)) {
            return "[视频]";
        }
        if ("resource".equals(normalized)) {
            if ("music".equals(payload.mediaKind)) {
                return "[音乐]";
            }
            return "[资源]";
        }
        if ("music".equals(normalized)) {
            return "[音乐]";
        }
        if ("red_packet".equals(normalized)) {
            return "[红包]";
        }
        String textPreview = sanitizeQuoteText(payload.text, 0);
        if (textPreview.length() > 0) {
            return textPreview;
        }
        return sanitizeQuoteText(body, 0);
    }

    public static String quotePreview(String type, String mediaKind, String text) {
        String safeText = sanitizeQuoteText(text, 0);
        if (safeText.length() > 0) {
            return safeText;
        }
        String mediaPreview = quoteMediaPreview(type, mediaKind);
        return mediaPreview.length() > 0 ? mediaPreview : "[消息]";
    }

    /**
     * 旧版本在“引用一条已经带引用的消息”时，可能把整段 MessagePayload JSON
     * 写入 quote.text。这里最多展开三层，只展示被引用消息的正文；若该消息仅有
     * 引用而无正文，则给出稳定的引用摘要，避免原始 JSON 泄漏或空白引用框。
     */
    private static String sanitizeQuoteText(String raw, int depth) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            return "";
        }
        if (!value.startsWith("{") || !value.endsWith("}")) {
            return value;
        }
        if (depth >= 3) {
            return "[引用消息]";
        }
        try {
            JSONObject obj = new JSONObject(value);
            if (!obj.has("v") && !obj.has("text") && !obj.has("quote")
                    && !obj.has("media_kind") && !obj.has("forward_v2")) {
                return "[消息]";
            }
            Object textValue = obj.opt("text");
            if (textValue instanceof String) {
                String ownText = sanitizeQuoteText((String) textValue, depth + 1);
                if (ownText.length() > 0) {
                    return ownText;
                }
            }
            if (obj.optJSONObject("forward_v2") != null) {
                return "[聊天记录]";
            }
            String ownMedia = obj.optString("media_kind", "");
            String mediaText = quoteMediaPreview(obj.optString("type", ""), ownMedia);
            if (mediaText.length() > 0) {
                return mediaText;
            }
            JSONObject nestedQuote = obj.optJSONObject("quote");
            if (nestedQuote != null) {
                String nestedText = sanitizeQuoteText(nestedQuote.optString("text", ""), depth + 1);
                if (nestedText.length() == 0) {
                    nestedText = quoteMediaPreview(nestedQuote.optString("type", ""),
                            nestedQuote.optString("media_kind", ""));
                }
                return nestedText.length() > 0 ? "[引用] " + nestedText : "[引用消息]";
            }
            return "";
        } catch (Exception ignored) {
            // 看起来像结构化消息却无法解析时不再原样显示，避免半截 JSON 进入气泡。
            return "[消息]";
        }
    }

    private static String quoteMediaPreview(String type, String mediaKind) {
        String normalized = type == null ? "" : type.toLowerCase();
        if ("image".equals(normalized)) {
            return "emoji".equals(mediaKind) ? "[表情]" : "[图片]";
        }
        if ("voice".equals(normalized)) {
            return "[语音]";
        }
        if ("video".equals(normalized)) {
            return "[视频]";
        }
        if ("resource".equals(normalized)) {
            return "music".equals(mediaKind) ? "[音乐]" : "[资源]";
        }
        if ("music".equals(normalized)) {
            return "[音乐]";
        }
        if ("red_packet".equals(normalized)) {
            return "[红包]";
        }
        return "";
    }

    public static long normalizeTimestamp(long value) {
        if (value <= 0) {
            return 0;
        }
        if (value < 100000000000L) {
            return value * 1000L;
        }
        return value;
    }

    public static long sanitizeTimestamp(long value) {
        if (value <= 0 || value < 1000000000L) {
            return System.currentTimeMillis() / 1000L;
        }
        return value;
    }

    private static boolean isBurnPreview(String msgType, int burnAfterSeconds) {
        if (!BurnAfterSecondsUtil.isEnabled(burnAfterSeconds)) {
            return false;
        }
        String type = msgType == null ? "text" : msgType.trim().toLowerCase();
        return !"recall".equals(type);
    }
}
