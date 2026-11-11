package com.im.oldchat.util;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class UserPresenceCache {
    public static class Presence {
        public boolean isOnline;
        public String presenceStatus;
        public Presence(boolean isOnline, String presenceStatus) {
            this.isOnline = isOnline;
            this.presenceStatus = presenceStatus == null ? "online" : presenceStatus;
        }
    }

    private static final ConcurrentHashMap<String, Presence> cache = new ConcurrentHashMap<String, Presence>();

    public static void put(String uid, boolean isOnline, String presenceStatus) {
        if (uid == null) return;
        String key = normalizeUID(uid);
        cache.put(key, new Presence(isOnline, presenceStatus));
    }

    public static void put(String uid, boolean isOnline) {
        put(uid, isOnline, "online");
    }

    public static Presence getPresence(String uid) {
        if (uid == null) return new Presence(false, "online");
        String key = normalizeUID(uid);
        Presence p = cache.get(key);
        return p != null ? p : new Presence(false, "online");
    }

    public static boolean get(String uid) {
        return getPresence(uid).isOnline;
    }

    public static String getStatus(String uid) {
        return getPresence(uid).presenceStatus;
    }

    public static void clear() {
        cache.clear();
    }

    private static String normalizeUID(String uid) {
        if (uid == null) return "";
        String s = uid.trim();
        if (s.startsWith("@")) s = s.substring(1);
        return s.toUpperCase(Locale.US);
    }
}
