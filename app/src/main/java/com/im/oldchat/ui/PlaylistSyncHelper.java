package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import com.im.oldchat.api.HttpProgressUtil;
import com.im.oldchat.data.MusicPlaylistStore;
import com.im.oldchat.models.MusicItem;
import com.im.oldchat.models.MusicPlaylist;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单云端同步（推送到服务器 / 从服务器拉取）。
 * 服务端限制：每用户最多 5 个歌单，每歌单最多 200 首。
 */
public final class PlaylistSyncHelper {

    public interface Callback {
        void onDone(boolean success, String message);
    }

    private static final String AUTH_PREFS = "auth";

    private PlaylistSyncHelper() {
    }

    private static String getToken(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        return prefs.getString("access_token", "");
    }

    /** 把本地全部歌单推送到服务器（全量覆盖该用户的云端歌单）。 */
    public static void push(Context context, final Callback callback) {
        push(context, null, callback);
    }

    public static void push(Context context, final HttpProgressUtil.ProgressListener progress,
                            final Callback callback) {
        final String token = getToken(context);
        if (token == null || token.length() == 0) {
            finish(context, callback, false, "请先登录");
            return;
        }
        List<MusicPlaylist> playlists = MusicPlaylistStore.getInstance(context).getPlaylists();
        if (playlists.size() > 5) {
            finish(context, callback, false, "本地歌单超过 5 个上限，请先删除多余的");
            return;
        }
        final JSONObject body = buildPushBody(playlists);
        if (body == null) {
            finish(context, callback, false, "歌单数据解析失败");
            return;
        }
        HttpProgressUtil.postJSON("/music/playlists/sync", body.toString(), token, progress,
                new HttpProgressUtil.ResultCallback() {
                    @Override
                    public void onSuccess(String response) {
                        finish(context, callback, true, "推送成功，云端已保存");
                    }

                    @Override
                    public void onError(int code, String error) {
                        finish(context, callback, false, "推送失败 (" + code + ")" + hint(error));
                    }
                });
    }

    /** 从服务器拉取歌单，覆盖本地（本地歌单将被替换）。 */
    public static void pull(Context context, final Callback callback) {
        pull(context, null, callback);
    }

    public static void pull(Context context, final HttpProgressUtil.ProgressListener progress,
                            final Callback callback) {
        final String token = getToken(context);
        if (token == null || token.length() == 0) {
            finish(context, callback, false, "请先登录");
            return;
        }
        HttpProgressUtil.getJSON("/music/playlists", token, progress,
                new HttpProgressUtil.ResultCallback() {
                    @Override
                    public void onSuccess(String response) {
                        try {
                            JSONObject obj = new JSONObject(response);
                            JSONArray items = obj.optJSONArray("items");
                            List<MusicPlaylist> playlists = new ArrayList<MusicPlaylist>();
                            if (items != null) {
                                for (int i = 0; i < items.length(); i++) {
                                    MusicPlaylist pl = parsePlaylist(items.optJSONObject(i));
                                    if (pl != null) {
                                        playlists.add(pl);
                                    }
                                }
                            }
                            MusicPlaylistStore.getInstance(context).savePlaylists(playlists);
                            finish(context, callback, true, "拉取成功，共 " + playlists.size() + " 个歌单");
                        } catch (Exception e) {
                            finish(context, callback, false, "拉取结果解析失败");
                        }
                    }

                    @Override
                    public void onError(int code, String error) {
                        finish(context, callback, false, "拉取失败 (" + code + ")" + hint(error));
                    }
                });
    }

    private static JSONObject buildPushBody(List<MusicPlaylist> playlists) {
        try {
            JSONArray arr = new JSONArray();
            for (MusicPlaylist pl : playlists) {
                JSONObject po = new JSONObject();
                po.put("id", value(pl.getId()));
                po.put("name", value(pl.getName()));
                po.put("cover_url", value(pl.getCover()));
                JSONArray songs = new JSONArray();
                List<MusicItem> list = pl.getSongs();
                int max = list != null ? list.size() : 0;
                if (max > 200) {
                    max = 200; // 单歌单最多 200 首，超出截断并仍可推送
                }
                for (int i = 0; i < max; i++) {
                    MusicItem s = list.get(i);
                    if (s == null || s.id == null || s.id.length() == 0) {
                        continue;
                    }
                    JSONObject so = new JSONObject();
                    so.put("song_id", s.id);
                    so.put("song", s.toJson());
                    songs.put(so);
                }
                po.put("songs", songs);
                arr.put(po);
            }
            JSONObject body = new JSONObject();
            body.put("playlists", arr);
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private static MusicPlaylist parsePlaylist(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        try {
            MusicPlaylist pl = new MusicPlaylist();
            pl.setId(obj.optString("id"));
            pl.setName(obj.optString("name"));
            pl.setCover(obj.optString("cover_url"));
            pl.setCreateTime(obj.optLong("created_at", 0L) * 1000L);
            pl.setUpdateTime(obj.optLong("updated_at", 0L) * 1000L);
            List<MusicItem> songs = new ArrayList<MusicItem>();
            JSONArray arr = obj.optJSONArray("songs");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject so = arr.optJSONObject(i);
                    if (so == null) {
                        continue;
                    }
                    MusicItem item = MusicItem.fromJson(so.optJSONObject("song"));
                    if (item != null && item.id != null && item.id.length() > 0) {
                        songs.add(item);
                    }
                }
            }
            pl.setSongs(songs);
            return pl;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hint(String error) {
        if (error == null || error.length() == 0) {
            return "";
        }
        try {
            JSONObject obj = new JSONObject(error);
            String msg = obj.optString("message");
            if (msg == null || msg.length() == 0) {
                msg = obj.optString("error");
            }
            if (msg != null && msg.length() > 0) {
                return "：" + msg;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static void finish(Context context, Callback callback, boolean success, String message) {
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
        if (callback != null) {
            callback.onDone(success, message);
        }
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }
}
