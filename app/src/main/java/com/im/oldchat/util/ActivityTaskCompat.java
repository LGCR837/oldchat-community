package com.im.oldchat.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.im.oldchat.ui.LoginActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** API 9/10 不支持 FLAG_ACTIVITY_CLEAR_TASK 时的应用内任务栈兼容实现。 */
public final class ActivityTaskCompat {
    private static final Set<Activity> ACTIVITIES = Collections.newSetFromMap(
            new WeakHashMap<Activity, Boolean>());

    private ActivityTaskCompat() {
    }

    public static synchronized void register(Activity activity) {
        if (activity != null) ACTIVITIES.add(activity);
    }

    public static synchronized void unregister(Activity activity) {
        if (activity != null) ACTIVITIES.remove(activity);
    }

    public static void openCleanLogin(Context context) {
        ArrayList<Activity> snapshot;
        synchronized (ActivityTaskCompat.class) {
            snapshot = new ArrayList<Activity>(ACTIVITIES);
        }
        for (Activity activity : snapshot) {
            if (activity == null || activity.isFinishing()) continue;
            try { activity.finish(); } catch (Exception ignored) { }
        }
        Intent intent = new Intent(context, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 11) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        context.startActivity(intent);
    }
}
