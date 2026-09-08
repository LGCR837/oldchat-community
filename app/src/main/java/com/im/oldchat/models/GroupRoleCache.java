package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

public class GroupRoleCache {
    private static final String PREF_NAME = "group_role_cache";
    private static final String KEY_PREFIX = "role_";
    private static final Object LOCK = new Object();
    private static Map<String, Integer> memoryMap = new HashMap<String, Integer>();
    private static boolean memoryLoaded;

    public static void put(Context context, String groupId, String uid, int role) {
        String scopedKey = buildScopedKey(groupId, uid);
        if (context == null || scopedKey.length() == 0) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String key = KEY_PREFIX + scopedKey;
        if (prefs.getInt(key, Integer.MIN_VALUE) == role) {
            return;
        }
        prefs.edit().putInt(key, role).apply();
        synchronized (LOCK) {
            memoryMap.put(scopedKey, Integer.valueOf(role));
            memoryLoaded = true;
        }
    }

    public static void putAll(Context context, String groupId, Map<String, Integer> roles) {
        if (context == null || groupId == null || groupId.isEmpty() || roles == null || roles.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (Map.Entry<String, Integer> entry : roles.entrySet()) {
            String uid = entry.getKey();
            Integer role = entry.getValue();
            if (uid == null || uid.isEmpty() || role == null) {
                continue;
            }
            String scopedKey = buildScopedKey(groupId, uid);
            if (scopedKey.length() == 0) {
                continue;
            }
            String key = KEY_PREFIX + scopedKey;
            if (prefs.getInt(key, Integer.MIN_VALUE) != role.intValue()) {
                editor.putInt(key, role.intValue());
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
            synchronized (LOCK) {
                for (Map.Entry<String, Integer> entry : roles.entrySet()) {
                    String uid = entry.getKey();
                    Integer role = entry.getValue();
                    if (uid == null || uid.isEmpty() || role == null) {
                        continue;
                    }
                    String scopedKey = buildScopedKey(groupId, uid);
                    if (scopedKey.length() == 0) {
                        continue;
                    }
                    memoryMap.put(scopedKey, Integer.valueOf(role.intValue()));
                }
                memoryLoaded = true;
            }
        }
    }

    public static int getRole(Context context, String groupId, String uid) {
        String scopedKey = buildScopedKey(groupId, uid);
        if (context == null || scopedKey.length() == 0) {
            return 0;
        }
        synchronized (LOCK) {
            if (memoryLoaded && memoryMap.containsKey(scopedKey)) {
                Integer cached = memoryMap.get(scopedKey);
                return cached == null ? 0 : cached.intValue();
            }
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int value = prefs.getInt(KEY_PREFIX + scopedKey, 0);
        synchronized (LOCK) {
            if (!memoryLoaded) {
                memoryLoaded = true;
            }
            memoryMap.put(scopedKey, Integer.valueOf(value));
        }
        return value;
    }

    public static void clearAll(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
        synchronized (LOCK) {
            memoryMap.clear();
            memoryLoaded = true;
        }
    }

    private static String buildScopedKey(String groupId, String uid) {
        if (groupId == null || groupId.isEmpty() || uid == null || uid.isEmpty()) {
            return "";
        }
        return groupId + "#" + uid;
    }
}
