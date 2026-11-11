package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.view.View;
import android.widget.AbsListView;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.util.MessageFieldRepair;
import com.im.oldchat.util.MyUidStore;

import org.json.JSONObject;

import java.util.List;

final class GroupChatSupportDelegate {
    private GroupChatSupportDelegate() {
    }

    static boolean isContextValid(GroupChatListHelperSupport helper) {
        if (helper.disposed || helper.context == null) {
            return false;
        }
        if (helper.context instanceof Activity) {
            Activity activity = (Activity) helper.context;
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                return !activity.isFinishing() && !activity.isDestroyed();
            }
            return !activity.isFinishing();
        }
        return true;
    }

    static GroupMessage parseMessageFromResponse(GroupChatListHelperSupport helper, String response) {
        try {
            JSONObject obj = new JSONObject(response);
            GroupMessage msg = new GroupMessage();
            String id = obj.optString("id", null);
            msg.id = (id != null && !id.isEmpty()) ? id : null;
            msg.group_id = obj.optString("group_id", helper.groupId);
            String fallbackUid = MyUidStore.getCurrentUid(helper.context);
            if (fallbackUid == null || fallbackUid.length() == 0) {
                fallbackUid = helper.myUID;
            }
            msg.from_uid = obj.optString("from_uid", fallbackUid);
            msg.sender_ncuid = obj.optString("from_ncuid",
                    com.im.oldchat.util.NcuidIdentity.getMyNcuid(helper.context));
            msg.body = obj.optString("body", "");
            msg.msg_type = obj.optString("msg_type", "text");
            msg.media_url = obj.optString("media_url", "");
            msg.thumb_url = obj.optString("thumb_url", "");
            msg.duration_ms = obj.optInt("duration_ms", 0);
            msg.burn_after_seconds = com.im.oldchat.util.BurnAfterSecondsUtil.normalize(
                    obj.optInt("burn_after_seconds", 0));
            msg.created_at = ChatMessageUtil.sanitizeTimestamp(obj.optLong("created_at", 0));
            msg.sort_seq = obj.optLong("sort_seq", 0L);
            msg.group_seq = obj.optLong("group_seq", 0L);
            MessageFieldRepair.repairGroup(msg);
            BurnMessageUiHelper.prepareGroupMessage(helper.context, msg,
                    com.im.oldchat.util.NcuidIdentity.isMine(
                            helper.context, msg.sender_ncuid, msg.from_uid, helper.myUID));
            return msg;
        } catch (Exception e) {
            return null;
        }
    }

    static void appendMessage(GroupChatListHelperSupport helper, GroupMessage msg, boolean forceScroll) {
        android.util.Log.w("GroupChatPerf", "appendMessage: type=" + (msg == null ? "null" : msg.msg_type) + " listSize=" + helper.messageList.size());
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
        GroupMessageSyncHelper.trimOldestInMemory(helper.messageList, helper.messageIds,
                GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
        if (helper.adapter != null) {
            if (com.im.oldchat.util.NcuidIdentity.isMine(
                    helper.context, msg.sender_ncuid, msg.from_uid, helper.myUID)) {
                helper.adapter.markMessageAnimating(msg);
            } else {
                helper.adapter.markIncomingMediaAnimating(msg);
            }
            helper.adapter.notifyRecyclerItemInserted(helper.messageList.size() - 1);
        }
        MessageHistoryCache.saveGroupMessages(helper.context, helper.groupId, helper.messageList);
        if (atBottom) {
            helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
            scrollToBottom(helper);
        } else if (!com.im.oldchat.util.NcuidIdentity.isMine(
                helper.context, msg.sender_ncuid, msg.from_uid, helper.myUID)) {
            helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
            onNewMessageReceived(helper);
        }
    }

    static void onNewMessageReceived(GroupChatListHelperSupport helper) {
        if (!helper.isAtBottom()) {
            helper.newMessageCount++;
            showNewMessageBubble(helper);
        } else {
            hideNewMessageBubbleInternal(helper);
        }
    }

    static void onUserScroll(GroupChatListHelperSupport helper, boolean atBottom) {
        if (atBottom) {
            helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
            hideNewMessageBubbleInternal(helper);
            if (helper.initialUnreadCount == 0) {
                helper.hideJumpToUnreadButton();
            }
        } else {
            helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
        }
    }

    static void recordScrollPosition(GroupChatListHelperSupport helper) {
        helper.lastKnownAtBottom = helper.isAtBottom();
    }

    static void scrollToBottom(final GroupChatListHelperSupport helper) {
        hideNewMessageBubbleInternal(helper);
        if (helper.lvMessages == null || helper.lvMessages.getCount() <= 0) return;
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

    private static void runBottomCorrection(final GroupChatListHelperSupport helper, final int generation) {
        if (!helper.isContextValid() || generation != helper.bottomScrollGeneration || helper.lvMessages == null
                || helper.lvMessages.getCount() <= 0
                || helper.lvMessages.getScrollState() == android.support.v7.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
            return;
        }
        helper.lvMessages.scrollToBottomCompat();
        hideNewMessageBubbleInternal(helper);
        helper.lastKnownAtBottom = true;
    }

    static void markRead(GroupChatListHelperSupport helper, String token) {
        if (helper.groupId == null || helper.groupId.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - helper.lastMarkReadAt < 2000) {
            return;
        }
        helper.lastMarkReadAt = now;
        GroupRecentChatCache.clearUnread(helper.context, helper.groupId);
        hideNewMessageBubbleInternal(helper);
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", helper.groupId);
            HttpUtil.post("/groups/read", json, token, new HttpUtil.Callback() {
                @Override public void onSuccess(String response) { }
                @Override public void onError(int code, String error) { }
            });
        } catch (Exception e) {
        }
    }

    static void showNewMessageBubble(GroupChatListHelperSupport helper) {
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

    static void hideNewMessageBubbleInternal(GroupChatListHelperSupport helper) {
        helper.newMessageCount = 0;
        if (helper.btnNewMessage != null) {
            helper.btnNewMessage.clearAnimation();
            helper.btnNewMessage.setClickable(false);
            helper.btnNewMessage.setVisibility(View.GONE);
        }
    }

    static void updateRecentFromMessages(GroupChatListHelperSupport helper, List<GroupMessage> incoming,
                                         boolean append) {
        if (append || incoming.isEmpty()) {
            return;
        }
        GroupMessage newest = incoming.get(incoming.size() - 1);
        GroupRecentChatCache.updateGroupOutgoing(helper.context, helper.groupId, helper.groupName,
                null, ChatMessageUtil.previewForType(newest.msg_type, newest.body,
                        newest.burn_after_seconds), newest.created_at);
    }

    static void loadFromCacheAsync(GroupChatListHelperSupport helper) {
        loadFromCacheAsync(helper, null);
    }

    static void loadFromCacheAsync(final GroupChatListHelperSupport helper,
                                   final GroupChatListHelperSupport.CacheLoadListener listener) {
        final int seq = ++helper.cacheLoadSeq;
        final Context appContext = helper.context != null ? helper.context.getApplicationContext() : null;
        final GroupMessageSyncHelper.MemberMetaSnapshot memberMetaSnapshot = helper.adapter != null
                ? helper.adapter.createMemberMetaSnapshot() : null;
        new AsyncTask<Void, Void, List<GroupMessage>>() {
            @Override protected List<GroupMessage> doInBackground(Void... voids) {
                if (appContext == null) {
                    return null;
                }
                List<GroupMessage> cached = MessageHistoryCache.getGroupMessages(appContext, helper.groupId);
                if (cached != null && !cached.isEmpty()) {
                    GroupMessageSyncHelper.sortMessages(cached);
                    // 首屏只恢复最新一页，先快速显示并稳定定位到底部；更早记录由历史分页加载。
                    int size = cached.size();
                    if (size > GroupChatListHelperSupport.INITIAL_PAGE_LIMIT) {
                        cached = new java.util.ArrayList<GroupMessage>(cached.subList(
                                size - GroupChatListHelperSupport.INITIAL_PAGE_LIMIT, size));
                    }
                    if (memberMetaSnapshot != null) {
                        GroupMessageSyncHelper.prewarmMessages(appContext, cached, memberMetaSnapshot);
                    }
                }
                return cached;
            }
            @Override protected void onPostExecute(List<GroupMessage> cached) {
                if (!helper.isContextValid() || seq != helper.cacheLoadSeq) {
                    return;
                }
                boolean loaded = false;
                if (cached != null && !cached.isEmpty()) {
                    applyCachedMessages(helper, cached);
                    loaded = true;
                }
                if (listener != null) {
                    listener.onCacheLoadFinished(loaded);
                }
            }
        }.execute();
    }

    private static void applyCachedMessages(GroupChatListHelperSupport helper, List<GroupMessage> cached) {
        helper.hasMore = true;
        if (helper.messageList.isEmpty()) {
            helper.messageList.clear();
            helper.messageIds.clear();
            helper.messageList.addAll(cached);
            GroupMessageSyncHelper.sortMessages(helper.messageList);
            helper.currentOffset = helper.messageList.size();
            for (int i = 0; i < cached.size(); i++) {
                GroupMessage msg = cached.get(i);
                if (msg != null && msg.id != null) {
                    helper.messageIds.add(msg.id);
                }
            }
            if (helper.adapter != null) {
                helper.adapter.notifyRecyclerItemRangeInserted(0, helper.messageList.size());
            }
        } else {
            // 活动列表已有实时 WS 或预热消息，执行增序去重合并，绝不冲掉新消息
            boolean changed = false;
            for (int i = 0; i < cached.size(); i++) {
                GroupMessage msg = cached.get(i);
                if (msg != null && msg.id != null && !helper.messageIds.contains(msg.id)) {
                    helper.messageList.add(msg);
                    helper.messageIds.add(msg.id);
                    changed = true;
                }
            }
            if (changed) {
                GroupMessageSyncHelper.sortMessages(helper.messageList);
                helper.currentOffset = helper.messageList.size();
                if (helper.adapter != null) {
                    helper.adapter.notifyDataSetChanged();
                }
            }
        }
        if (helper.context instanceof GroupChatActivityLifecycleA) {
            ((GroupChatActivityLifecycleA) helper.context).prewarmLoadedMemberMeta();
        }
        helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        helper.scrollToBottom();
        if (helper.btnLoadMore != null) {
            helper.btnLoadMore.setEnabled(true);
            helper.btnLoadMore.setText(R.string.load_more_messages);
        }
        updateRecentFromMessages(helper, cached, false);
        if (helper.perfTracer != null && cached.size() >= GroupChatListHelperSupport.WARM_CACHE_READY_THRESHOLD) {
            helper.perfTracer.finish("source=group cache size=" + cached.size());
        }
    }
}
