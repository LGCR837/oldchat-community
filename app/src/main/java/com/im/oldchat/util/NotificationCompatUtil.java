package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.support.v4.app.NotificationCompat;

public final class NotificationCompatUtil {

    private NotificationCompatUtil() {
    }

    public static int getSafeSmallIcon(int requestedIcon) {
        if (Build.VERSION.SDK_INT < 21) {
            // Android 5.0 (API 21) 以下系统服务无法解析 VectorDrawable，必须使用系统内置或纯位图图标
            return android.R.drawable.stat_notify_chat;
        }
        if (requestedIcon != 0) {
            return requestedIcon;
        }
        return android.R.drawable.stat_notify_chat;
    }

    public static Notification buildMessageNotification(Context context, String channelId, int smallIcon,
                                                        String title, String text, PendingIntent intent) {
        smallIcon = getSafeSmallIcon(smallIcon);
        if (Build.VERSION.SDK_INT >= 26) {
            return Api26.buildMessageNotification(context, channelId, smallIcon, title, text, intent);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setSmallIcon(smallIcon)
                .setContentTitle(title == null ? "" : title)
                .setContentText(text == null ? "" : text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text == null ? "" : text))
                .setAutoCancel(true)
                .setContentIntent(intent);
        return builder.build();
    }

    public static Notification buildImageMessageNotification(Context context, String channelId, int smallIcon,
                                                             String title, String text, Bitmap image,
                                                             PendingIntent intent) {
        smallIcon = getSafeSmallIcon(smallIcon);
        if (image == null) {
            return buildMessageNotification(context, channelId, smallIcon, title, text, intent);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            return Api26.buildImageMessageNotification(context, channelId, smallIcon, title, text, image, intent);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setSmallIcon(smallIcon)
                .setContentTitle(title == null ? "" : title)
                .setContentText(text == null ? "" : text)
                .setStyle(new NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(text))
                .setAutoCancel(true)
                .setContentIntent(intent);
        return builder.build();
    }

    public static Notification buildOngoingNotification(Context context, String channelId, int smallIcon,
                                                        String title, String text, boolean ongoing,
                                                        PendingIntent intent) {
        smallIcon = getSafeSmallIcon(smallIcon);
        if (Build.VERSION.SDK_INT >= 26) {
            return Api26.buildOngoingNotification(context, channelId, smallIcon, title, text, ongoing, intent);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setSmallIcon(smallIcon)
                .setContentTitle(title == null ? "" : title)
                .setContentText(text == null ? "" : text)
                .setOngoing(ongoing);
        if (intent != null) {
            builder.setContentIntent(intent);
        }
        return builder.build();
    }

    public static Notification buildProgressNotification(Context context, String channelId, int smallIcon,
                                                         String title, String text, boolean indeterminate,
                                                         int progressMax, int progress, boolean onlyAlertOnce,
                                                         int priority, PendingIntent intent) {
        smallIcon = getSafeSmallIcon(smallIcon);
        if (Build.VERSION.SDK_INT >= 26) {
            return Api26.buildProgressNotification(context, channelId, smallIcon, title, text,
                    indeterminate, progressMax, progress, onlyAlertOnce, priority, intent);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context)
                .setSmallIcon(smallIcon)
                .setContentTitle(title == null ? "" : title)
                .setContentText(text == null ? "" : text)
                .setOngoing(true)
                .setOnlyAlertOnce(onlyAlertOnce)
                .setPriority(priority);
        if (intent != null) {
            builder.setContentIntent(intent);
        }
        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(progressMax, progress, false);
        }
        return builder.build();
    }

    @TargetApi(26)
    private static class Api26 {
        static Notification buildMessageNotification(Context context, String channelId, int smallIcon,
                                                     String title, String text, PendingIntent intent) {
            Notification.Builder builder = new Notification.Builder(context, channelId)
                    .setSmallIcon(smallIcon)
                    .setContentTitle(title == null ? "" : title)
                    .setContentText(text == null ? "" : text)
                    .setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text))
                    .setAutoCancel(true)
                    .setContentIntent(intent);
            return builder.build();
        }

        static Notification buildImageMessageNotification(Context context, String channelId, int smallIcon,
                                                          String title, String text, Bitmap image,
                                                          PendingIntent intent) {
            Notification.Builder builder = new Notification.Builder(context, channelId)
                    .setSmallIcon(smallIcon)
                    .setContentTitle(title == null ? "" : title)
                    .setContentText(text == null ? "" : text)
                    .setStyle(new Notification.BigPictureStyle().bigPicture(image).setSummaryText(text))
                    .setAutoCancel(true)
                    .setContentIntent(intent);
            return builder.build();
        }

        static Notification buildOngoingNotification(Context context, String channelId, int smallIcon,
                                                     String title, String text, boolean ongoing,
                                                     PendingIntent intent) {
            Notification.Builder builder = new Notification.Builder(context, channelId)
                    .setSmallIcon(smallIcon)
                    .setContentTitle(title == null ? "" : title)
                    .setContentText(text == null ? "" : text)
                    .setOngoing(ongoing);
            if (intent != null) {
                builder.setContentIntent(intent);
            }
            return builder.build();
        }

        static Notification buildProgressNotification(Context context, String channelId, int smallIcon,
                                                      String title, String text, boolean indeterminate,
                                                      int progressMax, int progress, boolean onlyAlertOnce,
                                                      int priority, PendingIntent intent) {
            Notification.Builder builder = new Notification.Builder(context, channelId)
                    .setSmallIcon(smallIcon)
                    .setContentTitle(title == null ? "" : title)
                    .setContentText(text == null ? "" : text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(onlyAlertOnce)
                    .setPriority(priority);
            if (intent != null) {
                builder.setContentIntent(intent);
            }
            if (indeterminate) {
                builder.setProgress(0, 0, true);
            } else {
                builder.setProgress(progressMax, progress, false);
            }
            return builder.build();
        }
    }
}
