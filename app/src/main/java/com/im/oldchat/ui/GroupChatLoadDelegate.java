package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.widget.AbsListView;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.NetworkStateManager;

import java.net.URLEncoder;
import java.util.List;

final class GroupChatLoadDelegate {
    private GroupChatLoadDelegate() {
    }

    static void loadMessagesInternal(final GroupChatListHelperSupport helper, String token,
                                     boolean append, long before, boolean silent,
                                     boolean keepPosition, boolean cachePhaseDone,
                                     int initialEmptyRetryCount) {
        if (helper.groupId == null || helper.groupId.isEmpty()) {
            return;
        }
        final boolean appendFinal = append;
        final boolean silentFinal = silent;
        final boolean keepPositionFinal = keepPosition;
        final String tokenFinal = token;

        if (!appendFinal && !keepPositionFinal && !cachePhaseDone && helper.messageList.isEmpty()) {
            helper.loadFromCacheAsync();
            loadMessagesInternal(helper, tokenFinal, appendFinal, before, silentFinal,
                    keepPositionFinal, true, initialEmptyRetryCount);
            return;
        }
        if ((appendFinal && !NetworkStateManager.getInstance().isServerAvailable())
                || tokenFinal == null || tokenFinal.isEmpty()) {
            helper.setLoading(false);
            return;
        }

        if (appendFinal) {
            helper.isLoadingMore = true;
            helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
        } else {
            helper.currentOffset = 0;
            if (!keepPositionFinal) {
                helper.cursorPaginationDisabled = false;
            }
        }

        final int requestSeq = ++helper.remoteLoadSeq;
        final int requestOffset = helper.currentOffset;
        final int requestPageLimit = helper.resolveRequestPageLimit(appendFinal, keepPositionFinal);
        int rawFirstVisible = helper.lvMessages.getFirstVisiblePosition();
        int headerCount = helper.lvMessages.getHeaderViewsCount();
        // 加载按钮头部可见时，用第一条真实消息作为锚点；不能拿头部高度去恢复消息位置。
        int anchorVisible = Math.max(rawFirstVisible, headerCount);
        int anchorChildIndex = anchorVisible - rawFirstVisible;
        android.view.View anchorView = helper.lvMessages.getChildAt(anchorChildIndex);
        if (anchorView == null) {
            anchorVisible = rawFirstVisible;
            anchorView = helper.lvMessages.getChildAt(0);
        }
        final int firstVisible = anchorVisible;
        final int firstTop = anchorView == null ? 0 : anchorView.getTop();
        final long requestStartedAtMs = helper.perfTracer == null ? 0L : helper.perfTracer.now();
        helper.setLoading(true);

        StringBuilder path = new StringBuilder(helper.legacyHistoryRoute
                ? "/groups/messages?group_id=" : "/v2/groups/messages/v2?group_id=");
        path.append(helper.groupId);
        path.append("&limit=").append(requestPageLimit);
        path.append("&offset=").append(requestOffset);
        // 新客户端由独立 /groups/read 推进已读，避免历史 GET 在 SQLite 上重复写锁。
        if (!helper.legacyHistoryRoute) {
            path.append("&mark_read=0");
        }
        if (!helper.legacyHistoryRoute && appendFinal && !helper.cursorPaginationDisabled
                && (helper.pendingJumpMessageId == null
                || helper.pendingJumpMessageId.isEmpty())) {
            GroupMessage oldest = findOldestCursorMessage(helper.messageList);
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
                final Context appContext = helper.context != null ? helper.context.getApplicationContext() : null;
                final GroupMessageSyncHelper.MemberMetaSnapshot memberMetaSnapshot = helper.adapter != null
                        ? helper.adapter.createMemberMetaSnapshot() : null;
                // 必须深拷贝；后台 merge 不得修改 RecyclerView 正在绑定的活动消息对象。
                final java.util.List<GroupMessage> snapshotList =
                        deepCopyMessages(helper.messageList);
                final java.util.HashSet<String> snapshotIds =
                        new java.util.HashSet<String>(helper.messageIds);
                final int snapshotOffset = helper.currentOffset;
                AsyncTask<Void, Void, GroupMessageSyncHelper.GroupMessageLoadResult> task =
                        new AsyncTask<Void, Void, GroupMessageSyncHelper.GroupMessageLoadResult>() {
                            @Override
                            protected GroupMessageSyncHelper.GroupMessageLoadResult doInBackground(Void... voids) {
                                long t0 = android.os.SystemClock.uptimeMillis();
                                GroupMessageSyncHelper.GroupMessageLoadResult result =
                                        GroupMessageSyncHelper.parseLoadResult(response, requestOffset,
                                                appContext, memberMetaSnapshot, helper.perfTracer, 0L);
                                long t1 = android.os.SystemClock.uptimeMillis();
                                if (result != null && result.error == null && result.incoming != null) {
                                    if (!appendFinal) {
                                        preSortForRefresh(result, snapshotList, snapshotIds, requestPageLimit);
                                    }
                                    prewarmPayloadCache(result);
                                }
                                long t2 = android.os.SystemClock.uptimeMillis();
                                int count = result != null && result.incoming != null ? result.incoming.size() : 0;
                                android.util.Log.w("GroupChatPerf", "BG: parse=" + (t1-t0) + "ms sort=" + (t2-t1)
                                        + "ms total=" + (t2-t0) + "ms msgs=" + count + " append=" + appendFinal);
                                return result;
                            }

                            @Override
                            protected void onPostExecute(GroupMessageSyncHelper.GroupMessageLoadResult loadResult) {
                                if (!helper.isContextValid() || requestSeq != helper.remoteLoadSeq) {
                                    if (appendFinal) {
                                        helper.isLoadingMore = false;
                                        helper.setLoading(false);
                                    }
                                    return;
                                }
                                long t0 = android.os.SystemClock.uptimeMillis();
                                try {
                                    applyResult(helper, loadResult, appendFinal, keepPositionFinal,
                                            requestPageLimit, tokenFinal, firstVisible, firstTop,
                                            requestStartedAtMs);
                                } catch (Exception e) {
                                    if (!silentFinal && helper.isContextValid()) {
                                        android.widget.Toast.makeText(helper.context, "加载消息失败",
                                                android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                } finally {
                                    if (helper.context instanceof GroupChatActivityLifecycleA) {
                                        ((GroupChatActivityLifecycleA) helper.context).hideSyncBanner();
                                    }
                                }
                                long t1 = android.os.SystemClock.uptimeMillis();
                                android.util.Log.w("GroupChatPerf", "UI: applyResult=" + (t1-t0) + "ms"
                                        + " listSize=" + helper.messageList.size()
                                        + " append=" + appendFinal);
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
                    if (appendFinal) {
                        helper.isLoadingMore = false;
                        helper.setLoading(false);
                    }
                    return;
                }
                helper.setLoading(false);
                helper.isLoadingMore = false;
                if (code == 404 && !helper.legacyHistoryRoute) {
                    // v2 强制测试：不再回退 v1，直接暴露 v2 接口 404（确认 v2 兼容性缺口）。
                    // 旧版这里会 legacyHistoryRoute=true 回退 /groups/messages；v2-only 测试禁用。
                }
                helper.pendingJumpToUnread = false;
                helper.pendingJumpMessageId = null;
                helper.pendingJumpMessageToken = null;
                helper.pendingJumpAnchorUsed = false;
                helper.pendingJumpLoadCount = 0;
                // 隐藏同步消息横幅
                if (helper.context instanceof GroupChatActivityLifecycleA) {
                    ((GroupChatActivityLifecycleA) helper.context).hideSyncBanner();
                }
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                if ((code == 404 && isExplicitGroupNotFound(error)) || code == 403) {
                    GroupRecentChatCache.remove(helper.context, helper.groupId);
                    GroupCache.remove(helper.context, helper.groupId);
                    MessageHistoryCache.removeGroupMessages(helper.context, helper.groupId);
                    if (helper.context instanceof Activity) {
                        android.widget.Toast.makeText(helper.context,
                                (code == 403 ? "你已不在该群聊中" : "群聊不存在或已解散"),
                                android.widget.Toast.LENGTH_SHORT).show();
                        ((Activity) helper.context).finish();
                    }
                    return;
                }
                if (!appendFinal && helper.messageList.isEmpty()) {
                    helper.loadFromCacheAsync();
                }
                if (!silentFinal && helper.isContextValid()) {
                    android.widget.Toast.makeText(helper.context, "同步失败: " + code,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private static boolean isExplicitGroupNotFound(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.trim().toLowerCase(java.util.Locale.US);
        return lower.contains("group_not_found")
                || lower.contains("group not found")
                || lower.contains("群组不存在");
    }

    private static void applyResult(GroupChatListHelperSupport helper,
                                    GroupMessageSyncHelper.GroupMessageLoadResult loadResult,
                                    boolean appendFinal,
                                    boolean keepPositionFinal,
                                    int requestPageLimit,
                                    String tokenFinal,
                                    int firstVisible,
                                    int firstTop,
                                    long requestStartedAtMs) throws Exception {
        if (loadResult == null || loadResult.error != null || loadResult.incoming == null) {
            throw loadResult != null && loadResult.error != null
                    ? loadResult.error : new Exception("empty result");
        }
        // 跳过优化已移除：基于请求开始时快照的 size 比较，在 WS 期间插入/移除消息时
        // 可能错误跳过必要的 notifyDataSetChanged，导致消息不显示。
        List<GroupMessage> incoming = loadResult.incoming;
        int effectiveOffset = loadResult.effectiveOffset;
        int previousSize = helper.messageList.size();
        int newIncomingCount = 0;
        if (!appendFinal && keepPositionFinal && !incoming.isEmpty()) {
            newIncomingCount = GroupMessageSyncHelper.countIncomingForBubble(
                    helper.context, helper.myUID, incoming, helper.messageIds);
        }

        if (appendFinal) {
            applyAppend(helper, incoming, requestPageLimit, effectiveOffset,
                    previousSize, loadResult.hasMore, firstVisible, firstTop);
        } else {
            applyRefresh(helper, incoming, requestPageLimit, effectiveOffset,
                    keepPositionFinal, tokenFinal, firstVisible, firstTop, loadResult);
        }
        helper.updateRecentFromMessages(helper.messageList, false);
        MessageHistoryCache.saveGroupMessages(helper.context, helper.groupId, helper.messageList);
        if (helper.context instanceof GroupChatActivityLifecycleA) {
            ((GroupChatActivityLifecycleA) helper.context).prewarmLoadedMemberMeta();
        }
        if (!appendFinal && keepPositionFinal && newIncomingCount > 0) {
            helper.newMessageCount += newIncomingCount;
            helper.showNewMessageBubble();
        }
        if (helper.perfTracer != null && !appendFinal) {
            helper.perfTracer.traceFrom("ui_apply_done",
                    "source=group append=" + appendFinal + " keep="
                            + keepPositionFinal + " size=" + helper.messageList.size(),
                    requestStartedAtMs);
            if (helper.messageList.size() < GroupChatListHelperSupport.WARM_CACHE_READY_THRESHOLD) {
                helper.perfTracer.finish("source=group size=" + helper.messageList.size());
            }
        }
        // 隐藏同步消息横幅
        if (helper.context instanceof GroupChatActivityLifecycleA) {
            ((GroupChatActivityLifecycleA) helper.context).hideSyncBanner();
        }
        if (!appendFinal) {
            helper.scheduleReliableSync(tokenFinal);
        }
    }

    private static void applyAppend(GroupChatListHelperSupport helper, List<GroupMessage> incoming,
                                    int requestPageLimit, int effectiveOffset, int previousSize,
                                    Boolean responseHasMore, int firstVisible, int firstTop) {
        // 请求期间用户可能继续滚动，前插前重新读取当前第一条真实消息作为锚点。
        int rawFirst = helper.lvMessages.getFirstVisiblePosition();
        int headerCount = helper.lvMessages.getHeaderViewsCount();
        int anchorPosition = Math.max(rawFirst, headerCount);
        int childIndex = anchorPosition - rawFirst;
        android.view.View currentAnchor = helper.lvMessages.getChildAt(childIndex);
        int anchorTop = currentAnchor == null ? firstTop : currentAnchor.getTop();
        if (currentAnchor == null) {
            anchorPosition = firstVisible;
        }
        GroupMessageMergeResult mergeResult = GroupMessageSyncHelper.mergeAppend(
                helper.messageList, helper.messageIds, incoming, helper.currentOffset, requestPageLimit);
        helper.currentOffset = Math.max(mergeResult.currentOffset, effectiveOffset + incoming.size());
        helper.hasMore = responseHasMore != null
                ? responseHasMore.booleanValue() : mergeResult.hasMore;
        int insertedCount = helper.messageList.size() - previousSize;
        if (insertedCount < 0) {
            insertedCount = 0;
        }
        if (insertedCount == 0 && incoming != null && !incoming.isEmpty()) {
            // 游标页若全部重复，下一次自动回退 OFFSET，避免加载更多看似失效。
            helper.cursorPaginationDisabled = true;
            helper.currentOffset = effectiveOffset;
            helper.hasMore = true;
        }
        if (helper.adapter != null) {
            if (insertedCount > 0) {
                helper.adapter.notifyRecyclerItemRangeInserted(0, insertedCount);
                // 前插边界会影响旧第一条消息的时间标签与连续气泡样式，仅刷新边界行。
                helper.adapter.notifyRecyclerItemChanged(insertedCount);
            }
            // 重复页无可见变化时不再触发全量重绑。
        }
        if (helper.btnLoadMore != null) {
            helper.btnLoadMore.setEnabled(helper.hasMore);
            helper.btnLoadMore.setText(helper.hasMore
                    ? R.string.load_more_messages : R.string.no_more_messages);
        }
        int targetPos = anchorPosition + insertedCount;
        if (targetPos < 0) {
            targetPos = 0;
        }
        int listLastPos = helper.lvMessages.getCount() - 1;
        if (listLastPos >= 0 && targetPos > listLastPos) {
            targetPos = listLastPos;
        }
        if (listLastPos >= 0) {
            helper.lvMessages.setSelectionFromTop(targetPos, anchorTop);
        }
    }

    /**
     * 后台预排序：在副本上完成 merge + sort + trim，主线程直接替换列表
     */
    private static void preSortForRefresh(GroupMessageSyncHelper.GroupMessageLoadResult result,
                                           java.util.List<GroupMessage> currentList,
                                           java.util.HashSet<String> currentIds,
                                           int pageLimit) {
        java.util.List<GroupMessage> incoming = result.incoming;
        if (incoming == null || incoming.isEmpty()) {
            result.preSortedList = currentList;
            result.preSortedIds = currentIds;
            return;
        }
        java.util.List<GroupMessage> working = new java.util.ArrayList<GroupMessage>(currentList);
        java.util.HashSet<String> ids = new java.util.HashSet<String>(currentIds);

        // mergeRefresh 逻辑（与原始 applyRefresh 相同，但在后台执行）
        boolean hasOverlap = false;
        if (!working.isEmpty()) {
            for (GroupMessage newMsg : incoming) {
                if (newMsg.id != null && ids.contains(newMsg.id)) {
                    hasOverlap = true;
                    break;
                }
            }
        } else {
            hasOverlap = true;
        }
        boolean gapDetected = !hasOverlap && incoming.size() >= pageLimit;
        boolean existingChanged = false;
        if (gapDetected) {
            // 不再直接清空：保留当前列表中不在 incoming 里的消息，避免 WS 插入的消息丢失。
            java.util.HashSet<String> incomingIds = new java.util.HashSet<String>();
            for (GroupMessage msg : incoming) {
                if (msg != null && msg.id != null) incomingIds.add(msg.id);
            }
            java.util.List<GroupMessage> preserved = new java.util.ArrayList<GroupMessage>();
            for (GroupMessage msg : working) {
                if (msg != null && msg.id != null && !incomingIds.contains(msg.id)) {
                    preserved.add(msg);
                }
            }
            working.clear();
            ids.clear();
            working.addAll(preserved);
            working.addAll(incoming);
            for (GroupMessage msg : working) {
                if (msg != null && msg.id != null) ids.add(msg.id);
            }
            GroupMessageSyncHelper.sortMessages(working);
        } else {
            // 建立 ID 索引，避免每条响应消息都线性扫描整个活动窗口。
            java.util.HashMap<String, GroupMessage> existingById =
                    new java.util.HashMap<String, GroupMessage>(working.size() * 2 + 1);
            for (GroupMessage existing : working) {
                if (existing != null && existing.id != null) {
                    existingById.put(existing.id, existing);
                }
            }
            for (GroupMessage msg : incoming) {
                GroupMessage existing = msg == null || msg.id == null
                        ? null : existingById.get(msg.id);
                if (existing != null) {
                    if (!sameRefreshFields(existing, msg)) existingChanged = true;
                    existing.from_uid = msg.from_uid;
                    existing.sender_ncuid = msg.sender_ncuid;
                    existing.body = msg.body;
                    existing.msg_type = msg.msg_type;
                    existing.thumb_url = msg.thumb_url;
                    existing.duration_ms = msg.duration_ms;
                    existing.burn_after_seconds = msg.burn_after_seconds;
                    existing.created_at = msg.created_at;
                    existing.read_count = msg.read_count;
                    existing.media_url = msg.media_url;
                    existing.sort_seq = msg.sort_seq;
                        existing.group_seq = msg.group_seq;
                } else {
                    working.add(msg);
                    if (msg != null && msg.id != null) {
                        ids.add(msg.id);
                        existingById.put(msg.id, msg);
                    }
                }
            }
            GroupMessageSyncHelper.sortMessages(working);
        }
        int trimmed = GroupMessageSyncHelper.trimOldestInMemory(working, ids,
                GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
        result.preSortedList = working;
        result.preSortedIds = ids;
        result.newMessageCount = working.size() - currentList.size() + trimmed;
        result.oldSize = currentList.size();

        // 后台 diff：判断是否只是尾部追加
        if (!gapDetected && !existingChanged && trimmed == 0 && working.size() > currentList.size()) {
            int added = working.size() - currentList.size();
            // 检查旧列表的内容是否完全保留在新列表的前部
            boolean prefixMatch = true;
            for (int i = 0; i < currentList.size(); i++) {
                GroupMessage oldMsg = currentList.get(i);
                GroupMessage newMsg = working.get(i);
                if (oldMsg == null || newMsg == null) continue;
                if (oldMsg.id != null && !oldMsg.id.equals(newMsg.id)) {
                    prefixMatch = false;
                    break;
                }
            }
            if (prefixMatch) {
                result.isAppendOnly = true;
                result.insertPosition = currentList.size();
                result.insertCount = added;
            }
        }
    }

    private static java.util.List<GroupMessage> deepCopyMessages(java.util.List<GroupMessage> source) {
        java.util.List<GroupMessage> result = new java.util.ArrayList<GroupMessage>(source == null ? 0 : source.size());
        if (source == null) return result;
        for (GroupMessage value : source) {
            if (value == null) { result.add(null); continue; }
            GroupMessage copy = new GroupMessage();
            copy.id = value.id; copy.group_id = value.group_id; copy.from_uid = value.from_uid;
            copy.body = value.body; copy.msg_type = value.msg_type; copy.media_url = value.media_url;
            copy.thumb_url = value.thumb_url; copy.original_url = value.original_url; copy.duration_ms = value.duration_ms;
            copy.burn_after_seconds = value.burn_after_seconds;
            copy.burn_preview_text = value.burn_preview_text; copy.burn_preview_type = value.burn_preview_type;
            copy.burn_preview_media_url = value.burn_preview_media_url;
            copy.burn_preview_thumb_url = value.burn_preview_thumb_url;
            copy.created_at = value.created_at; copy.sort_seq = value.sort_seq; copy.group_seq = value.group_seq;
            copy.read_count = value.read_count; copy.localPending = value.localPending;
            copy.localFailed = value.localFailed; copy.localRequestId = value.localRequestId;
            copy.localPreviewUri = value.localPreviewUri; copy.localProgress = value.localProgress;
            copy.recall_edit_type = value.recall_edit_type; copy.recall_edit_text = value.recall_edit_text;
            copy.sender_ncuid = value.sender_ncuid; copy.cachedPayload = value.cachedPayload;
            copy.cachedMemberMetaVersion = value.cachedMemberMetaVersion;
            copy.cachedMemberMetaSourceUid = value.cachedMemberMetaSourceUid;
            copy.cachedCanonicalUid = value.cachedCanonicalUid; copy.cachedSenderName = value.cachedSenderName;
            copy.cachedSenderTitle = value.cachedSenderTitle; copy.cachedSenderRole = value.cachedSenderRole;
            copy.cachedAvatarSourceUid = value.cachedAvatarSourceUid; copy.cachedAvatarUrl = value.cachedAvatarUrl;
            result.add(copy);
        }
        return result;
    }

    private static boolean sameRefreshFields(GroupMessage a, GroupMessage b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return same(a.from_uid, b.from_uid) && same(a.sender_ncuid, b.sender_ncuid)
                && same(a.body, b.body)
                && same(a.msg_type, b.msg_type) && same(a.media_url, b.media_url)
                && same(a.thumb_url, b.thumb_url) && a.duration_ms == b.duration_ms
                && a.burn_after_seconds == b.burn_after_seconds && a.created_at == b.created_at
                && a.read_count == b.read_count && a.sort_seq == b.sort_seq && a.group_seq == b.group_seq;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean isTerminalMessageState(GroupMessage message) {
        if (message == null) return false;
        return "recall".equals(message.msg_type)
                || "[消息已焚毁]".equals(message.body);
    }

    /**
     * 后台预热 payload 缓存：提前解析 JSON 并存到消息对象，getView 直接命中
     */
    private static void prewarmPayloadCache(GroupMessageSyncHelper.GroupMessageLoadResult result) {
        if (result == null) return;
        List<GroupMessage> list = result.preSortedList != null ? result.preSortedList : result.incoming;
        if (list == null || list.isEmpty()) return;
        int count = Math.min(list.size(), 60);
        int start = Math.max(0, list.size() - count);
        for (int i = start; i < list.size(); i++) {
            GroupMessage msg = list.get(i);
            if (msg != null && msg.cachedPayload == null && msg.body != null && msg.body.length() > 0) {
                msg.cachedPayload = MessagePayload.fromBody(msg.body);
            }
        }
    }

    private static void applyRefresh(GroupChatListHelperSupport helper, List<GroupMessage> incoming,
                                     int requestPageLimit, int effectiveOffset,
                                     boolean keepPositionFinal, String tokenFinal,
                                     int firstVisible, int firstTop,
                                     GroupMessageSyncHelper.GroupMessageLoadResult loadResult) {
        int previousSize = helper.messageList.size();
        String currentAnchorId = null;
        int currentAnchorTop = firstTop;
        if (keepPositionFinal && helper.lvMessages != null) {
            int rawPosition = helper.lvMessages.getFirstVisiblePosition();
            int messagePosition = rawPosition - helper.lvMessages.getHeaderViewsCount();
            if (messagePosition >= 0 && messagePosition < helper.messageList.size()) {
                GroupMessage anchor = helper.messageList.get(messagePosition);
                currentAnchorId = anchor == null ? null : anchor.id;
                android.support.v7.widget.RecyclerView.ViewHolder holder =
                        helper.lvMessages.findViewHolderForAdapterPosition(rawPosition);
                if (holder != null) currentAnchorTop = holder.itemView.getTop();
            }
        }
        // 如果后台已预排序，合并预排序结果与当前列表，保留 WS 期间插入的消息。
        if (loadResult != null && loadResult.preSortedList != null) {
            // 空结果守卫：预排序结果为空且当前已有消息时不覆盖。
            if (loadResult.preSortedList.isEmpty() && !helper.messageList.isEmpty()) {
                if (helper.adapter != null) {
                    helper.adapter.notifyDataSetChangedNow();
                }
                if (helper.btnLoadMore != null) {
                    helper.btnLoadMore.setEnabled(helper.hasMore);
                    helper.btnLoadMore.setText(helper.hasMore
                            ? R.string.load_more_messages : R.string.no_more_messages);
                }
                if (!keepPositionFinal) helper.markRead(tokenFinal);
                return;
            }
            // 合并：用预排序结果作为基准，追加当前列表中不在预排序结果里的消息（WS 插入的）
            java.util.HashSet<String> preSortedIdSet = loadResult.preSortedIds != null
                    ? loadResult.preSortedIds : new java.util.HashSet<String>();
            java.util.List<GroupMessage> wsInserted = new java.util.ArrayList<GroupMessage>();
            java.util.HashMap<String, GroupMessage> currentById = new java.util.HashMap<String, GroupMessage>();
            for (GroupMessage msg : helper.messageList) {
                if (msg != null && msg.id != null) {
                    currentById.put(msg.id, msg);
                    if (!preSortedIdSet.contains(msg.id)) {
                        wsInserted.add(msg);
                        preSortedIdSet.add(msg.id);
                    }
                }
            }
            // HTTP 快照不能把请求期间已收到的召回/焚毁状态恢复成旧正文。
            for (int i = 0; i < loadResult.preSortedList.size(); i++) {
                GroupMessage candidate = loadResult.preSortedList.get(i);
                GroupMessage current = candidate == null ? null : currentById.get(candidate.id);
                if (current != null) {
                    if (isTerminalMessageState(current) && !isTerminalMessageState(candidate)) {
                        loadResult.preSortedList.set(i, current);
                    } else if (current.read_count > candidate.read_count) {
                        candidate.read_count = current.read_count;
                    }
                }
            }
            helper.messageList.clear();
            helper.messageList.addAll(loadResult.preSortedList);
            if (!wsInserted.isEmpty()) {
                helper.messageList.addAll(wsInserted);
                GroupMessageSyncHelper.sortMessages(helper.messageList);
                GroupMessageSyncHelper.trimOldestInMemory(helper.messageList, preSortedIdSet,
                        GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
            }
            helper.messageIds.clear();
            helper.messageIds.addAll(preSortedIdSet);
        } else {
            helper.messageList.clear();
            helper.messageIds.clear();
            helper.messageList.addAll(incoming);
            GroupMessageSyncHelper.sortMessages(helper.messageList);
            for (int i = 0; i < helper.messageList.size(); i++) {
                GroupMessage msg = helper.messageList.get(i);
                if (msg != null && msg.id != null) {
                    helper.messageIds.add(msg.id);
                }
            }
        }
        helper.currentOffset = Math.max(helper.messageList.size(), effectiveOffset + incoming.size());
        helper.hasMore = loadResult != null && loadResult.hasMore != null
                ? loadResult.hasMore.booleanValue() : incoming.size() >= requestPageLimit;
        if (helper.btnLoadMore != null) {
            helper.btnLoadMore.setEnabled(helper.hasMore);
            helper.btnLoadMore.setText(helper.hasMore
                    ? R.string.load_more_messages : R.string.no_more_messages);
        }
        if (helper.messageList.isEmpty()) {
            if (!keepPositionFinal) helper.markRead(tokenFinal);
            return;
        }
        // 通知和滚动放同一个 post 里，确保 adapter 更新后再滚动
        final boolean finalKeepPosition = keepPositionFinal;
        final int finalFirstVisible = firstVisible;
        final int finalFirstTop = firstTop;
        final String finalToken = tokenFinal;
        final String finalCurrentAnchorId = currentAnchorId;
        final int finalCurrentAnchorTop = currentAnchorTop;
        final boolean appendOnly = loadResult != null && loadResult.isAppendOnly;
        final int insPos = loadResult != null ? loadResult.insertPosition : 0;
        final int insCount = loadResult != null ? loadResult.insertCount : 0;
        helper.lvMessages.post(new Runnable() {
            @Override
            public void run() {
                // 桥接 Adapter 的 notify 会同步检查 transcriptMode，必须先禁用才能真正保位。
                if (finalKeepPosition) {
                    helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
                }
                if (appendOnly && insCount > 0) {
                    helper.adapter.notifyRecyclerItemRangeInserted(insPos, insCount);
                } else {
                    helper.adapter.notifyDataSetChangedNow();
                }
                if (finalKeepPosition) {
                    helper.cancelPendingBottomScroll();
                    int listLastPos = helper.lvMessages.getCount() - 1;
                    int messagePos = finalCurrentAnchorId == null ? -1
                            : findGroupMessagePosition(helper.messageList, finalCurrentAnchorId);
                    int targetPos = messagePos >= 0
                            ? messagePos + helper.lvMessages.getHeaderViewsCount()
                            : (listLastPos < 0 ? 0 : Math.min(finalFirstVisible, listLastPos));
                    helper.lvMessages.setSelectionFromTop(targetPos,
                            finalCurrentAnchorId == null ? finalFirstTop : finalCurrentAnchorTop);
                } else {
                    helper.lvMessages.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
                    helper.scrollToBottom();
                    helper.markRead(finalToken);
                }
            }
        });
    }

    private static int findGroupMessagePosition(java.util.List<GroupMessage> messages, String messageId) {
        if (messages == null || messageId == null) return -1;
        for (int i = 0; i < messages.size(); i++) {
            GroupMessage message = messages.get(i);
            if (message != null && messageId.equals(message.id)) return i;
        }
        return -1;
    }

    private static GroupMessage findOldestCursorMessage(java.util.List<GroupMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = 0; i < messages.size(); i++) {
            GroupMessage message = messages.get(i);
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
