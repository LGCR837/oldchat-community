package com.im.oldchat.ui;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.MessageHistoryCache;

final class GroupPendingMediaHelper {
    private GroupPendingMediaHelper() {
    }

    static void enqueueImage(GroupChatListHelper listHelper, String myUid, String localId,
                             String body, String previewUri, int burnAfterSeconds) {
        enqueueImageWithProgress(listHelper, myUid, localId, body, previewUri, burnAfterSeconds, 0);
    }

    static void enqueueImageSpinner(GroupChatListHelper listHelper, String myUid, String localId,
                                    String body, String previewUri, int burnAfterSeconds) {
        enqueueImageWithProgress(listHelper, myUid, localId, body, previewUri, burnAfterSeconds, -1);
    }

    private static void enqueueImageWithProgress(GroupChatListHelper listHelper, String myUid,
                                                 String localId, String body, String previewUri,
                                                 int burnAfterSeconds, int initialProgress) {
        if (listHelper == null || myUid == null || myUid.length() == 0
                || localId == null || localId.length() == 0) {
            return;
        }
        GroupMessage pending = new GroupMessage();
        pending.id = localId;
        pending.localRequestId = localId;
        pending.localPending = true;
        pending.localFailed = false;
        pending.group_id = listHelper.groupId;
        pending.from_uid = myUid;
        pending.sender_ncuid = com.im.oldchat.util.NcuidIdentity.getMyNcuid(listHelper.context);
        pending.msg_type = "image";
        pending.body = body == null ? "" : body;
        pending.media_url = "";
        pending.thumb_url = "";
        pending.localPreviewUri = previewUri;
        pending.localProgress = initialProgress;
        pending.duration_ms = 0;
        pending.burn_after_seconds = burnAfterSeconds;
        pending.created_at = System.currentTimeMillis() / 1000L;
        pending.read_count = 0;
        append(listHelper, pending);
    }

    static void updateProgress(GroupChatListHelper listHelper, String localId, int percent) {
        int index = findPendingIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        GroupMessage pending = listHelper.messageList.get(index);
        pending.localProgress = clampProgress(percent);
        notifyChanged(listHelper, index);
    }

    static void markUploadReady(GroupChatListHelper listHelper, String localId,
                                String mediaUrl, String thumbUrl, String originalUrl) {
        int index = findPendingIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        GroupMessage pending = listHelper.messageList.get(index);
        pending.media_url = mediaUrl == null ? "" : mediaUrl;
        pending.thumb_url = thumbUrl == null ? "" : thumbUrl;
        pending.original_url = originalUrl == null ? "" : originalUrl;
        pending.localProgress = -1;
        notifyChanged(listHelper, index);
    }

    private static void append(GroupChatListHelper listHelper, GroupMessage pending) {
        listHelper.messageList.add(pending);
        if (pending.id != null && pending.id.length() > 0) {
            listHelper.messageIds.add(pending.id);
        }
        GroupMessageSyncHelper.trimOldestInMemory(
                listHelper.messageList,
                listHelper.messageIds,
                GroupMessageSyncHelper.MAX_ACTIVE_WINDOW_MESSAGES);
        if (listHelper.adapter != null) {
            listHelper.adapter.markMessageAnimating(pending);
        }
        GroupRecentChatCache.updateGroupOutgoing(
                listHelper.context,
                listHelper.groupId,
                listHelper.groupName,
                null,
                ChatMessageUtil.previewForType(pending.msg_type, pending.body, pending.burn_after_seconds),
                pending.created_at);
        notifyInserted(listHelper, listHelper.messageList.size() - 1, true, true);
    }

    private static GroupMessage findPending(GroupChatListHelper listHelper, String localId) {
        int index = findPendingIndex(listHelper, localId);
        if (index < 0) {
            return null;
        }
        return listHelper.messageList.get(index);
    }

    private static int findPendingIndex(GroupChatListHelper listHelper, String localId) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return -1;
        }
        int size = listHelper.messageList.size();
        for (int i = 0; i < size; i++) {
            GroupMessage message = listHelper.messageList.get(i);
            if (message == null) {
                continue;
            }
            if (localId.equals(message.localRequestId)) {
                return i;
            }
            if (localId.equals(message.id) && message.localPending) {
                return i;
            }
        }
        return -1;
    }

    private static int clampProgress(int percent) {
        if (percent < 0) {
            return 0;
        }
        if (percent > 100) {
            return 100;
        }
        return percent;
    }

    private static void notifyInserted(GroupChatListHelper listHelper, int insertedIndex,
                                       boolean forceBottom, boolean saveCache) {
        if (listHelper.adapter != null) {
            if (insertedIndex >= 0 && insertedIndex < listHelper.messageList.size()) {
                listHelper.adapter.notifyRecyclerItemInserted(insertedIndex);
            } else {
                listHelper.adapter.notifyDataSetChanged();
            }
        }
        if (saveCache && listHelper.context != null) {
            MessageHistoryCache.saveGroupMessages(
                    listHelper.context,
                    listHelper.groupId,
                    listHelper.messageList);
        }
        if (forceBottom) {
            listHelper.scrollToBottom();
        }
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
        if (listHelper.context != null) {
            MessageHistoryCache.saveGroupMessages(
                    listHelper.context,
                    listHelper.groupId,
                    listHelper.messageList);
        }
    }
}
