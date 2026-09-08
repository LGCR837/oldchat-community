package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.im.oldchat.api.HttpUtil;

import java.net.URI;
import java.util.Locale;

public final class ServerBaseUrlManager {
    private static final String PREFS_SETTINGS = "settings";
    private static final String KEY_SERVER_BASE_URL = "server_base_url";

    private ServerBaseUrlManager() {
    }

    public static void applySavedBaseUrl(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences preferences = prefs(context);
        String saved = preferences.getString(KEY_SERVER_BASE_URL, null);
        String normalized = normalizeBaseUrl(saved);
        if (normalized == null) {
            HttpUtil.BASE_URL = HttpUtil.DEFAULT_BASE_URL;
            if (saved != null && saved.length() > 0) {
                preferences.edit().remove(KEY_SERVER_BASE_URL).apply();
            }
            return;
        }
        HttpUtil.BASE_URL = normalized;
    }

    public static String getCurrentBaseUrl() {
        return HttpUtil.BASE_URL;
    }

    public static String saveAndApply(Context context, String rawBaseUrl) {
        String normalized = normalizeBaseUrl(rawBaseUrl);
        if (normalized == null) {
            return null;
        }
        HttpUtil.BASE_URL = normalized;
        if (context != null) {
            prefs(context).edit().putString(KEY_SERVER_BASE_URL, normalized).apply();
        }
        return normalized;
    }

    public static void resetToDefault(Context context) {
        HttpUtil.BASE_URL = HttpUtil.DEFAULT_BASE_URL;
        if (context != null) {
            prefs(context).edit().remove(KEY_SERVER_BASE_URL).apply();
        }
    }

    public static String normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null) {
            return null;
        }
        String value = rawBaseUrl.trim();
        if (value.length() == 0) {
            return null;
        }
        if (value.indexOf("://") < 0) {
            value = "http://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            if (scheme == null || authority == null || authority.length() == 0) {
                return null;
            }
            String normalizedScheme = scheme.toLowerCase(Locale.US);
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                return null;
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            String path = uri.getRawPath();
            if (path == null || path.length() == 0 || "/".equals(path)) {
                path = "/v1";
            } else {
                while (path.endsWith("/") && path.length() > 1) {
                    path = path.substring(0, path.length() - 1);
                }
            }
            return normalizedScheme + "://" + authority + path;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
    }
}
