package com.im.oldchat.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 本地 SQLite 存储：消息历史 + pts 游标。
 * 兼容 Android 1.6+（API 4），不使用任何高版本 API。
 * 采用单库多表：direct_messages / group_messages / pts_state。
 */
public class OldChatDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "oldchat.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE_DIRECT = "direct_messages";
    public static final String TABLE_GROUP = "group_messages";
    public static final String TABLE_PTS = "pts_state";

    private static volatile OldChatDbHelper instance;

    public static OldChatDbHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (OldChatDbHelper.class) {
                if (instance == null) {
                    instance = new OldChatDbHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private OldChatDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DIRECT + " (" +
                "id TEXT PRIMARY KEY, " +
                "thread_id TEXT NOT NULL, " +
                "peer_uid TEXT NOT NULL, " +
                "peer_ncuid TEXT NOT NULL DEFAULT '', " +
                "from_uid TEXT NOT NULL, " +
                "from_ncuid TEXT NOT NULL DEFAULT '', " +
                "body TEXT NOT NULL, " +
                "msg_type TEXT NOT NULL DEFAULT 'text', " +
                "media_url TEXT NOT NULL DEFAULT '', " +
                "thumb_url TEXT NOT NULL DEFAULT '', " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "burn_after_seconds INTEGER NOT NULL DEFAULT 0, " +
                "burn_start_at INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL, " +
                "sort_seq INTEGER NOT NULL DEFAULT 0, " +
                "status INTEGER NOT NULL DEFAULT 0, " +
                "recall_edit_type TEXT NOT NULL DEFAULT '', " +
                "recall_edit_text TEXT NOT NULL DEFAULT '', " +
                "sender_ncuid TEXT NOT NULL DEFAULT '', " +
                "local_pending INTEGER NOT NULL DEFAULT 0, " +
                "local_failed INTEGER NOT NULL DEFAULT 0, " +
                "local_request_id TEXT NOT NULL DEFAULT '', " +
                "local_preview_uri TEXT NOT NULL DEFAULT '', " +
                "local_progress INTEGER NOT NULL DEFAULT -1)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP + " (" +
                "id TEXT PRIMARY KEY, " +
                "group_id TEXT NOT NULL, " +
                "from_uid TEXT NOT NULL, " +
                "sender_ncuid TEXT NOT NULL DEFAULT '', " +
                "body TEXT NOT NULL, " +
                "msg_type TEXT NOT NULL DEFAULT 'text', " +
                "media_url TEXT NOT NULL DEFAULT '', " +
                "thumb_url TEXT NOT NULL DEFAULT '', " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "burn_after_seconds INTEGER NOT NULL DEFAULT 0, " +
                "burn_start_at INTEGER NOT NULL DEFAULT 0, " +
                "burned_at INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL, " +
                "sort_seq INTEGER NOT NULL DEFAULT 0, " +
                "group_seq INTEGER NOT NULL DEFAULT 0, " +
                "read_count INTEGER NOT NULL DEFAULT 0, " +
                "recall_edit_type TEXT NOT NULL DEFAULT '', " +
                "recall_edit_text TEXT NOT NULL DEFAULT '', " +
                "local_pending INTEGER NOT NULL DEFAULT 0, " +
                "local_failed INTEGER NOT NULL DEFAULT 0, " +
                "local_request_id TEXT NOT NULL DEFAULT '', " +
                "local_preview_uri TEXT NOT NULL DEFAULT '', " +
                "local_progress INTEGER NOT NULL DEFAULT -1)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PTS + " (" +
                "key TEXT PRIMARY KEY, " +
                "value INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_direct_thread ON " + TABLE_DIRECT + " (thread_id, created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_gid ON " + TABLE_GROUP + " (group_id, created_at)");

        // 消息历史行级存储表。
        MessageDbStore.ensureTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 版本迁移骨架：每个版本按需执行幂等 DDL（CREATE TABLE IF NOT EXISTS /
        // ALTER TABLE ... ADD COLUMN 前先判断列是否存在），防止重复执行。
        // 当前 DB_VERSION = 1：所有建表都在 onCreate 且幂等，无需增量迁移。
        // 未来升版本示例：
        //   if (oldVersion < 2) { db.execSQL("ALTER TABLE ... ADD COLUMN ..."); }
        if (oldVersion < 1) {
            onCreate(db);
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // WAL 模式（API 11+ / SQLite 3.7+）：读写互不阻塞，适合 pts 高频写 + 消息读并发；
        // Android 2.3（API 10）及以下 SQLite 无 WAL，退回 TRUNCATE（fallback）。
        // 旧设备 WAL 可能不支持（PRAGMA 返回错误），静默降级不崩溃。
        try {
            db.execSQL("PRAGMA journal_mode = WAL");
        } catch (Throwable ignored) {
            try {
                db.execSQL("PRAGMA journal_mode = TRUNCATE");
            } catch (Throwable ignored2) {
            }
        }
    }

    // ---------- pts 游标 ----------

    public static long getLocalPts(Context context) {
        SQLiteDatabase db = getInstance(context).getReadableDatabase();
        long pts = 0;
        Cursor c = null;
        try {
            c = db.query(TABLE_PTS, new String[]{"value"}, "key=?", new String[]{"local_pts"}, null, null, null);
            if (c != null && c.moveToFirst()) {
                pts = c.getLong(0);
            }
        } finally {
            if (c != null) c.close();
        }
        return pts;
    }

    public static void saveLocalPts(Context context, long pts) {
        SQLiteDatabase db = getInstance(context).getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("key", "local_pts");
        values.put("value", pts);
        db.insertWithOnConflict(TABLE_PTS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 本地 pts 合法性校验：异常值视为从未同步（走全量 reset）。 */
    public static boolean isValidLocalPts(long pts) {
        return pts >= 0 && pts < 100000000L;
    }
}
