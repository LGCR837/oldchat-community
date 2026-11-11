package com.im.oldchat.api;

import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.BurnAfterSecondsUtil;

public final class WSMessagePreview {
    private static final String BURN_PREVIEW_TEXT = "(阅后即焚)";

    private WSMessagePreview() {
    }

    public static String direct(WSModels.DirectMessage msg) {
        if (msg == null) {
            return "";
        }
        if (isBurnPreview(msg.msgType, msg.burnAfterSeconds)) {
            return BURN_PREVIEW_TEXT;
        }
        String type = msg.msgType == null ? "" : msg.msgType.toLowerCase();
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

    public static String group(WSModels.GroupMessage msg) {
        if (msg == null) {
            return "";
        }
        if (isBurnPreview(msg.msgType, msg.burnAfterSeconds)) {
            return BURN_PREVIEW_TEXT;
        }
        String type = msg.msgType == null ? "" : msg.msgType.toLowerCase();
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

    private static boolean isBurnPreview(String msgType, int burnAfterSeconds) {
        if (!BurnAfterSecondsUtil.isEnabled(burnAfterSeconds)) {
            return false;
        }
        String type = msgType == null ? "text" : msgType.trim().toLowerCase();
        return !"recall".equals(type);
    }
}
