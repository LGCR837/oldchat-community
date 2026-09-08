package com.im.oldchat.util;

import android.os.AsyncTask;
import android.os.Build;

// 统一执行 AsyncTask，并把 API 11 符号隔离到独立类，兼容 Android 2.3 Dalvik 验证。
public final class AsyncTaskExecutionCompat {
    private AsyncTaskExecutionCompat() {
    }

    public static <Params, Progress, Result> void execute(
            AsyncTask<Params, Progress, Result> task, Params... params) {
        if (task == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 11) {
            AsyncTaskExecutionApi11.execute(task, params);
        } else {
            task.execute(params);
        }
    }
}
