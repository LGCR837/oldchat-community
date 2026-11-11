package com.im.oldchat.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.im.oldchat.models.Moment;
import com.im.oldchat.models.MomentNotice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MomentNoticeStore {
    private static final String PREF_NAME = "moment_notice";
    private static final String KEY_NOTICES = "notices";
    private static final String KEY_STATS = "stats";
    private static final int MAX_NOTICES = 200;

    private static class MomentStat {
        int likes;
        int comments;

        MomentStat(int likes, int comments) {
            this.likes = likes;
            this.comments = comments;
        }
    }

    public static List<MomentNotice> getNotices(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_NOTICES, null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            List<MomentNotice> list = new Gson().fromJson(json,
                    TypeToken.getParameterized(List.class, MomentNotice.class).getType());
            return list == null ? new ArrayList<MomentNotice>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean hasNotices(Context context) {
        List<MomentNotice> list = getNotices(context);
        return list != null && !list.isEmpty();
    }

    public static void saveNotices(Context context, List<MomentNotice> notices) {
        if (context == null || notices == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = new Gson().toJson(notices);
        prefs.edit().putString(KEY_NOTICES, json).apply();
    }

    public static void clearNotices(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NOTICES)
                .apply();
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

    public static int collectFromMoments(Context context, List<Moment> moments, String myUid, String myNcuid) {
        if (context == null || moments == null
                || ((myUid == null || myUid.isEmpty()) && (myNcuid == null || myNcuid.isEmpty()))) {
            return 0;
        }
        Map<String, MomentStat> stats = getStats(context);
        List<MomentNotice> existing = getNotices(context);
        List<MomentNotice> newNotices = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;

        for (Moment moment : moments) {
            if (moment == null || moment.id == null || moment.id.isEmpty()) {
                continue;
            }
            boolean own = myNcuid != null && myNcuid.length() > 0
                    && moment.from_ncuid != null && moment.from_ncuid.length() > 0
                    ? myNcuid.equalsIgnoreCase(moment.from_ncuid)
                    : myUid != null && myUid.equalsIgnoreCase(moment.from_uid);
            if (!own) {
                continue;
            }
            MomentStat prev = stats.get(moment.id);
            if (prev != null) {
                int likeDelta = moment.likes - prev.likes;
                int commentDelta = moment.comments - prev.comments;
                if (commentDelta > 0) {
                    MomentNotice notice = new MomentNotice();
                    notice.id = moment.id + ":comment:" + now;
                    notice.momentId = moment.id;
                    notice.momentBody = moment.body;
                    notice.momentImage = moment.image_url;
                    notice.ownerUid = moment.from_uid;
                    notice.type = "comment";
                    notice.delta = commentDelta;
                    notice.createdAt = now;
                    newNotices.add(notice);
                }
                if (likeDelta > 0) {
                    MomentNotice notice = new MomentNotice();
                    notice.id = moment.id + ":like:" + now;
                    notice.momentId = moment.id;
                    notice.momentBody = moment.body;
                    notice.momentImage = moment.image_url;
                    notice.ownerUid = moment.from_uid;
                    notice.type = "like";
                    notice.delta = likeDelta;
                    notice.createdAt = now;
                    newNotices.add(notice);
                }
            }
            stats.put(moment.id, new MomentStat(moment.likes, moment.comments));
        }

        if (!newNotices.isEmpty()) {
            List<MomentNotice> merged = new ArrayList<>();
            merged.addAll(newNotices);
            if (existing != null && !existing.isEmpty()) {
                merged.addAll(existing);
            }
            if (merged.size() > MAX_NOTICES) {
                merged = merged.subList(0, MAX_NOTICES);
            }
            saveNotices(context, merged);
        }
        saveStats(context, stats);
        return newNotices.size();
    }

    private static Map<String, MomentStat> getStats(Context context) {
        if (context == null) {
            return new HashMap<>();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_STATS, null);
        if (json == null) {
            return new HashMap<>();
        }
        try {
            Map<String, MomentStat> map = new Gson().fromJson(json,
                    TypeToken.getParameterized(Map.class, String.class, MomentStat.class).getType());
            return map == null ? new HashMap<String, MomentStat>() : map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void saveStats(Context context, Map<String, MomentStat> stats) {
        if (context == null || stats == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = new Gson().toJson(stats);
        prefs.edit().putString(KEY_STATS, json).apply();
    }
}
