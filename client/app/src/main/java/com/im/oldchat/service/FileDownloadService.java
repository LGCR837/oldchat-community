package com.im.oldchat.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;

import com.im.oldchat.R;
import com.im.oldchat.models.FileDownloadRecord;
import com.im.oldchat.ui.FileCenterActivity;
import com.im.oldchat.util.FileDownloadStorage;
import com.im.oldchat.util.FileDownloadStore;
import com.im.oldchat.util.NotificationChannelCompat;
import com.im.oldchat.util.NotificationCompatUtil;

import java.util.ArrayDeque;

public class FileDownloadService extends Service {
    public static final String ACTION_CHANGED = "com.im.oldchat.action.FILE_DOWNLOAD_CHANGED";
    private static final String ACTION_ENQUEUE = "com.im.oldchat.action.FILE_DOWNLOAD_ENQUEUE";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_MIME = "mime";
    private static final String CHANNEL_ID = "oldchat_download";
    private static final int NOTIFY_ID = 74;

    private final Object lock = new Object();
    private final ArrayDeque<FileDownloadRecord> queue = new ArrayDeque<FileDownloadRecord>();
    private boolean workerRunning;

    public static String enqueue(Context context, String url, String fileName, String mimeType) {
        if (context == null || url == null || url.length() == 0) return "";
        String id = "download_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 1000));
        FileDownloadRecord record = new FileDownloadRecord();
        record.id = id;
        record.url = forceAuthenticatedOrigin(url);
        record.fileName = FileDownloadStorage.sanitizeName(fileName);
        record.mimeType = FileDownloadStorage.guessMimeType(record.fileName, mimeType);
        record.status = FileDownloadRecord.STATUS_QUEUED;
        record.updatedAt = System.currentTimeMillis();
        FileDownloadStore.save(context, record);
        Intent intent = new Intent(context, FileDownloadService.class);
        intent.setAction(ACTION_ENQUEUE);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_URL, record.url);
        intent.putExtra(EXTRA_NAME, record.fileName);
        intent.putExtra(EXTRA_MIME, record.mimeType);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
        } catch (Exception e) {
            record.status = FileDownloadRecord.STATUS_FAILED;
            record.error = "无法启动后台下载";
            record.updatedAt = System.currentTimeMillis();
            FileDownloadStore.save(context, record);
        }
        return id;
    }

    private static String forceAuthenticatedOrigin(String url) {
        if (url == null) return "";
        String lower = url.toLowerCase(java.util.Locale.US);
        // 社区版只有一个源服务器：凡是源服务器的下载地址都要求携带登录态。
        String origin = com.im.oldchat.api.HttpUtil.getBaseOrigin();
        String originLower = origin == null ? "" : origin.toLowerCase(java.util.Locale.US);
        boolean sameOrigin = originLower.length() > 0 && lower.startsWith(originLower + "/");
        if (sameOrigin && lower.indexOf("auth_required=") < 0) {
            return url + (url.indexOf('?') >= 0 ? "&" : "?") + "auth_required=1";
        }
        return url;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ENQUEUE.equals(intent.getAction())) {
            FileDownloadRecord record = new FileDownloadRecord();
            record.id = intent.getStringExtra(EXTRA_ID);
            record.url = intent.getStringExtra(EXTRA_URL);
            record.fileName = intent.getStringExtra(EXTRA_NAME);
            record.mimeType = intent.getStringExtra(EXTRA_MIME);
            record.status = FileDownloadRecord.STATUS_QUEUED;
            record.updatedAt = System.currentTimeMillis();
            synchronized (lock) {
                queue.offer(record);
                if (!workerRunning) {
                    workerRunning = true;
                    startForeground(NOTIFY_ID, buildNotification(record, true));
                    new Thread(new Runnable() { @Override public void run() { runQueue(); } }).start();
                }
            }
        }
        return START_REDELIVER_INTENT;
    }

    private void runQueue() {
        while (true) {
            final FileDownloadRecord item;
            synchronized (lock) {
                item = queue.poll();
                if (item == null) {
                    workerRunning = false;
                    break;
                }
            }
            item.status = FileDownloadRecord.STATUS_DOWNLOADING;
            item.updatedAt = System.currentTimeMillis();
            saveAndBroadcast(item);
            updateNotification(item, true);
            String token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
            FileDownloadStorage.Result result = FileDownloadStorage.download(this, item.url, item.fileName,
                    item.mimeType, token, new FileDownloadStorage.ProgressListener() {
                        @Override public void onProgress(long downloaded, long total) {
                            item.downloadedBytes = downloaded;
                            item.totalBytes = total;
                            item.progress = total > 0 ? (int) Math.min(100L, downloaded * 100L / total) : 0;
                            item.updatedAt = System.currentTimeMillis();
                            saveAndBroadcast(item);
                            updateNotification(item, total <= 0);
                        }
                    });
            item.updatedAt = System.currentTimeMillis();
            if (result.success) {
                item.status = FileDownloadRecord.STATUS_DONE;
                item.progress = 100;
                item.savedPath = result.path;
                item.savedUri = result.uri;
                item.error = "";
            } else {
                item.status = FileDownloadRecord.STATUS_FAILED;
                item.error = result.error;
            }
            saveAndBroadcast(item);
        }
        stopForeground(true);
        stopSelf();
    }

    private void saveAndBroadcast(FileDownloadRecord item) {
        FileDownloadStore.save(this, item);
        Intent changed = new Intent(ACTION_CHANGED);
        changed.putExtra(EXTRA_ID, item.id);
        LocalBroadcastManager.getInstance(this).sendBroadcast(changed);
    }

    private void updateNotification(FileDownloadRecord item, boolean indeterminate) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotification(item, indeterminate));
    }

    private Notification buildNotification(FileDownloadRecord item, boolean indeterminate) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannelCompat.ensureChannel(nm, CHANNEL_ID, "文件下载", NotificationChannelCompat.IMPORTANCE_LOW);
        return NotificationCompatUtil.buildProgressNotification(this, CHANNEL_ID, android.R.drawable.stat_notify_chat,
                "正在下载文件", item.fileName, indeterminate, 100, item.progress, true,
                NotificationCompat.PRIORITY_LOW, contentIntent());
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, FileCenterActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= 0x04000000;
        return PendingIntent.getActivity(this, 74, intent, flags);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
