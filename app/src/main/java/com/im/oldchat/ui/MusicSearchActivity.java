package com.im.oldchat.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.MusicItem;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐广场搜索独立页。
 * 顶部搜索框 + 结果列表，支持播放 / 加入歌单 / 下载。
 */
public class MusicSearchActivity extends BaseActivity {

    private EditText etKeyword;
    private TextView btnSubmit;
    private ListView lv;
    private TextView tvEmpty;
    private SearchAdapter adapter;
    private final List<MusicItem> items = new ArrayList<MusicItem>();
    private String token;
    private boolean loading;
    private String lastQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_search);

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        etKeyword = (EditText) findViewByIdCompat(R.id.etSearchKeyword);
        btnSubmit = (TextView) findViewByIdCompat(R.id.btnSearchSubmit);
        lv = (ListView) findViewByIdCompat(R.id.lvSearch);
        tvEmpty = (TextView) findViewByIdCompat(R.id.tvSearchEmpty);

        View btnBack = findViewByIdCompat(R.id.btnSearchBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        adapter = new SearchAdapter(this);
        lv.setAdapter(adapter);
        lv.setEmptyView(tvEmpty);

        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                MusicItem item = getItem(position);
                if (item != null) {
                    playItem(item);
                }
            }
        });

        if (btnSubmit != null) {
            btnSubmit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doSearch();
                }
            });
        }
        if (etKeyword != null) {
            etKeyword.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH
                            || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                        doSearch();
                        return true;
                    }
                    return false;
                }
            });
        }

        // 自动带上音乐广场传入的初始关键词
        String initial = getIntent().getStringExtra("keyword");
        if (initial != null && initial.length() > 0) {
            etKeyword.setText(initial);
            doSearch();
        }
    }

    private MusicItem getItem(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    private void doSearch() {
        final String q = etKeyword == null || etKeyword.getText() == null
                ? "" : etKeyword.getText().toString().trim();
        if (q.length() == 0) {
            Toast.makeText(this, "\u8BF7\u8F93\u5165\u641C\u7D22\u5173\u952E\u8BCD", Toast.LENGTH_SHORT).show();
            return;
        }
        lastQuery = q;
        hideKeyboard();
        loadItems(q);
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void loadItems(final String q) {
        if (loading) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        loading = true;
        tvEmpty.setText("\u641C\u7D22\u4E2D...");
        StringBuilder path = new StringBuilder("/music/plaza?limit=50&offset=0&sort=latest");
        path.append("&q=").append(urlEncode(q));
        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                loading = false;
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("items");
                    items.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            MusicItem item = MusicItem.fromJson(arr.optJSONObject(i));
                            if (item != null && item.id != null && item.id.length() > 0
                                    && item.songUrl != null && item.songUrl.length() > 0) {
                                items.add(item);
                            }
                        }
                    }
                    if (items.isEmpty()) {
                        tvEmpty.setText("\u672A\u627E\u5230\u4E0E\u300C" + q + "\u300D\u76F8\u5173\u7684\u6B4C\u66F2");
                    } else {
                        tvEmpty.setText("\u8F93\u5165\u5173\u952E\u8BCD\u641C\u7D22\u6B4C\u66F2");
                    }
                    adapter.notifyDataSetChanged();
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onError(int code, String error) {
                loading = false;
                tvEmpty.setText("\u641C\u7D22\u5931\u8D25");
                if (code != 401) {
                    Toast.makeText(MusicSearchActivity.this,
                            "\u641C\u7D22\u5931\u8D25: " + code, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private int parseDurationMsFromJson(JSONObject obj) {
        if (obj == null) {
            return 0;
        }
        String[] keys = new String[]{"duration_ms", "durationMs", "duration", "duration_sec", "durationSec", "seconds", "length"};
        for (int i = 0; i < keys.length; i++) {
            long raw = obj.optLong(keys[i], -1);
            if (raw < 0) {
                continue;
            }
            String key = keys[i];
            if (key.contains("sec") || "seconds".equals(key)) {
                return (int) (raw * 1000);
            }
            if (key.contains("length")) {
                return (int) raw;
            }
            return (int) raw;
        }
        return 0;
    }

    private void playItem(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "\u6B4C\u66F2\u94FE\u63A5\u4E0D\u53EF\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolved = MediaUrlResolver.resolve(item.songUrl);
        if (resolved == null || resolved.length() == 0) {
            Toast.makeText(this, "\u6B4C\u66F2\u94FE\u63A5\u4E0D\u53EF\u7528", Toast.LENGTH_SHORT).show();
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

    private void showItemActions(final MusicItem item) {
        if (item == null) {
            return;
        }
        final String[] actions = new String[]{"播放", "添加到歌单", "下载到本地", "举报歌曲"};
        new AlertDialog.Builder(this)
                .setTitle(item.name == null || item.name.length() == 0 ? "歌曲操作" : item.name)
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            playItem(item);
                        } else if (which == 1) {
                            new AddToPlaylistDialog(MusicSearchActivity.this, item).show();
                        } else if (which == 2) {
                            downloadMusicItem(item);
                        } else if (which == 3) {
                            showMusicReportDialog(item);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMusicReportDialog(final MusicItem item) {
        if (item == null) {
            return;
        }
        final String targetUid = item.ownerUid == null ? "" : item.ownerUid.trim();
        if (targetUid.length() == 0) {
            Toast.makeText(this, "无法举报：缺少发布者信息", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("如：侵权、违规内容、恶意音频等");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(180)});
        int pad = (int) (getResources().getDisplayMetrics().density * 10);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("举报歌曲")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("提交", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String reason = input.getText() == null ? "" : input.getText().toString().trim();
                        StringBuilder detail = new StringBuilder();
                        detail.append("[音乐举报]");
                        if (item.id != null && item.id.length() > 0) {
                            detail.append(" 歌曲ID: ").append(item.id);
                        }
                        if (item.name != null && item.name.length() > 0) {
                            detail.append(" 歌名: ").append(item.name);
                        }
                        if (item.ownerName != null && item.ownerName.length() > 0) {
                            detail.append(" 上传者: ").append(item.ownerName);
                        }
                        if (item.songUrl != null && item.songUrl.length() > 0) {
                            detail.append("\n媒体: ").append(item.songUrl);
                        }
                        if (item.coverUrl != null && item.coverUrl.length() > 0) {
                            detail.append("\n封面: ").append(item.coverUrl);
                        }
                        String full = reason;
                        if (full.length() > 0) {
                            full += "\n";
                        }
                        full += detail.toString();
                        submitUserReport(targetUid, full);
                    }
                })
                .show();
    }

    private void submitUserReport(String targetUid, String reason) {
        if (targetUid == null || targetUid.length() == 0) {
            Toast.makeText(this, "举报失败：缺少目标用户", Toast.LENGTH_SHORT).show();
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("target_uid", targetUid);
            json.put("reason", reason == null ? "" : reason);
            HttpUtil.post("/reports/user", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(MusicSearchActivity.this, "举报已提交，已进入公开法庭", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicSearchActivity.this, "举报失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "举报失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadMusicItem(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "\u6B4C\u66F2\u94FE\u63A5\u4E0D\u53EF\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolved = MediaUrlResolver.resolve(item.songUrl);
        if (resolved == null || resolved.length() == 0) {
            Toast.makeText(this, "\u6B4C\u66F2\u94FE\u63A5\u4E0D\u53EF\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "\u5F00\u59CB\u4E0B\u8F7D\u5230\u672C\u5730", Toast.LENGTH_SHORT).show();
        DownloadUtil.saveUrlToDownloadsAsync(this, resolved, "oldchat_music_", ".mp3",
                new DownloadUtil.Callback() {
                    @Override
                    public void onResult(boolean success, String message, java.io.File file) {
                        Toast.makeText(MusicSearchActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private class SearchAdapter extends BaseAdapter {
        private final Context mContext;

        SearchAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            if (position < 0 || position >= items.size()) {
                return null;
            }
            return items.get(position);
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
                        .inflate(R.layout.item_music_result, parent, false);
                holder = new Holder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            final MusicItem item = (MusicItem) getItem(position);
            if (item == null) {
                return convertView;
            }
            holder.tvRank.setVisibility(View.GONE);
            holder.tvName.setText(MusicItemDisplay.title(item));
            holder.tvOwner.setText(MusicItemDisplay.owner(item));
            holder.tvMeta.setText(MusicItemDisplay.metadata(item));
            holder.tvStats.setText(MusicItemDisplay.stats(item));
            MusicItemDisplay.loadCover(holder.ivCover, item);
            holder.btnPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playItem(item);
                }
            });
            holder.btnMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showItemActions(item);
                }
            });
            return convertView;
        }
    }

    private String formatDuration(int durationMs) {
        if (durationMs <= 0) {
            return "--:--";
        }
        int totalSeconds = durationMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private static class Holder {
        final ImageView ivCover;
        final TextView tvRank;
        final TextView tvName;
        final TextView tvOwner;
        final TextView tvMeta;
        final TextView tvStats;
        final TextView btnPlay;
        final TextView btnMore;

        Holder(View view) {
            ivCover = (ImageView) view.findViewById(R.id.ivMusicResultCover);
            tvRank = (TextView) view.findViewById(R.id.tvMusicResultRank);
            tvName = (TextView) view.findViewById(R.id.tvMusicResultName);
            tvOwner = (TextView) view.findViewById(R.id.tvMusicResultOwner);
            tvMeta = (TextView) view.findViewById(R.id.tvMusicResultMeta);
            tvStats = (TextView) view.findViewById(R.id.tvMusicResultStats);
            btnPlay = (TextView) view.findViewById(R.id.btnMusicResultPlay);
            btnMore = (TextView) view.findViewById(R.id.btnMusicResultMore);
        }
    }
}
