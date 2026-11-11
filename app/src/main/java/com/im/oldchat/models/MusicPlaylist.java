package com.im.oldchat.models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 歌单模型（照搬 emusic Playlist.java，适配 MusicItem）。
 * 本地存储，含歌曲去重、置顶添加、更新时间维护。
 */
public class MusicPlaylist implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String cover;
    private long createTime;
    private long updateTime;
    private List<MusicItem> songs;

    public MusicPlaylist() {
        this.songs = new ArrayList<MusicItem>();
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    public MusicPlaylist(String id, String name) {
        this();
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public List<MusicItem> getSongs() {
        return songs;
    }

    public void setSongs(List<MusicItem> songs) {
        this.songs = songs != null ? songs : new ArrayList<MusicItem>();
    }

    public int getSongCount() {
        return songs != null ? songs.size() : 0;
    }

    public void addSong(MusicItem song) {
        if (songs == null) {
            songs = new ArrayList<MusicItem>();
        }
        if (song == null || song.id == null) {
            return;
        }
        for (MusicItem s : songs) {
            if (s.id != null && s.id.equals(song.id)) {
                return;
            }
        }
        songs.add(0, song);
        updateTime = System.currentTimeMillis();
    }

    public void removeSong(MusicItem song) {
        if (songs == null || song == null || song.id == null) {
            return;
        }
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id != null && songs.get(i).id.equals(song.id)) {
                songs.remove(i);
                updateTime = System.currentTimeMillis();
                return;
            }
        }
    }

    public boolean contains(MusicItem song) {
        if (songs == null || song == null || song.id == null) {
            return false;
        }
        for (MusicItem s : songs) {
            if (song.id.equals(s.id)) {
                return true;
            }
        }
        return false;
    }

    // ========== JSON 转换（供 MusicPlaylistStore 持久化） ==========

    public JSONObject toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", id == null ? "" : id);
            obj.put("name", name == null ? "" : name);
            obj.put("cover", cover == null ? "" : cover);
            obj.put("createTime", createTime);
            obj.put("updateTime", updateTime);
            JSONArray arr = new JSONArray();
            if (songs != null) {
                for (MusicItem song : songs) {
                    arr.put(song.toJson());
                }
            }
            obj.put("songs", arr);
            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static MusicPlaylist fromJson(JSONObject obj) {
        MusicPlaylist pl = new MusicPlaylist();
        if (obj == null) {
            return pl;
        }
        pl.setId(obj.optString("id"));
        pl.setName(obj.optString("name"));
        pl.setCover(obj.optString("cover"));
        pl.setCreateTime(obj.optLong("createTime"));
        pl.setUpdateTime(obj.optLong("updateTime"));
        List<MusicItem> songs = new ArrayList<MusicItem>();
        JSONArray arr = obj.optJSONArray("songs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                MusicItem song = MusicItem.fromJson(arr.optJSONObject(i));
                if (song != null && song.id != null && song.id.length() > 0) {
                    songs.add(song);
                }
            }
        }
        pl.setSongs(songs);
        return pl;
    }
}
