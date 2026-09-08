package com.im.oldchat.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 低内存设备（Android 2.3 小堆）文本光标/选择手柄适配。
 * 系统默认手柄是 PNG 资源，点击输入框时 appcompat 解码该 PNG 可能 OOM；
 * 这里用纯色 shape（无需解码位图）替换手柄，从源头消除崩溃点。
 */
final class LowMemoryHandleUtil {
    private static final int LOW_MEM_CLASS_MB = 32;

    private LowMemoryHandleUtil() {
    }

    /** 是否需要替换手柄：Android 2.3 或应用堆 ≤32MB 的低内存设备。 */
    static boolean isLowMemoryDevice(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT <= 10) {
            return true;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                return am.getMemoryClass() <= LOW_MEM_CLASS_MB;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 替换 activity content view 内所有 EditText 的文本手柄为纯色 shape。 */
    static void applyToRoot(View root) {
        if (root == null) {
            return;
        }
        Drawable handle = buildHandleDrawable(root.getContext());
        applyRecursive(root, handle);
    }

    private static void applyRecursive(View view, Drawable handle) {
        if (view instanceof EditText) {
            TextView tv = (TextView) view;
            try {
                tv.setTextSelectHandle(handle);
                tv.setTextSelectHandleLeft(handle);
                tv.setTextSelectHandleRight(handle);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyRecursive(group.getChildAt(i), handle);
            }
        }
    }

    private static Drawable buildHandleDrawable(Context context) {
        // 8dp x 14dp 的半透明小圆角条，仅用于光标指示，无需解码位图。
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setSize(Math.round(4f * density), Math.round(14f * density));
        drawable.setCornerRadius(2f * density);
        drawable.setColor(0x802563EB);
        return drawable;
    }
}
