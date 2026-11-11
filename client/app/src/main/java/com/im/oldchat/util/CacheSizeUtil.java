package com.im.oldchat.util;

import android.content.Context;
import java.io.File;

public class CacheSizeUtil {
    private CacheSizeUtil() {
    }

    public static long getDirSize(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        if (dir.isFile()) {
            return dir.length();
        }
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            total += getDirSize(file);
        }
        return total;
    }

    public static long getSharedPrefsSize(Context context, String prefsName) {
        if (context == null || prefsName == null || prefsName.isEmpty()) {
            return 0;
        }
        File prefsDir = new File(context.getFilesDir().getParentFile(), "shared_prefs");
        File prefsFile = new File(prefsDir, prefsName + ".xml");
        if (!prefsFile.exists()) {
            return 0;
        }
        return prefsFile.length();
    }

    public static void clearSharedPrefs(Context context, String prefsName) {
        if (context == null || prefsName == null || prefsName.isEmpty()) {
            return;
        }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format("%.1f GB", gb);
    }
}
