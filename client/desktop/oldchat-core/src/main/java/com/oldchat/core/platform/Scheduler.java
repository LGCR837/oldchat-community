package com.oldchat.core.platform;

/** 线程调度抽象（替代 Android Handler/Looper，桌面端由 JavaFX Platform.runLater 实现）。 */
@FunctionalInterface
public interface Scheduler {
    void runLater(Runnable task);
}
