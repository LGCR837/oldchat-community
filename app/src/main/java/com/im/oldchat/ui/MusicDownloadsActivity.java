package com.im.oldchat.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MusicDownloadsActivity extends BaseActivity {
    private ListView lvDownloads;
    private TextView tvSummary;
    private TextView tvEmpty;
    private DownloadAdapter adapter;
    private final ArrayList<DownloadItem> items = new ArrayList<DownloadItem>();
    private AsyncTask<Void, Void, ArrayList<DownloadItem>> scanTask;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_downloads);

        lvDownloads = findViewByIdCompat(R.id.lvMusicDownloads);
        tvSummary = findViewByIdCompat(R.id.tvMusicDownloadsSummary);
        tvEmpty = findViewByIdCompat(R.id.tvMusicDownloadsEmpty);

        adapter = new DownloadAdapter();
        lvDownloads.setAdapter(adapter);
        lvDownloads.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) {
                    playDownload(items.get(position));
                }
            }
        });

        View btnBack = findViewByIdCompat(R.id.btnMusicDownloadsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnRefresh = findViewByIdCompat(R.id.btnMusicDownloadsRefresh);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadDownloads();
                }
            });
        }
        loadDownloads();
    }
    @Override
    protected void onDestroy() {
        cancelScanTask();
        super.onDestroy();
    }
    private void loadDownloads() {
        cancelScanTask();
        if (tvSummary != null) {
            tvSummary.setText("正在扫描本地音乐...");
        }
        scanTask = new AsyncTask<Void, Void, ArrayList<DownloadItem>>() {
            @Override
            protected ArrayList<DownloadItem> doInBackground(Void... voids) {
                return scanLocalDownloads();
            }

            @Override
            protected void onPostExecute(ArrayList<DownloadItem> result) {
                if (isFinishing()) {
                    return;
                }
                items.clear();
                if (result != null) {
                    items.addAll(result);
                }
                adapter.notifyDataSetChanged();
                if (tvSummary != null) {
                    tvSummary.setText("本地歌曲 " + items.size() + " 首 · 点击即可播放");
                }
                if (tvEmpty != null) {
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                }
            }
        };
        scanTask.execute((Void[]) null);
    }
    private void cancelScanTask() {
        if (scanTask == null) {
            return;
        }
        try {
            scanTask.cancel(true);
        } catch (Exception ignore) {
        }
        scanTask = null;
    }
    private ArrayList<DownloadItem> scanLocalDownloads() {
        ArrayList<DownloadItem> out = new ArrayList<DownloadItem>();
        HashSet<String> pathSeen = new HashSet<String>();
        ArrayList<File> dirs = new ArrayList<File>();
        addIfValidDir(dirs, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        addIfValidDir(dirs, getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS));
        addIfValidDir(dirs, getExternalFilesDir(null));

        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            File[] list;
            try {
                list = dir.listFiles();
            } catch (Exception e) {
                list = null;
            }
            if (list == null) {
                continue;
            }
            for (int j = 0; j < list.length; j++) {
                File one = list[j];
                if (one == null || !one.isFile()) {
                    continue;
                }
                String name = one.getName();
                if (!isMusicFileName(name)) {
                    continue;
                }
                String path = one.getAbsolutePath();
                if (pathSeen.contains(path)) {
                    continue;
                }
                pathSeen.add(path);
                DownloadItem item = new DownloadItem();
                item.file = one;
                item.displayName = buildDisplayName(name);
                item.sizeBytes = Math.max(0L, one.length());
                item.modifiedAt = Math.max(0L, one.lastModified());
                out.add(item);
            }
        }

        Collections.sort(out, new Comparator<DownloadItem>() {
            @Override
            public int compare(DownloadItem left, DownloadItem right) {
                long l = left == null ? 0L : left.modifiedAt;
                long r = right == null ? 0L : right.modifiedAt;
                if (l == r) {
                    return 0;
                }
                return l > r ? -1 : 1;
            }
        });
        return out;
    }
    private void addIfValidDir(List<File> dirs, File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        for (int i = 0; i < dirs.size(); i++) {
            File one = dirs.get(i);
            if (one != null && one.getAbsolutePath().equals(dir.getAbsolutePath())) {
                return;
            }
        }
        dirs.add(dir);
    }
    private boolean isMusicFileName(String name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        boolean isAudio = lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".m4a");
        if (!isAudio) {
            return false;
        }
        return lower.startsWith("oldchat_music_") || lower.startsWith("oldchat_") || lower.startsWith("music_");
    }
    private String buildDisplayName(String fileName) {
        if (fileName == null || fileName.length() == 0) {
            return "本地歌曲";
        }
        int dot = fileName.lastIndexOf('.');
        String plain = dot > 0 ? fileName.substring(0, dot) : fileName;
        plain = plain.replace("oldchat_music_", "").replace("oldchat_", "").replace('_', ' ').trim();
        return plain.length() == 0 ? "本地歌曲" : plain;
    }
    private void playDownload(DownloadItem item) {
        if (item == null || item.file == null || !item.file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("song_name", item.displayName == null ? "本地歌曲" : item.displayName);
        intent.putExtra("song_url", Uri.fromFile(item.file).toString());
        intent.putExtra("owner_name", "本地下载");
        startActivity(intent);
    }
    private class DownloadAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DownloadHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MusicDownloadsActivity.this)
                        .inflate(R.layout.item_music_download, parent, false);
                holder = new DownloadHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (DownloadHolder) convertView.getTag();
            }

            DownloadItem item = (DownloadItem) getItem(position);
            if (item != null) {
                holder.tvName.setText(item.displayName);
                holder.tvMeta.setText(formatSize(item.sizeBytes) + " · " + formatTime(item.modifiedAt));
            }
            return convertView;
        }
    }
    private static class DownloadHolder {
        final TextView tvName;
        final TextView tvMeta;

        DownloadHolder(View root) {
            tvName = root.findViewById(R.id.tvMusicDownloadName);
            tvMeta = root.findViewById(R.id.tvMusicDownloadMeta);
        }
    }
    private static class DownloadItem {
        File file;
        String displayName;
        long sizeBytes;
        long modifiedAt;
    }
    private String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1fKB", kb);
        }
        return String.format(Locale.US, "%.1fMB", kb / 1024.0);
    }
    private String formatTime(long millis) {
        if (millis <= 0L) {
            return "未知时间";
        }
        return ChatTimeFormatter.formatMonthDayHourMinute(millis);
    }
}
