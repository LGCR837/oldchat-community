package com.im.oldchat.ui;

import android.content.Context;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.util.GroupAvatarCache;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.UidAliasStore;

import java.util.Map;

final class GroupTypingAvatarLoader {
    private GroupTypingAvatarLoader() {
    }

    static void load(Context context, Map<String, String> avatarMap, ImageView target, String uid) {
        if (context == null || avatarMap == null || target == null || uid == null || uid.isEmpty()) {
            return;
        }
        String senderUid = resolveSenderUid(context, uid);
        if (senderUid.length() == 0) {
            target.setImageResource(R.drawable.ic_avatar_placeholder);
            target.setTag(null);
            return;
        }
        String avatarUrl = GroupAvatarCache.getCachedAvatar(context, senderUid);
        if ((avatarUrl == null || avatarUrl.isEmpty()) && !senderUid.equals(uid)) {
            avatarUrl = GroupAvatarCache.getCachedAvatar(context, uid);
        }
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            avatarMap.put(senderUid, avatarUrl);
            if (!senderUid.equals(uid)) {
                avatarMap.put(uid, avatarUrl);
            }
        } else {
            avatarUrl = avatarMap.get(senderUid);
            if ((avatarUrl == null || avatarUrl.isEmpty()) && !senderUid.equals(uid)) {
                avatarUrl = avatarMap.get(uid);
            }
        }
        if (avatarUrl == null || avatarUrl.isEmpty()) {
            target.setImageResource(R.drawable.ic_avatar_placeholder);
            target.setTag(null);
            return;
        }
        Object currentTag = target.getTag();
        if (currentTag == null || !avatarUrl.equals(currentTag)) {
            target.setTag(avatarUrl);
            ImageLoader.loadAvatarCacheFirstTryOnce(target, avatarUrl);
        }
    }

    private static String resolveSenderUid(Context context, String rawUid) {
        if (rawUid == null) {
            return "";
        }
        String resolved = UidAliasStore.resolve(context, rawUid);
        if (resolved != null && resolved.length() > 0) {
            return resolved;
        }
        return rawUid;
    }
}
