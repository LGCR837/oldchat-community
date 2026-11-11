package com.im.oldchat.ui;

import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.api.WSModels;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.util.MessageFieldRepair;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.TypingStatusManager;
import java.util.HashSet;
import java.util.List;

public class GroupChatWsListener implements WSManager.Listener {
    private final GroupChatActivity activity;
    private final String groupId;
    private final java.util.HashSet<String> recentlyLookedUp = new java.util.HashSet<String>();
    private long lastLookupAtMs;
    private final GroupChatListHelper listHelper;
    private final HashSet<String> messageIds;
    private final List<GroupMessage> messageList;
    private final GroupMessageAdapter adapter;
    private final GroupMessageRecyclerView listView;
    private final Runnable onNewMessage;
    private final Runnable markRead;
    private final Runnable onReconnect;
    private boolean reconnectRefreshPending = false;

    public GroupChatWsListener(GroupChatActivity activity, String groupId, GroupChatListHelper listHelper,
                               HashSet<String> messageIds, List<GroupMessage> messageList,
                               GroupMessageAdapter adapter, GroupMessageRecyclerView listView,
                               Runnable onNewMessage, Runnable markRead,
                               Runnable onReconnect) {
        this.activity = activity;
        this.groupId = groupId;
        this.listHelper = listHelper;
        this.messageIds = messageIds;
        this.messageList = messageList;
        this.adapter = adapter;
        this.listView = listView;
        this.onNewMessage = onNewMessage;
        this.markRead = markRead;
        this.onReconnect = onReconnect;
    }

    @Override
    public void onDirectMessage(WSModels.DirectMessage message) {
    }

    @Override
    public void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt) {
    }

    @Override
    public void onDirectRecall(WSModels.DirectRecall recall) {
    }

    /**
     * 批量处理：所有消息插入完成后只做一次保存 + 通知
     */
    @Override
    public void onGroupMessagesBatch(java.util.List<WSModels.GroupMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        boolean atBottom = listHelper != null ? listHelper.isAtBottom() : ChatScrollBottomHelper.isAtBottom(listView);
        // 历史尚未加载完成时（列表为空）不自动已读/滚动，避免“消息来了不显示、却被自动已读”。
        boolean listReady = listHelper != null ? listHelper.isListReady()
                : (adapter != null && !messageList.isEmpty());
        atBottom = atBottom && listReady;
        boolean anyInserted = false;
        int firstInserted = -1;
        boolean anyRemovedHead = false;
        GroupMessage lastMsg = null;
        for (WSModels.GroupMessage message : messages) {
            if (!sameGroupId(groupId, message == null ? null : message.groupId)) continue;
            if (message.id != null && messageIds.contains(message.id)) continue;
            GroupMessage msg = new GroupMessage();
            msg.id = message.id;
            msg.group_id = message.groupId;
            msg.from_uid = message.fromUid;
            msg.sender_ncuid = message.fromNcuid;
            msg.body = message.body;
            msg.msg_type = message.msgType;
            msg.media_url = message.mediaUrl;
            msg.thumb_url = message.thumbUrl;
            msg.original_url = message.originalUrl;
            msg.duration_ms = message.durationMs;
            msg.burn_after_seconds = message.burnAfterSeconds;
            msg.created_at = ChatMessageUtil.sanitizeTimestamp(message.createdAt);
            msg.sort_seq = message.sortSeq;
            msg.group_seq = message.groupSeq;
            // 缓存 sender 名字（服务端返回 from_name），pending 渲染用。
            // 同时按 uid 和 ncuid 两个 key 写入：渲染端可能用任一作为查询键。
            if (message.fromName != null && !message.fromName.isEmpty()) {
                if (message.fromUid != null && !message.fromUid.isEmpty()) {
                    com.im.oldchat.models.UserNameCache.put(activity, message.fromUid, message.fromName);
                }
                if (message.fromNcuid != null && !message.fromNcuid.isEmpty()) {
                    com.im.oldchat.models.UserNameCache.put(activity, message.fromNcuid, message.fromName);
                }
                if (message.fromUid != null && message.fromNcuid != null
                        && !message.fromUid.isEmpty() && !message.fromNcuid.isEmpty()) {
                    // 记录 uid↔ncuid 映射，供后续 resolve 使用
                    com.im.oldchat.util.MyUidStore.recordUidAlias(activity, message.fromUid, message.fromNcuid);
                }
            }
            MessageFieldRepair.repairGroup(msg);
            boolean isMine = com.im.oldchat.util.NcuidIdentity.isMine(activity, msg.sender_ncuid, msg.from_uid, MyUidStore.getCurrentUid(activity));
            BurnMessageUiHelper.prepareGroupMessage(activity, msg, isMine);
            if (isMine && GroupPendingMessageHelper.tryApplyServerMessage(listHelper, msg)) continue;
            GroupMessageInsertHelper.InsertResult r = GroupMessageInsertHelper.insertIntoActiveWindow(
                    messageList, messageIds, msg, GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
            if (!r.inserted) continue;
            anyInserted = true;
            if (firstInserted < 0) firstInserted = r.insertedIndex;
            if (r.removedHead) anyRemovedHead = true;
            lastMsg = msg;
            if (adapter != null) {
                if (isMine) adapter.markMessageAnimating(msg);
                else adapter.markIncomingMediaAnimating(msg);
            }
            TypingStatusManager.getInstance().clearTypingOnMessage(groupId, message.fromUid);
        }
        if (!anyInserted) return;
        MessageHistoryCache.saveGroupMessages(activity, groupId, messageList);
        // 批量消息里的新发送者可能不在本地缓存：统一轻量补查一次。
        if (lastMsg != null) {
            maybeLookupSender(lastMsg);
        }
        // 只做一次通知
        if (adapter != null) {
            if (anyRemovedHead) adapter.notifyRecyclerItemRemoved(0);
            adapter.notifyDataSetChanged();
        }
        if (onNewMessage != null) onNewMessage.run();
        if (atBottom) {
            if (listHelper != null) listHelper.scrollToBottomCompat();
            else {
                int listLastPos = listView.getCount() - 1;
                if (listLastPos >= 0) listView.setSelection(listLastPos);
            }
            GroupRecentChatCache.clearUnread(activity, groupId);
            if (markRead != null) markRead.run();
        }
    }

    @Override
    public void onGroupMessage(WSModels.GroupMessage message) {
        long _t0 = android.os.SystemClock.uptimeMillis();
        if (!sameGroupId(groupId, message == null ? null : message.groupId)) {
            return;
        }
        if (message.id != null && messageIds.contains(message.id)) {
            return;
        }
        boolean atBottom = listHelper != null ? listHelper.isAtBottom() : ChatScrollBottomHelper.isAtBottom(listView);
        // 历史尚未加载完成时（列表为空）不自动已读/滚动，避免“消息来了不显示、却被自动已读”。
        boolean listReady = listHelper != null ? listHelper.isListReady()
                : (adapter != null && !messageList.isEmpty());
        atBottom = atBottom && listReady;
        GroupMessage msg = new GroupMessage();
        msg.id = message.id;
        msg.group_id = message.groupId;
        msg.from_uid = message.fromUid;
        msg.sender_ncuid = message.fromNcuid;
        msg.body = message.body;
        msg.msg_type = message.msgType;
        msg.media_url = message.mediaUrl;
        msg.thumb_url = message.thumbUrl;
        msg.original_url = message.originalUrl;
        msg.duration_ms = message.durationMs;
        msg.burn_after_seconds = message.burnAfterSeconds;
        msg.created_at = ChatMessageUtil.sanitizeTimestamp(message.createdAt);
        msg.sort_seq = message.sortSeq;
        msg.group_seq = message.groupSeq;
        MessageFieldRepair.repairGroup(msg);
        boolean isMine = com.im.oldchat.util.NcuidIdentity.isMine(activity, msg.sender_ncuid, msg.from_uid, MyUidStore.getCurrentUid(activity));
        BurnMessageUiHelper.prepareGroupMessage(activity, msg, isMine);
        if (isMine && GroupPendingMessageHelper.tryApplyServerMessage(listHelper, msg)) {
            TypingStatusManager.getInstance().clearTypingOnMessage(groupId, message.fromUid);
            return;
        }
        GroupMessageInsertHelper.InsertResult insertResult = GroupMessageInsertHelper.insertIntoActiveWindow(
                messageList,
                messageIds,
                msg,
                GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
        if (!insertResult.inserted) {
            TypingStatusManager.getInstance().clearTypingOnMessage(groupId, message.fromUid);
            return;
        }
        MessageHistoryCache.saveGroupMessages(activity, groupId, messageList);
        // 新消息发送者可能不在本地缓存（很久没发言的人）：立即轻量补查，避免显示 NCUID/无头像。
        maybeLookupSender(msg);
        if (adapter != null) {
            if (isMine) {
                adapter.markMessageAnimating(msg);
            } else {
                adapter.markIncomingMediaAnimating(msg);
            }
        }
        boolean transitioned = adapter != null && adapter.startTypingTransition(msg);
        TypingStatusManager.getInstance().clearTypingOnMessage(groupId, message.fromUid);
        if (adapter != null && !transitioned) {
            if (insertResult.removedHead) {
                adapter.notifyRecyclerItemRemoved(0);
            }
            if (insertResult.insertedIndex >= 0) {
                adapter.notifyRecyclerItemInserted(insertResult.insertedIndex);
            } else {
                adapter.notifyDataSetChanged();
            }
        }
        if (onNewMessage != null) {
            onNewMessage.run();
        }
        if (atBottom) {
            if (listHelper != null) listHelper.scrollToBottomCompat();
            else {
                int listLastPos = listView.getCount() - 1;
                if (listLastPos >= 0) {
                    listView.setSelection(listLastPos);
                }
            }
            GroupRecentChatCache.clearUnread(activity, groupId);
            if (markRead != null) {
                markRead.run();
            }
        }
        long _t1 = android.os.SystemClock.uptimeMillis();
        if (_t1 - _t0 > 4) {
            android.util.Log.w("GroupChatPerf", "onGroupMessage: " + (_t1-_t0) + "ms id=" + (message == null ? "null" : message.id));
        }
    }

    @Override
    public void onGroupRecall(WSModels.GroupRecall recall) {
        if (recall == null || recall.messageId == null || recall.messageId.isEmpty()) {
            return;
        }
        if (!sameGroupId(groupId, recall.groupId)) {
            return;
        }
        if (!messageIds.contains(recall.messageId)) {
            return;
        }
        for (int i = 0; i < messageList.size(); i++) {
            GroupMessage msg = messageList.get(i);
            if (msg != null && recall.messageId.equals(msg.id)) {
                String name = resolveName(recall.fromUid);
                msg.msg_type = "recall";
                msg.body = activity.getString(R.string.message_recalled_member, name);
                msg.media_url = "";
                msg.thumb_url = "";
                msg.duration_ms = 0;
                MessageHistoryCache.saveGroupMessages(activity, groupId, messageList);
                if (adapter != null) {
                    adapter.invalidateQuoteTargetCache();
                    adapter.notifyRecyclerItemChanged(i);
                }
                break;
            }
        }
    }

    @Override
    public void onTyping(WSModels.TypingEvent event) {
        if (event == null || !event.isGroup) {
            return;
        }
        if (!sameGroupId(groupId, event.chatId)) {
            return;
        }
        String identity = event.ncuid != null && event.ncuid.length() > 0
                ? event.ncuid : event.uid;
        if (event.uid != null && event.uid.length() > 0
                && event.ncuid != null && event.ncuid.length() > 0) {
            com.im.oldchat.util.UidAliasStore.putAlias(activity, event.uid, event.ncuid);
        }
        TypingStatusManager.getInstance().handleRemoteTyping(activity, groupId,
                identity, event.isTyping, event.avatarUrl);
    }

    @Override
    public void onPresence(WSModels.PresenceEvent event) {
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        ChatSyncBannerHelper.onConnectionChanged(activity, connected);
        TypingStatusManager typingManager = TypingStatusManager.getInstance();
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        if (connected) {
            if (reconnectRefreshPending && onReconnect != null) {
                reconnectRefreshPending = false;
                onReconnect.run();
            }
            typingManager.stopCheckingTyping(groupId);
            return;
        }
        reconnectRefreshPending = true;
        if (!SettingsPrefs.isTypingIndicatorEnabled(activity)) {
            typingManager.stopCheckingTyping(groupId);
            return;
        }
        android.content.SharedPreferences prefs = activity.getSharedPreferences("auth",
                android.content.Context.MODE_PRIVATE);
        String token = prefs.getString("access_token", "");
        typingManager.startCheckingTyping(activity, token, groupId, true);
    }

    private String resolveName(String uid) {
        if (uid == null || uid.isEmpty()) {
            return "成员";
        }
        String name = UserNameCache.getName(activity, uid);
        if (name == null || name.isEmpty()) {
            name = uid;
        }
        return name;
    }

    /** 新消息发送者未缓存头像时立即轻量补查（防抖：同一发送者 30 秒内只查一次）。 */
    private void maybeLookupSender(GroupMessage msg) {
        if (activity == null || msg == null) return;
        String ncuid = msg.sender_ncuid;
        if (ncuid == null || ncuid.length() == 0) return;
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (recentlyLookedUp) {
            if (recentlyLookedUp.contains(ncuid)) return;
            if (now - lastLookupAtMs < 500L) return;
            recentlyLookedUp.add(ncuid);
            lastLookupAtMs = now;
            if (recentlyLookedUp.size() > 64) {
                recentlyLookedUp.clear();
                recentlyLookedUp.add(ncuid);
            }
        }
        android.content.SharedPreferences prefs = activity.getSharedPreferences("auth",
                android.content.Context.MODE_PRIVATE);
        String token = prefs.getString("access_token", "");
        if (token == null || token.length() == 0) return;
        java.util.ArrayList<String> one = new java.util.ArrayList<String>();
        one.add(ncuid);
        GroupMemberLookup.lookup(activity, groupId, token, one, new GroupMemberLookup.Callback() {
            @Override
            public void onResult(java.util.List<com.im.oldchat.models.GroupMember> fetched) {
                if (fetched == null || fetched.isEmpty() || activity.isFinishing()) return;
                final com.im.oldchat.models.GroupMember fresh = fetched.get(0);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || adapter == null) return;
                        adapter.onMemberMetaResolved(fresh);
                    }
                });
            }

            @Override
            public void onError() {
            }
        });
    }

    private boolean sameGroupId(String left, String right) {
        String normalizedLeft = normalizeGroupId(left);
        String normalizedRight = normalizeGroupId(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String normalizeGroupId(String value) {
        if (value == null) {
            return null;
        }
        String out = value.trim();
        if (out.length() == 0) {
            return null;
        }
        return out.toUpperCase(java.util.Locale.US);
    }
}
