package com.im.oldchat.util;

import android.content.Context;

import java.io.File;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public class ImageCacheUtil {
    private ImageCacheUtil() {
    }

    public static File getCacheFile(Context context, String cacheDirName, String url) {
        if (context == null || url == null) {
            return null;
        }
        File base = new File(context.getCacheDir(), cacheDirName);
        if (!base.exists() && !base.mkdirs()) {
            return null;
        }
        String name = buildCacheKey(url);
        return new File(base, name + ".img");
    }

    public static String buildCacheKey(String url) {
        String key = normalizeCacheKey(url);
        return hashKey(key);
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private static String normalizeCacheKey(String value) {
        if (value == null) {
            return "";
        }
        String normalized = stripFragment(value.trim());
        if (normalized.length() == 0) {
            return "";
        }
        String uploadPath = normalizeUploadResourcePath(normalized);
        if (uploadPath != null) {
            return uploadPath;
        }
        return normalized;
    }

    private static String stripFragment(String value) {
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0) {
            return value.substring(0, hashIndex);
        }
        return value;
    }

    private static String normalizeUploadResourcePath(String value) {
        String path = extractPathOnly(value);
        if (path == null || path.length() == 0) {
            return null;
        }
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        String lower = path.toLowerCase(Locale.US);
        if (lower.startsWith("/v1/uploads/")) {
            return "/" + path.substring(12);
        }
        if (lower.startsWith("v1/uploads/")) {
            return "/" + path.substring(11);
        }
        if (lower.startsWith("/uploads/")) {
            return "/" + path.substring(9);
        }
        if (lower.startsWith("uploads/")) {
            return "/" + path.substring(8);
        }
        if (lower.startsWith("/media/") || lower.startsWith("/music/") || lower.startsWith("/lyrics/")
                || lower.startsWith("/avatars/") || lower.startsWith("/covers/")) {
            return path;
        }
        if (lower.startsWith("media/") || lower.startsWith("music/") || lower.startsWith("lyrics/")
                || lower.startsWith("avatars/") || lower.startsWith("covers/")) {
            return "/" + path;
        }
        return null;
    }

    private static String extractPathOnly(String value) {
        if (value == null) {
            return null;
        }
        String raw = value.trim();
        if (raw.length() == 0) {
            return "";
        }
        if (!(raw.startsWith("http://") || raw.startsWith("https://"))) {
            return raw;
        }
        try {
            URL parsed = new URL(raw);
            String path = parsed.getPath();
            String query = parsed.getQuery();
            if (query == null || query.length() == 0) {
                return path;
            }
            return path + "?" + query;
        } catch (Exception e) {
            return raw;
        }
    }

    private static String hashKey(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(value.hashCode());
        }
    }
}
