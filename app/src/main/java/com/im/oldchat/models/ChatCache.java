package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;

public class ChatCache {
    private static final String PREF_NAME = "chat_cache";
    private static final String KEY_MESSAGES_PREFIX = "messages_";
    
    public static void saveMessages(Context context, long friendId, List<Message> messages) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = new Gson().toJson(messages);
        prefs.edit().putString(KEY_MESSAGES_PREFIX + friendId, json).apply();
    }
    
    public static List<Message> getMessages(Context context, long friendId) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_MESSAGES_PREFIX + friendId, null);
        if (json == null) return new ArrayList<>();
        return new Gson().fromJson(json, TypeToken.getParameterized(List.class, Message.class).getType());
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
