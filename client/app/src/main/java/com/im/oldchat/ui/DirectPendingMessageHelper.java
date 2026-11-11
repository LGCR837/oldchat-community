package com.im.oldchat.ui;

import com.im.oldchat.models.Message;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.util.UiPerformance;

import java.util.ArrayList;

final class DirectPendingMessageHelper {
    private DirectPendingMessageHelper() {
    }

    static String enqueueText(DirectChatListHelper listHelper, String myUid, String body, int burnAfterSeconds) {
        if (listHelper == null || myUid == null || myUid.length() == 0) {
            return null;
        }
        Message pending = new Message();
        String localId = buildLocalId();
        pending.id = localId;
        pending.localRequestId = localId;
        pending.localPending = true;
        pending.localFailed = false;
        pending.from_uid = myUid;
        pending.msg_type = "text";
        pending.body = body == null ? "" : body;
        pending.media_url = "";
        pending.thumb_url = "";
        pending.localPreviewUri = null;
        pending.localProgress = -1;
        pending.duration_ms = 0;
        pending.burn_after_seconds = burnAfterSeconds;
        pending.created_at = System.currentTimeMillis() / 1000L;
        append(listHelper, pending, true);
        return localId;
    }

    static boolean completeText(DirectChatListHelper listHelper, String localId, Message serverMessage) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return false;
        }
        int index = findLocalIndex(listHelper, localId);
        if (index < 0) {
            if (canUseLooseMatch(serverMessage)) {
                index = findMatchingPendingIndex(listHelper, serverMessage);
            }
            if (index < 0) {
                return false;
            }
        }
        Message pending = listHelper.messageList.get(index);
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
        // pending 原地确认即可；正常发送位于列表尾部，无需再次全表排序和全量重绘。
        ArrayList<Integer> changedPositions = ChatMessageUtil.applyMessageStatusAndCollectChanged(
                listHelper.context, listHelper.messageList, listHelper.myUID);
        notifyChangedWithStatus(listHelper, index, changedPositions);
        return true;
    }

    static void failText(DirectChatListHelper listHelper, String localId) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return;
        }
        int index = findLocalIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        Message pending = listHelper.messageList.get(index);
        pending.localPending = false;
        pending.localFailed = true;
        pending.localProgress = -1;
        pending.status = Message.STATUS_NONE;
        notifyChanged(listHelper, index);
    }

    static void removeText(DirectChatListHelper listHelper, String localId) {
        if (listHelper == null || localId == null || localId.length() == 0) {
            return;
        }
        int index = findLocalIndex(listHelper, localId);
        if (index < 0) {
            return;
        }
        listHelper.messageList.remove(index);
        listHelper.messageIds.remove(localId);
        ArrayList<Integer> changedPositions = ChatMessageUtil.applyMessageStatusAndCollectChanged(
                listHelper.context, listHelper.messageList, listHelper.myUID);
        notifyRemovedWithStatus(listHelper, index, changedPositions);
    }

    private static void append(DirectChatListHelper listHelper, Message pending, boolean forceBottom) {
        if (pending == null) {
            return;
        }
        listHelper.messageList.add(pending);
        if (pending.id != null && pending.id.length() > 0) {
            listHelper.messageIds.add(pending.id);
        }
        int removedCount = DirectMessageMerger.trimOldestInMemory(
                listHelper.messageList,
                listHelper.messageIds,
                DirectMessageMerger.MAX_ACTIVE_WINDOW_MESSAGES);
        ArrayList<Integer> changedPositions = ChatMessageUtil.applyMessageStatusAndCollectChanged(
                listHelper.context, listHelper.messageList, listHelper.myUID);
        if (listHelper.adapter != null && !UiPerformance.shouldReduceMotion(listHelper.context)) {
            listHelper.adapter.markMessageAnimating(pending);
        }
        listHelper.updateRecentFromMessage(pending, true);
        notifyPendingInserted(listHelper, listHelper.messageList.size() - 1,
                removedCount, changedPositions);
        if (forceBottom) {
            listHelper.scrollToBottom();
        }
    }

    private static int findLocalIndex(DirectChatListHelper listHelper, String localId) {
        int size = listHelper.messageList.size();
        for (int i = 0; i < size; i++) {
            Message message = listHelper.messageList.get(i);
            if (message != null && localId.equals(message.localRequestId)) {
                return i;
            }
            if (message != null && localId.equals(message.id) && message.localPending) {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingPendingIndex(DirectChatListHelper listHelper, Message serverMessage) {
        if (listHelper == null || serverMessage == null) {
            return -1;
        }
        int size = listHelper.messageList.size();
        int matchedIndex = -1;
        for (int i = 0; i < size; i++) {
            Message pending = listHelper.messageList.get(i);
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

    private static boolean canUseLooseMatch(Message serverMessage) {
        if (serverMessage == null) {
            return false;
        }
        String type = serverMessage.msg_type == null ? "" : serverMessage.msg_type.trim().toLowerCase();
        if (!"text".equals(type)) {
            return false;
        }
        return isEmpty(serverMessage.media_url) && isEmpty(serverMessage.thumb_url);
    }

    private static void copyServerFields(Message target, Message serverMessage) {
        target.id = serverMessage.id;
        target.thread_id = serverMessage.thread_id;
        target.from_uid = serverMessage.from_uid;
        target.body = serverMessage.body;
        target.msg_type = serverMessage.msg_type;
        target.media_url = serverMessage.media_url;
        target.thumb_url = serverMessage.thumb_url;
        target.duration_ms = serverMessage.duration_ms;
        target.burn_after_seconds = serverMessage.burn_after_seconds;
        target.burn_start_at = serverMessage.burn_start_at;
        target.burn_preview_text = serverMessage.burn_preview_text;
        target.created_at = serverMessage.created_at;
        target.sort_seq = serverMessage.sort_seq;
        target.status = serverMessage.status;
        target.recall_edit_type = serverMessage.recall_edit_type;
        target.recall_edit_text = serverMessage.recall_edit_text;
        target.localPending = false;
        target.localFailed = false;
        target.localRequestId = null;
        target.localPreviewUri = null;
        target.localProgress = -1;
    }

    private static void notifyPendingInserted(DirectChatListHelper listHelper, int insertedIndex,
                                              int removedCount,
                                              ArrayList<Integer> changedPositions) {
        if (listHelper == null || listHelper.adapter == null) {
            return;
        }
        if (removedCount == 1) {
            listHelper.adapter.notifyItemRemoved(0);
        } else if (removedCount > 1) {
            listHelper.adapter.notifyDataSetChanged();
            return;
        }
        if (insertedIndex >= 0 && insertedIndex < listHelper.messageList.size()) {
            listHelper.adapter.notifyItemInserted(insertedIndex);
            listHelper.notifyDirectChangedPositions(changedPositions, insertedIndex);
        } else {
            listHelper.adapter.notifyDataSetChanged();
        }
        // pending 不会写入历史缓存，服务端确认后再保存一次，避免发送时复制 200 条消息。
    }

    private static void notifyChangedWithStatus(DirectChatListHelper listHelper, int changedIndex,
                                                ArrayList<Integer> changedPositions) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            if (changedIndex >= 0 && changedIndex < listHelper.messageList.size()) {
                listHelper.adapter.notifyItemChanged(changedIndex);
                listHelper.notifyDirectChangedPositions(changedPositions, changedIndex);
            } else {
                listHelper.adapter.notifyDataSetChanged();
            }
        }
        saveCache(listHelper);
    }

    private static void notifyRemovedWithStatus(DirectChatListHelper listHelper, int removedIndex,
                                                ArrayList<Integer> changedPositions) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            if (removedIndex >= 0) {
                listHelper.adapter.notifyItemRemoved(removedIndex);
                listHelper.notifyDirectChangedPositions(changedPositions, -1);
            } else {
                listHelper.adapter.notifyDataSetChanged();
            }
        }
        saveCache(listHelper);
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

    private static void notifyReloadWithStatus(DirectChatListHelper listHelper,
                                               ArrayList<Integer> changedPositions) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            listHelper.adapter.notifyDataSetChanged();
            listHelper.notifyDirectChangedPositions(changedPositions, -1);
        }
        saveCache(listHelper);
    }

    private static void notifyRemoved(DirectChatListHelper listHelper, int removedIndex) {
        if (listHelper == null) {
            return;
        }
        if (listHelper.adapter != null) {
            if (removedIndex >= 0) {
                listHelper.adapter.notifyItemRemoved(removedIndex);
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

    private static String buildLocalId() {
        return "local_text_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 1000));
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
}
