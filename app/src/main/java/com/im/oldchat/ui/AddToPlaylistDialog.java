package com.im.oldchat.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.data.MusicPlaylistStore;
import com.im.oldchat.models.MusicItem;
import com.im.oldchat.models.MusicPlaylist;

import java.util.ArrayList;
import java.util.List;

/**
 * 添加到歌单对话框（照搬 emusic AddToPlaylistDialog，适配 MusicItem）。
 */
public class AddToPlaylistDialog extends Dialog {

    private final MusicItem mSong;
    private ListView mLvPlaylists;
    private TextView mBtnNewPlaylist;
    private TextView mBtnCancel;
    private PlaylistPickAdapter mAdapter;

    public AddToPlaylistDialog(Context context, MusicItem song) {
        super(context);
        mSong = song;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_add_to_playlist);

        mLvPlaylists = (ListView) findViewById(R.id.lvDialogPlaylists);
        mBtnNewPlaylist = (TextView) findViewById(R.id.btnDialogNewPlaylist);
        mBtnCancel = (TextView) findViewById(R.id.btnDialogCancel);

        mAdapter = new PlaylistPickAdapter(getContext());
        mLvPlaylists.setAdapter(mAdapter);

        loadPlaylists();

        mBtnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        mBtnNewPlaylist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewPlaylistDialog();
            }
        });

        mLvPlaylists.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                MusicPlaylist pl = mAdapter.getData().get(position);
                if (mSong == null || mSong.id == null) {
                    Toast.makeText(getContext(), "歌曲信息不完整", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pl.contains(mSong)) {
                    Toast.makeText(getContext(), "\u6B4C\u66F2\u5DF2\u5728\u6B64\u6B4C\u5355\u4E2D", Toast.LENGTH_SHORT).show();
                } else {
                    boolean ok = MusicPlaylistStore.getInstance(getContext()).addToPlaylist(pl.getId(), mSong);
                    if (ok) {
                        Toast.makeText(getContext(), "\u5DF2\u6DFB\u52A0\u5230 " + pl.getName(), Toast.LENGTH_SHORT).show();
                        dismiss();
                    } else {
                        Toast.makeText(getContext(), "添加失败", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void loadPlaylists() {
        List<MusicPlaylist> playlists = MusicPlaylistStore.getInstance(getContext()).getPlaylists();
        mAdapter.setData(playlists);
    }

    private void showNewPlaylistDialog() {
        final android.widget.EditText etName = new android.widget.EditText(getContext());
        etName.setHint("\u8BF7\u8F93\u5165\u6B4C\u5355\u540D\u79F0");
        etName.setTextColor(getContext().getResources().getColor(R.color.music_plaza_text_primary));
        etName.setSingleLine(true);
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("\u65B0\u5EFA\u6B4C\u5355")
                .setView(etName)
                .setPositiveButton("\u786E\u5B9A", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String name = etName.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(getContext(), "歌单名称不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        MusicPlaylist pl = new MusicPlaylist(
                                "pl_" + System.currentTimeMillis(), name);
                        MusicPlaylistStore.getInstance(getContext()).addPlaylist(pl);
                        loadPlaylists();
                        Toast.makeText(getContext(), "歌单已创建", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static class PlaylistPickAdapter extends BaseAdapter {
        private final Context mContext;
        private final List<MusicPlaylist> mData = new ArrayList<MusicPlaylist>();

        PlaylistPickAdapter(Context context) {
            mContext = context;
        }

        void setData(List<MusicPlaylist> data) {
            mData.clear();
            if (data != null) {
                mData.addAll(data);
            }
            notifyDataSetChanged();
        }

        List<MusicPlaylist> getData() {
            return mData;
        }

        @Override
        public int getCount() {
            return mData.size();
        }

        @Override
        public Object getItem(int position) {
            return mData.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(mContext);
                tv.setPadding(dp(12), dp(12), dp(12), dp(12));
                tv.setTextSize(14);
                tv.setSingleLine(true);
            }
            MusicPlaylist pl = mData.get(position);
            tv.setText((pl.getName() == null ? "" : pl.getName())
                    + "  (" + pl.getSongCount() + "\u9996)");
            tv.setTextColor(mContext.getResources().getColor(R.color.music_plaza_text_primary));
            return tv;
        }

        private int dp(int value) {
            float density = mContext.getResources().getDisplayMetrics().density;
            return (int) (value * density + 0.5f);
        }
    }
}
