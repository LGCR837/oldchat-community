package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserNameCache {
    private static final String PREF_NAME = "user_name_cache";
    private static final String KEY_PREFIX = "name_";
    private static final int MAX_NAME_LENGTH = 15;
    private static final Object LOCK = new Object();
    private static Map<String, String> memoryMap = new HashMap<String, String>();
    private static boolean memoryLoaded;

    public static void put(Context context, String uid, String name) {
        if (context == null || uid == null || uid.isEmpty()) {
            return;
        }
        String finalName = sanitize(name);
        if (finalName.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String key = KEY_PREFIX + uid;
        String existing = prefs.getString(key, "");
        if (finalName.equals(existing)) {
            return;
        }
        prefs.edit().putString(key, finalName).apply();
        synchronized (LOCK) {
            memoryMap.put(uid, finalName);
            memoryLoaded = true;
        }
    }

    public static void putAll(Context context, Map<String, String> names) {
        if (context == null || names == null || names.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String uid = entry.getKey();
            if (uid == null || uid.isEmpty()) {
                continue;
            }
            String name = sanitize(entry.getValue());
            if (name.isEmpty()) {
                continue;
            }
            String key = KEY_PREFIX + uid;
            String existing = prefs.getString(key, "");
            if (!name.equals(existing)) {
                editor.putString(key, name);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
            synchronized (LOCK) {
                for (Map.Entry<String, String> entry : names.entrySet()) {
                    String uid = entry.getKey();
                    if (uid == null || uid.isEmpty()) {
                        continue;
                    }
                    String name = sanitize(entry.getValue());
                    if (!name.isEmpty()) {
                        memoryMap.put(uid, name);
                    }
                }
                memoryLoaded = true;
            }
        }
    }

    public static void mergeUsers(Context context, List<User> users) {
        if (context == null || users == null || users.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (User u : users) {
            if (u == null || u.uid == null || u.uid.isEmpty()) {
                continue;
            }
            String name = FriendNameResolver.resolve(u.remark_name, u.display_name, u.username, u.uid);
            if (name.isEmpty()) {
                continue;
            }
            String key = KEY_PREFIX + u.uid;
            String existing = prefs.getString(key, "");
            if (!name.equals(existing)) {
                editor.putString(key, name);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
            synchronized (LOCK) {
                for (User u : users) {
                    if (u == null || u.uid == null || u.uid.isEmpty()) {
                        continue;
                    }
                    String name = FriendNameResolver.resolve(u.remark_name, u.display_name, u.username, u.uid);
                    if (!name.isEmpty()) {
                        memoryMap.put(u.uid, name);
                    }
                }
                memoryLoaded = true;
            }
        }
    }

    public static String getName(Context context, String uid) {
        if (context == null || uid == null || uid.isEmpty()) {
            return "";
        }
        synchronized (LOCK) {
            if (memoryLoaded && memoryMap.containsKey(uid)) {
                return memoryMap.get(uid);
            }
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String value = sanitize(prefs.getString(KEY_PREFIX + uid, ""));
        synchronized (LOCK) {
            if (!memoryLoaded) {
                memoryLoaded = true;
            }
            memoryMap.put(uid, value);
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

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String out = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (out.length() > MAX_NAME_LENGTH) {
            out = out.substring(0, MAX_NAME_LENGTH);
        }
        return out;
    }
}
