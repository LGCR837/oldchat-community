package com.im.oldchat.util;

import android.content.Context;

import java.lang.reflect.Method;

/** Android 2.3/4.x 启动前必须完成的最小兼容初始化。 */
public final class LegacyPlatformCompat {
    private static boolean installed;

    private LegacyPlatformCompat() {
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        enableCompatVectors();
    }

    private static void enableCompatVectors() {
        try {
            Class<?> delegate = Class.forName("android.support.v7.app.AppCompatDelegate");
            Method method = delegate.getMethod("setCompatVectorFromResourcesEnabled", Boolean.TYPE);
            method.invoke(null, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }
}
