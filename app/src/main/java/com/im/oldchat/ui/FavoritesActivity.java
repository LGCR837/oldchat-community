package com.im.oldchat.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FavoriteItem;
import com.im.oldchat.util.ClipboardUtil;
import com.im.oldchat.util.MediaUrlResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class FavoritesActivity extends BaseActivity implements FavoriteItemAdapter.ActionListener {
    private static final String AUTH_PREFS = "auth";

    private ListView lvFavorites;
    private TextView tvEmpty;
    private FavoriteItemAdapter adapter;
    private final List<FavoriteItem> items = new ArrayList<FavoriteItem>();
    private String token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorites);

        lvFavorites = findViewByIdCompat(R.id.lvFavorites);
        tvEmpty = findViewByIdCompat(R.id.tvFavoritesEmpty);
        TextView btnRefresh = findViewByIdCompat(R.id.btnFavoritesRefresh);
        View btnBack = findViewByIdCompat(R.id.btnFavoritesBack);

        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        adapter = new FavoriteItemAdapter(this, items, this);
        lvFavorites.setAdapter(adapter);
        lvFavorites.setEmptyView(tvEmpty);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadFavorites();
                }
            });
        }
        loadFavorites();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFavorites();
    }

    private void loadFavorites() {
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        HttpUtil.get("/favorites?limit=100", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("items");
                    items.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject one = arr.optJSONObject(i);
                            if (one == null) {
                                continue;
                            }
                            FavoriteItem item = new FavoriteItem();
                            item.id = one.optString("id", "");
                            item.type = one.optString("type", "");
                            item.target_id = one.optString("target_id", "");
                            item.title = one.optString("title", "");
                            item.subtitle = one.optString("subtitle", "");
                            item.media_url = one.optString("media_url", "");
                            item.extra = one.optString("extra", "");
                            item.created_at = one.optLong("created_at", 0);
                            if (item.type == null || item.type.length() == 0) {
                                continue;
                            }
                            items.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    Toast.makeText(FavoritesActivity.this, "加载收藏失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(FavoritesActivity.this, "加载收藏失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onOpen(FavoriteItem item) {
        if (item == null) {
            return;
        }
        String type = item.type == null ? "" : item.type;
        if ("chat_image".equals(type) || "emoji_pack".equals(type)) {
            String imageUrl = MediaUrlResolver.resolve(item.media_url);
            if (imageUrl.length() == 0) {
                Toast.makeText(this, "图片地址无效", Toast.LENGTH_SHORT).show();
                return;
            }
            ImagePreviewActivity.start(this, imageUrl);
            return;
        }
        if ("music_song".equals(type)) {
            openMusicFavorite(item);
            return;
        }
        openByUrl(item.media_url);
    }

    private void openMusicFavorite(final FavoriteItem item) {
        if (item == null) {
            return;
        }
        String songUrl = item.media_url == null ? "" : item.media_url;
        String coverUrl = "";
        String lyricsUrl = "";
        try {
            JSONObject extraObj = new JSONObject(item.extra == null ? "" : item.extra);
            String extraSong = extraObj.optString("song_url", "");
            if (extraSong != null && extraSong.length() > 0) {
                songUrl = extraSong;
            }
            coverUrl = extraObj.optString("cover_url", "");
            lyricsUrl = extraObj.optString("lyrics_url", "");
        } catch (Exception e) {
        }
        final String fallbackSong = songUrl == null ? "" : songUrl;
        final String fallbackCover = coverUrl == null ? "" : coverUrl;
        final String fallbackLyrics = lyricsUrl == null ? "" : lyricsUrl;
        // 旧收藏未保存 lyrics_url；每次播放按 target_id 获取最新歌曲详情，
        // 同时覆盖后续上传/修改的歌词地址。详情失败时仍可用收藏快照播放。
        if (item.target_id != null && item.target_id.length() > 0) {
            HttpUtil.get("/music/plaza/detail?item_id=" + urlEncode(item.target_id), token,
                    new HttpUtil.Callback() {
                        @Override
                        public void onSuccess(String response) {
                            try {
                                JSONObject detail = new JSONObject(response);
                                String song = detail.optString("song_url", fallbackSong);
                                String cover = detail.optString("cover_url", fallbackCover);
                                String lyrics = detail.optString("lyrics_url", fallbackLyrics);
                                startMusicPlayer(item, song, cover, lyrics);
                            } catch (Exception e) {
                                startMusicPlayer(item, fallbackSong, fallbackCover, fallbackLyrics);
                            }
                        }

                        @Override
                        public void onError(int code, String error) {
                            startMusicPlayer(item, fallbackSong, fallbackCover, fallbackLyrics);
                        }
                    });
            return;
        }
        startMusicPlayer(item, fallbackSong, fallbackCover, fallbackLyrics);
    }

    private void startMusicPlayer(FavoriteItem item, String songUrl, String coverUrl, String lyricsUrl) {
        if (songUrl == null || songUrl.length() == 0) {
            Toast.makeText(this, "歌曲地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("item_id", item.target_id == null ? "" : item.target_id);
        intent.putExtra("song_name", item.title == null ? "" : item.title);
        intent.putExtra("song_url", songUrl);
        intent.putExtra("cover_url", coverUrl == null ? "" : coverUrl);
        intent.putExtra("lyrics_url", lyricsUrl == null ? "" : lyricsUrl);
        intent.putExtra("owner_name", item.subtitle == null ? "" : item.subtitle);
        startActivity(intent);
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private void openByUrl(String url) {
        final String resolved = MediaUrlResolver.resolve(url);
        if (resolved.length() == 0) {
            Toast.makeText(this, "链接无效", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(resolved));
            startActivity(intent);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("打开失败")
                    .setMessage(resolved)
                    .setPositiveButton("复制链接", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            ClipboardUtil.copyText(FavoritesActivity.this, resolved);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    @Override
    public void onRemove(final FavoriteItem item, final int position) {
        if (item == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("取消收藏")
                .setMessage("确定从收藏中移除吗？")
                .setPositiveButton("移除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        FavoriteHelper.removeFavorite(FavoritesActivity.this,
                                item.type,
                                item.target_id,
                                item.media_url,
                                new FavoriteHelper.Callback() {
                                    @Override
                                    public void onDone(boolean success) {
                                        if (!success) {
                                            Toast.makeText(FavoritesActivity.this, "移除失败", Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        if (position >= 0 && position < items.size()) {
                                            items.remove(position);
                                            adapter.notifyDataSetChanged();
                                        } else {
                                            loadFavorites();
                                        }
                                        Toast.makeText(FavoritesActivity.this, "已移除", Toast.LENGTH_SHORT).show();
                                    }
                                });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
