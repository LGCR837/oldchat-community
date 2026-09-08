package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecentChatCache {
    private static final String PREF_NAME = "recent_chats";
    private static final String KEY_LOCAL_CLEAR_PREFIX = "local_clear_";
    private static final int MAX_RECENT_CHATS = 200;
    private static final Gson GSON = new Gson();
    private static final Object CACHE_LOCK = new Object();
    private static List<RecentChat> memoryList;
    private static List<RecentChat> pendingSnapshot;
    private static int saveGeneration;
    private static boolean saveWorkerScheduled;
    
    public static class RecentChat {
        public String friendId;
        public String friendUID;
        public String friendNcuid;
        public String friendName;
        public String avatarUrl;
        public String lastMessage;
        public long timestamp;
        public int unreadCount;

        public RecentChat(String friendId, String friendUID, String friendName, String avatarUrl, String lastMessage, long timestamp, int unreadCount) {
            this(friendId, friendUID, null, friendName, avatarUrl, lastMessage, timestamp, unreadCount);
        }

        public RecentChat(String friendId, String friendUID, String friendNcuid, String friendName, String avatarUrl, String lastMessage, long timestamp, int unreadCount) {
            this.friendId = friendId;
            this.friendUID = friendUID;
            this.friendNcuid = friendNcuid;
            this.friendName = friendName;
            this.avatarUrl = avatarUrl;
            this.lastMessage = lastMessage;
            this.timestamp = timestamp;
            this.unreadCount = unreadCount;
        }
    }

    public static void updateRecentChat(Context context, String friendUID, String friendName, String avatarUrl, String lastMessage) {
        updateRecentChat(context, friendUID, null, friendName, avatarUrl, lastMessage);
    }

    public static void updateRecentChat(Context context, String friendUID, String friendNcuid, String friendName, String avatarUrl, String lastMessage) {
        updateRecentChatInternal(context, friendUID, friendNcuid, friendName, avatarUrl, lastMessage,
                System.currentTimeMillis(), 0, false);
    }

    public static void touchRecentChat(Context context, String friendUID, String friendName, String avatarUrl) {
        updateRecentChatInternal(context, friendUID, null, friendName, avatarUrl, null,
                System.currentTimeMillis(), 0, false);
    }

    public static void updateRecentChatIncoming(Context context, String friendUID, String friendName, String avatarUrl,
                                                String lastMessage, long timestampSeconds, int unreadDelta) {
        updateRecentChatIncoming(context, friendUID, null, friendName, avatarUrl,
                lastMessage, timestampSeconds, unreadDelta);
    }

    public static void updateRecentChatIncoming(Context context, String friendUID, String friendNcuid, String friendName, String avatarUrl,
                                                String lastMessage, long timestampSeconds, int unreadDelta) {
        updateRecentChatInternal(context, friendUID, friendNcuid, friendName, avatarUrl, lastMessage,
                normalizeTimestamp(timestampSeconds), unreadDelta, false);
    }

    public static void updateRecentChatOutgoing(Context context, String friendUID, String friendName, String avatarUrl,
                                                String lastMessage, long timestampSeconds) {
        updateRecentChatOutgoing(context, friendUID, null, friendName, avatarUrl,
                lastMessage, timestampSeconds);
    }

    public static void updateRecentChatOutgoing(Context context, String friendUID, String friendNcuid, String friendName, String avatarUrl,
                                                String lastMessage, long timestampSeconds) {
        updateRecentChatInternal(context, friendUID, friendNcuid, friendName, avatarUrl, lastMessage,
                normalizeTimestamp(timestampSeconds), 0, false);
    }

    public static void clearUnread(Context context, String friendUID) {
        String normalizedUid = normalizeUid(friendUID);
        if (normalizedUid == null) {
            return;
        }
        saveLocalClearAt(context, normalizedUid, System.currentTimeMillis());
        updateRecentChatInternal(context, friendUID, null, null, null, null, 0, 0, true);
    }

    /** 已读事件到达时轻量触摸最近会话（只更新排序时间，不修改未读数）。 */
    public static synchronized void touchIfPresent(Context context, String friendUID) {
        if (context == null || friendUID == null) {
            return;
        }
        String normalizedUid = normalizeUid(friendUID);
        if (normalizedUid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        saveLocalClearAt(context, normalizedUid, now);
    }

    public static synchronized void clearUnreadNotIn(Context context, Set<String> activeUnreadUids) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentChat> list = getRecentChats(context);
        HashSet<String> activeKeys = normalizeKeys(activeUnreadUids);
        boolean changed = false;
        for (RecentChat c : list) {
            if (c.unreadCount <= 0) {
                continue;
            }
            String key = normalizeUid(c.friendUID != null ? c.friendUID : c.friendId);
            if (key == null) {
                continue;
            }
            if (!activeKeys.contains(key)) {
                c.unreadCount = 0;
                changed = true;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static void setUnreadCount(Context context, String friendUID, String friendName, String avatarUrl,
                                      String lastMessage, long timestampSeconds, int unreadCount) {
        setUnreadCount(context, friendUID, "", friendName, avatarUrl, lastMessage, timestampSeconds, unreadCount);
    }

    public static void setUnreadCount(Context context, String friendUID, String friendNcuid,
                                      String friendName, String avatarUrl, String lastMessage,
                                      long timestampSeconds, int unreadCount) {
        updateRecentChatWithUnread(context, friendUID, friendNcuid, friendName, avatarUrl, lastMessage,
                normalizeTimestamp(timestampSeconds), unreadCount);
    }

    public static synchronized void mergeFriendInfo(Context context, List<User> friends) {
        if (context == null || friends == null || friends.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentChat> list = getRecentChats(context);
        boolean changed = false;
        for (RecentChat c : list) {
            if (c.friendUID == null || c.friendUID.isEmpty()) {
                continue;
            }
            for (User u : friends) {
                boolean uidMatch = u.uid != null && u.uid.equals(c.friendUID);
                boolean ncuidMatch = !uidMatch && c.friendNcuid != null && !c.friendNcuid.isEmpty()
                        && u.ncuid != null && u.ncuid.equalsIgnoreCase(c.friendNcuid);
                if (uidMatch || ncuidMatch) {
                    String name = FriendNameResolver.resolve(u);
                    if (name != null && !name.isEmpty() && !name.equals(c.friendName)) {
                        c.friendName = name;
                        changed = true;
                    }
                    if (u.avatar_url != null && !u.avatar_url.isEmpty() && !u.avatar_url.equals(c.avatarUrl)) {
                        c.avatarUrl = u.avatar_url;
                        changed = true;
                    }
                    // 好友改过 UID 后，用不可变 NCUID 对齐到最新 UID，避免会话丢失。
                    if (u.uid != null && !u.uid.isEmpty() && !u.uid.equals(c.friendUID)) {
                        c.friendUID = u.uid;
                        changed = true;
                    }
                    if (u.ncuid != null && !u.ncuid.isEmpty() && !u.ncuid.equalsIgnoreCase(c.friendNcuid)) {
                        c.friendNcuid = u.ncuid;
                        changed = true;
                    }
                    break;
                }
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static synchronized void cleanupInvalidChats(Context context, List<User> friends) {
        if (context == null || friends == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentChat> list = getRecentChats(context);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            RecentChat c = list.get(i);
            if (c.friendUID == null || c.friendUID.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (User u : friends) {
                boolean uidMatch = u.uid != null && u.uid.equals(c.friendUID);
                boolean ncuidMatch = !uidMatch && c.friendNcuid != null && !c.friendNcuid.isEmpty()
                        && u.ncuid != null && u.ncuid.equalsIgnoreCase(c.friendNcuid);
                if (uidMatch || ncuidMatch) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static List<RecentChat> getRecentChats(Context context) {
        if (context == null) return new ArrayList<RecentChat>();
        synchronized (CACHE_LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            List<RecentChat> copy = copyList(memoryList);
            sortChats(copy);
            return copy;
        }
    }

    public static RecentChat getChat(Context context, String friendUID) {
        String normalizedUid = normalizeUid(friendUID);
        if (context == null || normalizedUid == null) {
            return null;
        }
        List<RecentChat> chats = getRecentChats(context);
        for (RecentChat c : chats) {
            String key = c.friendUID != null ? c.friendUID : c.friendId;
            if (sameUid(key, normalizedUid)) {
                return c;
            }
        }
        return null;
    }

    public static synchronized void clearAll(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        synchronized (CACHE_LOCK) {
            memoryList = new ArrayList<RecentChat>();
            pendingSnapshot = null;
            saveGeneration++;
        }
        prefs.edit().clear().apply();
    }

    public static void remove(Context context, String friendUID) {
        removeChat(context, friendUID);
    }

    public static synchronized void removeChat(Context context, String friendUID) {
        String normalizedUid = normalizeUid(friendUID);
        if (context == null || normalizedUid == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentChat> list = getRecentChats(context);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            RecentChat c = list.get(i);
            String key = c.friendUID != null ? c.friendUID : c.friendId;
            if (sameUid(key, normalizedUid)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    private static synchronized void updateRecentChatInternal(Context context, String friendUID, String friendNcuid, String friendName, String avatarUrl,
                                                 String lastMessage, long timestamp, int unreadDelta, boolean clearUnread) {
        String normalizedUid = normalizeUid(friendUID);
        if (context == null || normalizedUid == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentChat> list = getRecentChats(context);

        RecentChat existing = null;
        for (RecentChat c : list) {
            String key = c.friendUID != null ? c.friendUID : c.friendId;
            boolean sameNcuid = friendNcuid != null && friendNcuid.length() > 0
                    && c.friendNcuid != null && friendNcuid.equalsIgnoreCase(c.friendNcuid);
            if (sameNcuid || sameUid(key, normalizedUid)) {
                existing = c;
                break;
            }
        }
        if (existing != null) {
            MessageHistoryCache.migrateDirectMessages(context, existing.friendUID, friendUID, friendNcuid);
            list.remove(existing);
        }

        String finalName = friendName;
        String finalAvatar = avatarUrl;
        int unread = existing != null ? existing.unreadCount : 0;
        if (existing != null) {
            if (finalName == null || finalName.isEmpty()) {
                finalName = existing.friendName;
            }
            if (finalAvatar == null || finalAvatar.isEmpty()) {
                finalAvatar = existing.avatarUrl;
            }
        }
        if (FriendNameResolver.isLikelyMediaUrl(finalName)) {
            finalName = null;
        }
        if (finalName == null || finalName.isEmpty()) {
            finalName = existing != null && existing.friendUID != null && !existing.friendUID.isEmpty()
                    ? existing.friendUID : friendUID;
        }
        if (clearUnread) {
            unread = 0;
        } else if (unreadDelta > 0) {
            unread += unreadDelta;
        }

        long ts = timestamp > 0 ? timestamp : (existing != null ? existing.timestamp : System.currentTimeMillis());
        String finalMessage = lastMessage != null ? lastMessage : (existing != null ? existing.lastMessage : "");
        String finalUid = friendUID;
        if ((finalUid == null || finalUid.isEmpty()) && existing != null
                && existing.friendUID != null && !existing.friendUID.isEmpty()) {
            finalUid = existing.friendUID;
        }
        if (finalUid == null || finalUid.isEmpty()) {
            finalUid = normalizedUid;
        }
        String finalNcuid = existing != null && existing.friendNcuid != null && !existing.friendNcuid.isEmpty()
                ? existing.friendNcuid : friendNcuid;
        if (finalNcuid == null) {
            finalNcuid = "";
        }
        list.add(0, new RecentChat(finalUid, finalUid, finalNcuid, finalName, finalAvatar, finalMessage, ts, unread));

        persistList(context, list);
    }

    private static synchronized void updateRecentChatWithUnread(Context context, String friendUID, String friendNcuid,
                                                    String friendName, String avatarUrl,
                                                    String lastMessage, long timestamp, int unreadCount) {
        String normalizedUid = normalizeUid(friendUID);
        if (context == null || normalizedUid == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentChat> list = getRecentChats(context);

        RecentChat existing = null;
        for (RecentChat c : list) {
            String key = c.friendUID != null ? c.friendUID : c.friendId;
            boolean sameNcuid = friendNcuid != null && friendNcuid.length() > 0
                    && c.friendNcuid != null && friendNcuid.equalsIgnoreCase(c.friendNcuid);
            if (sameNcuid || sameUid(key, normalizedUid)) {
                existing = c;
                break;
            }
        }
        if (existing != null) {
            MessageHistoryCache.migrateDirectMessages(context, existing.friendUID, friendUID, friendNcuid);
            list.remove(existing);
        }

        String finalName = friendName;
        String finalAvatar = avatarUrl;
        if (existing != null) {
            if (finalName == null || finalName.isEmpty()) {
                finalName = existing.friendName;
            }
            if (finalAvatar == null || finalAvatar.isEmpty()) {
                finalAvatar = existing.avatarUrl;
            }
        }
        if (FriendNameResolver.isLikelyMediaUrl(finalName)) {
            finalName = null;
        }
        if (finalName == null || finalName.isEmpty()) {
            finalName = existing != null && existing.friendUID != null && !existing.friendUID.isEmpty()
                    ? existing.friendUID : friendUID;
        }

        long ts = timestamp > 0 ? timestamp : (existing != null ? existing.timestamp : System.currentTimeMillis());
        String finalMessage = lastMessage != null ? lastMessage : (existing != null ? existing.lastMessage : "");
        int finalUnread = unreadCount < 0 ? 0 : unreadCount;
        long localClearAt = getLocalClearAt(context, normalizedUid);
        if (finalUnread > 0 && localClearAt > 0L && localClearAt >= ts) {
            finalUnread = 0;
        }
        String finalUid = friendUID;
        if ((finalUid == null || finalUid.isEmpty()) && existing != null
                && existing.friendUID != null && !existing.friendUID.isEmpty()) {
            finalUid = existing.friendUID;
        }
        if (finalUid == null || finalUid.isEmpty()) {
            finalUid = normalizedUid;
        }
        String finalNcuid = friendNcuid == null ? "" : friendNcuid;
        if (finalNcuid.length() == 0 && existing != null
                && existing.friendNcuid != null && !existing.friendNcuid.isEmpty()) {
            finalNcuid = existing.friendNcuid;
        }
        list.add(0, new RecentChat(finalUid, finalUid, finalNcuid, finalName, finalAvatar, finalMessage, ts, finalUnread));

        persistList(context, list);
    }

    private static void ensureLoadedLocked(Context context) {
        if (memoryList != null) return;
        String json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("list", null);
        try {
            List<RecentChat> parsed = json == null ? null : GSON.fromJson(json,
                    TypeToken.getParameterized(List.class, RecentChat.class).getType());
            memoryList = parsed == null ? new ArrayList<RecentChat>() : limitList(parsed);
            repairMisplacedMediaFields(memoryList);
        } catch (Exception ignored) {
            memoryList = new ArrayList<RecentChat>();
        }
    }

    private static void persistList(Context context, List<RecentChat> list) {
        if (context == null || list == null) return;
        final Context appContext = context.getApplicationContext();
        List<RecentChat> snapshot = copyList(limitList(list));
        synchronized (CACHE_LOCK) {
            memoryList = snapshot;
            pendingSnapshot = snapshot;
            saveGeneration++;
            if (saveWorkerScheduled) return;
            saveWorkerScheduled = true;
        }
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override public void run() {
                while (true) {
                    List<RecentChat> snapshot;
                    int generation;
                    synchronized (CACHE_LOCK) {
                        snapshot = pendingSnapshot == null ? copyList(memoryList) : pendingSnapshot;
                        pendingSnapshot = null;
                        generation = saveGeneration;
                    }
                    String json = GSON.toJson(snapshot);
                    synchronized (CACHE_LOCK) {
                        if (generation == saveGeneration) {
                            appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                    .edit().putString("list", json).apply();
                        }
                        if (pendingSnapshot == null && generation == saveGeneration) {
                            saveWorkerScheduled = false;
                            return;
                        }
                    }
                }
            }
        });
    }

    private static void repairMisplacedMediaFields(List<RecentChat> chats) {
        if (chats == null) return;
        for (RecentChat chat : chats) {
            if (chat == null || !FriendNameResolver.isLikelyMediaUrl(chat.friendName)) continue;
            String misplacedUrl = chat.friendName;
            // 兼容曾出现过的字段串位缓存：avatarUrl 若像普通名字，则交换回来。
            if (chat.avatarUrl != null && !chat.avatarUrl.trim().isEmpty()
                    && !FriendNameResolver.isLikelyMediaUrl(chat.avatarUrl)) {
                chat.friendName = chat.avatarUrl;
                chat.avatarUrl = misplacedUrl;
            } else {
                chat.friendName = "";
                if (chat.avatarUrl == null || chat.avatarUrl.trim().isEmpty()) {
                    chat.avatarUrl = misplacedUrl;
                }
            }
        }
    }

    private static List<RecentChat> limitList(List<RecentChat> source) {
        if (source == null || source.size() <= MAX_RECENT_CHATS) {
            return source == null ? new ArrayList<RecentChat>() : new ArrayList<RecentChat>(source);
        }
        return new ArrayList<RecentChat>(source.subList(0, MAX_RECENT_CHATS));
    }

    private static List<RecentChat> copyList(List<RecentChat> source) {
        List<RecentChat> out = new ArrayList<RecentChat>();
        if (source == null) return out;
        for (RecentChat c : source) {
            if (c == null) continue;
            out.add(new RecentChat(c.friendId, c.friendUID, c.friendNcuid, c.friendName, c.avatarUrl,
                    c.lastMessage, c.timestamp, c.unreadCount));
        }
        return out;
    }

    private static void sortChats(List<RecentChat> chats) {
        Collections.sort(chats, new java.util.Comparator<RecentChat>() {
            @Override public int compare(RecentChat a, RecentChat b) {
                if (a == b) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                if (a.timestamp != b.timestamp) return a.timestamp < b.timestamp ? 1 : -1;
                String aKey = a.friendUID != null && a.friendUID.length() > 0 ? a.friendUID : (a.friendId == null ? "" : a.friendId);
                String bKey = b.friendUID != null && b.friendUID.length() > 0 ? b.friendUID : (b.friendId == null ? "" : b.friendId);
                int keyCmp = aKey.compareTo(bKey);
                if (keyCmp != 0) return keyCmp;
                return (a.friendName == null ? "" : a.friendName).compareTo(b.friendName == null ? "" : b.friendName);
            }
        });
    }

    private static HashSet<String> normalizeKeys(Set<String> keys) {
        HashSet<String> normalized = new HashSet<String>();
        if (keys == null || keys.isEmpty()) {
            return normalized;
        }
        for (String key : keys) {
            String normalizedKey = normalizeUid(key);
            if (normalizedKey != null) {
                normalized.add(normalizedKey);
            }
        }
        return normalized;
    }

    private static boolean sameUid(String value, String normalizedTarget) {
        if (normalizedTarget == null) {
            return false;
        }
        String normalizedValue = normalizeUid(value);
        return normalizedTarget.equals(normalizedValue);
    }

    private static String normalizeUid(String uid) {
        if (uid == null) {
            return null;
        }
        String out = uid.trim();
        if (out.length() == 0) {
            return null;
        }
        if (out.startsWith("@")) {
            out = out.substring(1);
        }
        return out.toUpperCase(Locale.US);
    }

    private static void saveLocalClearAt(Context context, String normalizedUid, long clearedAtMs) {
        if (context == null || normalizedUid == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LOCAL_CLEAR_PREFIX + normalizedUid, clearedAtMs).apply();
    }

    private static long getLocalClearAt(Context context, String normalizedUid) {
        if (context == null || normalizedUid == null) {
            return 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_LOCAL_CLEAR_PREFIX + normalizedUid, 0L);
    }

    private static long normalizeTimestamp(long value) {
        if (value <= 0) {
            return System.currentTimeMillis();
        }
        if (value < 100000000000L) {
            return value * 1000L;
        }
        return value;
    }
}
