package com.im.oldchat.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.WeakHashMap;

public final class ScreenPerfTracer {
    private static final String TAG = "OldChatPerf";
    private static final Object LOCK = new Object();
    private static final WeakHashMap<Activity, Long> ACTIVITY_START_MS = new WeakHashMap<Activity, Long>();
    private static final WeakHashMap<Activity, Boolean> FIRST_DRAW_WATCHING = new WeakHashMap<Activity, Boolean>();
    private static final WeakHashMap<Activity, Boolean> FIRST_DRAW_LOGGED = new WeakHashMap<Activity, Boolean>();

    private ScreenPerfTracer() {
    }

    public static void onActivityCreate(Activity activity) {
        if (activity == null) {
            return;
        }
        synchronized (LOCK) {
            ACTIVITY_START_MS.put(activity, Long.valueOf(SystemClock.uptimeMillis()));
            FIRST_DRAW_WATCHING.remove(activity);
            FIRST_DRAW_LOGGED.remove(activity);
        }
    }

    public static void onContentReady(final Activity activity) {
        if (activity == null) {
            return;
        }
        synchronized (LOCK) {
            if (Boolean.TRUE.equals(FIRST_DRAW_WATCHING.get(activity))
                    || Boolean.TRUE.equals(FIRST_DRAW_LOGGED.get(activity))) {
                return;
            }
            FIRST_DRAW_WATCHING.put(activity, Boolean.TRUE);
        }
        final View root = activity.findViewById(android.R.id.content);
        if (root == null) {
            clearWatching(activity);
            return;
        }
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            clearWatching(activity);
            return;
        }
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver currentObserver = root.getViewTreeObserver();
                if (currentObserver != null && currentObserver.isAlive()) {
                    currentObserver.removeGlobalOnLayoutListener(this);
                }
                synchronized (LOCK) {
                    FIRST_DRAW_WATCHING.remove(activity);
                    FIRST_DRAW_LOGGED.put(activity, Boolean.TRUE);
                }
                writeLog(activity, activity.getClass().getSimpleName(), "first_draw",
                        elapsedFromCreate(activity), buildActivityDetail(activity));
            }
        });
    }

    public static long now() {
        return SystemClock.uptimeMillis();
    }

    public static void trace(Context context, String space, String stage, long startedAtMs, String detail) {
        long elapsed = startedAtMs > 0L ? (SystemClock.uptimeMillis() - startedAtMs) : -1L;
        writeLog(context, space, stage, elapsed, detail);
    }

    private static long elapsedFromCreate(Activity activity) {
        synchronized (LOCK) {
            Long started = ACTIVITY_START_MS.get(activity);
            if (started == null) {
                return -1L;
            }
            return SystemClock.uptimeMillis() - started.longValue();
        }
    }

    private static void clearWatching(Activity activity) {
        synchronized (LOCK) {
            FIRST_DRAW_WATCHING.remove(activity);
        }
    }

    private static String buildActivityDetail(Activity activity) {
        Intent intent = activity == null ? null : activity.getIntent();
        if (intent == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendExtra(sb, "uid", intent.getStringExtra("uid"));
        appendExtra(sb, "friend_uid", intent.getStringExtra("friend_uid"));
        appendExtra(sb, "group_id", intent.getStringExtra("group_id"));
        appendExtra(sb, "moment_id", intent.getStringExtra("moment_id"));
        return sb.toString();
    }

    private static void appendExtra(StringBuilder sb, String key, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(key).append('=').append(value);
    }

    private static void writeLog(Context context, String space, String stage, long costMs, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("space=").append(space == null ? "" : space);
        sb.append(" stage=").append(stage == null ? "" : stage);
        if (costMs >= 0L) {
            sb.append(" cost=").append(costMs).append("ms");
        }
        if (detail != null && detail.length() > 0) {
            sb.append(' ').append(detail);
        }
        String message = sb.toString();
        if (context != null) {
            try {
                LogManager.getInstance(context.getApplicationContext()).d(TAG, message);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.d(TAG, message);
    }
}
