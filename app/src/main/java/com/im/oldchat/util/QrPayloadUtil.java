package com.im.oldchat.util;

import android.net.Uri;

import java.util.Locale;

public final class QrPayloadUtil {
    public static final String TYPE_GROUP = "group";
    public static final String TYPE_USER = "user";

    private static final String SCHEME = "oldchat";
    private static final String HOST_GROUP = "group";
    private static final String HOST_USER = "user";

    private QrPayloadUtil() {
    }

    public static String buildGroupPayload(String groupId) {
        String normalized = normalizeGroupId(groupId);
        if (normalized.length() == 0) {
            return "";
        }
        return new Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST_GROUP)
                .appendQueryParameter("group_id", normalized)
                .build()
                .toString();
    }

    public static String buildUserPayload(String uid) {
        String normalized = normalizeUid(uid);
        if (normalized.length() == 0) {
            return "";
        }
        return new Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST_USER)
                .appendQueryParameter("uid", normalized)
                .build()
                .toString();
    }

    public static QrPayload parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() == 0) {
            return null;
        }
        QrPayload prefix = parsePrefixPayload(text);
        if (prefix != null) {
            return prefix;
        }
        try {
            Uri uri = Uri.parse(text);
            if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            String host = uri.getHost();
            if (HOST_GROUP.equalsIgnoreCase(host)) {
                String groupId = normalizeGroupId(firstNonEmpty(
                        uri.getQueryParameter("group_id"),
                        uri.getQueryParameter("id")));
                if (groupId.length() == 0) {
                    return null;
                }
                return new QrPayload(TYPE_GROUP, groupId);
            }
            if (HOST_USER.equalsIgnoreCase(host)) {
                String uid = normalizeUid(firstNonEmpty(
                        uri.getQueryParameter("uid"),
                        uri.getQueryParameter("id")));
                if (uid.length() == 0) {
                    return null;
                }
                return new QrPayload(TYPE_USER, uid);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static QrPayload parsePrefixPayload(String text) {
        String upper = text.toUpperCase(Locale.US);
        final String groupPrefix = "OLDCHAT_GROUP:";
        final String userPrefix = "OLDCHAT_USER:";
        if (upper.startsWith(groupPrefix)) {
            String groupId = normalizeGroupId(text.substring(groupPrefix.length()));
            if (groupId.length() > 0) {
                return new QrPayload(TYPE_GROUP, groupId);
            }
            return null;
        }
        if (upper.startsWith(userPrefix)) {
            String uid = normalizeUid(text.substring(userPrefix.length()));
            if (uid.length() > 0) {
                return new QrPayload(TYPE_USER, uid);
            }
            return null;
        }
        return null;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) {
            return a.trim();
        }
        if (b != null && b.trim().length() > 0) {
            return b.trim();
        }
        return "";
    }

    private static String normalizeGroupId(String groupId) {
        if (groupId == null) {
            return "";
        }
        return groupId.trim().toUpperCase(Locale.US);
    }

    private static String normalizeUid(String uid) {
        if (uid == null) {
            return "";
        }
        String value = uid.trim();
        while (value.startsWith("@")) {
            value = value.substring(1).trim();
        }
        return value.toUpperCase(Locale.US);
    }

    public static final class QrPayload {
        public final String type;
        public final String id;

        public QrPayload(String type, String id) {
            this.type = type == null ? "" : type;
            this.id = id == null ? "" : id;
        }
    }
}
