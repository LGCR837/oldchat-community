package com.im.oldchat.ui;

import android.support.v7.widget.RecyclerView;
import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.api.WSModels;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.MessageFieldRepair;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.TypingStatusManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ChatWsListener implements WSManager.Listener {
    private final ChatActivity activity;
    private final String friendUID;
    private String myUID;
    private final HashSet<String> messageIds;
    private final List<Message> messageList;
    private final MessageAdapter adapter;
    private final RecyclerView listView;
    private final DirectChatListHelperSupport listHelper;
    private final Runnable markRead;
    private final Runnable onNewMessage;
    private final Runnable onReconnect;
    private boolean reconnectRefreshPending = false;

    public ChatWsListener(ChatActivity activity, String friendUID, String myUID,
                          HashSet<String> messageIds, List<Message> messageList,
                          MessageAdapter adapter, RecyclerView listView, DirectChatListHelperSupport listHelper,
                          Runnable markRead,
                          Runnable onNewMessage,
                          Runnable onReconnect) {
        this.activity = activity;
        this.friendUID = friendUID;
        this.myUID = myUID;
        this.messageIds = messageIds;
        this.messageList = messageList;
        this.adapter = adapter;
        this.listView = listView;
        this.listHelper = listHelper;
        this.markRead = markRead;
        this.onNewMessage = onNewMessage;
        this.onReconnect = onReconnect;
    }

    public void setMyUID(String myUID) {
        this.myUID = myUID;
    }

    @Override
    public void onDirectMessage(WSModels.DirectMessage message) {
        if (!matchesConversation(message)) {
            return;
        }
        if (message.id != null && messageIds.contains(message.id)) {
            return;
        }
        boolean atBottom = listHelper != null ? listHelper.isAtBottom() : ChatScrollBottomHelper.isAtBottom(listView);
        // 历史尚未加载完成时（列表为空）不自动已读/滚动，避免“消息来了不显示、却被自动已读”。
        boolean listReady = listHelper != null ? listHelper.isListReady()
                : (adapter != null && adapter.getItemCount() > 0);
        atBottom = atBottom && listReady;
        Message m = new Message();
        m.id = message.id;
        m.from_uid = message.fromUid;
        m.sender_ncuid = message.fromNcuid;
        m.body = message.body;
        m.msg_type = message.msgType;
        m.media_url = message.mediaUrl;
        m.thumb_url = message.thumbUrl;
        m.original_url = message.originalUrl;
        m.duration_ms = message.durationMs;
        m.burn_after_seconds = message.burnAfterSeconds;
        m.burn_start_at = ChatMessageUtil.sanitizeTimestamp(message.burnStartAt);
        m.created_at = ChatMessageUtil.sanitizeTimestamp(message.createdAt);
        m.sort_seq = message.sortSeq;
        MessageFieldRepair.repairDirect(m);
        BurnMessageUiHelper.prepareDirectMessage(activity, m, MyUidStore.isMyUid(activity, m.from_uid, myUID));
        if (m.id != null && m.id.length() > 0) {
            messageIds.add(m.id);
        }
        int oldSize = messageList.size();
        messageList.add(m);
        DirectMessageMerger.sortMessages(messageList);
        DirectMessageMerger.trimOldestInMemory(messageList, messageIds, DirectMessageMerger.MAX_ACTIVE_WINDOW_MESSAGES);
        final boolean trimmedHead = messageList.size() < oldSize + 1;
        ArrayList<Integer> changedPositions = ChatMessageUtil.applyMessageStatusAndCollectChanged(activity, messageList, myUID);
        MessageHistoryCache.saveDirectMessages(activity, friendUID, activity.friendNcuid, messageList);
        if (adapter != null) {
            adapter.markIncomingMediaAnimating(m);
        }
        boolean transitioned = false;
        if (adapter != null && trimmedHead) {
            // 裁剪头部和插入发生在同一轮，完整刷新可避免 RecyclerView 计数不一致崩溃。
            adapter.clearTypingForDataReset();
            adapter.notifyDataSetChanged();
            transitioned = true;
        } else if (adapter != null) {
            transitioned = adapter.startTypingTransition(m);
        }
        TypingStatusManager.getInstance().clearTypingOnMessage(friendUID, message.fromUid);
        if (adapter != null && !transitioned) {
            int insertedIndex = messageList.indexOf(m);
            if (insertedIndex >= 0) {
                adapter.notifyItemInserted(insertedIndex);
                for (int i = 0; i < changedPositions.size(); i++) {
                    int changedIndex = changedPositions.get(i).intValue();
                    if (changedIndex >= 0 && changedIndex < messageList.size() && changedIndex != insertedIndex) {
                        adapter.notifyItemChanged(changedIndex);
                    }
                }
            } else {
                adapter.notifyDataSetChanged();
            }
        }
        if (onNewMessage != null) {
            onNewMessage.run();
        }
        if (atBottom) {
            if (listHelper != null) listHelper.scrollToBottom();
            else listView.scrollToPosition(messageList.size() - 1);
            RecentChatCache.clearUnread(activity, friendUID);
            if (markRead != null) {
                markRead.run();
            }
        }
    }

    @Override
    public void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt) {
        boolean sameNcuid = activity.friendNcuid != null && activity.friendNcuid.length() > 0
                && readerNcuid != null && activity.friendNcuid.equalsIgnoreCase(readerNcuid);
        if (!sameNcuid && !samePeer(friendUID, readerUid)) {
            return;
        }
        ArrayList<Integer> changedPositions = new ArrayList<Integer>();
        for (int i = 0; i < messageList.size(); i++) {
            Message msg = messageList.get(i);
            if (MyUidStore.isMyUid(activity, msg.from_uid, myUID)
                    && msg.status != Message.STATUS_READ) {
                msg.status = Message.STATUS_READ;
                changedPositions.add(i);
            }
        }
        if (changedPositions.isEmpty() || adapter == null) {
            return;
        }
        for (int i = 0; i < changedPositions.size(); i++) {
            adapter.notifyItemChanged(changedPositions.get(i).intValue());
        }
    }

    @Override
    public void onDirectRecall(WSModels.DirectRecall recall) {
        if (recall == null || recall.messageId == null || recall.messageId.isEmpty()) {
            return;
        }
        if (!messageIds.contains(recall.messageId)) {
            return;
        }
        for (int i = 0; i < messageList.size(); i++) {
            Message msg = messageList.get(i);
            if (msg != null && recall.messageId.equals(msg.id)) {
                msg.msg_type = "recall";
                if (MyUidStore.isMyUid(activity, recall.fromUid, myUID)) {
                    msg.body = activity.getString(R.string.message_recalled_self);
                } else {
                    msg.body = activity.getString(R.string.message_recalled_other);
                }
                msg.media_url = "";
                msg.thumb_url = "";
                msg.duration_ms = 0;
                MessageHistoryCache.saveDirectMessages(activity, friendUID, activity.friendNcuid, messageList);
                if (adapter != null) {
                    adapter.invalidateQuoteTargetCache();
                    adapter.notifyItemChanged(i);
                }
                break;
            }
        }
    }

    @Override
    public void onGroupMessage(WSModels.GroupMessage message) {
    }

    @Override
    public void onGroupRecall(WSModels.GroupRecall recall) {
    }

    @Override
    public void onTyping(WSModels.TypingEvent event) {
        if (event == null || event.isGroup) {
            return;
        }
        if (!samePeer(friendUID, event.chatId)) {
            return;
        }
        TypingStatusManager.getInstance().handleRemoteTyping(activity, friendUID,
                event.uid, event.isTyping, event.avatarUrl);
    }

    @Override
    public void onPresence(WSModels.PresenceEvent event) {
        if (event == null || friendUID == null) {
            return;
        }
        String normalizedFriend = normalizePeerUid(friendUID);
        String normalizedEvent = normalizePeerUid(event.uid);
        if (normalizedFriend == null || !normalizedFriend.equals(normalizedEvent)) {
            return;
        }
        com.im.oldchat.util.UserPresenceCache.put(event.uid, event.isOnline, event.presenceStatus);
    }

    private String normalizePeerUid(String uid) {
        if (uid == null) return null;
        String s = uid.trim();
        if (s.startsWith("@")) s = s.substring(1);
        return s.toUpperCase(java.util.Locale.US);
    }

    private boolean samePeer(String left, String right) {
        String normalizedLeft = normalizePeerUid(left);
        String normalizedRight = normalizePeerUid(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private boolean matchesConversation(WSModels.DirectMessage message) {
        if (message == null) {
            return false;
        }
        return samePeer(friendUID, message.fromUid) || samePeer(friendUID, message.peerUid);
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        ChatSyncBannerHelper.onConnectionChanged(activity, connected);
        TypingStatusManager typingManager = TypingStatusManager.getInstance();
        if (friendUID == null || friendUID.isEmpty()) {
            return;
        }
        if (connected) {
            if (reconnectRefreshPending && onReconnect != null) {
                reconnectRefreshPending = false;
                onReconnect.run();
            }
            typingManager.stopCheckingTyping(friendUID);
            return;
        }
        reconnectRefreshPending = true;
        if (!SettingsPrefs.isTypingIndicatorEnabled(activity)) {
            typingManager.stopCheckingTyping(friendUID);
            return;
        }
        android.content.SharedPreferences prefs = activity.getSharedPreferences("auth",
                android.content.Context.MODE_PRIVATE);
        String token = prefs.getString("access_token", "");
        typingManager.startCheckingTyping(activity, token, friendUID, false);
    }
}
