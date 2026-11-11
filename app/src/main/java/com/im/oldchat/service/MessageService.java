package com.im.oldchat.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.im.oldchat.MainActivity;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.util.AppState;
import com.im.oldchat.util.NotificationChannelCompat;
import com.im.oldchat.util.NotificationCompatUtil;

public class MessageService extends Service {
    private static final String CHANNEL_ID = "oldchat_service";
    private static final int NOTIFY_ID = 42;

    private volatile boolean foregroundStarted = false;

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, MessageService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (AppState.isForeground()) {
                    context.startService(intent);
                } else {
                    context.startForegroundService(intent);
                }
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            try {
                WSManager.getInstance().start(context.getApplicationContext());
            } catch (Exception ignored) {
            }
        }
    }

    public static void startIfAllowed(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 31 && !AppState.isForeground()) {
            try {
                WSManager.getInstance().start(appContext);
            } catch (Exception ignored) {
            }
            return;
        }
        start(appContext);
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        context.stopService(new Intent(context, MessageService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureForegroundStarted();
        WSManager.getInstance().start(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForegroundStarted();
        WSManager.getInstance().start(getApplicationContext());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        WSManager.getInstance().stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureForegroundStarted() {
        if (foregroundStarted) {
            return;
        }
        Notification notification = buildForegroundNotificationSafely();
        try {
            startForeground(NOTIFY_ID, notification);
            foregroundStarted = true;
        } catch (Throwable e) {
            try {
                Notification fallback = buildMinimalFallbackNotification();
                startForeground(NOTIFY_ID, fallback);
                foregroundStarted = true;
            } catch (Throwable ignored) {
                stopSelf();
            }
        }
    }

    private Notification buildForegroundNotificationSafely() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannelCompat.ensureChannel(nm, CHANNEL_ID, "后台连接", NotificationChannelCompat.IMPORTANCE_LOW);
            }
            Notification normal = NotificationCompatUtil.buildOngoingNotification(
                    this,
                    CHANNEL_ID,
                    android.R.drawable.stat_notify_chat,
                    "旧聊已连接",
                    "正在后台接收消息",
                    true,
                    createContentIntent()
            );
            if (normal != null) {
                return normal;
            }
        } catch (Throwable ignored) {
        }
        return buildMinimalFallbackNotification();
    }

    private Notification buildMinimalFallbackNotification() {
        PendingIntent contentIntent = createContentIntent();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannelCompat.ensureChannel(nm, CHANNEL_ID, "后台连接", NotificationChannelCompat.IMPORTANCE_LOW);
            }
            return Api26.build(this, contentIntent);
        }
        // API 9/10 没有 Notification.Builder；使用字段构造保证前台服务仍可启动。
        Notification notification = new Notification();
        notification.icon = android.R.drawable.stat_notify_chat;
        notification.tickerText = "旧聊已连接";
        notification.when = System.currentTimeMillis();
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        notification.contentIntent = contentIntent;
        return notification;
    }

    @android.annotation.TargetApi(26)
    private static final class Api26 {
        static Notification build(Context context, PendingIntent contentIntent) {
            return new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("旧聊已连接")
                    .setContentText("正在后台接收消息")
                    .setOngoing(true)
                    .setContentIntent(contentIntent)
                    .build();
        }
    }

    private PendingIntent createContentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, pendingFlags());
    }

    private static int pendingFlags() {
        final int FLAG_IMMUTABLE = 1 << 26;
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }
}
