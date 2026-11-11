package com.im.oldchat.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.im.oldchat.util.ImageCompressor;

import java.io.File;

public class ChatBackgroundStore {
    private static final String PREFS = "oldchat_chat_backgrounds";
    private static final String KEY_PREFIX = "bg_";
    private static final String KEY_GLOBAL_PREFIX = "bg_global_";
    private static final int MAX_SIZE = 1280;
    private static final int QUALITY = 80;
    private static final long MAX_BYTES = 800L * 1024L;

    public static boolean saveBackground(Context context, String conversationId, boolean isGroup, Uri uri) {
        if (context == null || TextUtils.isEmpty(conversationId) || uri == null) {
            return false;
        }
        File target = buildTargetFile(context, conversationId, isGroup);
        if (target == null) {
            return false;
        }
        String key = buildKey(context, conversationId, isGroup);
        String previous = prefs(context).getString(key, "");
        boolean ok = ImageCompressor.compressToTargetFile(
                context, uri, target, MAX_SIZE, QUALITY, false, MAX_BYTES);
        if (!ok) {
            return false;
        }
        prefs(context).edit().putString(key, target.getAbsolutePath()).apply();
        if (!TextUtils.isEmpty(previous) && !previous.equals(target.getAbsolutePath())) {
            deleteFile(previous);
        }
        return true;
    }

    public static boolean saveGlobalBackground(Context context, boolean isGroup, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        File target = buildGlobalTargetFile(context, isGroup);
        if (target == null) {
            return false;
        }
        String key = buildGlobalKey(context, isGroup);
        String previous = prefs(context).getString(key, "");
        boolean ok = ImageCompressor.compressToTargetFile(
                context, uri, target, MAX_SIZE, QUALITY, false, MAX_BYTES);
        if (!ok) {
            return false;
        }
        prefs(context).edit().putString(key, target.getAbsolutePath()).apply();
        if (!TextUtils.isEmpty(previous) && !previous.equals(target.getAbsolutePath())) {
            deleteFile(previous);
        }
        return true;
    }

    public static void clearBackground(Context context, String conversationId, boolean isGroup) {
        if (context == null || TextUtils.isEmpty(conversationId)) {
            return;
        }
        String key = buildKey(context, conversationId, isGroup);
        String path = prefs(context).getString(key, "");
        prefs(context).edit().remove(key).apply();
        deleteFile(path);
    }

    public static void clearGlobalBackground(Context context, boolean isGroup) {
        if (context == null) {
            return;
        }
        String key = buildGlobalKey(context, isGroup);
        String path = prefs(context).getString(key, "");
        prefs(context).edit().remove(key).apply();
        deleteFile(path);
    }

    public static String getBackgroundPath(Context context, String conversationId, boolean isGroup) {
        if (context == null || TextUtils.isEmpty(conversationId)) {
            return "";
        }
        return prefs(context).getString(buildKey(context, conversationId, isGroup), "");
    }

    public static String getGlobalBackgroundPath(Context context, boolean isGroup) {
        if (context == null) {
            return "";
        }
        return prefs(context).getString(buildGlobalKey(context, isGroup), "");
    }

    public static String getEffectiveBackgroundPath(Context context, String conversationId, boolean isGroup) {
        String path = getBackgroundPath(context, conversationId, isGroup);
        if (!TextUtils.isEmpty(path) && new File(path).exists()) {
            return path;
        }
        String global = getGlobalBackgroundPath(context, isGroup);
        if (!TextUtils.isEmpty(global) && new File(global).exists()) {
            return global;
        }
        return "";
    }

    public static boolean hasBackground(Context context, String conversationId, boolean isGroup) {
        String path = getBackgroundPath(context, conversationId, isGroup);
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        return new File(path).exists();
    }

    public static boolean hasGlobalBackground(Context context, boolean isGroup) {
        String path = getGlobalBackgroundPath(context, isGroup);
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        return new File(path).exists();
    }

    private static void deleteFile(String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
    }

    private static File buildTargetFile(Context context, String conversationId, boolean isGroup) {
        if (context == null || TextUtils.isEmpty(conversationId)) {
            return null;
        }
        File base = new File(context.getFilesDir(), "chat_backgrounds");
        String account = resolveAccountKey(context);
        if (TextUtils.isEmpty(account)) {
            account = "guest";
        }
        File accountDir = new File(base, account);
        if (!accountDir.exists()) {
            accountDir.mkdirs();
        }
        String safe = sanitizeKey(conversationId);
        String name = (isGroup ? "g_" : "u_") + safe + ".jpg";
        return new File(accountDir, name);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String buildKey(Context context, String conversationId, boolean isGroup) {
        String account = resolveAccountKey(context);
        if (TextUtils.isEmpty(account)) {
            account = "guest";
        }
        String safe = sanitizeKey(conversationId);
        String type = isGroup ? "g" : "u";
        return KEY_PREFIX + account + "_" + type + "_" + safe;
    }

    private static String buildGlobalKey(Context context, boolean isGroup) {
        String account = resolveAccountKey(context);
        if (TextUtils.isEmpty(account)) {
            account = "guest";
        }
        String type = isGroup ? "g" : "u";
        return KEY_GLOBAL_PREFIX + account + "_" + type;
    }

    private static File buildGlobalTargetFile(Context context, boolean isGroup) {
        if (context == null) {
            return null;
        }
        File base = new File(context.getFilesDir(), "chat_backgrounds");
        String account = resolveAccountKey(context);
        if (TextUtils.isEmpty(account)) {
            account = "guest";
        }
        File accountDir = new File(base, account);
        if (!accountDir.exists()) {
            accountDir.mkdirs();
        }
        String name = isGroup ? "global_group.jpg" : "global_chat.jpg";
        return new File(accountDir, name);
    }

    private static String resolveAccountKey(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences authPrefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
        String uid = authPrefs.getString("my_uid", "");
        if (TextUtils.isEmpty(uid)) {
            return "";
        }
        return sanitizeKey(uid);
    }

    private static String sanitizeKey(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z') || c == '_') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
