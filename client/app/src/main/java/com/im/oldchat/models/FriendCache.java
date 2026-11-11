package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 好友列表内存镜像；读取不再反复解析整份 SharedPreferences JSON。 */
public class FriendCache {
    private static final String PREF_NAME = "friend_cache";
    private static final String KEY_FRIENDS = "friends";
    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static List<User> memoryList;
    private static List<User> pendingSnapshot;
    private static int generation;
    private static boolean workerScheduled;

    public static void saveFriends(Context context, List<User> friends) {
        if (context == null || friends == null) return;
        final Context appContext = context.getApplicationContext();
        List<User> snapshot = copyList(friends);
        synchronized (LOCK) {
            // memoryList 不会直接暴露给调用方，可与只读待写快照共享，避免大账户再深拷贝一份。
            memoryList = snapshot;
            pendingSnapshot = snapshot;
            generation++;
            if (workerScheduled) return;
            workerScheduled = true;
        }
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override public void run() {
                while (true) {
                    List<User> latest;
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
                                .edit().putString(KEY_FRIENDS, json).apply();
                    }
                }
            }
        });
    }

    public static List<User> getFriends(Context context) {
        if (context == null) return new ArrayList<User>();
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            return copyList(memoryList);
        }
    }

    public static User getFriend(Context context, String uid) {
        if (context == null || uid == null || uid.length() == 0) {
            return null;
        }
        String target = uid.trim();
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            if (memoryList != null) {
                for (User u : memoryList) {
                    if (u != null && (target.equalsIgnoreCase(u.id) || target.equalsIgnoreCase(u.ncuid))) {
                        return copyUser(u);
                    }
                }
            }
        }
        return null;
    }

    public static void clearAll(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            memoryList = new ArrayList<User>();
            pendingSnapshot = null;
            generation++;
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    private static void ensureLoadedLocked(Context context) {
        if (memoryList != null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_FRIENDS, null);
        if (json == null) {
            memoryList = new ArrayList<User>();
            return;
        }
        try {
            List<User> parsed = GSON.fromJson(json,
                    TypeToken.getParameterized(List.class, User.class).getType());
            memoryList = parsed == null ? new ArrayList<User>() : copyList(parsed);
        } catch (Exception e) {
            memoryList = new ArrayList<User>();
        }
    }

    private static List<User> copyList(List<User> source) {
        List<User> result = new ArrayList<User>(source == null ? 0 : source.size());
        if (source == null) return result;
        for (User user : source) result.add(copyUser(user));
        return result;
    }

    private static User copyUser(User source) {
        if (source == null) return null;
        User target = new User();
        target.id = source.id;
        target.uid = source.uid;
        target.ncuid = source.ncuid;
        target.username = source.username;
        target.display_name = source.display_name;
        target.remark_name = source.remark_name;
        target.user_title = source.user_title;
        target.avatar_url = source.avatar_url;
        target.signature = source.signature;
        target.cover_url = source.cover_url;
        target.friend_added_at = source.friend_added_at;
        target.is_online = source.is_online;
        target.presence_status = source.presence_status;
        return target;
    }
}
