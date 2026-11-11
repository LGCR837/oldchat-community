package com.im.oldchat.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * MessageDbStore：消息历史 SQLite 行级存储。
 * 每消息一行（JSON 正文 + 索引列），替代旧 SharedPreferences 整串 JSON 快照。
 * 好处：按会话增量写入、索引排序、大历史不阻塞主线程、进程被杀不丢已提交行。
 *
 * 兼容 Android 1.6+（SQLiteOpenHelper 自 API 1 可用）。
 */
public final class MessageDbStore {
    private static final String TABLE_DIRECT_ROWS = "direct_message_rows";
    private static final String TABLE_GROUP_ROWS = "group_message_rows";
    private static final int MAX_ROWS_PER_CHAT = 320;

    private static final Gson GSON = new Gson();

    private MessageDbStore() {
    }

    /** 确保消息行表存在（幂等）。 */
    public static void ensureTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DIRECT_ROWS + " (" +
                "peer_key TEXT NOT NULL, " +
                "message_id TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL DEFAULT 0, " +
                "payload TEXT NOT NULL, " +
                "PRIMARY KEY (peer_key, message_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP_ROWS + " (" +
                "group_id TEXT NOT NULL, " +
                "message_id TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL DEFAULT 0, " +
                "payload TEXT NOT NULL, " +
                "PRIMARY KEY (group_id, message_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_direct_rows_created ON " + TABLE_DIRECT_ROWS + " (peer_key, created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_rows_created ON " + TABLE_GROUP_ROWS + " (group_id, created_at)");
    }

    // ---------- 私聊 ----------

    /** 保存私聊会话：增量 upsert，避免高频消息时全表 DELETE+INSERT 写放大。 */
    public static void saveDirect(Context context, String peerKey, List<Message> messages) {
        if (context == null || peerKey == null || peerKey.length() == 0 || messages == null) {
            return;
        }
        SQLiteDatabase db = OldChatDbHelper.getInstance(context).getWritableDatabase();
        db.beginTransaction();
        try {
            int from = Math.max(0, messages.size() - MAX_ROWS_PER_CHAT);
            for (int i = from; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m == null || m.id == null || m.id.length() == 0) {
                    continue;
                }
                ContentValues v = new ContentValues();
                v.put("peer_key", peerKey);
                v.put("message_id", m.id);
                v.put("created_at", m.created_at);
                v.put("payload", GSON.toJson(m));
                db.insertWithOnConflict(TABLE_DIRECT_ROWS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            // 只裁剪超出上限的最旧消息，不再全表删除（高频消息时写放大显著降低）。
            db.execSQL("DELETE FROM " + TABLE_DIRECT_ROWS
                    + " WHERE peer_key=? AND message_id NOT IN ("
                    + "SELECT message_id FROM " + TABLE_DIRECT_ROWS
                    + " WHERE peer_key=? ORDER BY created_at DESC LIMIT " + MAX_ROWS_PER_CHAT + ")",
                    new Object[]{peerKey, peerKey});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 读取私聊会话（按 created_at 升序返回）。 */
    public static List<Message> getDirect(Context context, String peerKey) {
        List<Message> result = new ArrayList<Message>();
        if (context == null || peerKey == null || peerKey.length() == 0) {
            return result;
        }
        SQLiteDatabase db = OldChatDbHelper.getInstance(context).getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE_DIRECT_ROWS, new String[]{"payload"},
                    "peer_key=?", new String[]{peerKey}, null, null, "created_at ASC");
            while (c != null && c.moveToNext()) {
                try {
                    Message m = GSON.fromJson(c.getString(0), new TypeToken<Message>() {}.getType());
                    if (m != null) {
                        result.add(m);
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    public static void removeDirect(Context context, String peerKey) {
        if (context == null || peerKey == null) return;
        OldChatDbHelper.getInstance(context).getWritableDatabase()
                .delete(TABLE_DIRECT_ROWS, "peer_key=?", new String[]{peerKey});
    }

    public static boolean hasDirectRows(Context context, String peerKey) {
        if (context == null || peerKey == null) return false;
        Cursor c = null;
        try {
            c = OldChatDbHelper.getInstance(context).getReadableDatabase().rawQuery(
                    "SELECT COUNT(1) FROM " + TABLE_DIRECT_ROWS + " WHERE peer_key=?",
                    new String[]{peerKey});
            if (c != null && c.moveToFirst()) {
                return c.getLong(0) > 0;
            }
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    // ---------- 群聊 ----------

    /** 保存群聊会话：增量 upsert，避免高频消息时全表 DELETE+INSERT 写放大。 */
    public static void saveGroup(Context context, String groupId, List<GroupMessage> messages) {
        if (context == null || groupId == null || groupId.length() == 0 || messages == null) {
            return;
        }
        SQLiteDatabase db = OldChatDbHelper.getInstance(context).getWritableDatabase();
        db.beginTransaction();
        try {
            int from = Math.max(0, messages.size() - MAX_ROWS_PER_CHAT);
            for (int i = from; i < messages.size(); i++) {
                GroupMessage m = messages.get(i);
                if (m == null || m.id == null || m.id.length() == 0) {
                    continue;
                }
                ContentValues v = new ContentValues();
                v.put("group_id", groupId);
                v.put("message_id", m.id);
                v.put("created_at", m.created_at);
                v.put("payload", GSON.toJson(m));
                db.insertWithOnConflict(TABLE_GROUP_ROWS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            // 只裁剪超出上限的最旧消息，不再全表删除。
            db.execSQL("DELETE FROM " + TABLE_GROUP_ROWS
                    + " WHERE group_id=? AND message_id NOT IN ("
                    + "SELECT message_id FROM " + TABLE_GROUP_ROWS
                    + " WHERE group_id=? ORDER BY created_at DESC LIMIT " + MAX_ROWS_PER_CHAT + ")",
                    new Object[]{groupId, groupId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 读取群聊会话（按 created_at 升序）。 */
    public static List<GroupMessage> getGroup(Context context, String groupId) {
        List<GroupMessage> result = new ArrayList<GroupMessage>();
        if (context == null || groupId == null || groupId.length() == 0) {
            return result;
        }
        SQLiteDatabase db = OldChatDbHelper.getInstance(context).getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE_GROUP_ROWS, new String[]{"payload"},
                    "group_id=?", new String[]{groupId}, null, null, "created_at ASC");
            while (c != null && c.moveToNext()) {
                try {
                    GroupMessage m = GSON.fromJson(c.getString(0), new TypeToken<GroupMessage>() {}.getType());
                    if (m != null) {
                        result.add(m);
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    public static void removeGroup(Context context, String groupId) {
        if (context == null || groupId == null) return;
        OldChatDbHelper.getInstance(context).getWritableDatabase()
                .delete(TABLE_GROUP_ROWS, "group_id=?", new String[]{groupId});
    }

    public static boolean hasGroupRows(Context context, String groupId) {
        if (context == null || groupId == null) return false;
        Cursor c = null;
        try {
            c = OldChatDbHelper.getInstance(context).getReadableDatabase().rawQuery(
                    "SELECT COUNT(1) FROM " + TABLE_GROUP_ROWS + " WHERE group_id=?",
                    new String[]{groupId});
            if (c != null && c.moveToFirst()) {
                return c.getLong(0) > 0;
            }
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    /** 清空全部消息行。 */
    public static void clearAll(Context context) {
        if (context == null) return;
        SQLiteDatabase db = OldChatDbHelper.getInstance(context).getWritableDatabase();
        db.delete(TABLE_DIRECT_ROWS, null, null);
        db.delete(TABLE_GROUP_ROWS, null, null);
    }

    /** 列出全部私聊会话 key（peer identity）。 */
    public static List<String> listDirectPeerKeys(Context context) {
        List<String> result = new ArrayList<String>();
        if (context == null) return result;
        Cursor c = null;
        try {
            c = OldChatDbHelper.getInstance(context).getReadableDatabase().rawQuery(
                    "SELECT DISTINCT peer_key FROM " + TABLE_DIRECT_ROWS, null);
            while (c != null && c.moveToNext()) {
                result.add(c.getString(0));
            }
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    /** 列出全部群聊会话 ID。 */
    public static List<String> listGroupIds(Context context) {
        List<String> result = new ArrayList<String>();
        if (context == null) return result;
        Cursor c = null;
        try {
            c = OldChatDbHelper.getInstance(context).getReadableDatabase().rawQuery(
                    "SELECT DISTINCT group_id FROM " + TABLE_GROUP_ROWS, null);
            while (c != null && c.moveToNext()) {
                result.add(c.getString(0));
            }
        } finally {
            if (c != null) c.close();
        }
        return result;
    }
}
