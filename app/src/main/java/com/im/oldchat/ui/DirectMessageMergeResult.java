package com.im.oldchat.ui;

public class DirectMessageMergeResult {
    public final int currentOffset;
    public final boolean hasMore;
    public final boolean gapReset;

    public DirectMessageMergeResult(int currentOffset, boolean hasMore, boolean gapReset) {
        this.currentOffset = currentOffset;
        this.hasMore = hasMore;
        this.gapReset = gapReset;
    }
}
