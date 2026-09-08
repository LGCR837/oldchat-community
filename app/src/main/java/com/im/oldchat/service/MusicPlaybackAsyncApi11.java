package com.im.oldchat.service;

import android.annotation.TargetApi;
import android.os.AsyncTask;

// 将 API 11 的 AsyncTask 并行执行符号隔离，避免 Android 2.3 Dalvik 验证服务类时失败。
@TargetApi(11)
final class MusicPlaybackAsyncApi11 {
    private MusicPlaybackAsyncApi11() {
    }

    static void execute(AsyncTask<Void, Void, java.io.File> task) {
        if (task == null) {
            return;
        }
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
    }
}
