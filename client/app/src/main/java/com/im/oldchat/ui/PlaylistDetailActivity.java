package com.im.oldchat.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.data.MusicPlaylistStore;
import com.im.oldchat.models.MusicItem;
import com.im.oldchat.models.MusicPlaylist;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单详情页（照搬 emusic PlaylistDetailActivity，适配 OldChat 播放服务）。
 */
public class PlaylistDetailActivity extends BaseActivity {

    private TextView tvTitle;
    private TextView tvPlaylistName;
    private TextView tvSongCount;
    private ImageView ivCover;
    private TextView btnPlayAll;
    private TextView tvEmpty;
    private ListView lvSongs;
    private SongAdapter mAdapter;

    private String playlistId;
    private MusicPlaylist mPlaylist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist_detail);

        playlistId = getIntent().getStringExtra("playlist_id");
        if (playlistId == null) {
            finish();
            return;
        }

        tvTitle = (TextView) findViewByIdCompat(R.id.tvPlaylistTitle);
        tvPlaylistName = (TextView) findViewByIdCompat(R.id.tvPlaylistName);
        tvSongCount = (TextView) findViewByIdCompat(R.id.tvPlaylistSongCount);
        ivCover = (ImageView) findViewByIdCompat(R.id.ivPlaylistCover);
        btnPlayAll = (TextView) findViewByIdCompat(R.id.btnPlaylistPlayAll);
        tvEmpty = (TextView) findViewByIdCompat(R.id.tvPlaylistEmpty);
        lvSongs = (ListView) findViewByIdCompat(R.id.lvPlaylistSongs);
        View btnBack = findViewByIdCompat(R.id.btnPlaylistBack);
        TextView btnMore = (TextView) findViewByIdCompat(R.id.btnPlaylistMore);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (btnMore != null) {
            btnMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPlaylistMenu();
                }
            });
        }

        mAdapter = new SongAdapter(this);
        lvSongs.setAdapter(mAdapter);
        lvSongs.setEmptyView(tvEmpty);

        lvSongs.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                MusicItem item = mAdapter.getItem(position);
                if (item != null) {
                    playItem(item);
                }
            }
        });

        if (btnPlayAll != null) {
            btnPlayAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playAll();
                }
            });
        }

        loadData();
    }

    private void loadData() {
        mPlaylist = MusicPlaylistStore.getInstance(this).getPlaylist(playlistId);
        if (mPlaylist == null) {
            finish();
            return;
        }
        tvTitle.setText(mPlaylist.getName());
        tvPlaylistName.setText(mPlaylist.getName());
        tvSongCount.setText(mPlaylist.getSongCount() + "\u9996\u6B4C\u66F2");
        List<MusicItem> songs = mPlaylist.getSongs();
        String cover = mPlaylist.getCover();
        if (cover != null && cover.length() > 0) {
            ImageLoader.load(ivCover, cover);
        } else if (songs != null && !songs.isEmpty() && songs.get(0).coverUrl != null
                && songs.get(0).coverUrl.length() > 0) {
            ImageLoader.load(ivCover, songs.get(0).coverUrl);
        } else {
            ivCover.setImageResource(R.drawable.ic_avatar_placeholder);
        }
        mAdapter.setData(songs);
    }

    private void playAll() {
        List<MusicItem> songs = mPlaylist != null ? mPlaylist.getSongs() : null;
        if (songs == null || songs.isEmpty()) {
            Toast.makeText(this, "\u6B4C\u5355\u4E3A\u7A7A", Toast.LENGTH_SHORT).show();
            return;
        }
        playItem(songs.get(0));
        Toast.makeText(this, "\u5DF2\u5F00\u59CB\u64AD\u653E\uFF0C\u5171 " + songs.size() + " \u9996",
                Toast.LENGTH_SHORT).show();
    }

    private void playItem(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolved = MediaUrlResolver.resolve(item.songUrl);
        if (resolved == null || resolved.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("song_name", item.name == null ? "" : item.name);
        intent.putExtra("song_url", resolved);
        intent.putExtra("cover_url", item.coverUrl == null ? "" : item.coverUrl);
        intent.putExtra("lyrics_url", item.lyricsUrl == null ? "" : item.lyricsUrl);
        intent.putExtra("owner_uid", item.ownerUid == null ? "" : item.ownerUid);
        intent.putExtra("owner_name", item.ownerName == null ? "" : item.ownerName);
        intent.putExtra("owner_title", item.ownerTitle == null ? "" : item.ownerTitle);
        intent.putExtra("owner_avatar", item.ownerAvatar == null ? "" : item.ownerAvatar);
        intent.putExtra("item_id", item.id == null ? "" : item.id);
        intent.putExtra("item_likes", Math.max(0, item.likes));
        intent.putExtra("item_comments", Math.max(0, item.comments));
        intent.putExtra("item_liked", item.liked);
        startActivity(intent);
    }

    private void showPlaylistMenu() {
        if (mPlaylist == null) {
            return;
        }
        final String[] items = new String[]{"\u91CD\u547D\u540D", "\u5220\u9664\u6B4C\u5355"};
        new AlertDialog.Builder(this)
                .setTitle(mPlaylist.getName())
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            showRenameDialog();
                        } else if (which == 1) {
                            showDeleteDialog();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameDialog() {
        final android.widget.EditText etName = new android.widget.EditText(this);
        etName.setText(mPlaylist.getName());
        etName.setSelectAllOnFocus(true);
        etName.setTextColor(getResources().getColor(R.color.music_plaza_text_primary));
        new AlertDialog.Builder(this)
                .setTitle("\u91CD\u547D\u540D\u6B4C\u5355")
                .setView(etName)
                .setPositiveButton("\u786E\u5B9A", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String name = etName.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(PlaylistDetailActivity.this, "歌单名称不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        mPlaylist.setName(name);
                        MusicPlaylistStore.getInstance(PlaylistDetailActivity.this).updatePlaylist(mPlaylist);
                        loadData();
                        Toast.makeText(PlaylistDetailActivity.this, "重命名成功", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteDialog() {
        new AlertDialog.Builder(this)
                .setTitle("\u5220\u9664\u6B4C\u5355")
                .setMessage("\u786E\u5B9A\u8981\u5220\u9664\u6B4C\u5355\u300C" + mPlaylist.getName() + "\u300D\u5417\uFF1F")
                .setPositiveButton("\u5220\u9664", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        MusicPlaylistStore.getInstance(PlaylistDetailActivity.this).deletePlaylist(playlistId);
                        Toast.makeText(PlaylistDetailActivity.this, "歌单已删除", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSong(final MusicItem song) {
        new AlertDialog.Builder(this)
                .setTitle("\u5220\u9664\u6B4C\u66F2")
                .setMessage("\u786E\u5B9A\u8981\u4ECE\u6B4C\u5355\u4E2D\u5220\u9664 " + song.name + " \u5417\uFF1F")
                .setPositiveButton("\u5220\u9664", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        MusicPlaylistStore.getInstance(PlaylistDetailActivity.this)
                                .removeSongFromPlaylist(playlistId, song);
                        loadData();
                        Toast.makeText(PlaylistDetailActivity.this, "已从歌单移除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class SongAdapter extends BaseAdapter {
        private final Context mContext;
        private final List<MusicItem> mData = new ArrayList<MusicItem>();

        SongAdapter(Context context) {
            mContext = context;
        }

        void setData(List<MusicItem> data) {
            mData.clear();
            if (data != null) {
                mData.addAll(data);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mData.size();
        }

        @Override
        public MusicItem getItem(int position) {
            if (position < 0 || position >= mData.size()) {
                return null;
            }
            return mData.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(mContext)
                        .inflate(R.layout.item_playlist_song, parent, false);
                holder = new Holder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            final MusicItem item = getItem(position);
            if (item == null) {
                return convertView;
            }
            holder.tvName.setText(item.name == null || item.name.length() == 0
                    ? "\u672A\u547D\u540D\u6B4C\u66F2" : item.name);
            String owner = item.ownerName != null && item.ownerName.length() > 0
                    ? item.ownerName : item.ownerUid;
            if (owner == null || owner.length() == 0) {
                owner = "\u533F\u540D\u7528\u6237";
            }
            holder.tvOwner.setText(owner);
            if (item.coverUrl != null && item.coverUrl.length() > 0) {
                ImageLoader.load(holder.ivCover, item.coverUrl);
            } else if (item.ownerAvatar != null && item.ownerAvatar.length() > 0) {
                ImageLoader.load(holder.ivCover, item.ownerAvatar);
            } else {
                holder.ivCover.setImageResource(R.drawable.ic_avatar_placeholder);
            }
            holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteSong(item);
                }
            });
            holder.btnDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    downloadSong(item);
                }
            });
            return convertView;
        }
    }

    private void downloadSong(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolved = MediaUrlResolver.resolve(item.songUrl);
        if (resolved == null || resolved.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "开始下载到本地", Toast.LENGTH_SHORT).show();
        DownloadUtil.saveUrlToDownloadsAsync(this, resolved, "oldchat_music_", ".mp3",
                new DownloadUtil.Callback() {
                    @Override
                    public void onResult(boolean success, String message, java.io.File file) {
                        Toast.makeText(PlaylistDetailActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private static class Holder {
        final ImageView ivCover;
        final TextView tvName;
        final TextView tvOwner;
        final TextView btnDelete;
        final TextView btnDownload;

        Holder(View view) {
            ivCover = (ImageView) view.findViewById(R.id.ivPlaylistSongCover);
            tvName = (TextView) view.findViewById(R.id.tvPlaylistSongName);
            tvOwner = (TextView) view.findViewById(R.id.tvPlaylistSongOwner);
            btnDelete = (TextView) view.findViewById(R.id.btnPlaylistSongDelete);
            btnDownload = (TextView) view.findViewById(R.id.btnPlaylistSongDownload);
        }
    }
}
