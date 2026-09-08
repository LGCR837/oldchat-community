package com.im.oldchat.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

public final class UiPerformance {
    private static final int LOW_MEMORY_CLASS_MB = 128;
    private static Boolean cachedReducedMotion;

    private UiPerformance() {
    }

    public static boolean shouldReduceMotion(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            return true;
        }
        if (cachedReducedMotion != null) {
            return cachedReducedMotion.booleanValue();
        }
        boolean reduce = Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2;
        try {
            ActivityManager am = context == null
                    ? null
                    : (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                int memoryClass = am.getMemoryClass();
                if (memoryClass > 0 && memoryClass <= LOW_MEMORY_CLASS_MB) {
                    reduce = true;
                }
                if (!reduce && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    reduce = am.isLowRamDevice();
                }
            }
        } catch (Throwable ignored) {
        }
        cachedReducedMotion = Boolean.valueOf(reduce);
        return reduce;
    }
}
