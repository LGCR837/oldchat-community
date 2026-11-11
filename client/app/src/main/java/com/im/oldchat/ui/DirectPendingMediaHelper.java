package com.im.oldchat.ui;

import com.im.oldchat.models.Message;
import com.im.oldchat.models.MessageHistoryCache;

final class DirectPendingMediaHelper {
    private DirectPendingMediaHelper() {
    }

    static void enqueueImage(DirectChatListHelper listHelper, String myUid, String localId,
                             String body, String previewUri, int burnAfterSeconds) {
        enqueueImageWithProgress(listHelper, myUid, localId, body, previewUri, burnAfterSeconds, 0);
    }

    static void enqueueImageSpinner(DirectChatListHelper listHelper, String myUid, String localId,
                                    String body, String previewUri, int burnAfterSeconds) {
        enqueueImageWithProgress(listHelper, myUid, localId, body, previewUri, burnAfterSeconds, -1);
    }

    private static void enqueueImageWithProgress(DirectChatListHelper listHelper, String myUid,
                                                 String localId, String body, String previewUri,
                                                 int burnAfterSeconds, int initialProgress) {
        if (listHelper == null || myUid == null || myUid.length() == 0
                || localId == null || localId.length() == 0) {
            return;
        }
        Message pending = new Message();
        pending.id = localId;
        pending.localRequestId = localId;
        pending.localPending = true;
        pending.localFailed = false;
        pending.from_uid = myUid;
        pending.msg_type = "image";
        pending.body = body == null ? "" : body;
        pending.media_url = "";
        pending.thumb_url = "";
        pending.localPreviewUri = previewUri;
        pending.localProgress = initialProgress;
        pending.duration_ms = 0;
        pending.burn_after_seconds = burnAfterSeconds;
        pending.created_at = System.currentTimeMillis() / 1000L;
        append(listHelper, pending);
    }

    static void updateProgress(DirectChatListHelper listHelper, String localId, int percent) {
        int index = findPendingIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        Message pending = listHelper.messageList.get(index);
        pending.localProgress = clampProgress(percent);
        notifyChanged(listHelper, index);
    }

    static void markUploadReady(DirectChatListHelper listHelper, String localId,
                                String mediaUrl, String thumbUrl, String originalUrl) {
        int index = findPendingIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        Message pending = listHelper.messageList.get(index);
        pending.media_url = mediaUrl == null ? "" : mediaUrl;
        pending.thumb_url = thumbUrl == null ? "" : thumbUrl;
        pending.original_url = originalUrl == null ? "" : originalUrl;
        pending.localProgress = -1;
        notifyChanged(listHelper, index);
    }

    private static void append(DirectChatListHelper listHelper, Message pending) {
        listHelper.messageList.add(pending);
        if (pending.id != null && pending.id.length() > 0) {
            listHelper.messageIds.add(pending.id);
        }
        DirectMessageMerger.trimOldestInMemory(
                listHelper.messageList,
                listHelper.messageIds,
                DirectMessageMerger.MAX_ACTIVE_WINDOW_MESSAGES);
        ChatMessageUtil.applyMessageStatus(listHelper.context, listHelper.messageList, listHelper.myUID);
        if (listHelper.adapter != null) {
            listHelper.adapter.markMessageAnimating(pending);
        }
        listHelper.updateRecentFromMessage(pending, true);
        notifyInserted(listHelper, listHelper.messageList.size() - 1, true);
    }

    private static Message findPending(DirectChatListHelper listHelper, String localId) {
        int index = findPendingIndex(listHelper, localId);
        if (index < 0) {
            return null;
        }
        return listHelper.messageList.get(index);
    }

    private static int findPendingIndex(DirectChatListHelper listHelper, String localId) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return -1;
        }
        int size = listHelper.messageList.size();
        for (int i = 0; i < size; i++) {
            Message message = listHelper.messageList.get(i);
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

    private static void notifyInserted(DirectChatListHelper listHelper, int insertedIndex, boolean forceBottom) {
        if (listHelper.adapter != null) {
            if (insertedIndex >= 0 && insertedIndex < listHelper.messageList.size()) {
                listHelper.adapter.notifyItemInserted(insertedIndex);
            } else {
                listHelper.adapter.notifyDataSetChanged();
            }
        }
        saveCache(listHelper);
        if (forceBottom) {
            listHelper.scrollToBottom();
        }
    }

    private static void notifyChanged(DirectChatListHelper listHelper, int changedIndex) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            if (changedIndex >= 0 && changedIndex < listHelper.messageList.size()) {
                listHelper.adapter.notifyItemChanged(changedIndex);
            } else {
                listHelper.adapter.notifyDataSetChanged();
            }
        }
        saveCache(listHelper);
    }

    private static void saveCache(DirectChatListHelper listHelper) {
        if (listHelper.context != null) {
            MessageHistoryCache.saveDirectMessages(
                    listHelper.context,
                    listHelper.friendUID,
                    listHelper.friendNcuid,
                    listHelper.messageList);
        }
    }
}
