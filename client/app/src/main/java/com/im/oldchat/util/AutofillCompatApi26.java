package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.View;

/**
 * 兼容处理 Android 8.0+ (API 26) Autofill 机制：
 * 部分定制系统/无 GMS 环境在 Autofill 服务不可用或异常时，
 * View.setVisibility 会触发 AutofillManager.notifyViewVisibilityChanged，
 * 进而因 IAutoFillManager.addClient 为 null 抛出 NullPointerException 导致崩溃。
 * 为输入框禁用 Autofill 并提供安全的 setVisibility 封装。
 */
public final class AutofillCompatApi26 {
    private AutofillCompatApi26() {}

    @TargetApi(26)
    public static void disableAutofill(View view) {
        if (view == null || Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        } catch (Throwable ignored) {
        }
    }

    public static void safeSetVisibility(View view, int visibility) {
        if (view == null) return;
        try {
            view.setVisibility(visibility);
        } catch (Throwable ignored) {
            // 捕获 Android 系统 AutofillManager / framework 在特定 ROM 下抛出的 NPE
        }
    }
}
