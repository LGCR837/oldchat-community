package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;

public class MomentCache {
    private static final String PREF_NAME = "moment_cache";
    private static final String KEY_FEED = "feed";
    private static final String KEY_COMMENTS_PREFIX = "comments_";

    public static void saveFeed(Context context, List<Moment> moments) {
        if (context == null || moments == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = new Gson().toJson(moments);
        prefs.edit().putString(KEY_FEED, json).apply();
    }

    public static List<Moment> getFeed(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_FEED, null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            List<Moment> feed = new Gson().fromJson(json,
                    TypeToken.getParameterized(List.class, Moment.class).getType());
            return feed == null ? new ArrayList<Moment>() : feed;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void saveComments(Context context, String momentId, List<MomentComment> comments) {
        if (context == null || momentId == null || momentId.isEmpty() || comments == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = new Gson().toJson(comments);
        prefs.edit().putString(KEY_COMMENTS_PREFIX + momentId, json).apply();
    }

    public static List<MomentComment> getComments(Context context, String momentId) {
        if (context == null || momentId == null || momentId.isEmpty()) {
            return new ArrayList<>();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_COMMENTS_PREFIX + momentId, null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            List<MomentComment> list = new Gson().fromJson(json,
                    TypeToken.getParameterized(List.class, MomentComment.class).getType());
            return list == null ? new ArrayList<MomentComment>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void clearAll(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }
}
