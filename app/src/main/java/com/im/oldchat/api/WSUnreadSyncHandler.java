package com.im.oldchat.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.AppState;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.MessageFieldRepair;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.NotificationHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class WSUnreadSyncHandler {
    public interface DirectMessageDispatcher {
        void dispatch(WSModels.DirectMessage message);
        void dispatchBatch(List<WSModels.DirectMessage> messages);
    }

    public interface GroupMessageDispatcher {
        void dispatch(WSModels.GroupMessage message);
        void dispatchBatch(List<WSModels.GroupMessage> messages);
    }

    public void handleDirectUnread(Context context, String response,
                                   DirectMessageDispatcher dispatcher, final Runnable completion) {
        if (context == null || response == null || dispatcher == null) {
            if (completion != null) completion.run();
            return;
        }
        final Context appContext = context.getApplicationContext();
        final String responseCopy = response;
        final DirectMessageDispatcher dispatcherCopy = dispatcher;
        new Thread(new Runnable() {
            @Override public void run() {
                processDirectUnread(appContext, responseCopy, dispatcherCopy);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { if (completion != null) completion.run(); }
                });
            }
        }, "direct-unread-parse").start();
    }

    private void processDirectUnread(Context context, String response,
                                     DirectMessageDispatcher dispatcher) {
        try {
            JSONObject obj = new JSONObject(response);
            JSONArray arr = obj.getJSONArray("messages");
            List<WSModels.DirectMessage> messages = new ArrayList<>();
            HashMap<String, WSModels.DirectMessage> latestByPeer = new HashMap<>();
            HashMap<String, Integer> countByPeer = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mObj = arr.getJSONObject(i);
                WSModels.DirectMessage msg = new WSModels.DirectMessage();
                msg.id = mObj.optString("id");
                msg.threadId = mObj.optString("thread_id");
                msg.fromUid = mObj.optString("from_uid");
                msg.fromNcuid = mObj.optString("from_ncuid");
                msg.peerUid = mObj.optString("peer_uid");
                msg.peerNcuid = mObj.optString("peer_ncuid");
                msg.body = mObj.optString("body");
                msg.msgType = mObj.optString("msg_type", "text");
                msg.mediaUrl = mObj.optString("media_url");
                msg.thumbUrl = mObj.optString("thumb_url");
                msg.originalUrl = mObj.optString("original_url");
                msg.durationMs = mObj.optInt("duration_ms", 0);
                msg.burnAfterSeconds = BurnAfterSecondsUtil.normalize(mObj.optInt("burn_after_seconds", 0));
                msg.burnStartAt = mObj.optLong("burn_start_at", 0L);
                msg.createdAt = mObj.optLong("created_at");
                msg.sortSeq = mObj.optLong("sort_seq", 0L);
                MessageFieldRepair.repairDirect(msg);
                messages.add(msg);

                String peer = msg.peerUid;
                if (peer != null && peer.length() > 0) {
                    WSModels.DirectMessage latest = latestByPeer.get(peer);
                    if (latest == null || isNewerDirect(msg, latest)) {
                        latestByPeer.put(peer, msg);
                    }
                    int count = countByPeer.containsKey(peer) ? countByPeer.get(peer) : 0;
                    countByPeer.put(peer, count + 1);
                }
            }
            for (String peer : latestByPeer.keySet()) {
                WSModels.DirectMessage latest = latestByPeer.get(peer);
                int count = countByPeer.containsKey(peer) ? countByPeer.get(peer) : 1;
                RecentChatCache.setUnreadCount(context, peer, latest == null ? "" : latest.peerNcuid,
                        null, null, WSMessagePreview.direct(latest), latest.createdAt, count);
                if (!AppState.isForeground() && latest != null) {
                    String imageUrl = null;
                    String msgType = latest.msgType == null ? "" : latest.msgType.toLowerCase();
                    if ("image".equals(msgType)) {
                        imageUrl = (latest.thumbUrl != null && latest.thumbUrl.length() > 0)
                                ? latest.thumbUrl : latest.mediaUrl;
                    }
                    NotificationHelper.notifyDirect(context, peer, latest.peerNcuid,
                            WSMessagePreview.direct(latest), imageUrl);
                }
            }
            RecentChatCache.clearUnreadNotIn(context, latestByPeer.keySet());
            dispatcher.dispatchBatch(messages);
        } catch (Exception e) {
        }
    }

    public void handleGroupUnread(Context context, String response, GroupMessageDispatcher dispatcher,
                                  final Runnable completion) {
        if (response == null || dispatcher == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final String responseCopy = response;
        final GroupMessageDispatcher dispatcherCopy = dispatcher;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = new JSONObject(responseCopy);
                    JSONArray arr = obj.getJSONArray("messages");
                    final List<WSModels.GroupMessage> messages = new ArrayList<>();
                    HashMap<String, WSModels.GroupMessage> latestByGroup = new HashMap<>();
                    HashMap<String, Integer> countByGroup = new HashMap<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject mObj = arr.getJSONObject(i);
                        WSModels.GroupMessage msg = new WSModels.GroupMessage();
                        msg.id = mObj.optString("id");
                        msg.groupId = mObj.optString("group_id");
                        msg.fromUid = mObj.optString("from_uid");
                        msg.fromNcuid = mObj.optString("from_ncuid");
                        msg.body = mObj.optString("body");
                        msg.msgType = mObj.optString("msg_type", "text");
                        msg.mediaUrl = mObj.optString("media_url");
                        msg.thumbUrl = mObj.optString("thumb_url");
                        msg.originalUrl = mObj.optString("original_url");
                        msg.durationMs = mObj.optInt("duration_ms", 0);
                        msg.burnAfterSeconds = BurnAfterSecondsUtil.normalize(mObj.optInt("burn_after_seconds", 0));
                        msg.createdAt = mObj.optLong("created_at");
                        msg.sortSeq = mObj.optLong("sort_seq", 0L);
                        msg.groupSeq = mObj.optLong("group_seq", 0L);
                        MessageFieldRepair.repairGroup(msg);
                        messages.add(msg);
                        // 记录快照包含的消息，防止同一 WS 事件延迟到达后再次 +1。
                        GroupRecentChatCache.markGroupMessageSeen(appContext, msg.id);

                        String groupId = msg.groupId;
                        if (groupId != null && groupId.length() > 0) {
                            WSModels.GroupMessage latest = latestByGroup.get(groupId);
                            if (latest == null || isNewerGroup(msg, latest)) {
                                latestByGroup.put(groupId, msg);
                            }
                            int count = countByGroup.containsKey(groupId) ? countByGroup.get(groupId) : 0;
                            countByGroup.put(groupId, count + 1);
                        }
                    }
                    for (String groupId : latestByGroup.keySet()) {
                        WSModels.GroupMessage latest = latestByGroup.get(groupId);
                        int count = countByGroup.containsKey(groupId) ? countByGroup.get(groupId) : 1;
                        GroupRecentChatCache.setUnreadCount(appContext, groupId, null, null,
                                WSMessagePreview.group(latest), latest.createdAt, count);
                        String token = appContext.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("access_token", "");
                        if (token != null && !token.isEmpty()) {
                            com.im.oldchat.models.GroupMessagePreloadManager.preloadGroup(appContext, groupId, token, count);
                        }
                    }
                    GroupRecentChatCache.clearUnreadNotIn(appContext, latestByGroup.keySet());
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            dispatcherCopy.dispatchBatch(messages);
                            if (completion != null) completion.run();
                        }
                    });
                } catch (Exception e) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { if (completion != null) completion.run(); }
                    });
                }
            }
        }).start();
    }

    private static boolean isNewerDirect(WSModels.DirectMessage a, WSModels.DirectMessage b) {
        if (a.createdAt != b.createdAt) return a.createdAt > b.createdAt;
        if (a.sortSeq > 0L && b.sortSeq > 0L && a.sortSeq != b.sortSeq) return a.sortSeq > b.sortSeq;
        return safe(a.id).compareTo(safe(b.id)) > 0;
    }

    private static boolean isNewerGroup(WSModels.GroupMessage a, WSModels.GroupMessage b) {
        if (a.createdAt != b.createdAt) return a.createdAt > b.createdAt;
        if (a.groupSeq > 0L && b.groupSeq > 0L && a.groupSeq != b.groupSeq) return a.groupSeq > b.groupSeq;
        if (a.sortSeq > 0L && b.sortSeq > 0L && a.sortSeq != b.sortSeq) return a.sortSeq > b.sortSeq;
        return safe(a.id).compareTo(safe(b.id)) > 0;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private String getMyUid(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
        return prefs.getString("my_uid", "");
    }
}
