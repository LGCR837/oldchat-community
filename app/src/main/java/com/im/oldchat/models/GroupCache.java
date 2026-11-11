package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 群资料内存镜像，避免主页和未读圆点反复全量解析 JSON。 */
public class GroupCache {
    private static final String PREF_NAME = "group_cache";
    private static final String KEY_GROUPS = "groups";
    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static List<Group> memoryList;
    private static List<Group> pendingSnapshot;
    private static int generation;
    private static boolean workerScheduled;

    public static void saveGroups(Context context, List<Group> groups) {
        if (context == null || groups == null) return;
        final Context appContext = context.getApplicationContext();
        List<Group> snapshot = copyList(groups);
        synchronized (LOCK) {
            // 内存镜像只通过副本读取，可复用只读保存快照，降低老账户瞬时内存峰值。
            memoryList = snapshot;
            pendingSnapshot = snapshot;
            generation++;
            if (workerScheduled) return;
            workerScheduled = true;
        }
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override public void run() {
                while (true) {
                    List<Group> latest;
                    int currentGeneration;
                    synchronized (LOCK) {
                        latest = pendingSnapshot;
                        pendingSnapshot = null;
                        currentGeneration = generation;
                        if (latest == null) {
                            workerScheduled = false;
                            return;
                        }
                    }
                    String json = GSON.toJson(latest);
                    synchronized (LOCK) {
                        if (currentGeneration != generation) continue;
                        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_GROUPS, json).apply();
                    }
                }
            }
        });
    }

    public static List<Group> getGroups(Context context) {
        if (context == null) return new ArrayList<Group>();
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            return copyList(memoryList);
        }
    }

    public static Group getGroup(Context context, String groupId) {
        if (context == null || groupId == null || groupId.isEmpty()) return null;
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            if (memoryList == null) return null;
            for (int i = 0; i < memoryList.size(); i++) {
                Group g = memoryList.get(i);
                if (g != null && groupId.equals(g.id)) {
                    return copyGroup(g);
                }
            }
            return null;
        }
    }

    public static void remove(Context context, String groupId) {
        if (context == null || groupId == null || groupId.isEmpty()) return;
        List<Group> groups = getGroups(context);
        boolean changed = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            Group group = groups.get(i);
            if (group != null && groupId.equals(group.id)) {
                groups.remove(i);
                changed = true;
            }
        }
        if (changed) saveGroups(context, groups);
    }

    public static void updateMemberCount(Context context, String groupId, int memberCount) {
        if (context == null || groupId == null || groupId.isEmpty() || memberCount <= 0) return;
        List<Group> groups = getGroups(context);
        boolean changed = false;
        for (Group group : groups) {
            if (group != null && groupId.equals(group.id)) {
                if (group.member_count != memberCount) {
                    group.member_count = memberCount;
                    changed = true;
                }
                break;
            }
        }
        if (changed) saveGroups(context, groups);
    }

    public static void updateName(Context context, String groupId, String name) {
        if (context == null || groupId == null || groupId.isEmpty()) return;
        String newName = name == null ? "" : name.trim();
        if (newName.length() == 0) return;
        List<Group> groups = getGroups(context);
        boolean changed = false;
        for (Group group : groups) {
            if (group != null && groupId.equals(group.id)) {
                if (group.name == null || !newName.equals(group.name)) {
                    group.name = newName;
                    changed = true;
                }
                break;
            }
        }
        if (changed) saveGroups(context, groups);
    }

    public static void clearAll(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            memoryList = new ArrayList<Group>();
            pendingSnapshot = null;
            generation++;
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    private static void ensureLoadedLocked(Context context) {
        if (memoryList != null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_GROUPS, null);
        if (json == null) {
            memoryList = new ArrayList<Group>();
            return;
        }
        try {
            List<Group> parsed = GSON.fromJson(json,
                    TypeToken.getParameterized(List.class, Group.class).getType());
            memoryList = parsed == null ? new ArrayList<Group>() : copyList(parsed);
        } catch (Exception e) {
            memoryList = new ArrayList<Group>();
        }
    }

    private static List<Group> copyList(List<Group> source) {
        List<Group> result = new ArrayList<Group>(source == null ? 0 : source.size());
        if (source == null) return result;
        for (Group group : source) result.add(copyGroup(group));
        return result;
    }

    private static Group copyGroup(Group source) {
        if (source == null) return null;
        Group target = new Group();
        target.id = source.id;
        target.name = source.name;
        target.avatar_url = source.avatar_url;
        target.join_approval = source.join_approval;
        target.global_mute = source.global_mute;
        target.role = source.role;
        target.announcement = source.announcement;
        target.announcement_mode = source.announcement_mode;
        target.announcement_updated_at = source.announcement_updated_at;
        target.announcement_read_at = source.announcement_read_at;
        target.member_count = source.member_count;
        return target;
    }
}
