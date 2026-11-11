package com.im.oldchat.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.im.oldchat.models.MusicItem;
import com.im.oldchat.models.MusicPlaylist;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单本地存储（照搬 emusic DataManager 歌单部分，去掉云端同步）。
 * SharedPreferences + JSON 序列化，首次初始化默认"我喜欢的音乐"歌单。
 */
public class MusicPlaylistStore {

    private static final String PREF_NAME = "oldchat_playlists";
    private static final String KEY_PLAYLISTS = "playlists";

    private static volatile MusicPlaylistStore sInstance;
    private final Context mContext;
    private final SharedPreferences mPrefs;

    public static MusicPlaylistStore getInstance(Context context) {
        if (sInstance == null) {
            synchronized (MusicPlaylistStore.class) {
                if (sInstance == null) {
                    sInstance = new MusicPlaylistStore(context.getApplicationContext());
                    sInstance.initDefaultPlaylistsIfNeeded();
                }
            }
        }
        return sInstance;
    }

    private MusicPlaylistStore(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private void initDefaultPlaylistsIfNeeded() {
        String json = mPrefs.getString(KEY_PLAYLISTS, "");
        if (json.length() == 0) {
            List<MusicPlaylist> list = new ArrayList<MusicPlaylist>();
            list.add(new MusicPlaylist("default", "\u6211\u559C\u6B22\u7684\u97F3\u4E50"));
            savePlaylists(list);
        }
    }

    public synchronized List<MusicPlaylist> getPlaylists() {
        List<MusicPlaylist> list = new ArrayList<MusicPlaylist>();
        String json = mPrefs.getString(KEY_PLAYLISTS, "");
        if (json.length() == 0) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                MusicPlaylist pl = MusicPlaylist.fromJson(arr.optJSONObject(i));
                if (pl != null) {
                    list.add(pl);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public synchronized void savePlaylists(List<MusicPlaylist> playlists) {
        try {
            JSONArray arr = new JSONArray();
            for (MusicPlaylist pl : playlists) {
                arr.put(pl.toJson());
            }
            mPrefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public synchronized void addPlaylist(MusicPlaylist playlist) {
        List<MusicPlaylist> list = getPlaylists();
        list.add(playlist);
        savePlaylists(list);
    }

    public synchronized void updatePlaylist(MusicPlaylist playlist) {
        List<MusicPlaylist> list = getPlaylists();
        String targetId = playlist != null ? playlist.getId() : null;
        for (int i = 0; i < list.size(); i++) {
            if (targetId != null && targetId.equals(list.get(i).getId())) {
                list.set(i, playlist);
                break;
            }
        }
        savePlaylists(list);
    }

    public synchronized void deletePlaylist(String playlistId) {
        List<MusicPlaylist> list = getPlaylists();
        for (int i = 0; i < list.size(); i++) {
            if (playlistId != null && playlistId.equals(list.get(i).getId())) {
                list.remove(i);
                break;
            }
        }
        savePlaylists(list);
    }

    public synchronized MusicPlaylist getPlaylist(String playlistId) {
        List<MusicPlaylist> list = getPlaylists();
        for (MusicPlaylist pl : list) {
            if (playlistId != null && playlistId.equals(pl.getId())) {
                return pl;
            }
        }
        return null;
    }

    /**
     * 添加歌曲到歌单（已存在返回 false）。
     */
    public synchronized boolean addToPlaylist(String playlistId, MusicItem song) {
        if (song == null || song.id == null || song.id.length() == 0) {
            return false;
        }
        MusicPlaylist pl = getPlaylist(playlistId);
        if (pl != null) {
            if (pl.contains(song)) {
                return false;
            }
            pl.addSong(song);
            updatePlaylist(pl);
            return true;
        }
        return false;
    }

    public synchronized void removeSongFromPlaylist(String playlistId, MusicItem song) {
        MusicPlaylist pl = getPlaylist(playlistId);
        if (pl != null) {
            pl.removeSong(song);
            updatePlaylist(pl);
        }
    }
}
