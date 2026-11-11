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

public class GroupRecentChatCache {
    private static final String PREF_NAME = "recent_groups";
    private static final String KEY_LOCAL_CLEAR_PREFIX = "local_clear_";
    private static final String KEY_SEEN_MESSAGE_IDS = "seen_message_ids";
    private static final int MAX_SEEN_MESSAGE_IDS = 256;
    private static final int MAX_RECENT_GROUPS = 200;
    private static final Gson GSON = new Gson();
    private static final Object CACHE_LOCK = new Object();
    private static List<RecentGroup> memoryList;
    private static List<RecentGroup> pendingSnapshot;
    private static int saveGeneration;
    private static boolean saveWorkerScheduled;

    public static class RecentGroup {
        public String groupId;
        public String groupName;
        public String avatarUrl;
        public String lastMessage;
        public long timestamp;
        public int unreadCount;
        public int role;
        public boolean mentionUnread;
        public int memberCount;

        public RecentGroup(String groupId, String groupName, String avatarUrl, String lastMessage,
                           long timestamp, int unreadCount, int role) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.avatarUrl = avatarUrl;
            this.lastMessage = lastMessage;
            this.timestamp = timestamp;
            this.unreadCount = unreadCount;
            this.role = role;
            this.mentionUnread = false;
            this.memberCount = -1;
        }

        public RecentGroup(String groupId, String groupName, String avatarUrl, String lastMessage,
                           long timestamp, int unreadCount, int role, boolean mentionUnread) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.avatarUrl = avatarUrl;
            this.lastMessage = lastMessage;
            this.timestamp = timestamp;
            this.unreadCount = unreadCount;
            this.role = role;
            this.mentionUnread = mentionUnread;
            this.memberCount = -1;
        }

        public RecentGroup(String groupId, String groupName, String avatarUrl, String lastMessage,
                           long timestamp, int unreadCount, int role, boolean mentionUnread, int memberCount) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.avatarUrl = avatarUrl;
            this.lastMessage = lastMessage;
            this.timestamp = timestamp;
            this.unreadCount = unreadCount;
            this.role = role;
            this.mentionUnread = mentionUnread;
            this.memberCount = memberCount;
        }
    }

    public static synchronized List<RecentGroup> getRecentGroups(Context context) {
        if (context == null) return new ArrayList<RecentGroup>();
        synchronized (CACHE_LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            List<RecentGroup> copy = copyList(memoryList);
            sortGroups(copy);
            return copy;
        }
    }

    public static synchronized RecentGroup getGroup(Context context, String groupId) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (context == null || normalizedGroupId == null) {
            return null;
        }
        List<RecentGroup> groups = getRecentGroups(context);
        for (RecentGroup g : groups) {
            if (sameGroupId(g.groupId, normalizedGroupId)) {
                return g;
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
            memoryList = new ArrayList<RecentGroup>();
            pendingSnapshot = null;
            saveGeneration++;
        }
        prefs.edit().clear().apply();
    }

    public static synchronized void updateGroupIncoming(Context context, String groupId, String groupName, String avatarUrl,
                                           String lastMessage, long timestampSeconds, int unreadDelta) {
        updateInternal(context, groupId, groupName, avatarUrl, lastMessage,
                normalizeTimestamp(timestampSeconds), unreadDelta, false, -1);
    }

    public static synchronized void setUnreadCount(Context context, String groupId, String groupName, String avatarUrl,
                                      String lastMessage, long timestampSeconds, int unreadCount) {
        updateInternalWithUnread(context, groupId, groupName, avatarUrl, lastMessage,
                normalizeTimestamp(timestampSeconds), unreadCount, -1);
    }

    public static synchronized void updateGroupOutgoing(Context context, String groupId, String groupName, String avatarUrl,
                                           String lastMessage, long timestampSeconds) {
        updateInternal(context, groupId, groupName, avatarUrl, lastMessage,
                normalizeTimestamp(timestampSeconds), 0, false, -1);
    }

    public static synchronized void touchGroup(Context context, String groupId, String groupName, String avatarUrl, int role) {
        updateInternal(context, groupId, groupName, avatarUrl, null,
                System.currentTimeMillis(), 0, false, role);
    }

    public static synchronized void clearUnread(Context context, String groupId) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (normalizedGroupId == null) {
            return;
        }
        saveLocalClearAt(context, normalizedGroupId, System.currentTimeMillis());
        updateInternal(context, groupId, null, null, null, 0, 0, true, -1);
        setMentionUnread(context, groupId, false);
    }

    /** 群级 pointer 事件到达时标记"有更新"，未读数由用户打开群后同步拉取补齐。 */
    public static synchronized void markHasUpdate(Context context, String groupId) {
        if (context == null || groupId == null) {
            return;
        }
        String normalizedGroupId = normalizeGroupId(groupId);
        if (normalizedGroupId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        saveLocalClearAt(context, normalizedGroupId, now);
        updateInternal(context, groupId, null, null, null, 0, 0, false, -1);
    }

    public static synchronized void clearUnreadNotIn(Context context, Set<String> activeUnreadGroupIds) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);
        HashSet<String> activeKeys = normalizeGroupKeys(activeUnreadGroupIds);
        boolean changed = false;
        for (RecentGroup g : list) {
            if (g.unreadCount <= 0 && !g.mentionUnread) {
                continue;
            }
            String normalizedGroupId = normalizeGroupId(g.groupId);
            if (normalizedGroupId == null) {
                continue;
            }
            if (!activeKeys.contains(normalizedGroupId)) {
                g.unreadCount = 0;
                g.mentionUnread = false;
                changed = true;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static synchronized void setMentionUnread(Context context, String groupId, boolean mentionUnread) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (context == null || normalizedGroupId == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);
        boolean changed = false;
        for (RecentGroup group : list) {
            if (sameGroupId(group.groupId, normalizedGroupId)) {
                boolean nextMentionUnread = mentionUnread && group.unreadCount > 0;
                if (group.mentionUnread != nextMentionUnread) {
                    group.mentionUnread = nextMentionUnread;
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static synchronized void updateMemberCount(Context context, String groupId, int memberCount) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (context == null || normalizedGroupId == null) {
            return;
        }
        if (memberCount <= 0) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);
        boolean changed = false;
        for (RecentGroup group : list) {
            if (sameGroupId(group.groupId, normalizedGroupId)) {
                if (group.memberCount != memberCount) {
                    group.memberCount = memberCount;
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static synchronized void updateName(Context context, String groupId, String name) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (context == null || normalizedGroupId == null) {
            return;
        }
        String newName = name == null ? "" : name.trim();
        if (newName.length() == 0) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);
        boolean changed = false;
        for (RecentGroup group : list) {
            if (sameGroupId(group.groupId, normalizedGroupId)) {
                if (group.groupName == null || !newName.equals(group.groupName)) {
                    group.groupName = newName;
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static synchronized void mergeGroupInfo(Context context, List<Group> groups) {
        if (context == null || groups == null || groups.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);
        boolean changed = false;
        for (Group g : groups) {
            if (g.id == null || g.id.isEmpty()) {
                continue;
            }
            RecentGroup existing = null;
            for (RecentGroup c : list) {
                if (sameGroupId(c.groupId, g.id)) {
                    existing = c;
                    break;
                }
            }
            if (existing == null) {
                int memberCount = g.member_count > 0 ? g.member_count : -1;
                list.add(new RecentGroup(g.id, g.name, g.avatar_url, "",
                        0, 0, g.role, false, memberCount));
                changed = true;
                continue;
            }
            if (g.name != null && !g.name.isEmpty() && !g.name.equals(existing.groupName)) {
                existing.groupName = g.name;
                changed = true;
            }
            if (g.avatar_url != null && !g.avatar_url.isEmpty() && !g.avatar_url.equals(existing.avatarUrl)) {
                existing.avatarUrl = g.avatar_url;
                changed = true;
            }
            if (existing.role != g.role) {
                existing.role = g.role;
                changed = true;
            }
            if (g.member_count > 0 && existing.memberCount != g.member_count) {
                existing.memberCount = g.member_count;
                changed = true;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static synchronized void remove(Context context, String groupId) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (context == null || normalizedGroupId == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            RecentGroup g = list.get(i);
            if (sameGroupId(g.groupId, normalizedGroupId)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) {
            persistList(context, list);
        }
    }

    public static synchronized boolean markGroupMessageSeen(Context context, String messageId) {
        if (context == null || messageId == null || messageId.trim().length() == 0) return true;
        String id = messageId.trim();
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SEEN_MESSAGE_IDS, "");
        String token = "|" + id + "|";
        if (raw.contains(token)) return false;
        String next = raw + token;
        String[] parts = next.split("\\|");
        if (parts.length > MAX_SEEN_MESSAGE_IDS + 1) {
            StringBuilder trimmed = new StringBuilder();
            int start = Math.max(1, parts.length - MAX_SEEN_MESSAGE_IDS);
            for (int i = start; i < parts.length; i++) if (parts[i] != null && parts[i].length() > 0) trimmed.append('|').append(parts[i]).append('|');
            next = trimmed.toString();
        }
        prefs.edit().putString(KEY_SEEN_MESSAGE_IDS, next).apply();
        return true;
    }

    private static void updateInternal(Context context, String groupId, String groupName, String avatarUrl,
                                       String lastMessage, long timestamp, int unreadDelta, boolean clearUnread, int role) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (context == null || normalizedGroupId == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);

        RecentGroup existing = null;
        for (RecentGroup c : list) {
            if (sameGroupId(c.groupId, normalizedGroupId)) {
                existing = c;
                break;
            }
        }
        if (existing != null) {
            list.remove(existing);
        }

        String finalName = groupName;
        String finalAvatar = avatarUrl;
        int unread = existing != null ? existing.unreadCount : 0;
        int finalRole = existing != null ? existing.role : 0;
        boolean mentionUnread = existing != null && existing.mentionUnread;
        int finalMemberCount = existing != null ? existing.memberCount : -1;
        if (existing != null) {
            if (finalName == null || finalName.isEmpty()) {
                finalName = existing.groupName;
            }
            if (finalAvatar == null || finalAvatar.isEmpty()) {
                finalAvatar = existing.avatarUrl;
            }
        }
        if (role >= 0) {
            finalRole = role;
        }
        if (finalName == null || finalName.isEmpty()) {
            finalName = existing != null && existing.groupId != null && !existing.groupId.isEmpty()
                    ? existing.groupId : groupId;
        }
        if (clearUnread) {
            unread = 0;
        } else if (unreadDelta > 0) {
            unread += unreadDelta;
        }

        long ts = timestamp > 0 ? timestamp : (existing != null ? existing.timestamp : System.currentTimeMillis());
        String finalMessage = lastMessage != null ? lastMessage : (existing != null ? existing.lastMessage : "");
        String finalGroupId = existing != null && existing.groupId != null && !existing.groupId.isEmpty()
                ? existing.groupId : groupId;
        if (finalGroupId == null || finalGroupId.isEmpty()) {
            finalGroupId = normalizedGroupId;
        }
        list.add(0, new RecentGroup(finalGroupId, finalName, finalAvatar, finalMessage, ts, unread, finalRole,
                mentionUnread, finalMemberCount));

        persistList(context, list);
    }

    private static void updateInternalWithUnread(Context context, String groupId, String groupName, String avatarUrl,
                                                  String lastMessage, long timestamp, int unreadCount, int role) {
        String normalizedGroupId = normalizeGroupId(groupId);
        if (context == null || normalizedGroupId == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<RecentGroup> list = getRecentGroups(context);

        RecentGroup existing = null;
        for (RecentGroup c : list) {
            if (sameGroupId(c.groupId, normalizedGroupId)) {
                existing = c;
                break;
            }
        }
        if (existing != null) {
            list.remove(existing);
        }

        String finalName = groupName;
        String finalAvatar = avatarUrl;
        int finalRole = existing != null ? existing.role : 0;
        boolean mentionUnread = existing != null && existing.mentionUnread;
        int finalMemberCount = existing != null ? existing.memberCount : -1;
        if (existing != null) {
            if (finalName == null || finalName.isEmpty()) {
                finalName = existing.groupName;
            }
            if (finalAvatar == null || finalAvatar.isEmpty()) {
                finalAvatar = existing.avatarUrl;
            }
        }
        if (role >= 0) {
            finalRole = role;
        }
        if (finalName == null || finalName.isEmpty()) {
            finalName = existing != null && existing.groupId != null && !existing.groupId.isEmpty()
                    ? existing.groupId : groupId;
        }

        long ts = timestamp > 0 ? timestamp : (existing != null ? existing.timestamp : System.currentTimeMillis());
        String finalMessage = lastMessage != null ? lastMessage : (existing != null ? existing.lastMessage : "");
        int finalUnread = unreadCount < 0 ? 0 : unreadCount;
        // 旧 HTTP 快照晚于新 WS 落盘时，不能用较旧时间和数量覆盖新消息。
        if (existing != null && existing.timestamp > ts) {
            ts = existing.timestamp;
            finalMessage = existing.lastMessage;
            finalUnread = Math.max(finalUnread, existing.unreadCount);
        }
        long localClearAt = getLocalClearAt(context, normalizedGroupId);
        if (finalUnread > 0 && localClearAt > 0L && localClearAt >= ts) {
            finalUnread = 0;
            mentionUnread = false;
        }
        if (finalUnread <= 0) {
            mentionUnread = false;
        }
        String finalGroupId = existing != null && existing.groupId != null && !existing.groupId.isEmpty()
                ? existing.groupId : groupId;
        if (finalGroupId == null || finalGroupId.isEmpty()) {
            finalGroupId = normalizedGroupId;
        }
        list.add(0, new RecentGroup(finalGroupId, finalName, finalAvatar, finalMessage, ts, finalUnread, finalRole,
                mentionUnread, finalMemberCount));

        persistList(context, list);
    }

    private static void ensureLoadedLocked(Context context) {
        if (memoryList != null) return;
        String json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("list", null);
        try {
            List<RecentGroup> parsed = json == null ? null : GSON.fromJson(json,
                    TypeToken.getParameterized(List.class, RecentGroup.class).getType());
            memoryList = parsed == null ? new ArrayList<RecentGroup>() : limitList(parsed);
        } catch (Exception ignored) {
            memoryList = new ArrayList<RecentGroup>();
        }
    }

    private static void persistList(Context context, List<RecentGroup> list) {
        if (context == null || list == null) return;
        final Context appContext = context.getApplicationContext();
        List<RecentGroup> snapshot = copyList(limitList(list));
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
                    List<RecentGroup> snapshot;
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

    private static List<RecentGroup> limitList(List<RecentGroup> source) {
        if (source == null || source.size() <= MAX_RECENT_GROUPS) {
            return source == null ? new ArrayList<RecentGroup>() : new ArrayList<RecentGroup>(source);
        }
        return new ArrayList<RecentGroup>(source.subList(0, MAX_RECENT_GROUPS));
    }

    private static List<RecentGroup> copyList(List<RecentGroup> source) {
        List<RecentGroup> out = new ArrayList<RecentGroup>();
        if (source == null) return out;
        for (RecentGroup g : source) {
            if (g == null) continue;
            out.add(new RecentGroup(g.groupId, g.groupName, g.avatarUrl, g.lastMessage,
                    g.timestamp, g.unreadCount, g.role, g.mentionUnread, g.memberCount));
        }
        return out;
    }

    private static void sortGroups(List<RecentGroup> groups) {
        Collections.sort(groups, new java.util.Comparator<RecentGroup>() {
            @Override public int compare(RecentGroup a, RecentGroup b) {
                if (a == b) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                if (a.timestamp != b.timestamp) return a.timestamp < b.timestamp ? 1 : -1;
                int keyCmp = (a.groupId == null ? "" : a.groupId).compareTo(b.groupId == null ? "" : b.groupId);
                if (keyCmp != 0) return keyCmp;
                return (a.groupName == null ? "" : a.groupName).compareTo(b.groupName == null ? "" : b.groupName);
            }
        });
    }

    private static HashSet<String> normalizeGroupKeys(Set<String> groupIds) {
        HashSet<String> normalized = new HashSet<String>();
        if (groupIds == null || groupIds.isEmpty()) {
            return normalized;
        }
        for (String groupId : groupIds) {
            String normalizedGroupId = normalizeGroupId(groupId);
            if (normalizedGroupId != null) {
                normalized.add(normalizedGroupId);
            }
        }
        return normalized;
    }

    private static boolean sameGroupId(String value, String normalizedTarget) {
        if (normalizedTarget == null) {
            return false;
        }
        String normalizedValue = normalizeGroupId(value);
        return normalizedTarget.equals(normalizedValue);
    }

    private static String normalizeGroupId(String groupId) {
        if (groupId == null) {
            return null;
        }
        String out = groupId.trim();
        if (out.length() == 0) {
            return null;
        }
        return out.toUpperCase(Locale.US);
    }

    private static void saveLocalClearAt(Context context, String normalizedGroupId, long clearedAtMs) {
        if (context == null || normalizedGroupId == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LOCAL_CLEAR_PREFIX + normalizedGroupId, clearedAtMs).apply();
    }

    private static long getLocalClearAt(Context context, String normalizedGroupId) {
        if (context == null || normalizedGroupId == null) {
            return 0L;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_LOCAL_CLEAR_PREFIX + normalizedGroupId, 0L);
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
