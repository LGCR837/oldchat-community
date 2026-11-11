package com.im.oldchat.ui;

import org.json.JSONObject;

import java.util.Locale;

final class FriendRequestErrorHelper {
    private FriendRequestErrorHelper() {
    }

    static boolean isPending(String error) {
        return containsError(error, "request_pending")
                || containsError(error, "pending");
    }

    static boolean isAlreadyFriend(String error) {
        return containsError(error, "already_friends")
                || containsError(error, "already friends");
    }

    private static boolean containsError(String error, String key) {
        if (error == null || error.length() == 0 || key == null || key.length() == 0) {
            return false;
        }
        String target = key.toLowerCase(Locale.US);
        String raw = error.toLowerCase(Locale.US);
        if (raw.contains(target)) {
            return true;
        }
        try {
            JSONObject obj = new JSONObject(error);
            String errCode = obj.optString("error", "");
            if (errCode != null && errCode.toLowerCase(Locale.US).contains(target)) {
                return true;
            }
            String errMessage = obj.optString("message", "");
            return errMessage != null && errMessage.toLowerCase(Locale.US).contains(target);
        } catch (Exception e) {
            return false;
        }
    }
}
