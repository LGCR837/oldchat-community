package com.im.oldchat.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import java.io.BufferedReader;
import java.io.FileReader;

public class ProcessUtil {
    public static String getProcessName() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                java.lang.reflect.Method m = android.app.Application.class.getMethod("getProcessName");
                Object v = m.invoke(null);
                if (v instanceof String) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        String name = readProcessName();
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        return null;
    }

    private static String readProcessName() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String line = reader.readLine();
            if (line != null) {
                return line.trim();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                }
            }
        }
        return null;
    }
}
