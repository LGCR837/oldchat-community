package com.im.oldchat.bili;

import android.content.Context;
import android.content.SharedPreferences;

public final class BiliAuthStore {
    private static final String PREFS_NAME = "bili_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_COOKIES = "cookies";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_MID = "mid";

    private BiliAuthStore() {
    }

    public static void saveAuth(Context context, String accessToken, String cookies, long expiresInSeconds) {
        if (context == null) {
            return;
        }
        long expiresAt = 0;
        if (expiresInSeconds > 0) {
            expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L);
        }
        prefs(context).edit()
                .putString(KEY_ACCESS_TOKEN, accessToken != null ? accessToken : "")
                .putString(KEY_COOKIES, cookies != null ? cookies : "")
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply();
    }

    public static void saveAuthWithMid(Context context, String accessToken, String cookies, long expiresInSeconds, long mid) {
        if (context == null) {
            return;
        }
        long expiresAt = 0;
        if (expiresInSeconds > 0) {
            expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L);
        }
        prefs(context).edit()
                .putString(KEY_ACCESS_TOKEN, accessToken != null ? accessToken : "")
                .putString(KEY_COOKIES, cookies != null ? cookies : "")
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .putLong(KEY_MID, mid)
                .apply();
    }

    public static String getAccessToken(Context context) {
        if (context == null) {
            return "";
        }
        return prefs(context).getString(KEY_ACCESS_TOKEN, "");
    }

    public static String getCookies(Context context) {
        if (context == null) {
            return "";
        }
        return prefs(context).getString(KEY_COOKIES, "");
    }

    public static long getMid(Context context) {
        if (context == null) {
            return 0L;
        }
        return prefs(context).getLong(KEY_MID, 0L);
    }

    public static void saveMid(Context context, long mid) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putLong(KEY_MID, mid).apply();
    }

    public static boolean isExpired(Context context) {
        if (context == null) {
            return true;
        }
        long expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L);
        if (expiresAt <= 0) {
            return false;
        }
        return System.currentTimeMillis() >= expiresAt;
    }

    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
