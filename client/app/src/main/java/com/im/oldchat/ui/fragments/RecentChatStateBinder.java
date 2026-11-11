package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.SharedPreferences;

import com.im.oldchat.data.SettingsStore;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RecentChatStateBinder {
    private static final String PREFS_DRAFTS = "drafts";

    private static final Comparator<RecentItem> ITEM_COMPARATOR = new Comparator<RecentItem>() {
        @Override
        public int compare(RecentItem a, RecentItem b) {
            if (a == b) {
                return 0;
            }
            if (a == null) {
                return 1;
            }
            if (b == null) {
                return -1;
            }
            if (a.isSystemNotification != b.isSystemNotification) {
                return a.isSystemNotification ? -1 : 1;
            }
            if (a.pinned != b.pinned) {
                return a.pinned ? -1 : 1;
            }
            if (a.timestamp < b.timestamp) {
                return 1;
            }
            if (a.timestamp > b.timestamp) {
                return -1;
            }
            int groupCmp = (a.isGroup ? 1 : 0) - (b.isGroup ? 1 : 0);
            if (groupCmp != 0) {
                return groupCmp;
            }
            String aId = a.id == null ? "" : a.id;
            String bId = b.id == null ? "" : b.id;
            int idCmp = aId.compareTo(bId);
            if (idCmp != 0) {
                return idCmp;
            }
            String aTitle = a.title == null ? "" : a.title;
            String bTitle = b.title == null ? "" : b.title;
            return aTitle.compareTo(bTitle);
        }
    };

    private RecentChatStateBinder() {
    }

    static boolean isKnownFriend(String uid, String ncuid, java.util.Set<String> friendUids, java.util.Set<String> friendIds, Map<String, com.im.oldchat.models.User> friendByNcuid) {
        if (uid == null || uid.isEmpty()) {
            return false;
        }
        if (friendUids.contains(uid) || friendIds.contains(uid)) {
            return true;
        }
        // 好友改过 UID：旧 uid 匹配不上时，用不可变 NCUID 兜底。
        if (ncuid != null && !ncuid.isEmpty() && friendByNcuid != null) {
            return friendByNcuid.containsKey(ncuid.trim().toUpperCase(Locale.US));
        }
        return false;
    }

    static boolean isKnownFriend(String uid, java.util.Set<String> friendUids, java.util.Set<String> friendIds) {
        if (uid == null || uid.isEmpty()) {
            return false;
        }
        return friendUids.contains(uid) || friendIds.contains(uid);
    }

    static void bind(Context context, Map<String, RecentItem> items, Map<String, String> friendIdToUid) {
        if (context == null || items == null || items.isEmpty()) {
            return;
        }
        SharedPreferences authPrefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
        SharedPreferences draftsPrefs = context.getSharedPreferences(PREFS_DRAFTS, Context.MODE_PRIVATE);
        SharedPreferences settingsPrefs = context.getSharedPreferences("oldchat_settings", Context.MODE_PRIVATE);
        String myUid = authPrefs.getString("my_uid", "");
        String accountKey = SettingsStore.resolveConversationAccountKey(authPrefs);

        for (RecentItem item : items.values()) {
            if (item == null || item.id == null || item.id.isEmpty()) {
                continue;
            }
            bindDraft(item, draftsPrefs, myUid);
            item.pinned = RecentPinStore.isPinned(context, item.isGroup, item.id, item.friendNcuid);
            item.folded = RecentFoldStore.isFolded(context, item.isGroup, item.id, item.friendNcuid);
            item.muted = SettingsStore.isConversationMuted(settingsPrefs, accountKey, item.id, item.isGroup);
            if (!item.isGroup && !item.muted && friendIdToUid != null) {
                String uidAlias = friendIdToUid.get(item.id);
                if (uidAlias != null && uidAlias.length() > 0) {
                    item.muted = SettingsStore.isConversationMuted(settingsPrefs, accountKey, uidAlias, false);
                }
            }
        }
    }

    static void sort(List<RecentItem> items) {
        if (items == null || items.size() <= 1) {
            return;
        }
        Collections.sort(items, ITEM_COMPARATOR);
    }

    private static void bindDraft(RecentItem item, SharedPreferences draftsPrefs, String myUid) {
        if (item == null || draftsPrefs == null || myUid == null || myUid.isEmpty()) {
            return;
        }
        String draft = draftsPrefs.getString(buildDraftKey(myUid, item.isGroup, item.id), "");
        if (draft != null && draft.trim().length() > 0) {
            item.draftText = draft;
        }
    }

    private static String buildDraftKey(String myUid, boolean isGroup, String id) {
        return (isGroup ? "group:" : "direct:") + myUid + ":" + id;
    }
}
