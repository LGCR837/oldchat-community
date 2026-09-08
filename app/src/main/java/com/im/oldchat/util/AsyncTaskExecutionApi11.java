package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.os.AsyncTask;

@TargetApi(11)
final class AsyncTaskExecutionApi11 {
    private AsyncTaskExecutionApi11() {
    }

    static <Params, Progress, Result> void execute(
            AsyncTask<Params, Progress, Result> task, Params... params) {
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
    }
}
