package com.im.oldchat.api;

import android.content.Context;
import android.content.Intent;

import com.im.oldchat.data.ChannelCacheStore;

import org.json.JSONArray;
import org.json.JSONObject;

/** 频道水位同步；WS 仅作提示，HTTP 状态和 after_seq 才是权威来源。 */
public final class ChannelSyncManager {
    public static final String ACTION_CHANNEL_POSTS_UPDATED = "com.im.oldchat.CHANNEL_POSTS_UPDATED";
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public interface Callback { void onComplete(boolean success); }

    private static long lastStatesSyncAt;
    private static boolean statesRunning;

    private ChannelSyncManager() { }

    public static synchronized void syncStates(final Context context, final String token,
                                               final boolean force, final Callback callback) {
        if (context == null || token == null || token.length() == 0) {
            done(callback, false); return;
        }
        long now = System.currentTimeMillis();
        if (statesRunning || (!force && now - lastStatesSyncAt < 20000L)) {
            done(callback, false); return;
        }
        statesRunning = true;
        final Context app = context.getApplicationContext();
        HttpUtil.get("/v2/channels/states", token, new HttpUtil.Callback() {
            @Override public void onSuccess(String response) {
                boolean ok = false;
                try {
                    JSONObject root = new JSONObject(response);
                    JSONArray channels = root.optJSONArray("channels");
                    ChannelCacheStore.get(app).replaceStates(app, channels == null ? new JSONArray() : channels);
                    ok = true;
                } catch (Exception ignored) { }
                finishStates(callback, ok);
            }
            @Override public void onError(int code, String error) { finishStates(callback, false); }
        });
    }

    private static synchronized void finishStates(Callback callback, boolean success) {
        statesRunning = false;
        if (success) lastStatesSyncAt = System.currentTimeMillis();
        done(callback, success);
    }

    public static void syncPosts(final Context context, final String token, final String channelId,
                                 final Callback callback) {
        if (context == null || channelId == null || channelId.length() == 0) {
            done(callback, false); return;
        }
        final Context app = context.getApplicationContext();
        long afterEvent = ChannelCacheStore.get(app).syncedEventSeq(app, channelId);
        fetchEventsPage(app, token, channelId, afterEvent, new Callback() {
            @Override public void onComplete(boolean success) {
                if (!success) { done(callback, false); return; }
                long afterPost = ChannelCacheStore.get(app).maxPostSeq(app, channelId);
                fetchPostsPage(app, token, channelId, afterPost, callback);
            }
        });
    }

    private static void fetchEventsPage(final Context app, final String token, final String channelId,
                                        final long after, final Callback callback) {
        String path = "/v2/channels/events/after?channel_id=" + url(channelId)
                + "&after_event_seq=" + after + "&limit=200";
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override public void onSuccess(String response) {
                try {
                    JSONObject root = new JSONObject(response);
                    JSONArray events = root.optJSONArray("events");
                    long next = root.optLong("next_event_seq", after);
                    ChannelCacheStore.get(app).applyEvents(app, channelId,
                            events == null ? new JSONArray() : events, next);
                    if (root.optBoolean("has_more", false) && next > after) {
                        fetchEventsPage(app, token, channelId, next, callback);
                    } else done(callback, true);
                } catch (Exception e) { done(callback, false); }
            }
            @Override public void onError(int code, String error) { done(callback, false); }
        });
    }

    private static void fetchPostsPage(final Context app, final String token, final String channelId,
                                       final long after, final Callback callback) {
        String path = "/v2/channels/posts/after?channel_id=" + url(channelId)
                + "&after_seq=" + after + "&limit=100";
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override public void onSuccess(String response) {
                try {
                    JSONObject root = new JSONObject(response);
                    JSONArray posts = root.optJSONArray("posts");
                    ChannelCacheStore.get(app).upsertPosts(app, posts == null ? new JSONArray() : posts);
                    ChannelCacheStore.get(app).updateReactions(app, root.optJSONObject("reactions"));
                    ChannelCacheStore.get(app).updateMediaURLs(app, root.optJSONObject("media_urls"));
                    long next = root.optLong("next_post_seq", after);
                    if (root.optBoolean("has_more", false) && next > after) {
                        fetchPostsPage(app, token, channelId, next, callback);
                    } else {
                        Intent intent = new Intent(ACTION_CHANNEL_POSTS_UPDATED);
                        intent.setPackage(app.getPackageName());
                        intent.putExtra(EXTRA_CHANNEL_ID, channelId);
                        app.sendBroadcast(intent);
                        done(callback, true);
                    }
                } catch (Exception e) { done(callback, false); }
            }
            @Override public void onError(int code, String error) { done(callback, false); }
        });
    }

    public static void toggleReaction(final Context context, final String token, final String channelId,
                                      String postId, String emoji, final Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("channel_id", channelId); body.put("post_id", postId); body.put("emoji", emoji);
            HttpUtil.post("/v2/channels/reactions/toggle", body, token, new HttpUtil.Callback() {
                @Override public void onSuccess(String response) { syncPosts(context, token, channelId, callback); }
                @Override public void onError(int code, String error) { done(callback, false); }
            });
        } catch (Exception e) { done(callback, false); }
    }

    public static void markRead(final Context context, String token, String channelId, long seq,
                                final Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("channel_id", channelId); body.put("read_post_seq", seq);
            HttpUtil.post("/v2/channels/read", body, token, new HttpUtil.Callback() {
                @Override public void onSuccess(String response) {
                    syncStates(context, token, true, callback);
                }
                @Override public void onError(int code, String error) { done(callback, false); }
            });
        } catch (Exception e) { done(callback, false); }
    }

    private static String url(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static void done(Callback callback, boolean success) {
        if (callback != null) callback.onComplete(success);
    }
}
