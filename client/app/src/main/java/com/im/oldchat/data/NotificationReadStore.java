package com.im.oldchat.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;

public class NotificationReadStore {
    private static final String PREFS = "notification_read";
    private static final String KEY_READ_IDS = "read_ids";

    public static boolean isRead(Context context, String notificationId) {
        if (context == null || notificationId == null || notificationId.isEmpty()) {
            return false;
        }
        Set<String> readIds = getReadIds(context);
        return readIds.contains(notificationId);
    }

    public static void markAsRead(Context context, String notificationId) {
        if (context == null || notificationId == null || notificationId.isEmpty()) {
            return;
        }
        Set<String> readIds = getReadIds(context);
        readIds.add(notificationId);
        saveReadIds(context, readIds);
    }

    public static void clearAll(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static Set<String> getReadIds(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 11) {
            Set<String> set = prefs.getStringSet(KEY_READ_IDS, new HashSet<String>());
            return new HashSet<String>(set == null ? new HashSet<String>() : set);
        }
        String raw = prefs.getString(KEY_READ_IDS, "");
        Set<String> result = new HashSet<String>();
        if (raw != null && raw.length() > 0) {
            String[] parts = raw.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                String value = parts[i];
                if (value != null && value.length() > 0) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static void saveReadIds(Context context, Set<String> readIds) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 11) {
            prefs.edit().putStringSet(KEY_READ_IDS, readIds).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (readIds != null) {
            for (String id : readIds) {
                if (id == null || id.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(id);
            }
        }
        prefs.edit().putString(KEY_READ_IDS, sb.toString()).apply();
    }
}
