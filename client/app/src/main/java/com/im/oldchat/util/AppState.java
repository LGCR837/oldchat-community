package com.im.oldchat.util;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import java.util.List;

public class AppState {
    private static int startedCount = 0;
    private static boolean initialized = false;
    private static Context appContext;

    public static void init(Application app) {
        if (initialized) {
            return;
        }
        initialized = true;
        appContext = app.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 14) {
            AppStateApi14.init(app);
        }
    }

    public static boolean isForeground() {
        if (startedCount > 0) {
            return true;
        }
        Context ctx = appContext;
        if (ctx == null) {
            return false;
        }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        String pkg = ctx.getPackageName();
        for (ActivityManager.RunningAppProcessInfo proc : processes) {
            if (pkg.equals(proc.processName)) {
                int importance = proc.importance;
                return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
            }
        }
        return false;
    }

    static boolean onActivityStarted() {
        startedCount++;
        return startedCount == 1;
    }

    static void onActivityStopped() {
        startedCount--;
        if (startedCount < 0) startedCount = 0;
    }
}
