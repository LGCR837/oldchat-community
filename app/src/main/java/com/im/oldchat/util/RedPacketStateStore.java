package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class RedPacketStateStore {
    private static final String PREF_NAME = "red_packet_state";
    private static final String KEY_DONE_IDS = "done_ids";
    private static final Set<String> DONE_IDS = Collections.synchronizedSet(new HashSet<String>());
    private static boolean loaded;

    private RedPacketStateStore() {
    }

    public static boolean isPacketDone(Context context, String packetId) {
        String key = normalizePacketId(packetId);
        if (key.length() == 0) {
            return false;
        }
        ensureLoaded(context);
        return DONE_IDS.contains(key);
    }

    public static void markPacketDone(Context context, String packetId) {
        String key = normalizePacketId(packetId);
        if (key.length() == 0) {
            return;
        }
        ensureLoaded(context);
        if (DONE_IDS.contains(key)) {
            return;
        }
        DONE_IDS.add(key);
        persist(context);
    }

    private static void ensureLoaded(Context context) {
        if (loaded || context == null) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_DONE_IDS, "");
        if (raw != null && raw.length() > 0) {
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String key = normalizePacketId(parts[i]);
                if (key.length() > 0) {
                    DONE_IDS.add(key);
                }
            }
        }
        loaded = true;
    }

    private static void persist(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        StringBuilder joined = new StringBuilder();
        synchronized (DONE_IDS) {
            Iterator<String> it = DONE_IDS.iterator();
            while (it.hasNext()) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(it.next());
            }
        }
        prefs.edit().putString(KEY_DONE_IDS, joined.toString()).apply();
    }

    private static String normalizePacketId(String packetId) {
        if (packetId == null) {
            return "";
        }
        String out = packetId.trim().toUpperCase();
        return out == null ? "" : out;
    }
}
