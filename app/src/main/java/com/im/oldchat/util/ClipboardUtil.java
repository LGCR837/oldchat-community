package com.im.oldchat.util;

import android.content.Context;
import android.os.Build;

public class ClipboardUtil {
    private ClipboardUtil() {
    }

    public static void copyText(Context context, String text) {
        if (context == null || text == null || text.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT < 11) {
            android.text.ClipboardManager clipboard =
                    (android.text.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setText(text);
                android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        try {
            Class<?> clipDataCls = Class.forName("android.content.ClipData");
            Class<?> clipMgrCls = Class.forName("android.content.ClipboardManager");
            Object clip = clipDataCls.getMethod("newPlainText", CharSequence.class, CharSequence.class)
                    .invoke(null, "message", text);
            Object clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipMgrCls.isInstance(clipboard)) {
                clipMgrCls.getMethod("setPrimaryClip", clipDataCls).invoke(clipboard, clip);
                android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }
}
