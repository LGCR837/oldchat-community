package com.im.oldchat.util;

import android.app.NotificationManager;
import android.os.Build;

/**
 * Creates notification channels on API 26+ without hard-referencing
 * android.app.NotificationChannel (keeps API 14 class loading safe).
 */
public final class NotificationChannelCompat {
    // Values from NotificationManager.IMPORTANCE_* (API 26).
    public static final int IMPORTANCE_LOW = 2;
    public static final int IMPORTANCE_DEFAULT = 3;

    private NotificationChannelCompat() {
    }

    public static void ensureChannel(NotificationManager nm, String id, String name, int importance) {
        if (nm == null || id == null || id.length() == 0) {
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            Class<?> channelCls = Class.forName("android.app.NotificationChannel");

            java.lang.reflect.Method getChannel = nm.getClass().getMethod("getNotificationChannel", String.class);
            Object existing = getChannel.invoke(nm, id);
            if (existing != null) {
                return;
            }

            java.lang.reflect.Constructor<?> ctor = channelCls.getConstructor(String.class, CharSequence.class, int.class);
            Object channel = ctor.newInstance(id, name == null ? "" : name, importance);

            java.lang.reflect.Method create = nm.getClass().getMethod("createNotificationChannel", channelCls);
            create.invoke(nm, channel);
        } catch (Throwable ignored) {
        }
    }
}
