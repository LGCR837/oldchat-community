package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.models.Message;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.ChatOpenPerfTracer;

import java.util.HashSet;
import java.util.List;

abstract class DirectChatListHelperSupport {
    protected static final int PAGE_LIMIT = 20;
    protected static final int INITIAL_PAGE_LIMIT = 12;
    protected static final int RESUME_REFRESH_EXTRA_LIMIT = 20;
    protected static final int RESUME_REFRESH_MAX_LIMIT = 100;
    protected static final int MAX_INITIAL_EMPTY_RETRY = 1;
    protected static final int MAX_ACTIVE_WINDOW_MESSAGES = DirectMessageMerger.MAX_ACTIVE_WINDOW_MESSAGES;
    protected static final int MAX_PENDING_JUMP_LOADS = 4;

    protected Context context;
    protected RecyclerView lvMessages;
    protected MessageAdapter adapter;
    protected List<Message> messageList;
    protected HashSet<String> messageIds;
    protected TextView btnLoadMore;
    protected TextView btnNewMessage;
    protected ProgressBar loadingBar;
    protected String friendUID;
    protected String friendNcuid;
    protected String friendName;
    protected String friendAvatar;
    protected String myUID;
    protected TextView btnJumpToUnread;
    protected boolean isLoadingMore = false;
    protected boolean hasMore = true;
    protected int newMessageCount = 0;
    protected int currentOffset = 0;
    protected int initialUnreadCount = 0;
    protected boolean pendingJumpToUnread = false;
    protected String pendingJumpToken = null;
    protected String pendingJumpMessageId = null;
    protected String pendingJumpMessageToken = null;
    protected boolean pendingJumpAnchorUsed = false;
    protected int pendingJumpLoadCount = 0;
    protected int cacheLoadSeq = 0;
    protected int remoteLoadSeq = 0;
    protected boolean cursorPaginationDisabled = false;
    protected boolean legacyHistoryRoute = false;
    protected int lastFirstVisible = 0;
    protected int lastFirstTop = 0;
    protected boolean lastKnownAtBottom = true;
    protected int bottomScrollGeneration = 0;
    protected boolean disposed = false;
    protected ChatOpenPerfTracer perfTracer;

    protected abstract void hideJumpToUnreadButton();

    protected int resolveRequestPageLimit(boolean append, boolean keepPosition) {
        if (append) {
            return PAGE_LIMIT;
        }
        if (!keepPosition) {
            return INITIAL_PAGE_LIMIT;
        }
        return resolveResumeRefreshLimit();
    }

    protected int resolveResumeRefreshLimit() {
        int desired = PAGE_LIMIT;
        RecentChatCache.RecentChat recentChat = RecentChatCache.getChat(context, friendUID);
        if (recentChat != null && recentChat.unreadCount > 0) {
            desired = recentChat.unreadCount + RESUME_REFRESH_EXTRA_LIMIT;
        }
        if (initialUnreadCount > desired) {
            desired = initialUnreadCount + RESUME_REFRESH_EXTRA_LIMIT;
        }
        if (desired < PAGE_LIMIT) {
            desired = PAGE_LIMIT;
        }
        if (desired > RESUME_REFRESH_MAX_LIMIT) {
            desired = RESUME_REFRESH_MAX_LIMIT;
        }
        return desired;
    }

    public Message parseMessageFromResponse(String response) {
        return DirectChatSupportDelegate.parseMessageFromResponse(this, response);
    }

    public void appendMessage(Message msg, boolean forceScroll, String token) {
        DirectChatSupportDelegate.appendMessage(this, msg, forceScroll, token);
    }

    public void markRead(String token) {
        DirectChatSupportDelegate.markRead(this, token);
    }

    public void onNewMessageReceived() {
        DirectChatSupportDelegate.onNewMessageReceived(this);
    }

    public void onUserScroll(boolean atBottom) {
        DirectChatSupportDelegate.onUserScroll(this, atBottom);
    }

    public void scrollToBottom() {
        DirectChatSupportDelegate.scrollToBottom(this);
    }

    protected void cancelPendingBottomScroll() {
        bottomScrollGeneration++;
    }

    public void dispose() {
        disposed = true;
        remoteLoadSeq++;
        cacheLoadSeq++;
        bottomScrollGeneration++;
    }

    protected boolean isContextValid() {
        if (disposed || context == null) return false;
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                return !activity.isFinishing() && !activity.isDestroyed();
            }
            return !activity.isFinishing();
        }
        return true;
    }

    public void recordScrollPosition() {
        DirectChatSupportDelegate.recordScrollPosition(this);
    }

    protected LinearLayoutManager getLayoutManager() {
        return DirectChatSupportDelegate.getLayoutManager(this);
    }

    protected void captureScrollPosition() {
        DirectChatSupportDelegate.captureScrollPosition(this);
    }

    public void hideNewMessageBubble() {
        hideNewMessageBubbleInternal();
    }

    protected void showNewMessageBubble() {
        DirectChatSupportDelegate.showNewMessageBubble(this);
    }

    protected void hideNewMessageBubbleInternal() {
        DirectChatSupportDelegate.hideNewMessageBubbleInternal(this);
    }

    public boolean isAtBottom() {
        if (messageList.isEmpty()) {
            lastKnownAtBottom = true;
            return true;
        }
        if (lvMessages == null || adapter == null || adapter.getItemCount() <= 0) {
            return lastKnownAtBottom;
        }
        lastKnownAtBottom = ChatScrollBottomHelper.isAtBottom(lvMessages);
        return lastKnownAtBottom;
    }

    /** 历史消息是否已就绪（首次加载完成）。列表为空时消息到达不应自动已读/滚动。 */
    public boolean isListReady() {
        return messageList != null && !messageList.isEmpty()
                && adapter != null && adapter.getItemCount() > 0;
    }

    protected void updateRecentChat(String lastMessage) {
        DirectChatSupportDelegate.updateRecentChat(this, lastMessage);
    }

    protected void updateRecentFromMessages(List<Message> incoming, boolean append) {
        DirectChatSupportDelegate.updateRecentFromMessages(this, incoming, append);
    }

    protected void updateRecentFromMessage(Message msg, boolean atBottom) {
        DirectChatSupportDelegate.updateRecentFromMessage(this, msg, atBottom);
    }

    protected void loadFromCacheAsync() {
        DirectChatSupportDelegate.loadFromCacheAsync(this);
    }

    protected void loadFromCacheSync() {
        DirectChatSupportDelegate.loadFromCacheSync(this);
    }

    protected void setLoading(boolean loading) {
        if (loadingBar != null) {
            boolean visible = loading && (messageList == null || messageList.isEmpty());
            loadingBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    protected void notifyDirectChangedPositions(List<Integer> changedPositions, int insertedPosition) {
        DirectChatSupportDelegate.notifyDirectChangedPositions(this, changedPositions, insertedPosition);
    }

    protected void loadMessagesInternal(String token, boolean append, long before, boolean silent,
                                        boolean keepPosition, boolean cachePhaseDone,
                                        int initialEmptyRetryCount) {
        DirectChatLoadDelegate.loadMessagesInternal(this, token, append, before, silent,
                keepPosition, cachePhaseDone, initialEmptyRetryCount);
    }

    protected void onLoadMoreCompleteForJump() {
    }

    protected String urlEncode(String value) {
        return DirectChatLoadDelegate.urlEncode(value);
    }
}
