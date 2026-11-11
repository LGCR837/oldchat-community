package com.im.oldchat.util;

import com.im.oldchat.api.WSModels;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;

public final class MessageFieldRepair {
    private MessageFieldRepair() {
    }

    public static boolean repairDirect(Message msg) {
        if (msg == null) {
            return false;
        }
        if (!shouldRotate(msg.from_uid, msg.body, msg.msg_type)) {
            return false;
        }
        String originalFrom = msg.from_uid;
        msg.from_uid = msg.msg_type;
        msg.msg_type = msg.body;
        msg.body = originalFrom;
        return true;
    }

    public static boolean repairGroup(GroupMessage msg) {
        if (msg == null) {
            return false;
        }
        if (!shouldRotate(msg.from_uid, msg.body, msg.msg_type)) {
            return false;
        }
        String originalFrom = msg.from_uid;
        msg.from_uid = msg.msg_type;
        msg.msg_type = msg.body;
        msg.body = originalFrom;
        return true;
    }

    public static boolean repairDirect(WSModels.DirectMessage msg) {
        if (msg == null) {
            return false;
        }
        if (!shouldRotate(msg.fromUid, msg.body, msg.msgType)) {
            return false;
        }
        String originalFrom = msg.fromUid;
        msg.fromUid = msg.msgType;
        msg.msgType = msg.body;
        msg.body = originalFrom;
        return true;
    }

    public static boolean repairGroup(WSModels.GroupMessage msg) {
        if (msg == null) {
            return false;
        }
        if (!shouldRotate(msg.fromUid, msg.body, msg.msgType)) {
            return false;
        }
        String originalFrom = msg.fromUid;
        msg.fromUid = msg.msgType;
        msg.msgType = msg.body;
        msg.body = originalFrom;
        return true;
    }

    private static boolean shouldRotate(String fromUid, String body, String msgType) {
        if (fromUid == null || fromUid.isEmpty()) {
            return false;
        }
        if (body == null || body.isEmpty()) {
            return false;
        }
        if (msgType == null || msgType.isEmpty()) {
            return false;
        }
        if (!isKnownType(body)) {
            return false;
        }
        if (isKnownType(msgType)) {
            return false;
        }
        if (!looksLikeUid(msgType)) {
            return false;
        }
        return !looksLikeUid(fromUid);
    }

    private static boolean isKnownType(String value) {
        if (value == null) {
            return false;
        }
        String type = value.toLowerCase();
        return "text".equals(type)
                || "image".equals(type)
                || "voice".equals(type)
                || "video".equals(type)
                || "resource".equals(type)
                || "music".equals(type)
                || "red_packet".equals(type)
                || "recall".equals(type);
    }

    private static boolean looksLikeUid(String value) {
        if (value == null) {
            return false;
        }
        String uid = value.trim();
        int len = uid.length();
        if (len < 4 || len > 32) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = uid.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                continue;
            }
            if (c == '_' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }
}
