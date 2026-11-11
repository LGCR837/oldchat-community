package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 主页自定义会话分组，仅保存在当前账号的本地配置中。 */
final class RecentCustomGroupStore {
    private static final String PREFS = "recent_custom_groups";
    private static final String GROUPS = "groups_";
    private static final String ASSIGNMENTS = "assignments_";

    static final class Group {
        final String id;
        final String name;

        Group(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private RecentCustomGroupStore() { }

    static List<Group> list(Context context) {
        ArrayList<Group> result = new ArrayList<Group>();
        if (context == null) return result;
        String raw = prefs(context).getString(GROUPS + account(context), "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject one = array.optJSONObject(i);
                if (one == null) continue;
                String id = one.optString("id", "").trim();
                String name = one.optString("name", "").trim();
                if (id.length() > 0 && name.length() > 0) result.add(new Group(id, name));
            }
        } catch (Exception ignored) { }
        return result;
    }

    static boolean create(Context context, String name) {
        String clean = cleanName(name);
        if (context == null || clean.length() == 0) return false;
        List<Group> groups = list(context);
        if (containsName(groups, clean, null)) return false;
        groups.add(new Group("cg_" + System.currentTimeMillis(), clean));
        saveGroups(context, groups);
        return true;
    }

    static boolean rename(Context context, String groupId, String name) {
        String clean = cleanName(name);
        List<Group> groups = list(context);
        if (clean.length() == 0 || containsName(groups, clean, groupId)) return false;
        ArrayList<Group> next = new ArrayList<Group>();
        boolean changed = false;
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            if (group.id.equals(groupId)) {
                next.add(new Group(group.id, clean));
                changed = true;
            } else {
                next.add(group);
            }
        }
        if (changed) saveGroups(context, next);
        return changed;
    }

    static void delete(Context context, String groupId) {
        List<Group> groups = list(context);
        ArrayList<Group> next = new ArrayList<Group>();
        for (int i = 0; i < groups.size(); i++) {
            if (!groups.get(i).id.equals(groupId)) next.add(groups.get(i));
        }
        saveGroups(context, next);
        JSONObject assignments = readAssignments(context);
        JSONArray keys = assignments.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i, "");
                if (groupId.equals(assignments.optString(key, ""))) assignments.remove(key);
            }
        }
        saveAssignments(context, assignments);
    }

    static boolean move(Context context, String groupId, int delta) {
        List<Group> groups = list(context);
        int from = indexOf(groups, groupId);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= groups.size()) return false;
        Group moving = groups.remove(from);
        groups.add(to, moving);
        saveGroups(context, groups);
        return true;
    }

    static String assignment(Context context, boolean isGroup, String chatId) {
        if (context == null || chatId == null) return "";
        return readAssignments(context).optString(chatKey(isGroup, chatId), "");
    }

    static void assign(Context context, boolean isGroup, String chatId, String groupId) {
        if (context == null || chatId == null || chatId.length() == 0) return;
        JSONObject assignments = readAssignments(context);
        String key = chatKey(isGroup, chatId);
        if (groupId == null || groupId.length() == 0) {
            assignments.remove(key);
        } else {
            try { assignments.put(key, groupId); } catch (Exception ignored) { return; }
        }
        saveAssignments(context, assignments);
    }

    private static int indexOf(List<Group> groups, String id) {
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).id.equals(id)) return i;
        return -1;
    }

    private static boolean containsName(List<Group> groups, String name, String exceptId) {
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            if ((exceptId == null || !exceptId.equals(group.id)) && group.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        return clean.length() > 16 ? clean.substring(0, 16) : clean;
    }

    private static void saveGroups(Context context, List<Group> groups) {
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < groups.size(); i++) {
                JSONObject one = new JSONObject();
                one.put("id", groups.get(i).id);
                one.put("name", groups.get(i).name);
                array.put(one);
            }
        } catch (Exception ignored) { }
        prefs(context).edit().putString(GROUPS + account(context), array.toString()).apply();
    }

    private static JSONObject readAssignments(Context context) {
        if (context == null) return new JSONObject();
        String raw = prefs(context).getString(ASSIGNMENTS + account(context), "{}");
        try { return new JSONObject(raw); } catch (Exception ignored) { return new JSONObject(); }
    }

    private static void saveAssignments(Context context, JSONObject assignments) {
        prefs(context).edit().putString(ASSIGNMENTS + account(context), assignments.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String account(Context context) {
        String uid = context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("my_uid", "default");
        return uid == null ? "default" : uid.trim().toUpperCase(Locale.US);
    }

    private static String chatKey(boolean isGroup, String id) {
        return (isGroup ? "g:" : "u:") + id.trim().toUpperCase(Locale.US);
    }
}
