package com.im.oldchat.service;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;

/** 隔离 API 26 通知构造器和 Action。 */
@TargetApi(26)
final class MusicPlaybackNotificationApi26 {
    private MusicPlaybackNotificationApi26() { }

    static Notification build(Context context, String channelId, int icon, String title, String text,
                              PendingIntent content, PendingIntent toggle, String toggleText,
                              PendingIntent repeat, String repeatText, PendingIntent stop) {
        Notification.Builder builder = new Notification.Builder(context, channelId)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content);
        if (toggle != null) {
            int toggleIcon = "暂停".equals(toggleText)
                    ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
            builder.addAction(new Notification.Action.Builder(toggleIcon, toggleText, toggle).build());
        }
        if (repeat != null) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_rotate, repeatText, repeat).build());
        }
        if (stop != null) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "关闭", stop).build());
        }
        return builder.build();
    }
}
