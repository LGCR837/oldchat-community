package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.SharedPreferences;

import com.im.oldchat.models.FriendNameResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class RecentSnapshotStore {
    private static final String PREFS = "recent_snapshot_cache";
    private static final String KEY_ITEMS = "items_v1";
    private static final int MAX_ITEMS = 120;

    private RecentSnapshotStore() {
    }

    static List<RecentItem> load(Context context) {
        ArrayList<RecentItem> items = new ArrayList<RecentItem>();
        if (context == null) {
            return items;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_ITEMS, "");
            if (raw == null || raw.length() == 0) {
                return items;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                RecentItem item = new RecentItem();
                item.isGroup = obj.optBoolean("g", false);
                item.isChannel = obj.optBoolean("c", false);
                item.isSystemNotification = obj.optBoolean("sys", false);
                item.isFoldedFolder = obj.optBoolean("folder", false);
                item.pinned = obj.optBoolean("pin", false);
                item.folded = obj.optBoolean("fold", false);
                item.id = obj.optString("id", "");
                item.title = obj.optString("t", "");
                if (FriendNameResolver.isLikelyMediaUrl(item.title)) {
                    item.title = item.id;
                }
                item.userTitle = obj.optString("ut", "");
                item.subtitle = obj.optString("sub", "");
                item.draftText = obj.optString("draft", "");
                item.avatarUrl = obj.optString("av", "");
                item.timestamp = obj.optLong("ts", 0L);
                item.unreadCount = obj.optInt("unread", 0);
                item.groupRole = obj.optInt("role", 0);
                item.mentionUnread = obj.optBoolean("mention", false);
                item.groupMemberCount = obj.optInt("members", 0);
                item.muted = obj.optBoolean("muted", false);
                item.isOnline = obj.optBoolean("online", false);
                item.presenceStatus = obj.optString("presence", "");
                if ((item.id == null || item.id.length() == 0) && !item.isSystemNotification && !item.isFoldedFolder) {
                    continue;
                }
                items.add(item);
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    static void save(Context context, List<RecentItem> items) {
        if (context == null || items == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            int limit = Math.min(items.size(), MAX_ITEMS);
            for (int i = 0; i < limit; i++) {
                RecentItem item = items.get(i);
                if (item == null) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("g", item.isGroup);
                obj.put("c", item.isChannel);
                obj.put("sys", item.isSystemNotification);
                obj.put("folder", item.isFoldedFolder);
                obj.put("pin", item.pinned);
                obj.put("fold", item.folded);
                obj.put("id", safe(item.id));
                obj.put("t", safe(item.title));
                obj.put("ut", safe(item.userTitle));
                obj.put("sub", safe(item.subtitle));
                obj.put("draft", safe(item.draftText));
                obj.put("av", safe(item.avatarUrl));
                obj.put("ts", item.timestamp);
                obj.put("unread", item.unreadCount);
                obj.put("role", item.groupRole);
                obj.put("mention", item.mentionUnread);
                obj.put("members", item.groupMemberCount);
                obj.put("muted", item.muted);
                obj.put("online", item.isOnline);
                obj.put("presence", safe(item.presenceStatus));
                arr.put(obj);
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
