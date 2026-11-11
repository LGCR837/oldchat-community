package com.im.oldchat.ui;

import com.im.oldchat.models.GroupMessage;

import java.util.HashSet;
import java.util.List;

final class GroupMessageInsertHelper {
    static final class InsertResult {
        final int insertedIndex;
        final boolean inserted;
        final boolean removedHead;

        InsertResult(int insertedIndex, boolean inserted, boolean removedHead) {
            this.insertedIndex = insertedIndex;
            this.inserted = inserted;
            this.removedHead = removedHead;
        }
    }

    private GroupMessageInsertHelper() {
    }

    static InsertResult insertIntoActiveWindow(List<GroupMessage> messageList,
                                               HashSet<String> messageIds,
                                               GroupMessage message,
                                               int maxCount) {
        if (messageList == null || message == null) {
            return new InsertResult(-1, false, false);
        }
        int insertIndex = findInsertIndex(messageList, message);
        messageList.add(insertIndex, message);
        if (message.id != null && message.id.length() > 0 && messageIds != null) {
            messageIds.add(message.id);
        }
        boolean removedHead = trimOldest(messageList, messageIds, maxCount);
        if (!removedHead) {
            return new InsertResult(insertIndex, true, false);
        }
        int finalIndex = messageList.indexOf(message);
        if (finalIndex < 0) {
            return new InsertResult(-1, false, true);
        }
        return new InsertResult(finalIndex, true, true);
    }

    private static boolean trimOldest(List<GroupMessage> messageList,
                                      HashSet<String> messageIds,
                                      int maxCount) {
        if (messageList == null || maxCount <= 0 || messageList.size() <= maxCount) {
            return false;
        }
        GroupMessage removed = messageList.remove(0);
        if (removed != null && removed.id != null && removed.id.length() > 0 && messageIds != null) {
            messageIds.remove(removed.id);
        }
        return true;
    }

    private static int findInsertIndex(List<GroupMessage> messageList, GroupMessage target) {
        int low = 0;
        int high = messageList == null ? 0 : messageList.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            GroupMessage current = messageList.get(mid);
            if (compare(target, current) < 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private static int compare(GroupMessage left, GroupMessage right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left.created_at < right.created_at) {
            return -1;
        }
        if (left.created_at > right.created_at) {
            return 1;
        }
        long leftSeq = left.sort_seq;
        long rightSeq = right.sort_seq;
        if (leftSeq > 0L && rightSeq > 0L) {
            if (leftSeq < rightSeq) {
                return -1;
            }
            if (leftSeq > rightSeq) {
                return 1;
            }
        }
        int idCompare = safeString(left.id).compareTo(safeString(right.id));
        if (idCompare != 0) {
            return idCompare;
        }
        return safeString(left.from_uid).compareTo(safeString(right.from_uid));
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }
}
