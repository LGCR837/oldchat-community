package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.im.oldchat.api.HttpUtil;

import java.net.URI;
import java.util.Locale;

/**
 * 服务器地址管理：持久化的是「站点根地址」，不带 /v1 版本前缀。
 * API 根地址（BASE_URL）由 HttpUtil 单向派生，任何地方都不允许再单独赋值，
 * 避免出现「业务请求打到自定义服务器、媒体仍指向默认服务器」的地址漂移。
 */
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
            HttpUtil.setBaseOrigin(HttpUtil.DEFAULT_BASE_HOST);
            if (saved != null && saved.length() > 0) {
                preferences.edit().remove(KEY_SERVER_BASE_URL).apply();
            }
            return;
        }
        HttpUtil.setBaseOrigin(normalized);
        // 旧版本存的是带 /v1 的地址，归一后回写，避免每次启动重复裁剪。
        if (!normalized.equals(saved)) {
            preferences.edit().putString(KEY_SERVER_BASE_URL, normalized).apply();
        }
    }

    /** 当前站点根地址（不含 /v1）。 */
    public static String getCurrentBaseUrl() {
        return HttpUtil.getBaseOrigin();
    }

    public static String saveAndApply(Context context, String rawBaseUrl) {
        String normalized = normalizeBaseUrl(rawBaseUrl);
        if (normalized == null) {
            return null;
        }
        HttpUtil.setBaseOrigin(normalized);
        if (context != null) {
            prefs(context).edit().putString(KEY_SERVER_BASE_URL, normalized).apply();
        }
        return normalized;
    }

    public static void resetToDefault(Context context) {
        HttpUtil.setBaseOrigin(HttpUtil.DEFAULT_BASE_HOST);
        if (context != null) {
            prefs(context).edit().remove(KEY_SERVER_BASE_URL).apply();
        }
    }

    /**
     * 归一为站点根地址：补全 http://、校验 scheme/authority、去掉结尾斜杠与 /v1、/v2 版本段。
     * 用户输入 "example.com"、"https://example.com/v1" 都会得到 "https://example.com"。
     */
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
            if (path == null) {
                path = "";
            }
            while (path.endsWith("/") && path.length() > 0) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.endsWith("/v1") || path.endsWith("/v2")) {
                path = path.substring(0, path.length() - 3);
            }
            while (path.endsWith("/") && path.length() > 0) {
                path = path.substring(0, path.length() - 1);
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
