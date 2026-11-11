package com.im.oldchat.ui;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.util.UiPerformance;

final class GroupPendingMessageHelper {
    private GroupPendingMessageHelper() {
    }

    private static String resolveMyDisplayName(android.content.Context context) {
        try {
            // 优先从 auth prefs 读 display_name（登录响应写入）
            android.content.SharedPreferences prefs = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE);
            String display = prefs.getString("display_name", "");
            if (display != null && !display.isEmpty()) {
                return display;
            }
            // 回退 username（登录响应的 username 字段）
            String username = prefs.getString("my_username", "");
            if (username != null && !username.isEmpty()) {
                return username;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String enqueueText(GroupChatListHelper listHelper, String myUid, String body, int burnAfterSeconds) {
        if (listHelper == null || myUid == null || myUid.length() == 0) {
            return null;
        }
        GroupMessage pending = new GroupMessage();
        String localId = buildLocalId();
        pending.id = localId;
        pending.localRequestId = localId;
        pending.localPending = true;
        pending.localFailed = false;
        pending.group_id = listHelper.groupId;
        pending.from_uid = myUid;
        pending.sender_ncuid = com.im.oldchat.util.NcuidIdentity.getMyNcuid(listHelper.context);
        // 把"自己的名字"写入名字缓存，避免 pending 消息（发送前）显示 UID/NCUID。
        // 发送成功后服务端返回真实消息，渲染走服务端字段，此处仅解决发送前的展示。
        String myName = resolveMyDisplayName(listHelper.context);
        if (myName != null && !myName.isEmpty() && myUid != null) {
            com.im.oldchat.models.UserNameCache.put(listHelper.context, myUid, myName);
        }
        pending.msg_type = "text";
        pending.body = body == null ? "" : body;
        pending.media_url = "";
        pending.thumb_url = "";
        pending.localPreviewUri = null;
        pending.localProgress = -1;
        pending.duration_ms = 0;
        pending.burn_after_seconds = burnAfterSeconds;
        pending.created_at = System.currentTimeMillis() / 1000L;
        pending.read_count = 0;
        append(listHelper, pending, true);
        return localId;
    }

    static boolean completeText(GroupChatListHelper listHelper, String localId, GroupMessage serverMessage) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return false;
        }
        int index = findLocalIndex(listHelper, localId);
        if (index < 0) {
            if (canUseLooseMatch(serverMessage) && tryApplyServerMessage(listHelper, serverMessage)) {
                return true;
            }
            return false;
        }
        GroupMessage pending = listHelper.messageList.get(index);
        if (serverMessage == null) {
            pending.localPending = false;
            pending.localFailed = true;
            notifyChanged(listHelper, index);
            return true;
        }
        if (serverMessage.id != null
                && serverMessage.id.length() > 0
                && listHelper.messageIds.contains(serverMessage.id)
                && !serverMessage.id.equals(pending.id)) {
            listHelper.messageList.remove(index);
            listHelper.messageIds.remove(localId);
            notifyRemoved(listHelper, index);
            return true;
        }
        listHelper.messageIds.remove(localId);
        copyServerFields(pending, serverMessage);
        if (pending.id != null && pending.id.length() > 0) {
            listHelper.messageIds.add(pending.id);
        }
        // pending 原地确认即可，避免旧设备对整个消息窗口排序和重绑。
        notifyChanged(listHelper, index);
        return true;
    }

    static void failText(GroupChatListHelper listHelper, String localId) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return;
        }
        int index = findLocalIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        GroupMessage pending = listHelper.messageList.get(index);
        pending.localPending = false;
        pending.localFailed = true;
        pending.localProgress = -1;
        notifyChanged(listHelper, index);
    }

    static void removeText(GroupChatListHelper listHelper, String localId) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return;
        }
        int index = findLocalIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        listHelper.messageList.remove(index);
        listHelper.messageIds.remove(localId);
        notifyRemoved(listHelper, index);
    }

    static boolean tryApplyServerMessage(GroupChatListHelper listHelper, GroupMessage serverMessage) {
        if (listHelper == null || serverMessage == null || !canUseLooseMatch(serverMessage)) {
            return false;
        }
        int index = findMatchingPendingIndex(listHelper, serverMessage);
        if (index < 0) {
            return false;
        }
        GroupMessage pending = listHelper.messageList.get(index);
        String localId = pending.localRequestId;
        listHelper.messageIds.remove(localId);
        copyServerFields(pending, serverMessage);
        if (pending.id != null && pending.id.length() > 0) {
            listHelper.messageIds.add(pending.id);
        }
        // WebSocket/同步确认命中本地 pending 时只刷新当前气泡。
        notifyChanged(listHelper, index);
        return true;
    }

    private static void append(GroupChatListHelper listHelper, GroupMessage pending, boolean forceBottom) {
        if (pending == null) {
            return;
        }
        listHelper.messageList.add(pending);
        if (pending.id != null && pending.id.length() > 0) {
            listHelper.messageIds.add(pending.id);
        }
        int removedCount = GroupMessageSyncHelper.trimOldestInMemory(
                listHelper.messageList,
                listHelper.messageIds,
                GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
        if (listHelper.adapter != null && !UiPerformance.shouldReduceMotion(listHelper.context)) {
            listHelper.adapter.markMessageAnimating(pending);
        }
        updateRecent(listHelper, pending);
        notifyPendingInserted(listHelper, listHelper.messageList.size() - 1, removedCount);
        if (forceBottom) {
            listHelper.scrollToBottom();
        }
    }

    private static int findLocalIndex(GroupChatListHelper listHelper, String localId) {
        int size = listHelper.messageList.size();
        for (int i = 0; i < size; i++) {
            GroupMessage message = listHelper.messageList.get(i);
            if (message != null && localId.equals(message.localRequestId)) {
                return i;
            }
            if (message != null && localId.equals(message.id) && message.localPending) {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingPendingIndex(GroupChatListHelper listHelper, GroupMessage serverMessage) {
        if (listHelper == null || serverMessage == null) {
            return -1;
        }
        int size = listHelper.messageList.size();
        int matchedIndex = -1;
        for (int i = 0; i < size; i++) {
            GroupMessage pending = listHelper.messageList.get(i);
            if (pending == null || !pending.localPending || pending.localFailed) {
                continue;
            }
            if (!sameValue(pending.msg_type, serverMessage.msg_type)) {
                continue;
            }
            if (!sameValue(pending.body, serverMessage.body)) {
                continue;
            }
            if (!sameValue(pending.media_url, serverMessage.media_url)) {
                continue;
            }
            if (!sameValue(pending.thumb_url, serverMessage.thumb_url)) {
                continue;
            }
            if (matchedIndex >= 0) {
                return -1;
            }
            matchedIndex = i;
        }
        return matchedIndex;
    }

    private static boolean canUseLooseMatch(GroupMessage serverMessage) {
        if (serverMessage == null) {
            return false;
        }
        String type = serverMessage.msg_type == null ? "" : serverMessage.msg_type.trim().toLowerCase();
        if (!"text".equals(type)) {
            return false;
        }
        return isEmpty(serverMessage.media_url) && isEmpty(serverMessage.thumb_url);
    }

    private static void copyServerFields(GroupMessage target, GroupMessage serverMessage) {
        target.id = serverMessage.id;
        target.group_id = serverMessage.group_id;
        target.from_uid = serverMessage.from_uid;
        target.sender_ncuid = serverMessage.sender_ncuid;
        target.body = serverMessage.body;
        target.msg_type = serverMessage.msg_type;
        target.media_url = serverMessage.media_url;
        target.thumb_url = serverMessage.thumb_url;
        target.duration_ms = serverMessage.duration_ms;
        target.burn_after_seconds = serverMessage.burn_after_seconds;
        target.burn_preview_text = serverMessage.burn_preview_text;
        target.created_at = serverMessage.created_at;
        target.sort_seq = serverMessage.sort_seq;
        target.group_seq = serverMessage.group_seq;
        target.read_count = serverMessage.read_count;
        target.recall_edit_type = serverMessage.recall_edit_type;
        target.recall_edit_text = serverMessage.recall_edit_text;
        target.localPending = false;
        target.localFailed = false;
        target.localRequestId = null;
        target.localPreviewUri = null;
        target.localProgress = -1;
    }

    private static void updateRecent(GroupChatListHelper listHelper, GroupMessage message) {
        if (listHelper.context == null || listHelper.groupId == null || listHelper.groupId.length() == 0) {
            return;
        }
        GroupRecentChatCache.updateGroupOutgoing(
                listHelper.context,
                listHelper.groupId,
                listHelper.groupName,
                null,
                ChatMessageUtil.previewForType(message.msg_type, message.body, message.burn_after_seconds),
                message.created_at);
    }

    private static void notifyPendingInserted(GroupChatListHelper listHelper, int insertedIndex,
                                              int removedCount) {
        if (listHelper == null || listHelper.adapter == null) {
            return;
        }
        if (removedCount == 1) {
            listHelper.adapter.notifyRecyclerItemRemoved(0);
        } else if (removedCount > 1) {
            listHelper.adapter.notifyDataSetChanged();
            return;
        }
        if (insertedIndex >= 0 && insertedIndex < listHelper.messageList.size()) {
            listHelper.adapter.notifyRecyclerItemInserted(insertedIndex);
        } else {
            listHelper.adapter.notifyDataSetChanged();
        }
        // pending 不进入历史缓存，确认后再保存，减少主线程列表快照和 Dalvik GC。
    }

    private static void notifyChanged(GroupChatListHelper listHelper, int changedIndex) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            if (changedIndex >= 0 && changedIndex < listHelper.messageList.size()) {
                listHelper.adapter.notifyRecyclerItemChanged(changedIndex);
            } else {
                listHelper.adapter.refreshVisibleRows();
            }
        }
        saveCache(listHelper);
    }

    private static void notifyRemoved(GroupChatListHelper listHelper, int removedIndex) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            if (removedIndex >= 0) {
                listHelper.adapter.notifyRecyclerItemRemoved(removedIndex);
            } else {
                listHelper.adapter.notifyDataSetChanged();
            }
        }
        saveCache(listHelper);
    }

    private static void notifyReload(GroupChatListHelper listHelper) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            listHelper.adapter.notifyDataSetChanged();
        }
        saveCache(listHelper);
    }

    private static void saveCache(GroupChatListHelper listHelper) {
        if (listHelper.context != null) {
            MessageHistoryCache.saveGroupMessages(
                    listHelper.context,
                    listHelper.groupId,
                    listHelper.messageList);
        }
    }

    private static boolean sameValue(String first, String second) {
        if (first == null) {
            return second == null || second.length() == 0;
        }
        if (second == null) {
            return first.length() == 0;
        }
        return first.equals(second);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static String buildLocalId() {
        return "local_group_text_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 1000));
    }
}
