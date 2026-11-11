package com.im.oldchat.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.MusicItem;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 音乐二级菜单页：推荐歌曲 / 热门排行 / 全部歌曲。
 * 传入 category = recommend | ranking | latest
 */
public class MusicCategoryActivity extends BaseActivity {

    public static final String EXTRA_CATEGORY = "category";
    public static final String CATEGORY_RECOMMEND = "recommend";
    public static final String CATEGORY_RANKING = "ranking";
    public static final String CATEGORY_LATEST = "latest";

    private String category;
    private String token;
    private ListView lv;
    private TextView tvEmpty;
    private TextView tvCount;
    private TextView tvLoadMore;
    private BaseAdapter adapter;
    private final List<MusicItem> items = new ArrayList<MusicItem>();
    private boolean loading;
    private boolean hasMore = false;
    private int offset = 0;
    private static final int PAGE_SIZE = 30;
    private boolean usingRowLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_category);

        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        if (category == null) {
            category = CATEGORY_LATEST;
        }
        usingRowLayout = CATEGORY_LATEST.equals(category);

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        TextView tvTitle = (TextView) findViewByIdCompat(R.id.tvCategoryTitle);
        String title = "\u97F3\u4E50\u5217\u8868";
        if (CATEGORY_RECOMMEND.equals(category)) {
            title = "\u63A8\u8350\u6B4C\u66F2";
        } else if (CATEGORY_RANKING.equals(category)) {
            title = "热门歌曲";
        } else if (CATEGORY_LATEST.equals(category)) {
            title = "\u5168\u90E8\u6B4C\u66F2";
        }
        tvTitle.setText(title);

        tvCount = (TextView) findViewByIdCompat(R.id.tvCategoryCount);

        View btnBack = findViewByIdCompat(R.id.btnCategoryBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        lv = (ListView) findViewByIdCompat(R.id.lvCategory);
        tvEmpty = (TextView) findViewByIdCompat(R.id.tvCategoryEmpty);

        // 加载更多 footer（必须在 setAdapter 之前添加）
        View footer = getLayoutInflater().inflate(R.layout.view_load_more_footer, lv, false);
        tvLoadMore = (TextView) footer.findViewById(R.id.tvLoadMore);
        lv.addFooterView(footer);

        adapter = usingRowLayout ? new RowAdapter(this)
                : (CATEGORY_RANKING.equals(category) ? new RankingAdapter(this) : new CategoryAdapter(this));
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

        // 滚动到底部自动加载更多
        lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 2) {
                    loadMore();
                }
            }
        });

        loadItems(0, false);
    }

    private MusicItem getItem(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    private void loadMore() {
        if (usingRowLayout && hasMore && !loading) {
            loadItems(offset, true);
        }
    }

    private void loadItems(final int startOffset, final boolean append) {
        if (loading) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        loading = true;
        if (append && tvLoadMore != null) {
            tvLoadMore.setText("\u52A0\u8F7D\u4E2D...");
        }
        String path;
        if (CATEGORY_RANKING.equals(category)) {
            path = "/music/plaza/ranking?limit=50";
        } else if (CATEGORY_RECOMMEND.equals(category)) {
            path = "/music/plaza?limit=50&offset=0&sort=latest";
        } else {
            path = "/music/plaza?limit=" + PAGE_SIZE + "&offset=" + startOffset + "&sort=latest";
        }
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                loading = false;
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("items");
                    if (!append) {
                        items.clear();
                    }
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            MusicItem item = MusicItem.fromJson(arr.optJSONObject(i));
                            if (item != null && item.id != null && item.id.length() > 0
                                    && item.songUrl != null && item.songUrl.length() > 0) {
                                items.add(item);
                            }
                        }
                    }
                    int total = obj.optInt("total", items.size());
                    if (tvCount != null) {
                        tvCount.setText("\u5171 " + total + " \u9996");
                    }
                    if (CATEGORY_RECOMMEND.equals(category)) {
                        Collections.shuffle(items);
                        if (items.size() > 20) {
                            items.subList(20, items.size()).clear();
                        }
                        hasMore = false;
                    } else {
                        hasMore = obj.optBoolean("has_more", false)
                                || (arr != null && arr.length() >= PAGE_SIZE && append);
                        offset = startOffset + (arr == null ? 0 : arr.length());
                    }
                    if (tvLoadMore != null) {
                        if (!usingRowLayout || !hasMore) {
                            tvLoadMore.setText("\u5DF2\u663E\u793A\u5168\u90E8");
                        } else {
                            tvLoadMore.setText("\u4E0A\u62C9\u52A0\u8F7D\u66F4\u591A");
                        }
                    }
                    adapter.notifyDataSetChanged();
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onError(int code, String error) {
                loading = false;
                if (tvLoadMore != null) {
                    tvLoadMore.setText("\u52A0\u8F7D\u5931\u8D25\uFF0C\u4E0A\u62C9\u91CD\u8BD5");
                }
                if (code != 401) {
                    Toast.makeText(MusicCategoryActivity.this,
                            "\u52A0\u8F7D\u5931\u8D25", Toast.LENGTH_SHORT).show();
                }
            }
        });
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
        final String[] actions = new String[]{"\u64AD\u653E", "\u6DFB\u52A0\u5230\u6B4C\u5355", "\u4E0B\u8F7D\u5230\u672C\u5730"};
        new AlertDialog.Builder(this)
                .setTitle(item.name == null || item.name.length() == 0 ? "\u6B4C\u66F2\u64CD\u4F5C" : item.name)
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            playItem(item);
                        } else if (which == 1) {
                            new AddToPlaylistDialog(MusicCategoryActivity.this, item).show();
                        } else if (which == 2) {
                            downloadMusicItem(item);
                        }
                    }
                })
                .setNegativeButton("\u53D6\u6D88", null)
                .show();
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
                        Toast.makeText(MusicCategoryActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private class RankingAdapter extends BaseAdapter {
        private final Context mContext;

        RankingAdapter(Context context) {
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
            RankingHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(mContext)
                        .inflate(R.layout.item_music_ranking, parent, false);
                holder = new RankingHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (RankingHolder) convertView.getTag();
            }
            final MusicItem item = (MusicItem) getItem(position);
            if (item == null) {
                return convertView;
            }
            holder.tvName.setText(MusicItemDisplay.title(item));
            String owner = MusicItemDisplay.owner(item).replace("上传者 · ", "");
            holder.tvOwner.setText(owner + " · " + MusicItemDisplay.duration(item.durationMs));
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

    private static class RankingHolder {
        final ImageView ivCover;
        final TextView tvName;
        final TextView tvOwner;
        final TextView btnPlay;
        final TextView btnMore;

        RankingHolder(View view) {
            ivCover = (ImageView) view.findViewById(R.id.ivRankingCover);
            tvName = (TextView) view.findViewById(R.id.tvRankingName);
            tvOwner = (TextView) view.findViewById(R.id.tvRankingOwner);
            btnPlay = (TextView) view.findViewById(R.id.btnRankingPlay);
            btnMore = (TextView) view.findViewById(R.id.btnRankingMore);
        }
    }

    private class CategoryAdapter extends BaseAdapter {
        private final Context mContext;

        CategoryAdapter(Context context) {
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
            if (CATEGORY_RANKING.equals(category)) {
                holder.tvRank.setVisibility(View.VISIBLE);
                holder.tvRank.setText(String.format(java.util.Locale.getDefault(), "%02d", position + 1));
            } else {
                holder.tvRank.setVisibility(View.GONE);
            }
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

    private class RowAdapter extends BaseAdapter {
        private final Context mContext;

        RowAdapter(Context context) {
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
            RowHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(mContext)
                        .inflate(R.layout.item_music_plaza_row, parent, false);
                holder = new RowHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (RowHolder) convertView.getTag();
            }
            final MusicItem item = (MusicItem) getItem(position);
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
            holder.tvStats.setText("\u8D5E " + Math.max(0, item.likes)
                    + " \u00B7 \u8BC4\u8BBA " + Math.max(0, item.comments)
                    + " \u00B7 \u64AD\u653E " + Math.max(0, item.playCount));
            holder.tvDuration.setText(formatDuration(item.durationMs));
            if (item.coverUrl != null && item.coverUrl.length() > 0) {
                ImageLoader.load(holder.ivCover, item.coverUrl);
            } else if (item.ownerAvatar != null && item.ownerAvatar.length() > 0) {
                ImageLoader.load(holder.ivCover, item.ownerAvatar);
            } else {
                holder.ivCover.setImageResource(R.drawable.ic_avatar_placeholder);
            }
            holder.btnMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showItemActions(item);
                }
            });
            return convertView;
        }
    }

    private static class RowHolder {
        final ImageView ivCover;
        final TextView tvName;
        final TextView tvOwner;
        final TextView tvStats;
        final TextView tvDuration;
        final TextView btnMore;

        RowHolder(View view) {
            ivCover = (ImageView) view.findViewById(R.id.ivMusicRowCover);
            tvName = (TextView) view.findViewById(R.id.tvMusicRowName);
            tvOwner = (TextView) view.findViewById(R.id.tvMusicRowOwner);
            tvStats = (TextView) view.findViewById(R.id.tvMusicRowStats);
            tvDuration = (TextView) view.findViewById(R.id.tvMusicRowDuration);
            btnMore = (TextView) view.findViewById(R.id.btnMusicRowMore);
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
