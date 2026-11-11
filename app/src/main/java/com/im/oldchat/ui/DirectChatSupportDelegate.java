package com.im.oldchat.ui;

import android.content.Context;
import android.support.v7.widget.LinearLayoutManager;
import android.view.View;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.MessageFieldRepair;
import com.im.oldchat.util.MyUidStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class DirectChatSupportDelegate {
    private DirectChatSupportDelegate() {
    }

    static Message parseMessageFromResponse(DirectChatListHelperSupport helper, String response) {
        try {
            JSONObject obj = new JSONObject(response);
            Message msg = new Message();
            String id = obj.optString("id", null);
            msg.id = (id != null && !id.isEmpty()) ? id : null;
            msg.thread_id = obj.optString("thread_id", null);
            msg.from_uid = obj.optString("from_uid", helper.myUID);
            msg.sender_ncuid = obj.optString("from_ncuid", "");
            msg.body = obj.optString("body", "");
            msg.msg_type = obj.optString("msg_type", "text");
            msg.media_url = obj.optString("media_url", "");
            msg.thumb_url = obj.optString("thumb_url", "");
            msg.duration_ms = obj.optInt("duration_ms", 0);
            msg.burn_after_seconds = obj.optInt("burn_after_seconds", 0);
            msg.burn_start_at = ChatMessageUtil.sanitizeTimestamp(obj.optLong("burn_start_at", 0L));
            msg.created_at = ChatMessageUtil.sanitizeTimestamp(obj.optLong("created_at", 0));
            msg.sort_seq = obj.optLong("sort_seq", 0L);
            msg.status = ChatMessageUtil.parseStatus(obj);
            MessageFieldRepair.repairDirect(msg);
            BurnMessageUiHelper.prepareDirectMessage(helper.context, msg,
                    MyUidStore.isMyUid(helper.context, msg.from_uid, helper.myUID));
            return msg;
        } catch (Exception e) {
            return null;
        }
    }

    static void appendMessage(DirectChatListHelperSupport helper, Message msg,
                              boolean forceScroll, String token) {
        if (msg == null) {
            return;
        }
        boolean atBottom = forceScroll || helper.isAtBottom();
        if (msg.id != null && helper.messageIds.contains(msg.id)) {
            return;
        }
        helper.messageList.add(msg);
        if (msg.id != null) {
            helper.messageIds.add(msg.id);
        }
        DirectMessageMerger.trimOldestInMemory(helper.messageList, helper.messageIds,
                DirectChatListHelperSupport.MAX_ACTIVE_WINDOW_MESSAGES);
        ArrayList<Integer> changed = ChatMessageUtil.applyMessageStatusAndCollectChanged(
                helper.context, helper.messageList, helper.myUID);
        if (helper.adapter != null) {
            if (MyUidStore.isMyUid(helper.context, msg.from_uid, helper.myUID)) {
                helper.adapter.markMessageAnimating(msg);
            } else {
                helper.adapter.markIncomingMediaAnimating(msg);
            }
            int insertedPosition = helper.messageList.size() - 1;
            helper.adapter.notifyItemInserted(insertedPosition);
            notifyDirectChangedPositions(helper, changed, insertedPosition);
        }
        MessageHistoryCache.saveDirectMessages(helper.context, helper.friendUID, helper.friendNcuid, helper.messageList);
        updateRecentFromMessage(helper, msg, atBottom);
        if (atBottom) {
            scrollToBottom(helper);
            if (msg.from_uid != null && !MyUidStore.isMyUid(helper.context, msg.from_uid, helper.myUID)) {
                markRead(helper, token);
            }
        } else if (msg.from_uid != null && !MyUidStore.isMyUid(helper.context, msg.from_uid, helper.myUID)) {
            onNewMessageReceived(helper);
        }
    }

    static void markRead(DirectChatListHelperSupport helper, String token) {
        if (helper.friendUID == null || helper.friendUID.isEmpty()) {
            return;
        }
        RecentChatCache.clearUnread(helper.context, helper.friendUID);
        hideNewMessageBubbleInternal(helper);
        try {
            JSONObject json = new JSONObject();
            json.put("with_uid", helper.friendUID);
            if (helper.friendNcuid != null && helper.friendNcuid.length() > 0) {
                json.put("with_ncuid", helper.friendNcuid);
            }
            HttpUtil.post("/direct/read", json, token, new HttpUtil.Callback() {
                @Override public void onSuccess(String response) { }
                @Override public void onError(int code, String error) { }
            });
        } catch (Exception e) {
        }
    }

    static void onNewMessageReceived(DirectChatListHelperSupport helper) {
        if (!helper.isAtBottom()) {
            helper.newMessageCount++;
            showNewMessageBubble(helper);
        } else {
            hideNewMessageBubbleInternal(helper);
        }
    }

    static void onUserScroll(DirectChatListHelperSupport helper, boolean atBottom) {
        captureScrollPosition(helper);
        if (atBottom) {
            hideNewMessageBubbleInternal(helper);
            if (helper.initialUnreadCount == 0) {
                helper.hideJumpToUnreadButton();
            }
        }
    }

    static void scrollToBottom(final DirectChatListHelperSupport helper) {
        hideNewMessageBubbleInternal(helper);
        if (helper.lvMessages == null || helper.adapter == null || helper.adapter.getItemCount() <= 0) return;
        helper.lastKnownAtBottom = true;
        final int generation = ++helper.bottomScrollGeneration;
        runBottomCorrection(helper, generation);
        if (com.im.oldchat.util.UiPerformance.shouldReduceMotion(helper.context)) {
            return;
        }
        helper.lvMessages.postDelayed(new Runnable() {
            @Override public void run() { runBottomCorrection(helper, generation); }
        }, 80L);
        helper.lvMessages.postDelayed(new Runnable() {
            @Override public void run() { runBottomCorrection(helper, generation); }
        }, 320L);
    }

    private static void runBottomCorrection(final DirectChatListHelperSupport helper, final int generation) {
        if (!helper.isContextValid() || generation != helper.bottomScrollGeneration || helper.lvMessages == null
                || helper.adapter == null || helper.adapter.getItemCount() <= 0
                || helper.lvMessages.getScrollState() == android.support.v7.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
            return;
        }
        final int last = helper.adapter.getItemCount() - 1;
        helper.lvMessages.scrollToPosition(last);
        if (com.im.oldchat.util.UiPerformance.shouldReduceMotion(helper.context)) {
            hideNewMessageBubbleInternal(helper);
            helper.lastKnownAtBottom = true;
            return;
        }
        helper.lvMessages.post(new Runnable() {
            @Override public void run() {
                if (!helper.isContextValid() || generation != helper.bottomScrollGeneration || helper.lvMessages == null
                        || helper.lvMessages.getScrollState() == android.support.v7.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                    return;
                }
                // 二次吃掉布局、键盘和图片尺寸变化造成的剩余滚动距离。
                helper.lvMessages.scrollBy(0, 1000000);
                hideNewMessageBubbleInternal(helper);
                helper.lastKnownAtBottom = true;
            }
        });
    }

    static void recordScrollPosition(DirectChatListHelperSupport helper) {
        helper.lastKnownAtBottom = helper.isAtBottom();
        captureScrollPosition(helper);
    }

    static LinearLayoutManager getLayoutManager(DirectChatListHelperSupport helper) {
        android.support.v7.widget.RecyclerView.LayoutManager lm = helper.lvMessages.getLayoutManager();
        return lm instanceof LinearLayoutManager ? (LinearLayoutManager) lm : null;
    }

    static void captureScrollPosition(DirectChatListHelperSupport helper) {
        LinearLayoutManager layoutManager = getLayoutManager(helper);
        if (layoutManager == null) {
            return;
        }
        int pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0) {
            return;
        }
        View firstView = layoutManager.findViewByPosition(pos);
        helper.lastFirstVisible = pos;
        helper.lastFirstTop = firstView == null ? 0 : firstView.getTop();
    }

    static void showNewMessageBubble(DirectChatListHelperSupport helper) {
        if (helper.btnNewMessage == null) {
            return;
        }
        if (helper.newMessageCount <= 0 || helper.isAtBottom()) {
            hideNewMessageBubbleInternal(helper);
            return;
        }
        helper.btnNewMessage.clearAnimation();
        helper.btnNewMessage.setClickable(true);
        helper.btnNewMessage.setText(helper.newMessageCount + " 条新消息");
        helper.btnNewMessage.setVisibility(View.VISIBLE);
    }

    static void hideNewMessageBubbleInternal(DirectChatListHelperSupport helper) {
        helper.newMessageCount = 0;
        if (helper.btnNewMessage != null) {
            helper.btnNewMessage.clearAnimation();
            helper.btnNewMessage.setClickable(false);
            helper.btnNewMessage.setVisibility(View.GONE);
        }
    }

    static void updateRecentChat(DirectChatListHelperSupport helper, String lastMessage) {
        if (helper.friendUID == null || helper.friendUID.isEmpty()) {
            return;
        }
        String name = helper.friendName != null ? helper.friendName : helper.friendUID;
        RecentChatCache.updateRecentChat(helper.context, helper.friendUID, helper.friendNcuid, name,
                helper.friendAvatar, lastMessage);
    }

    static void updateRecentFromMessages(DirectChatListHelperSupport helper, List<Message> incoming,
                                         boolean append) {
        if (append || incoming.isEmpty()) {
            return;
        }
        updateRecentChat(helper, ChatMessageUtil.previewForMessage(incoming.get(incoming.size() - 1)));
    }

    static void updateRecentFromMessage(DirectChatListHelperSupport helper, Message msg, boolean atBottom) {
        if (msg == null || helper.friendUID == null || helper.friendUID.isEmpty()) {
            return;
        }
        String preview = ChatMessageUtil.previewForMessage(msg);
        if (msg.from_uid != null && MyUidStore.isMyUid(helper.context, msg.from_uid, helper.myUID)) {
            RecentChatCache.updateRecentChatOutgoing(helper.context, helper.friendUID, helper.friendNcuid,
                    helper.friendName, helper.friendAvatar, preview, msg.created_at);
            return;
        }
        RecentChatCache.updateRecentChatIncoming(helper.context, helper.friendUID, helper.friendNcuid,
                helper.friendName, helper.friendAvatar, preview, msg.created_at, atBottom ? 0 : 1);
    }

    static void loadFromCacheAsync(final DirectChatListHelperSupport helper) {
        final int seq = ++helper.cacheLoadSeq;
        final Context appContext = helper.context != null ? helper.context.getApplicationContext() : null;
        android.os.AsyncTask<Void, Void, List<Message>> task =
                new android.os.AsyncTask<Void, Void, List<Message>>() {
            @Override protected List<Message> doInBackground(Void... voids) {
                return appContext == null ? null : MessageHistoryCache.getDirectMessages(
                        appContext, helper.friendUID, helper.friendNcuid);
            }
            @Override protected void onPostExecute(List<Message> cached) {
                if (!helper.isContextValid() || seq != helper.cacheLoadSeq || cached == null || cached.isEmpty() || !helper.messageList.isEmpty()) {
                    return;
                }
                applyCachedMessages(helper, cached);
            }
        };
        com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task, (Void[]) null);
    }

    static void loadFromCacheSync(DirectChatListHelperSupport helper) {
        if (helper.context == null || !helper.messageList.isEmpty()) {
            return;
        }
        List<Message> cached = MessageHistoryCache.getDirectMessages(
                helper.context, helper.friendUID, helper.friendNcuid);
        if (cached == null || cached.isEmpty()) {
            return;
        }
        applyCachedMessages(helper, cached);
    }

    static void notifyDirectChangedPositions(DirectChatListHelperSupport helper,
                                             List<Integer> changedPositions, int insertedPosition) {
        if (helper.adapter == null || changedPositions == null || changedPositions.isEmpty()) {
            return;
        }
        for (int i = 0; i < changedPositions.size(); i++) {
            Integer boxed = changedPositions.get(i);
            if (boxed == null) {
                continue;
            }
            int position = boxed.intValue();
            if (position < 0 || position == insertedPosition || position >= helper.messageList.size()) {
                continue;
            }
            helper.adapter.notifyItemChanged(position);
        }
    }

    private static void applyCachedMessages(DirectChatListHelperSupport helper, List<Message> cached) {
        helper.hasMore = true;
        helper.messageList.clear();
        helper.messageIds.clear();
        helper.messageList.addAll(cached);
        DirectMessageMerger.sortMessages(helper.messageList);
        for (int i = 0; i < cached.size(); i++) {
            Message msg = cached.get(i);
            if (msg.id != null) {
                helper.messageIds.add(msg.id);
            }
        }
        DirectMessageMerger.trimOldestInMemory(helper.messageList, helper.messageIds,
                DirectChatListHelperSupport.MAX_ACTIVE_WINDOW_MESSAGES);
        helper.currentOffset = helper.messageList.size();
        ChatMessageUtil.applyMessageStatus(helper.context, helper.messageList, helper.myUID);
        if (helper.adapter != null) {
            helper.adapter.notifyItemRangeInserted(0, helper.messageList.size());
        }
        helper.scrollToBottom();
        if (helper.btnLoadMore != null) {
            helper.btnLoadMore.setEnabled(true);
            helper.btnLoadMore.setText(R.string.load_more_messages);
        }
        updateRecentFromMessages(helper, cached, false);
    }
}
