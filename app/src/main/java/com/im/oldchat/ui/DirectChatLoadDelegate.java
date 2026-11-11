package com.im.oldchat.ui;

import android.support.v7.widget.LinearLayoutManager;
import android.view.View;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.NetworkStateManager;

import java.net.URLEncoder;
import java.util.HashSet;
import java.util.List;

final class DirectChatLoadDelegate {
    private DirectChatLoadDelegate() {}

    static void loadMessagesInternal(final DirectChatListHelperSupport helper, String token, boolean append,
                                     long before, boolean silent, boolean keepPosition,
                                     boolean cachePhaseDone, int initialEmptyRetryCount) {
        final boolean appendFinal = append;
        final boolean silentFinal = silent;
        final boolean keepPositionFinal = keepPosition;
        final String tokenFinal = token;
        final long beforeFinal = before;

        // 缓存读取和 Gson 反序列化不得阻塞 Activity 首帧；缓存与网络并行，
        // 网络先返回时缓存回调会因列表非空自动丢弃。
        if (!appendFinal && !keepPositionFinal && !cachePhaseDone && helper.messageList.isEmpty()) {
            helper.loadFromCacheAsync();
            loadMessagesInternal(helper, tokenFinal, appendFinal, beforeFinal, silentFinal,
                    keepPositionFinal, true, initialEmptyRetryCount);
            return;
        }
        if ((appendFinal && !NetworkStateManager.getInstance().isServerAvailable()) || tokenFinal == null || tokenFinal.isEmpty()) {
            helper.setLoading(false);
            return;
        }
        if (appendFinal) {
            helper.isLoadingMore = true;
        } else {
            helper.currentOffset = 0;
            if (!keepPositionFinal) {
                helper.cursorPaginationDisabled = false;
            }
        }

        final int requestOffset = helper.currentOffset;
        final int requestPageLimit = helper.resolveRequestPageLimit(appendFinal, keepPositionFinal);
        final int requestSeq = ++helper.remoteLoadSeq;
        final long requestStartedAtMs = helper.perfTracer == null ? 0L : helper.perfTracer.now();
        helper.setLoading(true);

        int firstVisible = helper.lastFirstVisible;
        int firstTop = helper.lastFirstTop;
        LinearLayoutManager layoutManager = helper.getLayoutManager();
        if (layoutManager != null) {
            int pos = layoutManager.findFirstVisibleItemPosition();
            if (pos >= 0) {
                View firstView = layoutManager.findViewByPosition(pos);
                firstVisible = pos;
                firstTop = firstView == null ? 0 : firstView.getTop();
            }
        }
        final int firstVisibleFinal = firstVisible;
        final int firstTopFinal = firstTop;
        final LinearLayoutManager layoutManagerFinal = layoutManager;

        StringBuilder path = new StringBuilder(helper.legacyHistoryRoute
                ? "/direct/messages?with_uid=" : "/v2/direct/messages/v2?with_uid=");
        path.append(urlEncode(helper.friendUID));
        if (helper.friendNcuid != null && helper.friendNcuid.length() > 0) {
            path.append("&with_ncuid=").append(urlEncode(helper.friendNcuid));
        }
        path.append("&limit=").append(requestPageLimit);
        path.append("&offset=").append(requestOffset);
        if (!helper.legacyHistoryRoute && appendFinal && !helper.cursorPaginationDisabled
                && (helper.pendingJumpMessageId == null
                || helper.pendingJumpMessageId.isEmpty())) {
            Message oldest = findOldestCursorMessage(helper.messageList);
            if (oldest != null) {
                path.append("&before_created_at=").append(oldest.created_at);
                path.append("&before_id=").append(urlEncode(oldest.id));
            }
        }
        if (!helper.legacyHistoryRoute && appendFinal && helper.pendingJumpMessageId != null
                && !helper.pendingJumpMessageId.isEmpty() && !helper.pendingJumpAnchorUsed) {
            path.append("&anchor_message_id=").append(urlEncode(helper.pendingJumpMessageId));
            helper.pendingJumpAnchorUsed = true;
        }

        HttpUtil.get(path.toString(), tokenFinal, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String response) {
                if (!helper.isContextValid()) return;
                android.os.AsyncTask<Void, Void, DirectMessageLoadResult> task =
                        new android.os.AsyncTask<Void, Void, DirectMessageLoadResult>() {
                            @Override
                            protected DirectMessageLoadResult doInBackground(Void... voids) {
                                return DirectMessageParser.parseLoadResult(response, requestOffset);
                            }

                            @Override
                            protected void onPostExecute(DirectMessageLoadResult loadResult) {
                                if (!helper.isContextValid() || requestSeq != helper.remoteLoadSeq) {
                                    return;
                                }
                                try {
                                    applyResult(helper, loadResult, appendFinal, keepPositionFinal,
                                            requestPageLimit, tokenFinal, firstVisibleFinal,
                                            firstTopFinal, layoutManagerFinal, requestStartedAtMs);
                                } catch (Exception e) {
                                    if (!silentFinal) {
                                        Toast.makeText(helper.context, "加载消息失败", Toast.LENGTH_SHORT).show();
                                    }
                                } finally {
                                    if (helper.context instanceof ChatActivityLifecycle) {
                                        ((ChatActivityLifecycle) helper.context).hideSyncBanner();
                                    }
                                }
                                helper.setLoading(false);
                                helper.isLoadingMore = false;
                                if (appendFinal) {
                                    helper.onLoadMoreCompleteForJump();
                                }
                            }
                        };
                com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task, (Void[]) null);
            }

            @Override
            public void onError(int code, String error) {
                if (!helper.isContextValid() || requestSeq != helper.remoteLoadSeq) {
                    return;
                }
                helper.setLoading(false);
                helper.isLoadingMore = false;
                if (code == 404 && !helper.legacyHistoryRoute) {
                    // v2 强制测试：不再回退 v1，直接暴露 v2 接口 404（确认 v2 兼容性缺口）。
                    // 旧版这里会 legacyHistoryRoute=true 回退 /direct/messages；v2-only 测试禁用。
                }
                helper.pendingJumpToUnread = false;
                helper.pendingJumpMessageId = null;
                helper.pendingJumpMessageToken = null;
                helper.pendingJumpAnchorUsed = false;
                helper.pendingJumpLoadCount = 0;
                // 隐藏同步消息横幅
                if (helper.context instanceof ChatActivityLifecycle) {
                    ((ChatActivityLifecycle) helper.context).hideSyncBanner();
                }
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                if (code == 404 && isExplicitUserNotFound(error)) {
                    Toast.makeText(helper.context, "该用户不存在，可能已更改UID", Toast.LENGTH_LONG).show();
                    RecentChatCache.removeChat(helper.context, helper.friendUID);
                    if (helper.context instanceof ChatActivity) {
                        ((ChatActivity) helper.context).finish();
                    }
                    return;
                }
                if (!appendFinal && helper.messageList.isEmpty()) {
                    helper.loadFromCacheAsync();
                }
                if (!silentFinal) {
                    Toast.makeText(helper.context, "同步失败: " + code, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private static boolean isExplicitUserNotFound(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.trim().toLowerCase(java.util.Locale.US);
        return lower.contains("user_not_found")
                || lower.contains("user not found")
                || lower.contains("用户不存在");
    }

    private static void applyResult(DirectChatListHelperSupport helper,
                                    DirectMessageLoadResult loadResult,
                                    boolean appendFinal,
                                    boolean keepPositionFinal,
                                    int requestPageLimit,
                                    String tokenFinal,
                                    int firstVisibleFinal,
                                    int firstTopFinal,
                                    LinearLayoutManager layoutManagerFinal,
                                    long requestStartedAtMs) throws Exception {
        if (loadResult == null || loadResult.error != null || loadResult.incoming == null) {
            throw loadResult != null && loadResult.error != null
                    ? loadResult.error : new Exception("empty result");
        }
        List<Message> incoming = loadResult.incoming;
        int effectiveOffset = loadResult.effectiveOffset;
        int previousSize = helper.messageList.size();
        int newIncomingCount = countNewIncoming(helper, incoming, appendFinal, keepPositionFinal);

        if (appendFinal) {
            applyAppend(helper, incoming, requestPageLimit, effectiveOffset, previousSize,
                    loadResult.hasMore, firstVisibleFinal, firstTopFinal, layoutManagerFinal);
        } else {
            applyRefresh(helper, incoming, requestPageLimit, effectiveOffset, loadResult.hasMore,
                    keepPositionFinal, tokenFinal, firstVisibleFinal, firstTopFinal, layoutManagerFinal);
        }
        helper.updateRecentFromMessages(helper.messageList, false);
        MessageHistoryCache.saveDirectMessages(helper.context, helper.friendUID, helper.friendNcuid, helper.messageList);
        if (!appendFinal && keepPositionFinal && newIncomingCount > 0) {
            helper.newMessageCount += newIncomingCount;
            helper.showNewMessageBubble();
        }
        if (helper.perfTracer != null && !appendFinal) {
            helper.perfTracer.traceFrom("ui_apply_done",
                    "source=direct append=" + appendFinal + " keep="
                            + keepPositionFinal + " size=" + helper.messageList.size(),
                    requestStartedAtMs);
            helper.perfTracer.finish("source=direct size=" + helper.messageList.size());
        }
        // 隐藏同步消息横幅
        if (helper.context instanceof ChatActivityLifecycle) {
            ((ChatActivityLifecycle) helper.context).hideSyncBanner();
        }
    }

    private static int countNewIncoming(DirectChatListHelperSupport helper, List<Message> incoming,
                                        boolean appendFinal, boolean keepPositionFinal) {
        if (appendFinal || !keepPositionFinal || incoming.isEmpty()) {
            return 0;
        }
        int count = 0;
        HashSet<String> existingIds = new HashSet<String>(helper.messageIds);
        for (int i = 0; i < incoming.size(); i++) {
            Message msg = incoming.get(i);
            if (msg == null || msg.id == null || existingIds.contains(msg.id)) {
                continue;
            }
            if (msg.from_uid == null || !MyUidStore.isMyUid(helper.context, msg.from_uid, helper.myUID)) {
                count++;
            }
        }
        return count;
    }

    private static void applyAppend(DirectChatListHelperSupport helper, List<Message> incoming,
                                    int requestPageLimit, int effectiveOffset, int previousSize,
                                    Boolean responseHasMore, int firstVisibleFinal, int firstTopFinal,
                                    LinearLayoutManager layoutManagerFinal) {
        // 网络返回前用户可能继续滚动，真正前插前重新捕获当前锚点。
        int anchorPosition = firstVisibleFinal;
        int anchorTop = firstTopFinal;
        if (layoutManagerFinal != null) {
            int current = layoutManagerFinal.findFirstVisibleItemPosition();
            if (current >= 0) {
                View anchorView = layoutManagerFinal.findViewByPosition(current);
                anchorPosition = current;
                anchorTop = anchorView == null ? 0 : anchorView.getTop();
            }
        }
        DirectMessageMergeResult mergeResult = DirectMessageMerger.mergeAppend(
                helper.messageList, helper.messageIds, incoming, helper.currentOffset, requestPageLimit);
        helper.currentOffset = Math.max(mergeResult.currentOffset, effectiveOffset + incoming.size());
        helper.hasMore = responseHasMore != null
                ? responseHasMore.booleanValue() : mergeResult.hasMore;
        int insertedCount = helper.messageList.size() - previousSize;
        if (insertedCount < 0) {
            insertedCount = 0;
        }
        if (insertedCount == 0 && incoming != null && !incoming.isEmpty()) {
            // 游标页若全部重复，下一次自动回退 OFFSET，避免用户反复点击没有变化。
            helper.cursorPaginationDisabled = true;
            helper.currentOffset = effectiveOffset;
            helper.hasMore = true;
        }
        if (helper.adapter != null) {
            if (insertedCount > 0) {
                helper.adapter.notifyItemRangeInserted(0, insertedCount);
            }
            // 重复页没有任何可见变化，不触发全量重绑。
        }
        if (!helper.hasMore && helper.btnLoadMore != null) {
            helper.btnLoadMore.setEnabled(false);
            helper.btnLoadMore.setText(R.string.no_more_messages);
        }
        int targetPos = anchorPosition + insertedCount;
        if (targetPos < 0) {
            targetPos = 0;
        }
        if (!helper.messageList.isEmpty() && targetPos >= helper.messageList.size()) {
            targetPos = helper.messageList.size() - 1;
        }
        if (!helper.messageList.isEmpty()) {
            if (layoutManagerFinal != null) {
                layoutManagerFinal.scrollToPositionWithOffset(targetPos, anchorTop);
            } else {
                helper.lvMessages.scrollToPosition(targetPos);
            }
        }
    }

    private static void applyRefresh(DirectChatListHelperSupport helper, List<Message> incoming,
                                     int requestPageLimit, int effectiveOffset, Boolean responseHasMore,
                                     boolean keepPositionFinal, String tokenFinal,
                                     int firstVisibleFinal, int firstTopFinal,
                                     LinearLayoutManager layoutManagerFinal) {
        String currentAnchorId = null;
        int currentAnchorTop = firstTopFinal;
        if (keepPositionFinal && layoutManagerFinal != null) {
            int currentPos = layoutManagerFinal.findFirstVisibleItemPosition();
            if (currentPos >= 0 && currentPos < helper.messageList.size()) {
                Message anchor = helper.messageList.get(currentPos);
                currentAnchorId = anchor == null ? null : anchor.id;
                View anchorView = layoutManagerFinal.findViewByPosition(currentPos);
                if (anchorView != null) currentAnchorTop = anchorView.getTop();
            }
        }
        // 空结果守卫：服务端返回空时不覆盖已有消息（可能是瞬时异常或消息被焚毁），
        // 保留 WS 实时插入的消息，避免“主页有未读但进入后不显示”。
        if (incoming == null || incoming.isEmpty()) {
            if (helper.adapter != null && !helper.messageList.isEmpty()) {
                helper.adapter.notifyDataSetChanged();
            }
            if (helper.btnLoadMore != null) {
                helper.btnLoadMore.setEnabled(helper.hasMore);
                helper.btnLoadMore.setText(helper.hasMore
                        ? R.string.load_more_messages : R.string.no_more_messages);
            }
            if (!keepPositionFinal) helper.markRead(tokenFinal);
            return;
        }
        // 合并而非清空：保留 WS 实时插入的消息，避免竞态丢消息。
        boolean hasMore = responseHasMore != null
                ? responseHasMore.booleanValue() : incoming.size() >= requestPageLimit;
        DirectMessageMerger.mergeRefresh(helper.messageList, helper.messageIds,
                incoming, requestPageLimit, hasMore);
        DirectMessageMerger.sortMessages(helper.messageList);
        DirectMessageMerger.trimOldestInMemory(helper.messageList, helper.messageIds,
                DirectChatListHelperSupport.MAX_ACTIVE_WINDOW_MESSAGES);
        helper.currentOffset = Math.max(helper.messageList.size(), effectiveOffset + incoming.size());
        helper.hasMore = hasMore;
        ChatMessageUtil.applyMessageStatus(helper.context, helper.messageList, helper.myUID);
        if (helper.adapter != null) {
            helper.adapter.notifyDataSetChanged();
        }
        if (helper.btnLoadMore != null) {
            helper.btnLoadMore.setEnabled(helper.hasMore);
            helper.btnLoadMore.setText(helper.hasMore
                    ? R.string.load_more_messages : R.string.no_more_messages);
        }
        if (helper.messageList.isEmpty()) {
            return;
        }
        if (keepPositionFinal) {
            helper.cancelPendingBottomScroll();
            int targetPos = currentAnchorId == null
                    ? Math.min(firstVisibleFinal, helper.messageList.size() - 1)
                    : findMessagePosition(helper.messageList, currentAnchorId);
            if (targetPos < 0) targetPos = Math.min(firstVisibleFinal, helper.messageList.size() - 1);
            if (layoutManagerFinal != null) {
                layoutManagerFinal.scrollToPositionWithOffset(targetPos, currentAnchorTop);
            } else {
                helper.lvMessages.scrollToPosition(targetPos);
            }
        } else {
            helper.scrollToBottom();
            helper.markRead(tokenFinal);
        }
    }

    private static int findMessagePosition(List<Message> messages, String messageId) {
        if (messages == null || messageId == null) return -1;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message != null && messageId.equals(message.id)) return i;
        }
        return -1;
    }

    private static Message findOldestCursorMessage(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message != null && message.created_at > 0L
                    && message.id != null && !message.id.isEmpty()) {
                return message;
            }
        }
        return null;
    }

    static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
