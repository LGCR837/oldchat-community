package com.im.oldchat.ui;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.GroupSyncWatermarkStore;
import com.im.oldchat.models.MessageHistoryCache;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** WebSocket 负责低延迟，本类用 HTTP 水位补偿保证后台断线后最终不漏消息。 */
final class GroupReliableSync {
    private static final int PAGE_LIMIT = 100;

    private GroupReliableSync() { }

    static void start(final GroupChatListHelperSupport helper, final String token) {
        if (helper == null || helper.context == null || helper.groupId == null || helper.groupId.length() == 0
                || token == null || token.length() == 0) return;
        if (helper.reliableSyncRunning) {
            helper.reliableSyncPending = true;
            return;
        }
        helper.reliableSyncRunning = true;
        helper.reliableSyncPending = false;
        GroupSyncWatermarkStore.Watermark watermark = GroupSyncWatermarkStore.get(
                helper.context, helper.groupId, helper.messageIds);
        if (watermark.seq > 0L) {
            pullAfter(helper, token, watermark.seq);
        } else {
            pullLegacyUntilOverlap(helper, token, oldestAnchors(helper.messageList),
                    0L, "", 0L, "", true);
        }
    }

    private static void pullAfter(final GroupChatListHelperSupport helper, final String token, final long afterSeq) {
        String path = "/v2/groups/messages/after?group_id=" + encode(helper.groupId)
                + "&after_seq=" + afterSeq + "&limit=" + PAGE_LIMIT;
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override public void onSuccess(String response) {
                if (!helper.isContextValid()) { finish(helper, token); return; }
                try {
                    JSONObject root = new JSONObject(response);
                    long serverSeq = root.optLong("server_group_seq", -1L);
                    if (serverSeq >= 0L && serverSeq < afterSeq) {
                        GroupSyncWatermarkStore.clear(helper.context, helper.groupId);
                        pullLegacyUntilOverlap(helper, token, oldestAnchors(helper.messageList),
                                0L, "", 0L, "", true);
                        return;
                    }
                    List<GroupMessage> incoming = GroupMessageSyncHelper.parseIncoming(response);
                    // 普通历史解析会把服务端倒序页翻成升序；after 接口本身已经是升序，因此翻回。
                    java.util.Collections.reverse(incoming);
                    long next = root.optLong("next_group_seq", afterSeq);
                    boolean hasMore = root.optBoolean("has_more", false);
                    if (!incoming.isEmpty()) mergePage(helper, incoming);
                    String anchor = findMessageIdForSeq(incoming, next);
                    if (anchor.length() == 0) anchor = newestMessageId(incoming);
                    if (anchor.length() == 0) anchor = newestMessageId(helper.messageList);
                    if (next > afterSeq && anchor.length() > 0) {
                        MessageHistoryCache.saveGroupMessages(helper.context, helper.groupId, helper.messageList);
                        GroupSyncWatermarkStore.save(helper.context, helper.groupId, next, anchor);
                    }
                    if (hasMore) {
                        if (next <= afterSeq) { finish(helper, token); return; }
                        pullAfter(helper, token, next);
                    } else {
                        finish(helper, token);
                    }
                } catch (Exception e) {
                    finish(helper, token);
                }
            }
            @Override public void onError(int code, String error) {
                if (code == 404 || code == 405) {
                    GroupSyncWatermarkStore.clear(helper.context, helper.groupId);
                    pullLegacyUntilOverlap(helper, token, oldestAnchors(helper.messageList), 0L, "", 0L, "", true);
                } else {
                    finish(helper, token);
                }
            }
        });
    }

    /** 旧服务器兜底：从最新页不断向前拉，直到与开始同步时的本地 ID 相交。 */
    private static void pullLegacyUntilOverlap(final GroupChatListHelperSupport helper, final String token,
                                               final HashSet<String> originalAnchors,
                                               final long beforeCreatedAt, final String beforeId,
                                               final long highestSeq, final String highestId,
                                               final boolean firstPage) {
        StringBuilder path = new StringBuilder("/v2/groups/messages/v2?group_id=")
                .append(encode(helper.groupId)).append("&limit=").append(PAGE_LIMIT)
                .append("&offset=0&mark_read=0");
        if (!firstPage && beforeCreatedAt > 0L && beforeId.length() > 0) {
            path.append("&before_created_at=").append(beforeCreatedAt)
                    .append("&before_id=").append(encode(beforeId));
        }
        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override public void onSuccess(String response) {
                if (!helper.isContextValid()) { finish(helper, token); return; }
                try {
                    JSONObject root = new JSONObject(response);
                    List<GroupMessage> incoming = GroupMessageSyncHelper.parseIncoming(response);
                    boolean overlap = originalAnchors.isEmpty();
                    for (GroupMessage message : incoming) {
                        if (message != null && message.id != null && originalAnchors.contains(message.id)) overlap = true;
                    }
                    long pageHighest = highestSeq;
                    String pageHighestId = highestId;
                    for (GroupMessage message : incoming) {
                        if (message != null && message.group_seq > pageHighest) {
                            pageHighest = message.group_seq;
                            pageHighestId = message.id == null ? "" : message.id;
                        }
                    }
                    if (!incoming.isEmpty()) mergePage(helper, incoming);
                    boolean hasMore = root.has("has_more")
                            ? root.optBoolean("has_more", false) : incoming.size() >= PAGE_LIMIT;
                    if (overlap || !hasMore || incoming.isEmpty()) {
                        if (pageHighest > 0L && pageHighestId.length() > 0) {
                            MessageHistoryCache.saveGroupMessages(helper.context, helper.groupId, helper.messageList);
                            GroupSyncWatermarkStore.save(helper.context, helper.groupId, pageHighest, pageHighestId);
                            pullAfter(helper, token, pageHighest);
                        } else {
                            finish(helper, token);
                        }
                        return;
                    }
                    GroupMessage oldest = incoming.get(0);
                    if (oldest.created_at <= 0L || oldest.id == null || oldest.id.length() == 0
                            || (oldest.created_at == beforeCreatedAt && oldest.id.equals(beforeId))) {
                        finish(helper, token);
                        return;
                    }
                    pullLegacyUntilOverlap(helper, token, originalAnchors, oldest.created_at, oldest.id,
                            pageHighest, pageHighestId, false);
                } catch (Exception e) {
                    finish(helper, token);
                }
            }
            @Override public void onError(int code, String error) { finish(helper, token); }
        });
    }

    private static void mergePage(GroupChatListHelperSupport helper, List<GroupMessage> incoming) {
        GroupMessageSyncHelper.mergeRefresh(helper.messageList, helper.messageIds, incoming,
                PAGE_LIMIT, false);
        GroupMessageSyncHelper.sortMessages(helper.messageList);
        GroupMessageSyncHelper.trimOldestInMemory(helper.messageList, helper.messageIds,
                GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
        // 多页补偿期间只更新内存，最终完成时统一刷新 UI，避免每 100 条全量抖动。
    }

    private static HashSet<String> oldestAnchors(List<GroupMessage> messages) {
        HashSet<String> anchors = new HashSet<String>();
        if (messages == null) return anchors;
        // 只用活动窗口最早的一小段作为交点，不能把刚刷到的最新页当交点，
        // 否则离线新增超过一页时会过早停止，留下中间缺口。
        for (int i = 0; i < messages.size() && anchors.size() < 20; i++) {
            GroupMessage message = messages.get(i);
            if (message != null && !message.localPending && message.id != null && message.id.length() > 0) {
                anchors.add(message.id);
            }
        }
        return anchors;
    }

    private static String newestMessageId(List<GroupMessage> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            GroupMessage message = messages.get(i);
            if (message != null && !message.localPending && message.id != null && message.id.length() > 0) return message.id;
        }
        return "";
    }

    private static String findMessageIdForSeq(List<GroupMessage> messages, long seq) {
        if (messages == null || seq <= 0L) return "";
        for (GroupMessage message : messages) {
            if (message != null && message.group_seq == seq && message.id != null) return message.id;
        }
        return "";
    }

    private static void finish(GroupChatListHelperSupport helper, String token) {
        helper.reliableSyncRunning = false;
        if (helper.isContextValid()) {
            // 只有用户仍处于跟随底部模式时才在同步完成后重新钉住底部；
            // 用户主动上滑会把 transcriptMode 改为 DISABLED，不应被强制拉回。
            boolean keepBottom = helper.lvMessages != null
                    && helper.lvMessages.getTranscriptMode()
                    == android.widget.AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL;
            if (helper.adapter != null) helper.adapter.notifyDataSetChangedNow();
            if (keepBottom) helper.scrollToBottom();
            helper.updateRecentFromMessages(helper.messageList, false);
            MessageHistoryCache.saveGroupMessages(helper.context, helper.groupId, helper.messageList);
        }
        if (helper.reliableSyncPending && helper.isContextValid()) {
            helper.reliableSyncPending = false;
            start(helper, token);
        } else {
            helper.reliableSyncPending = false;
        }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception ignored) { return value == null ? "" : value; }
    }
}
