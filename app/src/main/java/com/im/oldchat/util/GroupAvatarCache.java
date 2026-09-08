package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.im.oldchat.models.GroupMember;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GroupAvatarCache {
    private static final String PREFS = "group_avatar_cache";
    private static final String KEY_MAP = "avatar_map";
    private static final Object LOCK = new Object();
    private static Map<String, String> memoryMap = new HashMap<String, String>();
    private static boolean memoryLoaded;

    private GroupAvatarCache() {
    }

    public static void fillMissing(Context context, Map<String, String> avatarMap) {
        if (context == null || avatarMap == null) {
            return;
        }
        Map<String, String> map = getMemoryMap(context.getApplicationContext());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String uid = entry.getKey();
            if (uid == null || uid.isEmpty()) {
                continue;
            }
            String normalizedUid = normalizeUid(uid);
            if (normalizedUid.length() == 0) {
                continue;
            }
            String cachedUrl = entry.getValue();
            if (cachedUrl == null || cachedUrl.isEmpty()) {
                continue;
            }
            String current = avatarMap.get(normalizedUid);
            if (current == null || current.isEmpty()) {
                avatarMap.put(normalizedUid, cachedUrl);
            }
            String rawCurrent = avatarMap.get(uid);
            if (rawCurrent == null || rawCurrent.isEmpty()) {
                avatarMap.put(uid, cachedUrl);
            }
        }
    }

    public static String getCachedAvatar(Context context, String uid) {
        if (context == null || uid == null || uid.isEmpty()) {
            return "";
        }
        String normalizedUid = normalizeUid(uid);
        if (normalizedUid.length() == 0) {
            return "";
        }
        Map<String, String> map = getMemoryMap(context.getApplicationContext());
        String cached = map.get(normalizedUid);
        if (cached == null || cached.isEmpty()) {
            cached = map.get(normalizeUid(uid.trim()));
        }
        return cached == null ? "" : cached;
    }

    public static void updateFromMembers(Context context, List<GroupMember> members) {
        if (context == null || members == null || members.isEmpty()) {
            return;
        }
        Map<String, String> map = new HashMap<>();
        for (GroupMember m : members) {
            if (m == null || m.avatar_url == null || m.avatar_url.isEmpty()) {
                continue;
            }
            String identity = m.ncuid != null && !m.ncuid.isEmpty() ? m.ncuid : m.uid;
            if (identity == null || identity.isEmpty()) {
                continue;
            }
            map.put(identity, m.avatar_url);
        }
        updateFromAvatarMap(context, map);
    }

    public static void updateFromAvatarMap(Context context, Map<String, String> avatarMap) {
        updateFromAvatarMap(context, avatarMap, true);
    }

    public static void updateFromAvatarMap(Context context, Map<String, String> avatarMap, boolean prefetch) {
        if (context == null || avatarMap == null || avatarMap.isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject map = loadMap(prefs);
        boolean changed = false;
        for (Map.Entry<String, String> entry : avatarMap.entrySet()) {
            String uid = entry.getKey();
            String url = entry.getValue();
            if (uid == null || uid.isEmpty() || url == null || url.isEmpty()) {
                continue;
            }
            String normalizedUid = normalizeUid(uid);
            if (normalizedUid.length() == 0) {
                continue;
            }
            String cached = map.optString(normalizedUid, "");
            if (cached == null || cached.isEmpty()) {
                cached = map.optString(uid, "");
            }
            if (!url.equals(cached)) {
                try {
                    map.put(normalizedUid, url);
                    changed = true;
                } catch (Exception e) {
                }
            }
            if (prefetch && !ImageLoader.isCached(appContext, url)) {
                ImageLoader.prefetch(appContext, url);
            }
        }
        if (changed) {
            prefs.edit().putString(KEY_MAP, map.toString()).apply();
            synchronized (LOCK) {
                memoryMap = toMemoryMap(map);
                memoryLoaded = true;
            }
        }
    }

    private static JSONObject loadMap(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return loadMap(prefs);
    }

    private static JSONObject loadMap(SharedPreferences prefs) {
        try {
            String raw = prefs.getString(KEY_MAP, "{}");
            return new JSONObject(raw);
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
        JSONObject map = loadMap(context);
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
            String value = map.optString(keys.optString(i), "");
            if (value == null || value.isEmpty()) {
                continue;
            }
            out.put(key, value);
        }
        return out;
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
