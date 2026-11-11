package com.im.oldchat.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * UpdatesDispatchHelper：把账号级事件翻译为现有 WSManager.Listener 回调，
 * 复用旧 UI 路径（ChatWsListener / GroupChatWsListener 等），
 * 避免重复实现消息插入/去重/缓存逻辑。
 */
public final class UpdatesDispatchHelper {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdatesDispatchHelper() {
    }

    /** DIRECT_MESSAGE_NEW：解析为 WSModels.DirectMessage 并分发。 */
    static void dispatchDirectNew(final Context context, final JSONObject message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WSModels.DirectMessage msg = new WSModels.DirectMessage();
                    msg.id = message.optString("id");
                    msg.threadId = message.optString("thread_id");
                    msg.fromUid = message.optString("from_uid");
                    msg.fromNcuid = message.optString("from_ncuid");
                    msg.peerUid = message.optString("to_uid");
                    msg.peerNcuid = message.optString("to_ncuid");
                    msg.body = message.optString("body");
                    msg.msgType = message.optString("msg_type", "text");
                    msg.mediaUrl = message.optString("media_url");
                    msg.thumbUrl = message.optString("thumb_url");
                    msg.originalUrl = message.optString("original_url");
                    msg.durationMs = message.optInt("duration_ms", 0);
                    msg.burnAfterSeconds = message.optInt("burn_after_seconds", 0);
                    msg.createdAt = message.optLong("created_at", 0L);
                    msg.sortSeq = message.optLong("sort_seq", 0L);
                    WSManager.getInstance().dispatchIncomingDirect(msg);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** DIRECT_MESSAGE_RECALL：通过 WSManager 现有撤回分发。 */
    static void dispatchDirectRecall(final Context context, final String messageId, final String peerUid) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WSModels.DirectRecall recall = new WSModels.DirectRecall();
                    recall.messageId = messageId;
                    WSManager.getInstance().dispatchIncomingDirectRecall(recall);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** DIRECT_READ：已读游标（简化：触发最近会话标记，具体已读逻辑在聊天页）。 */
    static void dispatchDirectRead(final Context context, final String peerUid, final String readUpTo) {
        if (context == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    com.im.oldchat.models.RecentChatCache.touchIfPresent(context, peerUid);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** GROUP_POINTER_UPDATE：只更新未读标记；用户打开该群时由水位同步拉取内容。 */
    static void dispatchGroupPointer(final Context context, final String groupId, final long groupSeq,
                                     final String hintType) {
        if (context == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    com.im.oldchat.models.GroupRecentChatCache.markHasUpdate(context, groupId);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** GROUP_MEMBERSHIP_CHANGE：群列表刷新。 */
    static void dispatchGroupMembershipChange(final Context context, final String groupId) {
        if (context == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    com.im.oldchat.models.GroupRecentChatCache.markHasUpdate(context, groupId);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** reset 全量兜底：复用旧 unread 同步。 */
    static void dispatchFullSync(final Context context, final long serverCurrentPts) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = WSManagerSupport.getToken(context);
                    if (token != null && !token.isEmpty()) {
                        WSManager.getInstance().syncUnread(context, token);
                        WSManager.getInstance().syncGroupUnread(context, token);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }
}
