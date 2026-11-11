package com.im.oldchat.ui;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.util.ChatOpenPerfTracer;

import java.util.HashSet;
import java.util.List;

abstract class GroupChatListHelperSupport {
    protected static final int WARM_CACHE_READY_THRESHOLD = 12;
    protected static final int PAGE_LIMIT = 20;
    protected static final int INITIAL_PAGE_LIMIT = 12;
    protected static final int RESUME_REFRESH_EXTRA_LIMIT = 20;
    protected static final int RESUME_REFRESH_MAX_LIMIT = 100;
    protected static final int MAX_INITIAL_EMPTY_RETRY = 1;
    protected static final int MAX_PENDING_JUMP_LOADS = 4;

    protected Context context;
    protected GroupMessageRecyclerView lvMessages;
    protected GroupMessageAdapter adapter;
    protected List<GroupMessage> messageList;
    protected HashSet<String> messageIds;
    protected TextView btnLoadMore;
    protected TextView btnNewMessage;
    protected ProgressBar loadingBar;
    protected String groupId;
    protected String groupName;
    protected String myUID;
    protected boolean isLoadingMore = false;
    protected boolean hasMore = true;
    protected int newMessageCount = 0;
    protected int currentOffset = 0;
    protected long lastMarkReadAt = 0;
    protected TextView btnJumpToUnread;
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
    protected boolean reliableSyncRunning = false;
    protected boolean reliableSyncPending = false;
    protected ChatOpenPerfTracer perfTracer;
    protected boolean lastKnownAtBottom = true;
    protected int bottomScrollGeneration = 0;
    protected boolean disposed = false;

    protected interface CacheLoadListener {
        void onCacheLoadFinished(boolean loaded);
    }

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
        GroupRecentChatCache.RecentGroup recentGroup = GroupRecentChatCache.getGroup(context, groupId);
        if (recentGroup != null && recentGroup.unreadCount > 0) {
            desired = recentGroup.unreadCount + RESUME_REFRESH_EXTRA_LIMIT;
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

    protected boolean isContextValid() {
        return GroupChatSupportDelegate.isContextValid(this);
    }

    protected void scheduleReliableSync(String token) {
        GroupReliableSync.start(this, token);
    }

    public GroupMessage parseMessageFromResponse(String response) {
        return GroupChatSupportDelegate.parseMessageFromResponse(this, response);
    }

    public void appendMessage(GroupMessage msg, boolean forceScroll) {
        GroupChatSupportDelegate.appendMessage(this, msg, forceScroll);
    }

    public void onNewMessageReceived() {
        GroupChatSupportDelegate.onNewMessageReceived(this);
    }

    public void onUserScroll(boolean atBottom) {
        GroupChatSupportDelegate.onUserScroll(this, atBottom);
    }

    public void recordScrollPosition() {
        GroupChatSupportDelegate.recordScrollPosition(this);
    }

    public void scrollToBottom() {
        GroupChatSupportDelegate.scrollToBottom(this);
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

    public void markRead(String token) {
        GroupChatSupportDelegate.markRead(this, token);
    }

    public void hideNewMessageBubble() {
        hideNewMessageBubbleInternal();
    }

    protected void showNewMessageBubble() {
        GroupChatSupportDelegate.showNewMessageBubble(this);
    }

    private void hideNewMessageBubbleInternal() {
        GroupChatSupportDelegate.hideNewMessageBubbleInternal(this);
    }

    public boolean isAtBottom() {
        if (messageList.isEmpty()) {
            lastKnownAtBottom = true;
            return true;
        }
        if (lvMessages == null || lvMessages.getCount() <= 0) {
            return lastKnownAtBottom;
        }
        lastKnownAtBottom = ChatScrollBottomHelper.isAtBottom(lvMessages);
        return lastKnownAtBottom;
    }

    /** 历史消息是否已就绪（首次加载完成）。列表为空时消息到达不应自动已读/滚动。 */
    public boolean isListReady() {
        return messageList != null && !messageList.isEmpty()
                && lvMessages != null && lvMessages.getCount() > 0;
    }

    /** 滚动到底并二次校正（避开历史预取/布局竞争导致滚不到位、新消息看不见）。 */
    public void scrollToBottomCompat() {
        if (lvMessages == null || lvMessages.getCount() <= 0) {
            return;
        }
        final int last = lvMessages.getCount() - 1;
        if (last < 0) {
            return;
        }
        final int generation = ++bottomScrollGeneration;
        lvMessages.setSelection(last);
        lvMessages.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation != bottomScrollGeneration || lvMessages == null
                        || lvMessages.getCount() <= 0) {
                    return;
                }
                lvMessages.setSelection(lvMessages.getCount() - 1);
            }
        }, 80L);
        lvMessages.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation != bottomScrollGeneration || lvMessages == null
                        || lvMessages.getCount() <= 0) {
                    return;
                }
                lvMessages.setSelection(lvMessages.getCount() - 1);
            }
        }, 320L);
    }

    protected void updateRecentFromMessages(List<GroupMessage> incoming, boolean append) {
        GroupChatSupportDelegate.updateRecentFromMessages(this, incoming, append);
    }

    protected void loadFromCacheAsync() {
        GroupChatSupportDelegate.loadFromCacheAsync(this);
    }

    protected void loadFromCacheAsync(CacheLoadListener listener) {
        GroupChatSupportDelegate.loadFromCacheAsync(this, listener);
    }

    protected void loadFromCacheSync() {
    }

    protected void setLoading(boolean loading) {
        if (loadingBar != null) {
            boolean visible = loading && (messageList == null || messageList.isEmpty());
            loadingBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    protected void loadMessagesInternal(String token, boolean append, long before, boolean silent,
                                        boolean keepPosition, boolean cachePhaseDone,
                                        int initialEmptyRetryCount) {
        GroupChatLoadDelegate.loadMessagesInternal(this, token, append, before, silent,
                keepPosition, cachePhaseDone, initialEmptyRetryCount);
    }

    protected void onLoadMoreCompleteForJump() {
    }

    protected String urlEncode(String value) {
        return GroupChatLoadDelegate.urlEncode(value);
    }
}
