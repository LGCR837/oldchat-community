package com.im.oldchat.util;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

final class AppDpiScaleApplier {
    private static float baseDensity = -1f;
    private static float baseScaledDensity = -1f;
    private static int baseDensityDpi = 0;

    private AppDpiScaleApplier() {
    }

    static void apply(Resources resources, float scale) {
        if (resources == null) {
            return;
        }
        if (scale <= 0f) {
            scale = 1.0f;
        }
        try {
            DisplayMetrics metrics = resources.getDisplayMetrics();
            if (metrics == null) {
                return;
            }
            ensureBaseMetrics(metrics);
            float targetDensity = baseDensity * scale;
            float targetScaledDensity = baseScaledDensity * scale;
            int targetDensityDpi = (int) (baseDensityDpi * scale + 0.5f);
            if (targetDensityDpi <= 0) {
                targetDensityDpi = (int) (DisplayMetrics.DENSITY_DEFAULT * targetDensity + 0.5f);
            }
            if (targetDensity <= 0f || targetScaledDensity <= 0f || targetDensityDpi <= 0) {
                return;
            }
            metrics.density = targetDensity;
            metrics.scaledDensity = targetScaledDensity;
            metrics.densityDpi = targetDensityDpi;
            Configuration configuration = new Configuration(resources.getConfiguration());
            setConfigDensityDpi(configuration, targetDensityDpi);
            resources.updateConfiguration(configuration, metrics);
        } catch (Throwable ignored) {
        }
    }

    private static void ensureBaseMetrics(DisplayMetrics metrics) {
        if (metrics == null || baseDensity > 0f) {
            return;
        }
        baseDensity = metrics.density;
        baseScaledDensity = metrics.scaledDensity;
        baseDensityDpi = metrics.densityDpi;
        if (baseDensityDpi <= 0 && baseDensity > 0f) {
            baseDensityDpi = (int) (DisplayMetrics.DENSITY_DEFAULT * baseDensity + 0.5f);
        }
        if (baseDensity <= 0f) {
            baseDensity = 1.0f;
        }
        if (baseScaledDensity <= 0f) {
            baseScaledDensity = baseDensity;
        }
        if (baseDensityDpi <= 0) {
            baseDensityDpi = DisplayMetrics.DENSITY_DEFAULT;
        }
    }

    private static void setConfigDensityDpi(Configuration configuration, int densityDpi) {
        if (configuration == null || densityDpi <= 0) {
            return;
        }
        try {
            java.lang.reflect.Field field = Configuration.class.getField("densityDpi");
            field.setInt(configuration, densityDpi);
        } catch (Throwable ignored) {
        }
    }
}
