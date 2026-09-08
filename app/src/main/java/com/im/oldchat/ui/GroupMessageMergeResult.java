package com.im.oldchat.ui;

class GroupMessageMergeResult {
    final int currentOffset;
    final boolean hasMore;
    final boolean gapReset;
    final boolean appendedTailOnly;
    final int insertedCount;
    final boolean updatedExisting;

    GroupMessageMergeResult(int currentOffset, boolean hasMore, boolean gapReset,
                            boolean appendedTailOnly, int insertedCount,
                            boolean updatedExisting) {
        this.currentOffset = currentOffset;
        this.hasMore = hasMore;
        this.gapReset = gapReset;
        this.appendedTailOnly = appendedTailOnly;
        this.insertedCount = insertedCount;
        this.updatedExisting = updatedExisting;
    }
}
