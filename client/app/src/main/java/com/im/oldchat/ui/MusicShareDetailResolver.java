package com.im.oldchat.ui;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.MediaUrlResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/** 为不含 item_id/封面/歌词/发布者UID等字段的音乐分享或历史记录反查音乐广场详情。 */
final class MusicShareDetailResolver {
    static final class Detail {
        String itemId;
        String name;
        String coverUrl;
        String lyricsUrl;
        String ownerUid;
        String ownerName;
        String ownerTitle;
        String ownerAvatar;
        int likes;
        int comments;
        boolean liked;
    }

    interface Callback {
        void onResolved(Detail detail);
        void onUnavailable();
    }

    private MusicShareDetailResolver() {
    }

    /** 通过 item_id 精确查询歌曲元数据 */
    static void resolveById(String token, String itemId, final Callback callback) {
        if (callback == null) {
            return;
        }
        if (empty(token) || empty(itemId)) {
            callback.onUnavailable();
            return;
        }
        String path = "/music/plaza/detail?item_id=" + encode(itemId.trim());
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject item = new JSONObject(response == null ? "" : response);
                    Detail detail = parseSingleItem(item);
                    if (detail != null) {
                        callback.onResolved(detail);
                    } else {
                        callback.onUnavailable();
                    }
                } catch (Exception e) {
                    callback.onUnavailable();
                }
            }

            @Override
            public void onError(int code, String error) {
                callback.onUnavailable();
            }
        });
    }

    /** 通过歌曲名与播放地址在音乐广场列表中模糊反查 */
    static void resolve(String token, String title, final String songUrl, final Callback callback) {
        if (callback == null) {
            return;
        }
        if (empty(token) || empty(songUrl)) {
            callback.onUnavailable();
            return;
        }
        String queryTitle = empty(title) ? "" : title.trim();
        String path = "/music/plaza?q=" + encode(queryTitle) + "&limit=50&offset=0";
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                Detail detail = findMatchingDetail(response, songUrl);
                if (detail != null) {
                    callback.onResolved(detail);
                } else {
                    callback.onUnavailable();
                }
            }

            @Override
            public void onError(int code, String error) {
                callback.onUnavailable();
            }
        });
    }

    private static Detail findMatchingDetail(String response, String targetSongUrl) {
        try {
            JSONObject root = new JSONObject(response == null ? "" : response);
            JSONArray items = root.optJSONArray("items");
            if (items == null) {
                return null;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !sameMedia(item.optString("song_url", ""), targetSongUrl)) {
                    continue;
                }
                return parseSingleItem(item);
            }
        } catch (Exception e) {
        }
        return null;
    }

    private static Detail parseSingleItem(JSONObject item) {
        if (item == null) {
            return null;
        }
        Detail detail = new Detail();
        detail.itemId = item.optString("id", "");
        detail.name = item.optString("name", "");
        detail.coverUrl = item.optString("cover_url", "");
        detail.lyricsUrl = item.optString("lyrics_url", "");
        detail.ownerUid = item.optString("owner_uid", "");
        detail.ownerName = item.optString("owner_name", "");
        detail.ownerTitle = item.optString("owner_title", "");
        detail.ownerAvatar = item.optString("owner_avatar", "");
        detail.likes = item.optInt("likes", 0);
        detail.comments = item.optInt("comments", 0);
        detail.liked = item.optBoolean("liked", false);
        return detail;
    }

    private static boolean sameMedia(String left, String right) {
        if (empty(left) || empty(right)) {
            return false;
        }
        String leftKey = MediaUrlResolver.cacheIdentity(left);
        String rightKey = MediaUrlResolver.cacheIdentity(right);
        if (!empty(leftKey) && leftKey.equals(rightKey)) {
            return true;
        }
        return MediaUrlResolver.resolve(left).equals(MediaUrlResolver.resolve(right));
    }

    private static boolean empty(String value) {
        return value == null || value.trim().length() == 0;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }
}
