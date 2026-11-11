package com.im.oldchat.ui.fragments;

import com.im.oldchat.models.Moment;
import com.im.oldchat.util.MomentImageUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MomentParser {
    public static List<Moment> parse(JSONArray arr) throws Exception {
        List<Moment> incoming = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject mObj = arr.getJSONObject(i);
            Moment m = new Moment();
            m.id = mObj.optString("id");
            m.from_uid = mObj.optString("from_uid");
            m.from_ncuid = mObj.optString("from_ncuid");
            m.from_name = mObj.optString("from_name");
            m.from_title = mObj.optString("from_title");
            m.from_avatar = mObj.optString("from_avatar");
            m.body = mObj.optString("body");
            m.image_url = mObj.optString("image_url");
            m.parsedImageUrls = MomentImageUtil.parseUrls(m.image_url);
            m.created_at = mObj.optLong("created_at");
            m.likes = mObj.optInt("likes", 0);
            m.comments = mObj.optInt("comments", 0);
            m.liked = mObj.optBoolean("liked", false);
            incoming.add(m);
        }
        return incoming;
    }
}
