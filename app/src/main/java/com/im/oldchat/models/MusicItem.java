package com.im.oldchat.models;

import org.json.JSONObject;

import java.io.Serializable;

/**
 * 音乐广场歌曲模型（公共版，供歌单/列表/详情复用）。
 * 与 MusicPlazaActivity / MusicManageActivity 内部 MusicItem 字段保持一致。
 */
public class MusicItem implements Serializable {
    private static final long serialVersionUID = 1L;

    public String id;
    public String name;
    public String songUrl;
    public String coverUrl;
    public String lyricsUrl;
    public long sizeBytes;
    public int durationMs;
    public String ownerUid;
    public String ownerName;
    public String ownerTitle;
    public String ownerAvatar;
    public int likes;
    public int comments;
    public int playCount;
    public boolean liked;
    public boolean canDelete;

    public MusicItem() {
    }

    public JSONObject toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", value(id));
            obj.put("name", value(name));
            obj.put("song_url", value(songUrl));
            obj.put("cover_url", value(coverUrl));
            obj.put("lyrics_url", value(lyricsUrl));
            obj.put("size_bytes", sizeBytes);
            obj.put("duration_ms", durationMs);
            obj.put("owner_uid", value(ownerUid));
            obj.put("owner_name", value(ownerName));
            obj.put("owner_title", value(ownerTitle));
            obj.put("owner_avatar", value(ownerAvatar));
            obj.put("likes", likes);
            obj.put("comments", comments);
            obj.put("play_count", playCount);
            obj.put("liked", liked);
            obj.put("can_delete", canDelete);
            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static MusicItem fromJson(JSONObject obj) {
        MusicItem item = new MusicItem();
        if (obj == null) {
            return item;
        }
        item.id = obj.optString("id");
        item.name = obj.optString("name");
        item.songUrl = obj.optString("song_url");
        item.coverUrl = obj.optString("cover_url");
        item.lyricsUrl = obj.optString("lyrics_url");
        item.sizeBytes = obj.optLong("size_bytes");
        item.durationMs = obj.optInt("duration_ms");
        item.ownerUid = obj.optString("owner_uid");
        item.ownerName = obj.optString("owner_name");
        item.ownerTitle = obj.optString("owner_title");
        item.ownerAvatar = obj.optString("owner_avatar");
        item.likes = obj.optInt("likes");
        item.comments = obj.optInt("comments");
        item.playCount = obj.optInt("play_count");
        item.liked = obj.optBoolean("liked");
        item.canDelete = obj.optBoolean("can_delete");
        return item;
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }
}
