package com.im.oldchat.ui;

public final class UiMemoryCleaner {
    private UiMemoryCleaner() {
    }

    public static void clearMemoryCaches() {
        EmojiBitmapLoader.clearMemoryCache();
        PendingImagePreviewBinder.clearMemoryCache();
    }

    public static void trimMemory(int level) {
        EmojiBitmapLoader.trimMemory(level);
        PendingImagePreviewBinder.trimMemory(level);
    }
}
