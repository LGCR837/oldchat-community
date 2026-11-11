package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.User;
import com.im.oldchat.models.UserNameCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class AvatarSyncManager {
    private static final String PREFS = "avatar_cache";
    private static final String KEY_SELF = "self_avatar_file";
    private static final String KEY_FRIENDS = "friend_avatar_map";
    private static final String KEY_LAST_SYNC_REQUEST_AT = "last_sync_request_at";
    private static final long SYNC_REQUEST_INTERVAL_MS = 5L * 60L * 1000L;

    public static void syncAll(Context context, String token) {
        if (context == null || token == null || token.isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (!shouldRequestSync(appContext)) {
            return;
        }
        syncSelf(appContext, token);
        syncFriends(appContext, token);
    }

    public static void clearAll(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    private static void syncSelf(Context context, String token) {
        final Context contextFinal = context;
        HttpUtil.get("/me", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String response) {
                ThreadPoolManager.getInstance().executeIo(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject obj = new JSONObject(response);
                            String avatarUrl = obj.optString("avatar_url", "");
                            syncSingle(contextFinal, KEY_SELF, "self", avatarUrl);
                        } catch (Exception ignored) {
                        }
                    }
                });
            }

            @Override
            public void onError(int code, String error) {
            }
        });
    }

    private static void syncFriends(Context context, String token) {
        final Context contextFinal = context;
        HttpUtil.get("/friends", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String response) {
                ThreadPoolManager.getInstance().executeIo(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject obj = new JSONObject(response);
                            JSONArray arr = obj.getJSONArray("friends");
                            SharedPreferences prefs = contextFinal.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                            JSONObject map = loadMap(prefs);
                            boolean changed = false;
                            List<User> friends = new ArrayList<>();
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject uObj = arr.getJSONObject(i);
                                String uid = uObj.optString("uid", "");
                                String avatarUrl = uObj.optString("avatar_url", "");
                                User user = new User();
                                user.id = uObj.optString("id", "");
                                user.uid = uid;
                                user.username = uObj.optString("username", "");
                                user.display_name = uObj.optString("display_name", "");
                                user.user_title = uObj.optString("user_title", "");
                                user.avatar_url = avatarUrl;
                                friends.add(user);
                                if (uid.isEmpty()) {
                                    continue;
                                }
                                String fileName = getFileName(avatarUrl);
                                if (fileName.isEmpty()) {
                                    continue;
                                }
                                String cached = map.optString(uid, "");
                                boolean cachedFile = ImageLoader.isCached(contextFinal, avatarUrl);
                                if (fileName.equals(cached) && cachedFile) {
                                    continue;
                                }
                                map.put(uid, fileName);
                                ImageLoader.prefetch(contextFinal, avatarUrl);
                                changed = true;
                            }
                            if (changed) {
                                prefs.edit().putString(KEY_FRIENDS, map.toString()).apply();
                            }
                            if (!friends.isEmpty()) {
                                RecentChatCache.mergeFriendInfo(contextFinal, friends);
                                UserNameCache.mergeUsers(contextFinal, friends);
                                com.im.oldchat.models.UserTitleCache.mergeUsers(contextFinal, friends);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });
            }

            @Override
            public void onError(int code, String error) {
            }
        });
    }

    private static void syncSingle(Context context, String key, String mapKey, String avatarUrl) {
        if (context == null) {
            return;
        }
        if (avatarUrl == null || avatarUrl.isEmpty()) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(key, "").apply();
            return;
        }
        String fileName = getFileName(avatarUrl);
        if (fileName.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (KEY_SELF.equals(key)) {
            String cached = prefs.getString(KEY_SELF, "");
            if (fileName.equals(cached) && ImageLoader.isCached(context, avatarUrl)) {
                return;
            }
            prefs.edit().putString(KEY_SELF, fileName).apply();
            ImageLoader.prefetch(context, avatarUrl);
            return;
        }
        JSONObject map = loadMap(prefs);
        String cached = map.optString(mapKey, "");
        if (fileName.equals(cached) && ImageLoader.isCached(context, avatarUrl)) {
            return;
        }
        try {
            map.put(mapKey, fileName);
            prefs.edit().putString(KEY_FRIENDS, map.toString()).apply();
        } catch (Exception e) {
        }
        ImageLoader.prefetch(context, avatarUrl);
    }

    private static boolean shouldRequestSync(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastRequestAt = prefs.getLong(KEY_LAST_SYNC_REQUEST_AT, 0L);
        if (lastRequestAt > 0L && now - lastRequestAt < SYNC_REQUEST_INTERVAL_MS) {
            return false;
        }
        prefs.edit().putLong(KEY_LAST_SYNC_REQUEST_AT, now).apply();
        return true;
    }

    private static JSONObject loadMap(SharedPreferences prefs) {
        try {
            String raw = prefs.getString(KEY_FRIENDS, "{}");
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String getFileName(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int q = url.indexOf('?');
        if (q >= 0) {
            url = url.substring(0, q);
        }
        int idx = url.lastIndexOf('/');
        if (idx >= 0 && idx < url.length() - 1) {
            return url.substring(idx + 1);
        }
        return "";
    }
}
