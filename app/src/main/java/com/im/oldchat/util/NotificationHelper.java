package com.im.oldchat.util;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import com.im.oldchat.R;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.ui.ChatActivity;
import com.im.oldchat.ui.GroupChatActivity;
import com.im.oldchat.ui.NotificationChatActivity;
import com.im.oldchat.util.MediaUrlResolver;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class NotificationHelper {
    private static final String CHANNEL_ID = "oldchat_messages";
    private static final int REQ_BASE_DIRECT = 10000;
    private static final int REQ_BASE_GROUP = 20000;
    private static final int REQ_BASE_SYSTEM = 30000;
    // Value from PendingIntent.FLAG_IMMUTABLE (API 23)
    private static final int FLAG_IMMUTABLE = 1 << 26;
    private static final int NOTIFY_IMAGE_MAX_BYTES = 1024 * 1024;


    public static void notifyDirect(Context context, String friendUid, String body) {
        notifyDirect(context, friendUid, "", body, null);
    }

    public static void notifyDirect(Context context, String friendUid, String body, String imageUrl) {
        notifyDirect(context, friendUid, "", body, imageUrl);
    }

    public static void notifyDirect(Context context, String friendUid, String friendNcuid,
                                    String body, String imageUrl) {
        if (context == null || friendUid == null || friendUid.isEmpty()) {
            return;
        }
        if (!isEnabled(context)) {
            return;
        }
        String stableConversationId = friendNcuid == null || friendNcuid.length() == 0 ? friendUid : friendNcuid;
        if (SettingsStore.isConversationMuted(context, stableConversationId, friendUid, false)) {
            return;
        }
        String title = friendUid;
        String avatar = null;
        List<RecentChatCache.RecentChat> chats = RecentChatCache.getRecentChats(context);
        for (RecentChatCache.RecentChat chat : chats) {
            boolean sameNcuid = friendNcuid != null && friendNcuid.length() > 0
                    && friendNcuid.equalsIgnoreCase(chat.friendNcuid);
            if (sameNcuid || friendUid.equals(chat.friendUID)) {
                if (chat.friendName != null && !chat.friendName.isEmpty()) {
                    title = chat.friendName;
                }
                avatar = chat.avatarUrl;
                break;
            }
        }
        if (title == null || title.isEmpty() || title.equals(friendUid)) {
            String cached = UserNameCache.getName(context, friendUid);
            if (cached != null && !cached.isEmpty()) {
                title = cached;
            }
        }

        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra("friend_uid", friendUid);
        intent.putExtra("friend_ncuid", friendNcuid == null ? "" : friendNcuid);
        intent.putExtra("friend_name", title);
        intent.putExtra("friend_avatar", avatar);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, REQ_BASE_DIRECT + hash(stableConversationId), intent, pendingFlags());

        notify(context, title, body, pi, REQ_BASE_DIRECT + hash(stableConversationId), imageUrl);
    }

    public static void notifyGroup(Context context, String groupId, String fromUid, String body) {
        notifyGroup(context, groupId, fromUid, body, null);
    }

    public static void notifyGroup(Context context, String groupId, String fromUid, String body, String imageUrl) {
        if (context == null || groupId == null || groupId.isEmpty()) {
            return;
        }
        if (!isEnabled(context)) {
            return;
        }
        if (SettingsStore.isConversationMuted(context, groupId, true)) {
            return;
        }
        String title = groupId;
        String avatar = null;
        int role = 0;
        List<GroupRecentChatCache.RecentGroup> groups = GroupRecentChatCache.getRecentGroups(context);
        for (GroupRecentChatCache.RecentGroup g : groups) {
            if (groupId.equals(g.groupId)) {
                if (g.groupName != null && !g.groupName.isEmpty()) {
                    title = g.groupName;
                }
                avatar = g.avatarUrl;
                role = g.role;
                break;
            }
        }
        String text = body;
        if (fromUid != null && !fromUid.isEmpty()) {
            String sender = UserNameCache.getName(context, fromUid);
            if (sender == null || sender.isEmpty()) {
                sender = fromUid;
            }
            text = sender + ": " + (body == null ? "" : body);
        }

        Intent intent = new Intent(context, GroupChatActivity.class);
        intent.putExtra("group_id", groupId);
        intent.putExtra("group_name", title);
        intent.putExtra("group_avatar", avatar);
        intent.putExtra("group_role", role);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, REQ_BASE_GROUP + hash(groupId), intent, pendingFlags());

        notify(context, title, text, pi, REQ_BASE_GROUP + hash(groupId), imageUrl);
    }

    public static void notifySystem(Context context, String notificationId, String title, String body) {
        if (context == null) {
            return;
        }
        if (!isEnabled(context)) {
            return;
        }
        if (SettingsStore.isConversationMuted(context, NotificationChatActivity.SYSTEM_UID, false)) {
            return;
        }
        String safeTitle = title;
        if (safeTitle == null || safeTitle.length() == 0) {
            safeTitle = "系统通知";
        }
        String safeBody = body == null ? "" : body;
        String stableId = notificationId == null ? "" : notificationId;

        Intent intent = new Intent(context, NotificationChatActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                REQ_BASE_SYSTEM + hash(stableId.length() == 0 ? "system" : stableId),
                intent,
                pendingFlags()
        );

        notify(context, safeTitle, safeBody, pi, REQ_BASE_SYSTEM + hash(stableId.length() == 0 ? "system" : stableId), null);
    }

    private static void notify(Context context, String title, String body, PendingIntent pi, int id, String imageUrl) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannel(nm);
        try {
            nm.notify(id, NotificationCompatUtil.buildMessageNotification(
                    context,
                    CHANNEL_ID,
                    android.R.drawable.stat_notify_chat,
                    title == null ? "" : title,
                    body == null ? "" : body,
                    pi
            ));
        } catch (Throwable firstError) {
            try {
                nm.notify(id, NotificationCompatUtil.buildMessageNotification(
                        context,
                        CHANNEL_ID,
                        android.R.drawable.stat_notify_chat,
                        title == null ? "" : title,
                        body == null ? "" : body,
                        pi
                ));
            } catch (Throwable secondError) {
                return;
            }
        }
        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final String url = MediaUrlResolver.resolve(imageUrl);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = downloadBitmap(url, 512);
                if (bmp == null) {
                    return;
                }
                NotificationManager nmInner = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nmInner == null) {
                    return;
                }
                ensureChannel(nmInner);
                try {
                    nmInner.notify(id, NotificationCompatUtil.buildImageMessageNotification(
                            appContext,
                            CHANNEL_ID,
                            android.R.drawable.stat_notify_chat,
                            title == null ? "" : title,
                            body == null ? "" : body,
                            bmp,
                            pi
                    ));
                } catch (Throwable firstError) {
                    try {
                        nmInner.notify(id, NotificationCompatUtil.buildImageMessageNotification(
                                appContext,
                                CHANNEL_ID,
                                android.R.drawable.stat_notify_chat,
                                title == null ? "" : title,
                                body == null ? "" : body,
                                bmp,
                                pi
                        ));
                    } catch (Throwable secondError) {
                    }
                }
            }
        }, "notify-image").start();
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannelCompat.ensureChannel(nm, CHANNEL_ID, "消息通知", NotificationChannelCompat.IMPORTANCE_DEFAULT);
    }

    private static int pendingFlags() {
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private static int hash(String value) {
        return Math.abs(value.hashCode() % 10000);
    }

    private static boolean isEnabled(Context context) {
        return SettingsPrefs.isNotifyEnabled(context);
    }

    private static Bitmap downloadBitmap(String url, int maxSize) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        byte[] data = downloadImageBytes(url, NOTIFY_IMAGE_MAX_BYTES);
        if (data == null || data.length == 0) {
            return null;
        }
        return decodeBitmapSafe(data, maxSize);
    }

    private static byte[] downloadImageBytes(String url, int maxBytes) {
        HttpURLConnection conn = null;
        InputStream is = null;
        ByteArrayOutputStream bos = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(true);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.connect();
            if (conn.getResponseCode() != 200) {
                return null;
            }
            int contentLength = conn.getContentLength();
            if (maxBytes > 0 && contentLength > maxBytes) {
                return null;
            }
            is = conn.getInputStream();
            bos = new ByteArrayOutputStream(contentLength > 0 ? Math.min(contentLength, maxBytes) : 8192);
            byte[] buf = new byte[8192];
            int len;
            int total = 0;
            while ((len = is.read(buf)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return null;
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (Exception e) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception e) {
                }
            }
        }
    }

    private static Bitmap decodeBitmapSafe(byte[] data, int maxSize) {
        if (data == null || data.length == 0) {
            return null;
        }
        int targetMax = maxSize > 0 ? maxSize : 512;

        BitmapFactory.Options bound = new BitmapFactory.Options();
        bound.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bound);
        int sample = calculateInSampleSize(bound, targetMax);

        Bitmap bitmap = null;
        for (int i = 0; i < 5; i++) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inDither = true;
            if (Build.VERSION.SDK_INT < 21) {
                opts.inPurgeable = true;
                opts.inInputShareable = true;
            }
            try {
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            } catch (OutOfMemoryError oom) {
                bitmap = null;
            }
            if (bitmap != null) {
                break;
            }
            sample = sample * 2;
        }

        if (bitmap == null) {
            return null;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int max = Math.max(w, h);
        if (targetMax > 0 && max > targetMax) {
            float ratio = targetMax / (float) max;
            int targetW = Math.max(1, Math.round(w * ratio));
            int targetH = Math.max(1, Math.round(h * ratio));
            try {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            } catch (OutOfMemoryError oom) {
                // keep sampled bitmap
            }
        }
        return bitmap;
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqMax) {
        if (options == null || reqMax <= 0) {
            return 1;
        }
        int width = options.outWidth;
        int height = options.outHeight;
        if (width <= 0 || height <= 0) {
            return 1;
        }
        int sample = 1;
        while ((width / sample) > reqMax || (height / sample) > reqMax) {
            sample = sample * 2;
            if (sample <= 0) {
                return 1;
            }
        }
        return Math.max(1, sample);
    }
}
