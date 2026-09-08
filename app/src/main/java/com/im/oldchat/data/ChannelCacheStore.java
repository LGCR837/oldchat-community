package com.im.oldchat.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.im.oldchat.models.ChannelPost;
import com.im.oldchat.models.ChannelState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** 频道状态与帖子有界 SQLite 缓存；每帖单行 upsert，不整频道重写。 */
public final class ChannelCacheStore extends SQLiteOpenHelper {
    private static final String DB = "oldchat_channels.db";
    private static final int VERSION = 5;
    private static final int MAX_POSTS_PER_CHANNEL = 320;
    private static volatile ChannelCacheStore instance;

    public static ChannelCacheStore get(Context context) {
        if (instance == null) {
            synchronized (ChannelCacheStore.class) {
                if (instance == null) instance = new ChannelCacheStore(context.getApplicationContext());
            }
        }
        return instance;
    }

    private ChannelCacheStore(Context context) { super(context, DB, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE channel_states(account TEXT NOT NULL,channel_id TEXT NOT NULL,name TEXT,handle TEXT,avatar_url TEXT,subscriber_count INTEGER,post_seq INTEGER,event_seq INTEGER,synced_event_seq INTEGER DEFAULT 0,settings_version INTEGER,role TEXT,joined_post_seq INTEGER,last_read_post_seq INTEGER,notification_level TEXT,unread_count INTEGER,updated_at INTEGER,PRIMARY KEY(account,channel_id))");
        db.execSQL("CREATE TABLE channel_posts(account TEXT NOT NULL,id TEXT NOT NULL,channel_id TEXT NOT NULL,post_seq INTEGER,author_uid TEXT,author_name TEXT,author_avatar TEXT,reply_to_post_id TEXT,reply_author TEXT,reply_body TEXT,body TEXT,msg_type TEXT,media_url TEXT,thumb_url TEXT,view_count INTEGER,comment_count INTEGER,reactions_json TEXT DEFAULT '[]',created_at INTEGER,PRIMARY KEY(account,id))");
        db.execSQL("CREATE INDEX idx_channel_posts_seq ON channel_posts(account,channel_id,post_seq)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE channel_states ADD COLUMN synced_event_seq INTEGER DEFAULT 0");
        if (oldVersion < 3) db.execSQL("ALTER TABLE channel_posts ADD COLUMN reactions_json TEXT DEFAULT '[]'");
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN author_name TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN author_avatar TEXT DEFAULT ''");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN reply_to_post_id TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN reply_author TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN reply_body TEXT DEFAULT ''");
        }
    }

    public void replaceStates(Context context, JSONArray array) {
        String account = account(context);
        SQLiteDatabase db = getWritableDatabase();
        Map<String, Long> syncedEvents = new HashMap<String, Long>();
        Cursor old = db.rawQuery("SELECT channel_id,synced_event_seq FROM channel_states WHERE account=?", new String[]{account});
        try { while (old.moveToNext()) syncedEvents.put(old.getString(0), Long.valueOf(old.getLong(1))); }
        finally { old.close(); }
        db.beginTransaction();
        try {
            db.delete("channel_states", "account=?", new String[]{account});
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject one = array.optJSONObject(i);
                if (one == null) continue;
                ContentValues v = new ContentValues();
                v.put("account", account);
                v.put("channel_id", one.optString("channel_id", ""));
                v.put("name", one.optString("name", ""));
                v.put("handle", one.optString("handle", ""));
                v.put("avatar_url", one.optString("avatar_url", ""));
                v.put("subscriber_count", one.optInt("subscriber_count", 0));
                v.put("post_seq", one.optLong("post_seq", 0L));
                v.put("event_seq", one.optLong("event_seq", 0L));
                Long synced = syncedEvents.get(one.optString("channel_id", ""));
                v.put("synced_event_seq", synced == null ? 0L : synced.longValue());
                v.put("settings_version", one.optLong("settings_version", 0L));
                v.put("role", one.optString("role", "subscriber"));
                v.put("joined_post_seq", one.optLong("joined_post_seq", 0L));
                v.put("last_read_post_seq", one.optLong("last_read_post_seq", 0L));
                v.put("notification_level", one.optString("notification_level", "all"));
                v.put("unread_count", one.optInt("unread_count", 0));
                v.put("updated_at", System.currentTimeMillis());
                db.insertWithOnConflict("channel_states", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<ChannelState> listStates(Context context) {
        String account = account(context);
        ArrayList<ChannelState> result = new ArrayList<ChannelState>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.channel_id,s.name,s.handle,s.avatar_url,s.subscriber_count,s.post_seq,s.event_seq,s.settings_version,s.role,s.joined_post_seq,s.last_read_post_seq,s.notification_level,s.unread_count,p.body,p.created_at FROM channel_states s LEFT JOIN channel_posts p ON p.account=s.account AND p.id=(SELECT p2.id FROM channel_posts p2 WHERE p2.account=s.account AND p2.channel_id=s.channel_id ORDER BY p2.post_seq DESC LIMIT 1) WHERE s.account=? ORDER BY p.created_at DESC,s.updated_at DESC",
                new String[]{account});
        try {
            while (c.moveToNext()) {
                ChannelState s = new ChannelState();
                s.channelId = c.getString(0); s.name = c.getString(1); s.handle = c.getString(2);
                s.avatarUrl = c.getString(3); s.subscriberCount = c.getInt(4); s.postSeq = c.getLong(5);
                s.eventSeq = c.getLong(6); s.settingsVersion = c.getLong(7); s.role = c.getString(8);
                s.joinedPostSeq = c.getLong(9); s.lastReadPostSeq = c.getLong(10);
                s.notificationLevel = c.getString(11); s.unreadCount = c.getInt(12);
                s.latestText = c.isNull(13) ? "暂无帖子" : c.getString(13);
                s.latestTimestamp = c.isNull(14) ? 0L : c.getLong(14);
                result.add(s);
            }
        } finally { c.close(); }
        return result;
    }

    public void clearPreviewPosts(Context context, String channelId) {
        getWritableDatabase().delete("channel_posts", "account=? AND channel_id=?",
                new String[]{account(context), channelId});
    }

    public long maxPostSeq(Context context, String channelId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(post_seq),0) FROM channel_posts WHERE account=? AND channel_id=?", new String[]{account(context), channelId});
        try { return c.moveToFirst() ? c.getLong(0) : 0L; } finally { c.close(); }
    }

    public void upsertPosts(Context context, JSONArray posts) {
        String account = account(context);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String touched = null;
            for (int i = 0; posts != null && i < posts.length(); i++) {
                JSONObject one = posts.optJSONObject(i);
                if (one == null) continue;
                touched = one.optString("channel_id", "");
                ContentValues v = new ContentValues();
                v.put("account", account); v.put("id", one.optString("id", "")); v.put("channel_id", touched);
                v.put("post_seq", one.optLong("post_seq", 0L)); v.put("author_uid", one.optString("author_uid", ""));
                v.put("author_name", one.optString("author_name", "")); v.put("author_avatar", one.optString("author_avatar", ""));
                v.put("reply_to_post_id", one.optString("reply_to_post_id", "")); v.put("reply_author", one.optString("reply_author", ""));
                v.put("reply_body", one.optString("reply_body", "")); v.put("body", one.optString("body", "")); v.put("msg_type", one.optString("msg_type", "text"));
                v.put("media_url", one.optString("media_url", "")); v.put("thumb_url", one.optString("thumb_url", ""));
                v.put("view_count", one.optLong("view_count", 0L)); v.put("comment_count", one.optLong("comment_count", 0L));
                v.put("created_at", parseTime(one.optString("created_at", "")));
                db.insertWithOnConflict("channel_posts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (touched != null && touched.length() > 0) {
                db.execSQL("DELETE FROM channel_posts WHERE account=? AND channel_id=? AND id NOT IN (SELECT id FROM channel_posts WHERE account=? AND channel_id=? ORDER BY post_seq DESC LIMIT " + MAX_POSTS_PER_CHANNEL + ")", new Object[]{account,touched,account,touched});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public long syncedEventSeq(Context context, String channelId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(synced_event_seq,0) FROM channel_states WHERE account=? AND channel_id=?", new String[]{account(context), channelId});
        try { return c.moveToFirst() ? c.getLong(0) : 0L; } finally { c.close(); }
    }

    public void applyEvents(Context context, String channelId, JSONArray events, long nextEventSeq) {
        String account = account(context);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; events != null && i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String type = event.optString("event_type", "");
                String postId = event.optString("ref_id", "");
                if ("POST_DELETE".equals(type)) {
                    db.delete("channel_posts", "account=? AND channel_id=? AND id=?", new String[]{account, channelId, postId});
                } else if ("POST_EDIT".equals(type)) {
                    try {
                        JSONObject payload = new JSONObject(event.optString("payload", "{}"));
                        ContentValues values = new ContentValues(); values.put("body", payload.optString("body", ""));
                        db.update("channel_posts", values, "account=? AND channel_id=? AND id=?", new String[]{account, channelId, postId});
                    } catch (Exception ignored) { }
                }
            }
            ContentValues state = new ContentValues(); state.put("synced_event_seq", nextEventSeq);
            db.update("channel_states", state, "account=? AND channel_id=?", new String[]{account, channelId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void updateMediaURLs(Context context, JSONObject mediaURLs) {
        if (mediaURLs == null) return;
        String account=account(context);SQLiteDatabase db=getWritableDatabase();JSONArray names=mediaURLs.names();
        db.beginTransaction();try{for(int i=0;names!=null&&i<names.length();i++){
            String postId=names.optString(i,"");ContentValues values=new ContentValues();values.put("media_url",mediaURLs.optString(postId,""));
            db.update("channel_posts",values,"account=? AND id=?",new String[]{account,postId});
        }db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public void updateReactions(Context context, JSONObject reactions) {
        if (reactions == null) return;
        String account = account(context);
        SQLiteDatabase db = getWritableDatabase();
        JSONArray names = reactions.names();
        db.beginTransaction();
        try {
            for (int i = 0; names != null && i < names.length(); i++) {
                String postId = names.optString(i, "");
                JSONArray counts = reactions.optJSONArray(postId);
                ContentValues values = new ContentValues();
                values.put("reactions_json", counts == null ? "[]" : counts.toString());
                db.update("channel_posts", values, "account=? AND id=?", new String[]{account, postId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<ChannelPost> listPosts(Context context, String channelId) {
        ArrayList<ChannelPost> result = new ArrayList<ChannelPost>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,channel_id,post_seq,author_uid,author_name,author_avatar,reply_to_post_id,reply_author,reply_body,body,msg_type,media_url,thumb_url,view_count,comment_count,reactions_json,created_at FROM channel_posts WHERE account=? AND channel_id=? ORDER BY post_seq ASC", new String[]{account(context), channelId});
        try {
            while (c.moveToNext()) {
                ChannelPost p = new ChannelPost();
                p.id=c.getString(0); p.channelId=c.getString(1); p.postSeq=c.getLong(2); p.authorUid=c.getString(3);
                p.authorName=c.getString(4); p.authorAvatar=c.getString(5); p.replyToPostId=c.getString(6);
                p.replyAuthor=c.getString(7); p.replyBody=c.getString(8); p.body=c.getString(9); p.msgType=c.getString(10);
                p.mediaUrl=c.getString(11); p.thumbUrl=c.getString(12); p.viewCount=c.getLong(13); p.commentCount=c.getLong(14);
                p.reactionsJson=c.getString(15); p.createdAt=c.getLong(16); result.add(p);
            }
        } finally { c.close(); }
        return result;
    }

    private static String account(Context context) {
        return context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("my_uid", "default");
    }

    private static long parseTime(String value) {
        if (value == null || value.length() == 0) return System.currentTimeMillis();
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC")); Date d = f.parse(value); return d == null ? 0L : d.getTime();
        } catch (Exception ignored) { return System.currentTimeMillis(); }
    }
}
