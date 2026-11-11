package com.im.oldchat.lua;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;

import com.im.oldchat.R;
import com.im.oldchat.ui.CipVibeCodingActivity;

/** 保持 VibeCoding 网络与工具循环在离开页面后继续执行。 */
public class CipVibeBackgroundService extends Service {
    private static final String CHANNEL = "cip_vibe_worker";
    private static final String ACTION_STOP = "com.im.oldchat.action.STOP_VIBE";
    private static final int NOTIFICATION_ID = 7361;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            CipVibeNotificationApi26.ensureChannel(manager, CHANNEL);
        }
        Intent open = new Intent(this, CipVibeCodingActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
        Intent stop = new Intent(this, CipVibeBackgroundService.class); stop.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop, flags);
        Notification notification;
        if (Build.VERSION.SDK_INT >= 26) {
            notification = CipVibeNotificationApi26.build(
                    this, CHANNEL, R.mipmap.ic_launcher, pending, stopPending);
        } else {
            notification = new NotificationCompat.Builder(this)
                    .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("VibeCoding 正在后台工作")
                    .setContentText("工具可持续调用，点击可返回查看").setContentIntent(pending)
                    .addAction(0, "停止", stopPending).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build();
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            CipVibeSessionManager.get().stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!CipVibeSessionManager.get().isRunning()) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
