package com.im.oldchat.lua;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;

/** 隔离 API 26 的通知渠道和构造器。 */
@TargetApi(26)
final class CipVibeNotificationApi26 {
    private CipVibeNotificationApi26() { }

    static void ensureChannel(NotificationManager manager, String channel) {
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    channel, "CIP 后台开发", NotificationManager.IMPORTANCE_LOW));
        }
    }

    static Notification build(Context context, String channel, int icon,
                              PendingIntent open, PendingIntent stop) {
        return new Notification.Builder(context, channel)
                .setSmallIcon(icon)
                .setContentTitle("VibeCoding 正在后台工作")
                .setContentText("工具可持续调用，点击可返回查看")
                .setContentIntent(open)
                .addAction(0, "停止", stop)
                .setOngoing(true)
                .build();
    }
}
