package com.im.oldchat.util;

import java.util.concurrent.atomic.AtomicLong;

/** 进程内资料更新信号，用于让已打开的群聊立即丢弃成员资料缓存。 */
public final class ProfileUpdateSignal {
    private static final AtomicLong VERSION = new AtomicLong(1L);

    private ProfileUpdateSignal() {
    }

    public static long currentVersion() {
        return VERSION.get();
    }

    public static void markUpdated() {
        VERSION.incrementAndGet();
    }
}
