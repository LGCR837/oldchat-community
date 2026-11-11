package com.im.oldchat.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.models.FileDownloadRecord;
import com.im.oldchat.service.FileDownloadService;
import com.im.oldchat.util.ExternalFileOpener;
import com.im.oldchat.util.FileDownloadStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileCenterActivity extends BaseActivity {
    private final List<FileDownloadRecord> items = new ArrayList<FileDownloadRecord>();
    private FileAdapter adapter;
    private TextView empty;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reload(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_center);
        View back = findViewById(R.id.btnFileCenterBack);
        if (back != null) back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        View btnClear = findViewById(R.id.btnFileCenterClear);
        if (btnClear != null) {
            btnClear.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirmClearAll();
                }
            });
        }
        ListView list = (ListView) findViewById(R.id.lvFileDownloads);
        empty = (TextView) findViewById(R.id.tvFileDownloadsEmpty);
        adapter = new FileAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                FileDownloadRecord item = items.get(position);
                if (FileDownloadRecord.STATUS_DONE.equals(item.status)) {
                    ExternalFileOpener.open(FileCenterActivity.this, item);
                } else if (FileDownloadRecord.STATUS_FAILED.equals(item.status)) {
                    FileDownloadService.enqueue(FileCenterActivity.this, item.url, item.fileName, item.mimeType);
                }
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) {
                    showItemOptions(items.get(position));
                    return true;
                }
                return false;
            }
        });
        reload();
    }

    private void showItemOptions(final FileDownloadRecord item) {
        if (item == null) return;
        List<String> options = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();

        if (FileDownloadRecord.STATUS_DONE.equals(item.status)) {
            options.add("打开文件");
            actions.add(1);
        } else if (FileDownloadRecord.STATUS_FAILED.equals(item.status)) {
            options.add("重新下载");
            actions.add(2);
        }
        options.add("删除文件及记录");
        actions.add(3);

        String[] optArray = options.toArray(new String[options.size()]);
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(item.fileName == null || item.fileName.length() == 0 ? "文件操作" : item.fileName)
                .setItems(optArray, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        int act = actions.get(which);
                        if (act == 1) {
                            ExternalFileOpener.open(FileCenterActivity.this, item);
                        } else if (act == 2) {
                            FileDownloadService.enqueue(FileCenterActivity.this, item.url, item.fileName, item.mimeType);
                        } else if (act == 3) {
                            confirmDeleteItem(item);
                        }
                    }
                })
                .show();
    }

    private void confirmDeleteItem(final FileDownloadRecord item) {
        if (item == null) return;
        String name = item.fileName == null || item.fileName.length() == 0 ? "此文件" : ("「" + item.fileName + "」");
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage("确定要删除" + name + "吗？本地下载的文件及记录将被一并清理。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteSingleItem(item);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSingleItem(FileDownloadRecord item) {
        if (item == null) return;
        deleteLocalFile(item);
        FileDownloadStore.delete(this, item.id);
        reload();
        android.widget.Toast.makeText(this, "已删除文件", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void confirmClearAll() {
        if (items.isEmpty()) {
            android.widget.Toast.makeText(this, "暂无文件记录", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle("清空文件记录")
                .setMessage("确定要清空所有文件下载记录吗？本地已下载的文件将被一并删除。")
                .setPositiveButton("全部清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        for (int i = 0; i < items.size(); i++) {
                            deleteLocalFile(items.get(i));
                        }
                        FileDownloadStore.clear(FileCenterActivity.this);
                        reload();
                        android.widget.Toast.makeText(FileCenterActivity.this, "已清空所有文件记录", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteLocalFile(FileDownloadRecord item) {
        if (item == null) return;
        if (item.savedPath != null && item.savedPath.length() > 0) {
            try {
                java.io.File f = new java.io.File(item.savedPath);
                if (f.exists()) {
                    f.delete();
                }
            } catch (Exception ignored) {
            }
        }
        if (item.savedUri != null && item.savedUri.length() > 0) {
            try {
                android.net.Uri uri = android.net.Uri.parse(item.savedUri);
                getContentResolver().delete(uri, null, null);
            } catch (Exception ignored) {
            }
        }
    }

    @Override protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver,
                new IntentFilter(FileDownloadService.ACTION_CHANGED));
        reload();
    }

    @Override protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        super.onStop();
    }

    private void reload() {
        items.clear();
        items.addAll(FileDownloadStore.list(this));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class FileAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(FileCenterActivity.this);
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_file_center, parent, false);
                holder = new Holder();
                holder.name = (TextView) convertView.findViewById(R.id.tvFileName);
                holder.status = (TextView) convertView.findViewById(R.id.tvFileStatus);
                holder.progress = (ProgressBar) convertView.findViewById(R.id.pbFileDownload);
                holder.btnDelete = convertView.findViewById(R.id.btnFileDelete);
                convertView.setTag(holder);
            } else holder = (Holder) convertView.getTag();
            final FileDownloadRecord item = items.get(position);
            holder.name.setText(item.fileName);
            bindStatus(holder, item);
            if (holder.btnDelete != null) {
                holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        confirmDeleteItem(item);
                    }
                });
            }
            return convertView;
        }
    }

    private void bindStatus(Holder holder, FileDownloadRecord item) {
        if (FileDownloadRecord.STATUS_DOWNLOADING.equals(item.status)) {
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setIndeterminate(item.totalBytes <= 0);
            if (item.totalBytes > 0) holder.progress.setProgress(item.progress);
            holder.status.setText("下载中 " + item.progress + "% · " + formatSize(item.downloadedBytes)
                    + (item.totalBytes > 0 ? "/" + formatSize(item.totalBytes) : ""));
        } else if (FileDownloadRecord.STATUS_QUEUED.equals(item.status)) {
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setIndeterminate(true);
            holder.status.setText("等待下载");
        } else if (FileDownloadRecord.STATUS_DONE.equals(item.status)) {
            holder.progress.setVisibility(View.GONE);
            holder.status.setText("已下载 · " + formatTime(item.updatedAt) + " · 点击打开");
        } else {
            holder.progress.setVisibility(View.GONE);
            holder.status.setText("下载失败 · 点击重试" + (item.error == null || item.error.length() == 0 ? "" : " · " + item.error));
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double value = bytes / 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1fKB", value);
        return String.format(Locale.US, "%.1fMB", value / 1024.0);
    }

    private static String formatTime(long millis) {
        return ChatTimeFormatter.formatMonthDayHourMinute(millis);
    }

    private static class Holder {
        TextView name;
        TextView status;
        ProgressBar progress;
        View btnDelete;
    }
}
