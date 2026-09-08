package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class UidAliasStore {
    private static final String PREFS = "uid_alias_store";
    private static final String KEY_MAP = "uid_alias_map";
    private static final int MAX_CHAIN_DEPTH = 8;
    private static final Object LOCK = new Object();
    private static Map<String, String> memoryMap = new HashMap<String, String>();
    private static boolean memoryLoaded;

    private UidAliasStore() {
    }

    public static void putAlias(Context context, String oldUid, String newUid) {
        if (context == null) {
            return;
        }
        String oldNorm = normalizeUid(oldUid);
        String newNorm = normalizeUid(newUid);
        if (oldNorm.length() == 0 || newNorm.length() == 0 || oldNorm.equals(newNorm)) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject map = loadMap(prefs);
        String target = resolveInternal(map, newNorm);
        String oldTarget = resolveInternal(map, oldNorm);
        if (target.equals(oldTarget)) {
            return;
        }
        try {
            map.put(oldNorm, target);
        } catch (Exception e) {
            return;
        }
        // Path compression for keys that still point to old aliases.
        JSONArray keys = map.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String key = normalizeUid(keys.optString(i));
                if (key.length() == 0) {
                    continue;
                }
                String value = resolveInternal(map, key);
                if (!value.equals(key)) {
                    try {
                        map.put(key, value);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        prefs.edit().putString(KEY_MAP, map.toString()).apply();
        synchronized (LOCK) {
            memoryMap = toMemoryMap(map);
            memoryLoaded = true;
        }
    }

    /** 一次写入多组 UID -> NCUID，避免群成员列表逐条读写 SharedPreferences。 */
    public static void putAliases(Context context, Map<String, String> aliases) {
        if (context == null || aliases == null || aliases.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject map = loadMap(prefs);
        boolean changed = false;
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String oldNorm = normalizeUid(entry.getKey());
            String newNorm = normalizeUid(entry.getValue());
            if (oldNorm.length() == 0 || newNorm.length() == 0 || oldNorm.equals(newNorm)) {
                continue;
            }
            String target = resolveInternal(map, newNorm);
            if (!target.equals(resolveInternal(map, oldNorm))) {
                try {
                    map.put(oldNorm, target);
                    changed = true;
                } catch (Exception ignored) {
                }
            }
        }
        if (!changed) {
            return;
        }
        prefs.edit().putString(KEY_MAP, map.toString()).apply();
        synchronized (LOCK) {
            memoryMap = toMemoryMap(map);
            memoryLoaded = true;
        }
    }

    public static String resolve(Context context, String uid) {
        String normalized = normalizeUid(uid);
        if (normalized.length() == 0 || context == null) {
            return normalized;
        }
        Map<String, String> map = getMemoryMap(context.getApplicationContext());
        return resolveInternal(map, normalized);
    }

    public static void clearAll(Context context) {
        if (context == null) {
            return;
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
        synchronized (LOCK) {
            memoryMap.clear();
            memoryLoaded = true;
        }
    }

    private static JSONObject loadMap(SharedPreferences prefs) {
        try {
            String raw = prefs.getString(KEY_MAP, "{}");
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static Map<String, String> getMemoryMap(Context context) {
        synchronized (LOCK) {
            if (memoryLoaded) {
                return memoryMap;
            }
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject map = loadMap(prefs);
        Map<String, String> parsed = toMemoryMap(map);
        synchronized (LOCK) {
            if (!memoryLoaded) {
                memoryMap = parsed;
                memoryLoaded = true;
            }
            return memoryMap;
        }
    }

    private static Map<String, String> toMemoryMap(JSONObject map) {
        Map<String, String> out = new HashMap<String, String>();
        if (map == null) {
            return out;
        }
        JSONArray keys = map.names();
        if (keys == null) {
            return out;
        }
        for (int i = 0; i < keys.length(); i++) {
            String key = normalizeUid(keys.optString(i));
            if (key.length() == 0) {
                continue;
            }
            String value = normalizeUid(map.optString(keys.optString(i), ""));
            if (value.length() == 0) {
                continue;
            }
            out.put(key, value);
        }
        return out;
    }

    private static String resolveInternal(JSONObject map, String uid) {
        String current = normalizeUid(uid);
        if (current.length() == 0 || map == null) {
            return current;
        }
        for (int i = 0; i < MAX_CHAIN_DEPTH; i++) {
            String next = normalizeUid(map.optString(current, ""));
            if (next.length() == 0 || next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static String resolveInternal(Map<String, String> map, String uid) {
        String current = normalizeUid(uid);
        if (current.length() == 0 || map == null) {
            return current;
        }
        for (int i = 0; i < MAX_CHAIN_DEPTH; i++) {
            String next = normalizeUid(map.get(current));
            if (next.length() == 0 || next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static String normalizeUid(String uid) {
        if (uid == null) {
            return "";
        }
        String out = uid.trim();
        while (out.startsWith("@")) {
            out = out.substring(1).trim();
        }
        if (out.length() == 0) {
            return "";
        }
        return out.toUpperCase(Locale.US);
    }
}
