package com.im.oldchat.api;

import android.content.Context;
import android.content.SharedPreferences;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.data.NotificationReadStore;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.AppState;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.CryptoUtil;
import com.im.oldchat.util.MessageFieldRepair;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.NcuidIdentity;
import com.im.oldchat.util.NotificationHelper;
import org.json.JSONObject;

public class WSIncomingHandler {
    public interface Callback {
        void onDirectMessage(WSModels.DirectMessage message);
        void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt);
        void onDirectRecall(WSModels.DirectRecall recall);
        void onGroupMessage(WSModels.GroupMessage message);
        void onGroupRecall(WSModels.GroupRecall recall);
        void onTyping(WSModels.TypingEvent event);
        void onPresence(WSModels.PresenceEvent event);
    }

    public void handleMessage(Context context, String raw, Callback callback) {
        if (callback == null || raw == null) {
            return;
        }
        try {
            String decrypted = CryptoUtil.decryptIfNeeded(raw);
            if (decrypted != null) {
                raw = decrypted;
            }
            JSONObject obj = new JSONObject(raw);
            String type = obj.optString("type");
            JSONObject data = obj.optJSONObject("data");
            if (data == null) {
                return;
            }
            if ("direct_message".equals(type)) {
                WSModels.DirectMessage msg = parseDirectMessage(data);
                MessageFieldRepair.repairDirect(msg);
                if (context != null) {
                    RecentChatCache.updateRecentChatIncoming(context, msg.peerUid, msg.fromNcuid, null, null,
                            WSMessagePreview.direct(msg), msg.createdAt, 1);
                    String imageUrl = null;
                    String msgType = msg.msgType == null ? "" : msg.msgType.toLowerCase();
                    if ("image".equals(msgType)) {
                        imageUrl = (msg.thumbUrl != null && !msg.thumbUrl.isEmpty()) ? msg.thumbUrl : msg.mediaUrl;
                    }
                    if (!AppState.isForeground()) {
                        NotificationHelper.notifyDirect(context, msg.peerUid, msg.fromNcuid,
                                WSMessagePreview.direct(msg), imageUrl);
                    }
                }
                callback.onDirectMessage(msg);
            } else if ("account_event".equals(type)) {
                // 账号级 pts 事件流（新协议）：交给 UpdatesManager 做连续性校验与分发。
                // 同时仍以兼容方式解析旧字段，保证老客户端行为不变。
                long pts = data.optLong("pts", 0L);
                int ptsCount = data.optInt("pts_count", 1);
                String eventType = data.optString("type", "");
                JSONObject payload = data.optJSONObject("payload");
                if (context != null && pts > 0L) {
                    UpdatesManager.getInstance().onWsAccountEvent(pts, ptsCount, eventType, payload);
                }
            } else if ("direct_read".equals(type)) {
                String threadId = data.optString("thread_id");
                String readerUid = data.optString("reader_uid");
                String readerNcuid = data.optString("reader_ncuid");
                long readAt = data.optLong("read_at");
                callback.onDirectRead(threadId, readerUid, readerNcuid, readAt);
            } else if ("direct_recall".equals(type)) {
                callback.onDirectRecall(parseDirectRecall(data));
            } else if ("group_message".equals(type)) {
                WSModels.GroupMessage msg = parseGroupMessage(data);
                MessageFieldRepair.repairGroup(msg);
                if (context != null) {
                    boolean firstSeen = GroupRecentChatCache.markGroupMessageSeen(context, msg.id);
                    if (firstSeen) {
                        GroupRecentChatCache.updateGroupIncoming(context, msg.groupId, null, null,
                                WSMessagePreview.group(msg), msg.createdAt, 1);
                    }
                    if (firstSeen && isMentionForMe(context, msg)) {
                        GroupRecentChatCache.setMentionUnread(context, msg.groupId, true);
                    }
                    String imageUrl = null;
                    String msgType = msg.msgType == null ? "" : msg.msgType.toLowerCase();
                    if ("image".equals(msgType)) {
                        imageUrl = (msg.thumbUrl != null && !msg.thumbUrl.isEmpty()) ? msg.thumbUrl : msg.mediaUrl;
                    }
                    if (!AppState.isForeground()) {
                        NotificationHelper.notifyGroup(context, msg.groupId, msg.fromUid, WSMessagePreview.group(msg), imageUrl);
                    }
                }
                callback.onGroupMessage(msg);
            } else if ("group_recall".equals(type)) {
                callback.onGroupRecall(parseGroupRecall(data));
            } else if ("channel_update".equals(type)) {
                if (context != null) {
                    final String channelId = data.optString("channel_id", "");
                    String token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                            .getString("access_token", "");
                    if (channelId.length() > 0 && token.length() > 0) {
                        ChannelSyncManager.syncPosts(context, token, channelId, new ChannelSyncManager.Callback() {
                            @Override public void onComplete(boolean success) { }
                        });
                        ChannelSyncManager.syncStates(context, token, true, new ChannelSyncManager.Callback() {
                            @Override public void onComplete(boolean success) { }
                        });
                    }
                }
            } else if ("system_notification".equals(type)) {
                if (context != null) {
                    String id = data.optString("id", "");
                    String title = data.optString("title", "");
                    String body = data.optString("body", "");
                    if (title == null || title.length() == 0) {
                        title = "系统通知";
                    }
                    if (body == null) {
                        body = "";
                    }
                    incrementSystemNotificationUnread(context, id);
                    if (!AppState.isForeground()) {
                        NotificationHelper.notifySystem(context, id, title, body);
                    }
                }
            } else if ("typing".equals(type)) {
                callback.onTyping(parseTypingEvent(data));
            } else if ("presence".equals(type)) {
                callback.onPresence(parsePresenceEvent(data));
            }
        } catch (Exception e) {
        }
    }

    private void incrementSystemNotificationUnread(Context context, String notificationId) {
        if (context == null) {
            return;
        }
        String id = notificationId == null ? "" : notificationId;
        if (id.length() > 0 && NotificationReadStore.isRead(context, id)) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences("notification", Context.MODE_PRIVATE);
        if (id.length() > 0) {
            String lastId = prefs.getString("last_notification_id", "");
            if (id.equals(lastId)) {
                return;
            }
        }
        int unread = prefs.getInt("unread_count", 0);
        if (unread < 0) {
            unread = 0;
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("unread_count", unread + 1);
        if (id.length() > 0) {
            editor.putString("last_notification_id", id);
        }
        editor.apply();
    }

    private WSModels.DirectMessage parseDirectMessage(JSONObject data) {
        WSModels.DirectMessage msg = new WSModels.DirectMessage();
        msg.id = data.optString("id");
        msg.threadId = data.optString("thread_id");
        msg.fromUid = data.optString("from_uid");
        msg.fromNcuid = data.optString("from_ncuid");
        msg.body = data.optString("body");
        msg.msgType = data.optString("msg_type", "text");
        msg.mediaUrl = data.optString("media_url");
        msg.thumbUrl = data.optString("thumb_url");
        msg.originalUrl = data.optString("original_url");
        msg.durationMs = data.optInt("duration_ms", 0);
        msg.burnAfterSeconds = BurnAfterSecondsUtil.normalize(data.optInt("burn_after_seconds", 0));
        msg.burnStartAt = data.optLong("burn_start_at", 0L);
        msg.createdAt = data.optLong("created_at");
        msg.sortSeq = data.optLong("sort_seq", 0L);
        msg.peerUid = msg.fromUid;
        return msg;
    }

    private WSModels.GroupMessage parseGroupMessage(JSONObject data) {
        WSModels.GroupMessage msg = new WSModels.GroupMessage();
        msg.id = data.optString("id");
        msg.groupId = data.optString("group_id");
        msg.fromUid = data.optString("from_uid");
        msg.fromNcuid = data.optString("from_ncuid");
        msg.body = data.optString("body");
        msg.msgType = data.optString("msg_type", "text");
        msg.mediaUrl = data.optString("media_url");
        msg.thumbUrl = data.optString("thumb_url");
        msg.originalUrl = data.optString("original_url");
        msg.durationMs = data.optInt("duration_ms", 0);
        msg.burnAfterSeconds = BurnAfterSecondsUtil.normalize(data.optInt("burn_after_seconds", 0));
        msg.createdAt = data.optLong("created_at");
        msg.sortSeq = data.optLong("sort_seq", 0L);
        msg.groupSeq = data.optLong("group_seq", 0L);
        return msg;
    }

    private WSModels.DirectRecall parseDirectRecall(JSONObject data) {
        WSModels.DirectRecall recall = new WSModels.DirectRecall();
        recall.messageId = data.optString("message_id");
        recall.threadId = data.optString("thread_id");
        recall.fromUid = data.optString("from_uid");
        return recall;
    }

    private WSModels.GroupRecall parseGroupRecall(JSONObject data) {
        WSModels.GroupRecall recall = new WSModels.GroupRecall();
        recall.messageId = data.optString("message_id");
        recall.groupId = data.optString("group_id");
        recall.fromUid = data.optString("from_uid");
        return recall;
    }

    private WSModels.TypingEvent parseTypingEvent(JSONObject data) {
        WSModels.TypingEvent event = new WSModels.TypingEvent();
        event.chatId = data.optString("chat_id");
        event.uid = data.optString("uid");
        event.ncuid = data.optString("ncuid");
        event.avatarUrl = data.optString("avatar_url");
        event.isGroup = data.optBoolean("is_group", false);
        event.isTyping = data.optBoolean("is_typing", false);
        return event;
    }

    private WSModels.PresenceEvent parsePresenceEvent(JSONObject data) {
        WSModels.PresenceEvent event = new WSModels.PresenceEvent();
        event.uid = data.optString("uid");
        event.isOnline = data.optBoolean("is_online", false);
        event.presenceStatus = data.optString("presence_status", "online");
        return event;
    }

    private boolean isMentionForMe(Context context, WSModels.GroupMessage msg) {
        if (context == null || msg == null) {
            return false;
        }
        String myUid = getMyUid(context);
        if (myUid == null || myUid.isEmpty()) {
            return false;
        }
        if (msg.fromUid != null && msg.fromUid.equals(myUid)) {
            return false;
        }
        MessagePayload payload = MessagePayload.fromBody(msg.body);
        // 优先按不可变 NCUID 匹配（改 UID 后旧 @ 仍能识别），hasMention 内部自动回退 UID。
        String myNcuid = NcuidIdentity.getMyNcuid(context);
        if (myNcuid != null && !myNcuid.isEmpty()) {
            return payload.hasMention(myNcuid);
        }
        return payload.hasMention(myUid);
    }

    private String getMyUid(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
        return prefs.getString("my_uid", "");
    }
}
