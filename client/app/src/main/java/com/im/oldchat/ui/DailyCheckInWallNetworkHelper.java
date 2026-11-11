package com.im.oldchat.ui;

import com.im.oldchat.models.DailyCheckInWallMessage;
import com.im.oldchat.models.DailyCheckInWallState;

import org.json.JSONArray;

import org.json.JSONObject;

import java.util.ArrayList;

final class DailyCheckInWallNetworkHelper {
    private DailyCheckInWallNetworkHelper() {
    }

    static DailyCheckInWallState parseWallState(String response) {
        JSONObject obj = parseJson(response);
        if (obj == null) {
            return null;
        }
        DailyCheckInWallState state = new DailyCheckInWallState();
        state.checkInCount = obj.optInt("checkin_count", 0);
        state.checkedIn = obj.optBoolean("checked_in", false);
        state.alreadyPosted = obj.optBoolean("already_posted", false);
        state.myPost = DailyCheckInWallSupport.parseMessage(obj.optJSONObject("my_post"));
        state.featuredMessages = parseMessages(obj.optJSONArray("featured_messages"));
        return state;
    }

    static DailyCheckInWallMessage parseCreatedPost(String response) {
        JSONObject obj = parseJson(response);
        return obj == null ? null : DailyCheckInWallSupport.parseMessage(obj.optJSONObject("post"));
    }

    static int parseLikeCount(String response, int fallback) {
        JSONObject obj = parseJson(response);
        return obj == null ? fallback : obj.optInt("like_count", fallback);
    }

    static int parseCommentCount(String response, int fallback) {
        JSONObject obj = parseJson(response);
        return obj == null ? fallback : obj.optInt("comment_count", fallback);
    }

    private static ArrayList<DailyCheckInWallMessage> parseMessages(JSONArray arr) {
        ArrayList<DailyCheckInWallMessage> list = new ArrayList<DailyCheckInWallMessage>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            DailyCheckInWallMessage item = DailyCheckInWallSupport.parseMessage(arr.optJSONObject(i));
            if (item != null) {
                list.add(item);
            }
        }
        return list;
    }

    private static JSONObject parseJson(String response) {
        if (response == null || response.length() == 0) {
            return null;
        }
        try {
            return new JSONObject(response);
        } catch (Exception e) {
            return null;
        }
    }
}
