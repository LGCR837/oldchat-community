package com.oldchat.fx;

import com.oldchat.core.platform.Scheduler;
import javafx.application.Platform;

/** 将 core 的 Scheduler 接到 JavaFX 应用线程。 */
public class FxScheduler implements Scheduler {
    @Override
    public void runLater(Runnable task) {
        Platform.runLater(task);
    }
}
