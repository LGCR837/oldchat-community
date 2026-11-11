package com.im.oldchat.models;

public class FriendNameResolver {
    private static final int MAX_NAME_LENGTH = 15;

    private FriendNameResolver() {
    }

    public static String resolve(User user) {
        if (user == null) {
            return "";
        }
        return resolve(user.remark_name, user.display_name, user.username, user.uid);
    }

    public static String resolve(String remarkName, String displayName, String username, String uid) {
        String name = trimName(remarkName);
        if (name.length() > 0) {
            return name;
        }
        name = trimName(displayName);
        if (name.length() > 0) {
            return name;
        }
        name = trimName(username);
        if (name.length() > 0) {
            return name;
        }
        return trimUid(uid);
    }

    public static String normalizeDisplayName(String value) {
        return normalizeDisplayName(value, MAX_NAME_LENGTH);
    }

    public static String normalizeDisplayName(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String out = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (isLikelyMediaUrl(out)) {
            return "";
        }
        if (maxLength > 0 && out.length() > maxLength) {
            out = out.substring(0, maxLength);
        }
        return out;
    }

    /** 防止旧缓存或字段错位把头像地址当作昵称显示。 */
    public static boolean isLikelyMediaUrl(String value) {
        if (value == null) {
            return false;
        }
        String out = value.trim().toLowerCase(java.util.Locale.US);
        return out.startsWith("/v1/uploads/")
                || out.startsWith("/uploads/")
                || out.contains("/v1/uploads/avatars/")
                || out.contains("/uploads/avatars/");
    }

    private static String trimName(String value) {
        return normalizeDisplayName(value, MAX_NAME_LENGTH);
    }

    private static String trimUid(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
