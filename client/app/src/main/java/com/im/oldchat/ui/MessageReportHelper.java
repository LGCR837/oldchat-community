package com.im.oldchat.ui;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;

public class MessageReportHelper {

    private MessageReportHelper() {
    }

    public static void reportDirectMessage(Context context, Message msg) {
        if (context == null || msg == null) {
            return;
        }
        String targetUid = safe(msg.from_uid);
        String targetNcuid = safe(msg.sender_ncuid);
        if (targetUid.isEmpty() && targetNcuid.isEmpty()) {
            Toast.makeText(context, "无法识别该消息的发送者", Toast.LENGTH_SHORT).show();
            return;
        }
        String senderName = !targetUid.isEmpty() ? targetUid : targetNcuid;
        String evidence = buildDirectEvidence(msg);
        String sourceTag = "来源：私聊消息";
        ReportActivity.start(context, targetUid, targetNcuid, senderName, "", evidence, sourceTag);
    }

    public static void reportGroupMessage(Context context, GroupMessage msg) {
        if (context == null || msg == null) {
            return;
        }
        String targetUid = safe(msg.from_uid);
        String targetNcuid = safe(msg.sender_ncuid);
        if (targetUid.isEmpty() && targetNcuid.isEmpty()) {
            Toast.makeText(context, "无法识别该消息的发送者", Toast.LENGTH_SHORT).show();
            return;
        }
        String senderName = safe(msg.cachedSenderName);
        if (senderName.isEmpty()) {
            senderName = !targetUid.isEmpty() ? targetUid : targetNcuid;
        }
        String evidence = buildGroupEvidence(msg);
        String sourceTag = "来源：群聊 (" + safe(msg.group_id) + ")";
        ReportActivity.start(context, targetUid, targetNcuid, senderName, "", evidence, sourceTag);
    }

    private static String buildDirectEvidence(Message msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("【消息类型】").append(safe(msg.msg_type));
        if (!TextUtils.isEmpty(msg.id)) {
            sb.append("\n【消息编号】").append(msg.id);
        }
        if (msg.created_at > 0) {
            sb.append("\n【发送时间】").append(formatTime(msg.created_at));
        }
        if (!TextUtils.isEmpty(msg.body)) {
            sb.append("\n【消息正文】\n").append(truncate(msg.body, 2000));
        }
        if (!TextUtils.isEmpty(msg.media_url)) {
            sb.append("\n【附件地址】").append(safe(msg.media_url));
        }
        return sb.toString();
    }

    private static String buildGroupEvidence(GroupMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("【群组标识】").append(safe(msg.group_id));
        sb.append("\n【消息类型】").append(safe(msg.msg_type));
        if (!TextUtils.isEmpty(msg.id)) {
            sb.append("\n【消息编号】").append(msg.id);
        }
        if (msg.created_at > 0) {
            sb.append("\n【发送时间】").append(formatTime(msg.created_at));
        }
        if (!TextUtils.isEmpty(msg.body)) {
            sb.append("\n【消息正文】\n").append(truncate(msg.body, 2000));
        }
        if (!TextUtils.isEmpty(msg.media_url)) {
            sb.append("\n【附件地址】").append(safe(msg.media_url));
        }
        return sb.toString();
    }

    private static String formatTime(long timestamp) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
            long ts = timestamp > 1000000000000L ? timestamp : timestamp * 1000L;
            return sdf.format(new java.util.Date(ts));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
