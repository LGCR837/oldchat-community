package com.im.oldchat.util;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

final class TypingAvatarCache {
    private TypingAvatarCache() {
    }

    static void seed(Context context, String uid, String avatarUrl) {
        if (context == null || uid == null || uid.length() == 0 || avatarUrl == null || avatarUrl.length() == 0) {
            return;
        }
        Map<String, String> avatarMap = new HashMap<String, String>();
        avatarMap.put(uid, avatarUrl);
        GroupAvatarCache.updateFromAvatarMap(context, avatarMap, false);
    }
}
