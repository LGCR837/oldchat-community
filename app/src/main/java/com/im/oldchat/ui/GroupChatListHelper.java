package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.ChatOpenPerfTracer;
import java.util.HashSet;
import java.util.List;

public class GroupChatListHelper extends GroupChatListHelperSupport {

    public GroupChatListHelper(Context context, GroupMessageRecyclerView lvMessages, GroupMessageAdapter adapter,
                               List<GroupMessage> messageList, HashSet<String> messageIds,
                               TextView btnLoadMore, TextView btnNewMessage, ProgressBar loadingBar,
                               String groupId, String groupName, String myUID,
                               ChatOpenPerfTracer perfTracer) {
        this.context = context;
        this.lvMessages = lvMessages;
        this.adapter = adapter;
        this.messageList = messageList;
        this.messageIds = messageIds;
        this.btnLoadMore = btnLoadMore;
        this.btnNewMessage = btnNewMessage;
        this.loadingBar = loadingBar;
        this.groupId = groupId;
        this.groupName = groupName;
        this.myUID = myUID;
        this.perfTracer = perfTracer;
    }

    public void setMyUID(String myUID) {
        this.myUID = myUID;
    }

    public void setJumpToUnreadButton(TextView btnJumpToUnread) {
        this.btnJumpToUnread = btnJumpToUnread;
    }

    public void setInitialUnreadCount(int count) {
        if (count <= 8) {
            this.initialUnreadCount = 0;
            if (btnJumpToUnread != null) {
                btnJumpToUnread.clearAnimation();
                btnJumpToUnread.setClickable(false);
                btnJumpToUnread.setVisibility(View.GONE);
            }
            return;
        }
        this.initialUnreadCount = count;
        if (btnJumpToUnread != null) {
            btnJumpToUnread.clearAnimation();
            btnJumpToUnread.setClickable(true);
            btnJumpToUnread.setText(count + " 条未读");
            btnJumpToUnread.setVisibility(View.VISIBLE);
        }
    }

    public void jumpToEarliestUnread(String token) {
        hideNewMessageBubble();
        if (initialUnreadCount <= 0) {
            return;
        }
        int totalMessages = messageList.size();
        int earliestUnreadPos = totalMessages - initialUnreadCount;
        if (earliestUnreadPos < 0) {
            earliestUnreadPos = 0;
        }
        pendingJumpToken = token;
        if (earliestUnreadPos == 0 && hasMore && !isLoadingMore) {
            pendingJumpToUnread = true;
            long before = getOldestTimestamp();
            loadMessages(token, true, before, false);
        } else {
            scrollToPosition(earliestUnreadPos);
            hideJumpToUnreadButton();
        }
    }

    private void scrollToPosition(final int position) {
        cancelPendingBottomScroll();
        if (position < 0 || position >= messageList.size()) {
            return;
        }
        final int headerCount = lvMessages == null ? 0 : lvMessages.getHeaderViewsCount();
        final int listPos = headerCount + position;
        lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
        lvMessages.setSelectionFromTop(listPos, 0);
        lvMessages.post(new Runnable() {
            @Override
            public void run() {
                if (position < 0 || position >= messageList.size()) {
                    return;
                }
                lvMessages.setSelectionFromTop(headerCount + position, 0);
            }
        });
    }

    public void hideJumpToUnreadButton() {
        initialUnreadCount = 0;
        pendingJumpToUnread = false;
        pendingJumpToken = null;
        if (btnJumpToUnread != null) {
            btnJumpToUnread.clearAnimation();
            btnJumpToUnread.setClickable(false);
            btnJumpToUnread.setVisibility(View.GONE);
        }
    }

    protected void onLoadMoreCompleteForJump() {
        if (tryJumpToMessage()) {
            return;
        }
        if (!pendingJumpToUnread) {
            return;
        }
        int totalMessages = messageList.size();
        int earliestUnreadPos = totalMessages - initialUnreadCount;
        if (earliestUnreadPos < 0) {
            earliestUnreadPos = 0;
        }
        if (earliestUnreadPos == 0 && hasMore && !isLoadingMore && pendingJumpToken != null && !pendingJumpToken.isEmpty()) {
            long before = getOldestTimestamp();
            loadMessages(pendingJumpToken, true, before, false);
            return;
        }
        scrollToPosition(earliestUnreadPos);
        hideJumpToUnreadButton();
    }

    public void jumpToMessageId(String token, String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return;
        }
        int pos = findMessagePosition(messageId);
        if (pos >= 0) {
            scrollToPosition(pos);
            if (adapter != null) {
                adapter.highlightJumpMessage(messageId);
            }
            return;
        }
        if (isLoadingMore) {
            pendingJumpMessageId = messageId;
            pendingJumpMessageToken = token;
            pendingJumpAnchorUsed = false;
            pendingJumpLoadCount = 0;
            return;
        }
        if (!canLoadMore() || token == null || token.isEmpty()) {
            Toast.makeText(context, "未找到目标消息，可能已撤回或被清理", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingJumpMessageId = messageId;
        pendingJumpMessageToken = token;
        pendingJumpAnchorUsed = false;
        pendingJumpLoadCount = 1;
        long before = getOldestTimestamp();
        loadMessages(token, true, before, true);
    }

    private int findMessagePosition(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < messageList.size(); i++) {
            GroupMessage msg = messageList.get(i);
            if (msg != null && messageId.equals(msg.id)) {
                return i;
            }
        }
        return -1;
    }

    private boolean tryJumpToMessage() {
        if (pendingJumpMessageId == null || pendingJumpMessageId.isEmpty()) {
            return false;
        }
        int pos = findMessagePosition(pendingJumpMessageId);
        if (pos >= 0) {
            scrollToPosition(pos);
            if (adapter != null) {
                adapter.highlightJumpMessage(pendingJumpMessageId);
            }
            pendingJumpMessageId = null;
            pendingJumpMessageToken = null;
            pendingJumpAnchorUsed = false;
            pendingJumpLoadCount = 0;
            return true;
        }
        if (pendingJumpLoadCount >= MAX_PENDING_JUMP_LOADS) {
            pendingJumpMessageId = null;
            pendingJumpMessageToken = null;
            pendingJumpAnchorUsed = false;
            pendingJumpLoadCount = 0;
            Toast.makeText(context, "引用消息太远，已停止自动溯源", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (hasMore && !isLoadingMore && pendingJumpMessageToken != null && !pendingJumpMessageToken.isEmpty()) {
            pendingJumpLoadCount++;
            long before = getOldestTimestamp();
            loadMessages(pendingJumpMessageToken, true, before, true);
            return true;
        }
        pendingJumpMessageId = null;
        pendingJumpMessageToken = null;
        pendingJumpAnchorUsed = false;
        pendingJumpLoadCount = 0;
        Toast.makeText(context, "未找到目标消息，可能已撤回或被清理", Toast.LENGTH_SHORT).show();
        return true;
    }

    public boolean canLoadMore() {
        return !isLoadingMore && hasMore;
    }

    public long getOldestTimestamp() {
        if (messageList.isEmpty()) {
            return 0;
        }
        return messageList.get(0).created_at;
    }

    public void loadMessages(String token, boolean append, long before, boolean silent) {
        loadMessages(token, append, before, silent, false);
    }

    public void loadMessages(String token, boolean append, long before, boolean silent, boolean keepPosition) {
        loadMessagesInternal(token, append, before, silent, keepPosition, false, 0);
    }

}
