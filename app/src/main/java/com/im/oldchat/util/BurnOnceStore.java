package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BurnOnceStore {
    private static final String PREFS_NAME = "burn_once_store";
    private static final String KEY_PREFIX = "consumed_";
    private static final int MAX_CACHE_SIZE = 512;
    private static final Object CACHE_LOCK = new Object();
    private static final LinkedHashMap<String, Boolean> CONSUMED_CACHE =
            new LinkedHashMap<String, Boolean>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            };

    private BurnOnceStore() {
    }

    public static boolean isConsumed(Context context, String messageId) {
        if (context == null || messageId == null || messageId.length() == 0) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        String key = sanitize(messageId);
        Boolean cached = getCached(key);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean consumed = prefs(appContext).getBoolean(KEY_PREFIX + key, false);
        putCached(key, consumed);
        return consumed;
    }

    public static void markConsumed(Context context, String messageId) {
        if (context == null || messageId == null || messageId.length() == 0) {
            return;
        }
        Context appContext = context.getApplicationContext();
        String key = sanitize(messageId);
        prefs(appContext).edit().putBoolean(KEY_PREFIX + key, true).apply();
        putCached(key, true);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Boolean getCached(String messageId) {
        synchronized (CACHE_LOCK) {
            return CONSUMED_CACHE.get(messageId);
        }
    }

    private static void putCached(String messageId, boolean consumed) {
        synchronized (CACHE_LOCK) {
            CONSUMED_CACHE.put(messageId, Boolean.valueOf(consumed));
        }
    }

    private static String sanitize(String messageId) {
        if (messageId == null || messageId.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(messageId.length());
        for (int i = 0; i < messageId.length(); i++) {
            char c = messageId.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
