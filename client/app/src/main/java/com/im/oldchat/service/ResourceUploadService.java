package com.im.oldchat.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import com.im.oldchat.R;
import com.im.oldchat.MainActivity;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.NotificationChannelCompat;
import com.im.oldchat.util.NotificationCompatUtil;
import com.im.oldchat.util.NetworkStateManager;

public class ResourceUploadService extends Service {
    public static final String ACTION_START = "com.im.oldchat.action.RESOURCE_UPLOAD_START";
    public static final String ACTION_PROGRESS = "com.im.oldchat.action.RESOURCE_UPLOAD_PROGRESS";
    public static final String ACTION_DONE = "com.im.oldchat.action.RESOURCE_UPLOAD_DONE";
    public static final String ACTION_ERROR = "com.im.oldchat.action.RESOURCE_UPLOAD_ERROR";

    public static final String EXTRA_SECTION_ID = "section_id";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_CONTENT_TYPE = "content_type";
    public static final String EXTRA_TOTAL_BYTES = "total_bytes";
    public static final String EXTRA_UPLOADED_BYTES = "uploaded_bytes";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_INDETERMINATE = "indeterminate";
    public static final String EXTRA_SPEED_BPS = "speed_bps";
    public static final String EXTRA_RESPONSE = "response";
    public static final String EXTRA_ERROR_CODE = "error_code";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";

    private static final String AUTH_PREFS = "auth";
    private static final String CHANNEL_ID = "oldchat_upload";
    private static final int NOTIFY_ID = 73;
    private static final Object STATE_LOCK = new Object();

    private static UploadState current;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastProgressAt = 0;
    private int lastProgress = -1;
    private long lastSpeedAt = 0;
    private long lastSpeedBytes = 0;
    private long lastSpeedBps = 0;

    public static class UploadState {
        public String sectionId;
        public String fileName;
        public long totalBytes;
        public long uploadedBytes;
        public int progress;
        public boolean indeterminate;
        public boolean running;
        public long speedBps;
    }

    public static void startUpload(Context context, String sectionId, Uri uri,
                                   String fileName, String contentType, long totalBytes) {
        if (context == null || uri == null) {
            return;
        }
        Intent intent = new Intent(context, ResourceUploadService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SECTION_ID, sectionId);
        intent.putExtra(EXTRA_URI, uri.toString());
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_CONTENT_TYPE, contentType);
        intent.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
        }
    }

    public static boolean isUploading() {
        synchronized (STATE_LOCK) {
            return current != null && current.running;
        }
    }

    public static boolean isUploadingSection(String sectionId) {
        synchronized (STATE_LOCK) {
            return current != null && current.running
                    && sectionId != null && sectionId.equals(current.sectionId);
        }
    }

    public static UploadState getCurrentUpload() {
        synchronized (STATE_LOCK) {
            if (current == null) {
                return null;
            }
            UploadState copy = new UploadState();
            copy.sectionId = current.sectionId;
            copy.fileName = current.fileName;
            copy.totalBytes = current.totalBytes;
            copy.uploadedBytes = current.uploadedBytes;
            copy.progress = current.progress;
            copy.indeterminate = current.indeterminate;
            copy.running = current.running;
            copy.speedBps = current.speedBps;
            return copy;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            startUploadInternal(intent);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startUploadInternal(Intent intent) {
        if (isUploading()) {
            sendErrorBroadcast(intent.getStringExtra(EXTRA_SECTION_ID), -2, "upload_busy");
            return;
        }
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            sendErrorBroadcast(intent.getStringExtra(EXTRA_SECTION_ID), -1, "network_unavailable");
            stopSelf();
            return;
        }
        final String sectionId = intent.getStringExtra(EXTRA_SECTION_ID);
        final String uriStr = intent.getStringExtra(EXTRA_URI);
        final String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        final String contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE);
        final long totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L);
        if (sectionId == null || sectionId.length() == 0 || uriStr == null || uriStr.length() == 0) {
            stopSelf();
            return;
        }
        final String token = resolveToken();
        if (token == null || token.length() == 0) {
            sendErrorBroadcast(sectionId, 401, "no_token");
            stopSelf();
            return;
        }
        final Uri uri = Uri.parse(uriStr);
        updateState(sectionId, fileName, totalBytes, 0, totalBytes <= 0, true, 0);
        startForegroundCompat(fileName, 0, totalBytes <= 0);
        final String path = "/v2/resources/upload";
        HttpUtil.postMultipartStream(path, new HttpUtil.StreamProvider() {
            @Override
            public java.io.InputStream open() throws Exception {
                java.io.InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) {
                    throw new Exception("open_failed");
                }
                return input;
            }

            @Override
            public long length() {
                return totalBytes;
            }
        }, fileName, contentType, token, "section_id", sectionId, new HttpUtil.ProgressCallback() {
            @Override
            public void onProgress(long written, long total) {
                reportProgress(sectionId, fileName, written, total);
            }
        }, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                updateState(sectionId, fileName, totalBytes, totalBytes, false, false, 0);
                stopForegroundCompat();
                sendDoneBroadcast(sectionId, response, fileName);
                stopSelf();
            }

            @Override
            public void onError(int code, String error) {
                updateState(sectionId, fileName, totalBytes, 0, totalBytes <= 0, false, 0);
                stopForegroundCompat();
                sendErrorBroadcast(sectionId, code, error);
                stopSelf();
            }
        });
    }

    private String resolveToken() {
        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        return prefs.getString("access_token", "");
    }

    private void reportProgress(final String sectionId, final String fileName,
                                final long written, final long total) {
        long now = System.currentTimeMillis();
        int percent = total > 0 ? (int) ((written * 100) / total) : 0;
        if (percent == lastProgress && now - lastProgressAt < 300) {
            return;
        }
        if (lastSpeedAt == 0) {
            lastSpeedAt = now;
            lastSpeedBytes = written;
        } else if (now - lastSpeedAt >= 500) {
            long deltaBytes = written - lastSpeedBytes;
            long deltaMs = now - lastSpeedAt;
            if (deltaMs > 0 && deltaBytes >= 0) {
                lastSpeedBps = (deltaBytes * 1000L) / deltaMs;
            }
            lastSpeedAt = now;
            lastSpeedBytes = written;
        }
        lastProgress = percent;
        lastProgressAt = now;
        final int progressFinal = percent;
        final long speedFinal = lastSpeedBps;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean indeterminate = total <= 0;
                updateState(sectionId, fileName, total, written, indeterminate, true, speedFinal);
                updateNotification(fileName, progressFinal, indeterminate);
                sendProgressBroadcast(sectionId, fileName, written, total, progressFinal, indeterminate, speedFinal);
            }
        });
    }

    private void updateState(String sectionId, String fileName, long total, long written,
                             boolean indeterminate, boolean running, long speedBps) {
        synchronized (STATE_LOCK) {
            if (current == null) {
                current = new UploadState();
            }
            current.sectionId = sectionId;
            current.fileName = fileName;
            current.totalBytes = total;
            current.uploadedBytes = written;
            current.progress = total > 0 ? (int) ((written * 100) / total) : 0;
            current.indeterminate = indeterminate;
            current.running = running;
            current.speedBps = speedBps;
        }
    }

    private void sendProgressBroadcast(String sectionId, String fileName, long written, long total,
                                       int progress, boolean indeterminate, long speedBps) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.putExtra(EXTRA_SECTION_ID, sectionId);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_UPLOADED_BYTES, written);
        intent.putExtra(EXTRA_TOTAL_BYTES, total);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_INDETERMINATE, indeterminate);
        intent.putExtra(EXTRA_SPEED_BPS, speedBps);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendDoneBroadcast(String sectionId, String response, String fileName) {
        Intent intent = new Intent(ACTION_DONE);
        intent.putExtra(EXTRA_SECTION_ID, sectionId);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_RESPONSE, response);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendErrorBroadcast(String sectionId, int code, String error) {
        Intent intent = new Intent(ACTION_ERROR);
        intent.putExtra(EXTRA_SECTION_ID, sectionId);
        intent.putExtra(EXTRA_ERROR_CODE, code);
        intent.putExtra(EXTRA_ERROR_MESSAGE, error);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void startForegroundCompat(String fileName, int progress, boolean indeterminate) {
        Notification notification = buildNotification(fileName, progress, indeterminate);
        try {
            startForeground(NOTIFY_ID, notification);
        } catch (Throwable firstError) {
            try {
                Notification fallback = buildNotificationWithIcon(fileName, progress, indeterminate, R.drawable.ic_avatar_placeholder);
                startForeground(NOTIFY_ID, fallback);
            } catch (Throwable secondError) {
                stopSelf();
            }
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(true);
        } catch (Exception e) {
        }
    }

    private void updateNotification(String fileName, int progress, boolean indeterminate) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification notification = buildNotification(fileName, progress, indeterminate);
        try {
            nm.notify(NOTIFY_ID, notification);
        } catch (Throwable firstError) {
            try {
                Notification fallback = buildNotificationWithIcon(fileName, progress, indeterminate, R.drawable.ic_avatar_placeholder);
                nm.notify(NOTIFY_ID, fallback);
            } catch (Throwable secondError) {
            }
        }
    }

    private Notification buildNotification(String fileName, int progress, boolean indeterminate) {
        return buildNotificationWithIcon(fileName, progress, indeterminate, android.R.drawable.stat_notify_chat);
    }

    private Notification buildNotificationWithIcon(String fileName, int progress, boolean indeterminate, int iconRes) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannelCompat.ensureChannel(nm, CHANNEL_ID, "资源上传", NotificationChannelCompat.IMPORTANCE_LOW);
        }
        String safeName = fileName == null || fileName.length() == 0 ? "资源" : fileName;
        int clamped = Math.max(0, Math.min(progress, 100));
        return NotificationCompatUtil.buildProgressNotification(
                this,
                CHANNEL_ID,
                iconRes,
                "正在上传资源",
                safeName,
                indeterminate,
                100,
                clamped,
                true,
                NotificationCompat.PRIORITY_LOW,
                createContentIntent()
        );
    }

    private PendingIntent createContentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, pendingFlags());
    }

    private static int pendingFlags() {
        // Value from PendingIntent.FLAG_IMMUTABLE (API 23)
        final int FLAG_IMMUTABLE = 1 << 26;
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }
}
