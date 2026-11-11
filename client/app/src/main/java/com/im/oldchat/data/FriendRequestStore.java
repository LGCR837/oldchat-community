package com.im.oldchat.data;

import android.content.Context;
import android.content.SharedPreferences;

public class FriendRequestStore {
    private static final String PREFS = "friend_requests";
    private static final String KEY_PENDING_COUNT = "pending_count";

    private FriendRequestStore() {
    }

    public static int getPendingCount(Context context) {
        if (context == null) {
            return 0;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_PENDING_COUNT, 0);
    }

    public static void setPendingCount(Context context, int count) {
        if (context == null) {
            return;
        }
        if (count < 0) {
            count = 0;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_PENDING_COUNT, count).apply();
    }

    public static void clearAll(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }
}
