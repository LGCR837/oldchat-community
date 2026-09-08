package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 群成员磁盘缓存：NCUID 主键、分页 staging、最终原子替换 live。 */
public final class GroupMemberMetaCache {
    private static final String DB_NAME = "group_member_meta.db";
    private static final int DB_VERSION = 3;
    private static final String LIVE = "members_live";
    private static final String STAGE = "members_stage";
    private static final String SYNC = "member_sync";
    private static final String GROUPS = "cached_groups";
    private static final int MAX_GROUP_CACHE = 30;
    private static final Object SYNC_LOCK = new Object();
    private static final HashSet<String> ACTIVE_SYNCS = new HashSet<String>();
    private static volatile DbHelper helper;

    private GroupMemberMetaCache() { }

    public static String beginSync(Context context, String groupId) {
        if (context == null || TextUtils.isEmpty(groupId)) return "";
        // 同一群同步进行中则忽略（防止 @ 触发与进群分页并发互相清 staging）。
        synchronized (SYNC_LOCK) {
            if (!ACTIVE_SYNCS.add(groupId)) {
                return "";
            }
        }
        String syncId = Long.toHexString(System.currentTimeMillis()) + "_"
                + Integer.toHexString(System.identityHashCode(new Object()));
        SQLiteDatabase db = null;
        try {
            String account = accountKey(context);
            db = helper(context).getWritableDatabase();
            db.beginTransaction();
            db.delete(STAGE, "account=? AND group_id=?", new String[]{account, groupId});
            db.delete(SYNC, "account=? AND group_id=?", new String[]{account, groupId});
            SQLiteStatement state = db.compileStatement("INSERT INTO " + SYNC
                    + "(account,group_id,sync_id,expected_total,received,updated_at) VALUES(?,?,?,?,?,?)");
            bind(state, 1, account); bind(state, 2, groupId); bind(state, 3, syncId);
            state.bindLong(4, -1); state.bindLong(5, 0);
            state.bindLong(6, System.currentTimeMillis()); state.executeInsert(); state.close();
            db.setTransactionSuccessful();
            return syncId;
        } catch (Throwable ignored) {
            synchronized (SYNC_LOCK) {
                ACTIVE_SYNCS.remove(groupId);
            }
            return "";
        } finally {
            end(db);
        }
    }

    public static boolean appendPage(Context context, String groupId, String syncId,
                                     int total, List<GroupMember> page) {
        if (context == null || TextUtils.isEmpty(groupId) || TextUtils.isEmpty(syncId)
                || page == null) return false;
        SQLiteDatabase db = null;
        SQLiteStatement insert = null;
        try {
            String account = accountKey(context);
            db = helper(context).getWritableDatabase();
            db.beginTransaction();
            insert = db.compileStatement("INSERT OR REPLACE INTO " + STAGE
                    + "(account,group_id,sync_id,ncuid,uid,username,display_name,user_title,avatar_url,role,joined_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)");
            for (int i = 0; i < page.size(); i++) {
                GroupMember m = page.get(i);
                if (m == null || TextUtils.isEmpty(m.ncuid)) continue;
                insert.clearBindings();
                bind(insert, 1, account); bind(insert, 2, groupId); bind(insert, 3, syncId);
                bind(insert, 4, m.ncuid); bind(insert, 5, m.uid); bind(insert, 6, m.username);
                bind(insert, 7, m.display_name); bind(insert, 8, m.user_title);
                bind(insert, 9, m.avatar_url); insert.bindLong(10, m.role);
                insert.bindLong(11, m.joined_at); insert.executeInsert();
            }
            // executeUpdateDelete() 仅 API 11+；使用 API 4 即有的 execSQL 兼容 Android 2.3。
            db.execSQL("UPDATE " + SYNC
                    + " SET expected_total=?,received=(SELECT COUNT(*) FROM " + STAGE
                    + " WHERE account=? AND group_id=? AND sync_id=?),updated_at=?"
                    + " WHERE account=? AND group_id=? AND sync_id=?",
                    new Object[]{Integer.valueOf(total), account, groupId, syncId,
                            Long.valueOf(System.currentTimeMillis()), account, groupId, syncId});
            // 边拉边写 LIVE：每页立即合并，当前页成员马上可见，无需等全部页拉完。
            // 大群 3000 人分 15 页，若等全部完成才 commit，期间一直显示 NCUID。
            db.execSQL("INSERT OR REPLACE INTO " + LIVE
                    + "(account,group_id,ncuid,uid,username,display_name,user_title,avatar_url,role,joined_at)"
                    + " SELECT account,group_id,ncuid,uid,username,display_name,user_title,avatar_url,role,joined_at"
                    + " FROM " + STAGE + " WHERE account=? AND group_id=? AND sync_id=?",
                    new Object[]{account, groupId, syncId});
            db.setTransactionSuccessful();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (insert != null) try { insert.close(); } catch (Throwable ignored) { }
            end(db);
        }
    }

    /** 仅完整 staging 才提交；读者只能看到完整旧版或完整新版。 */
    public static boolean commitSync(Context context, String groupId, String syncId) {
        if (context == null || TextUtils.isEmpty(groupId) || TextUtils.isEmpty(syncId)) return false;
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            String account = accountKey(context);
            db = helper(context).getWritableDatabase();
            c = db.query(SYNC, new String[]{"expected_total", "received"},
                    "account=? AND group_id=? AND sync_id=?",
                    new String[]{account, groupId, syncId}, null, null, null);
            if (!c.moveToFirst() || c.getInt(0) < 0 || c.getInt(0) != c.getInt(1)) return false;
            c.close(); c = null;
            db.beginTransaction();
            String[] args = new String[]{account, groupId};
            // 不整体删除 LIVE：STAGE 分页数据可能因服务端排序/分页错位而不完整，
            // 整体替换会冲掉 lookup 增量写入的当前可见成员（导致名字/头像消失）。
            // 改为合并：STAGE 覆盖 LIVE 同 key，保留 lookup 已写入的成员。
            db.execSQL("INSERT OR REPLACE INTO " + LIVE
                    + "(account,group_id,ncuid,uid,username,display_name,user_title,avatar_url,role,joined_at)"
                    + " SELECT account,group_id,ncuid,uid,username,display_name,user_title,avatar_url,role,joined_at"
                    + " FROM " + STAGE + " WHERE account=? AND group_id=? AND sync_id=?",
                    new Object[]{account, groupId, syncId});
            db.execSQL("INSERT OR REPLACE INTO " + GROUPS
                    + "(account,group_id,updated_at) VALUES(?,?,?)",
                    new Object[]{account, groupId, Long.valueOf(System.currentTimeMillis())});
            db.delete(STAGE, "account=? AND group_id=?", args);
            db.delete(SYNC, "account=? AND group_id=?", args);
            trimOldGroups(db, account);
            db.setTransactionSuccessful();
            synchronized (SYNC_LOCK) {
                ACTIVE_SYNCS.remove(groupId);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (c != null) c.close();
            end(db);
        }
    }

    public static void abortSync(Context context, String groupId, String syncId) {
        if (context == null || TextUtils.isEmpty(groupId)) return;
        synchronized (SYNC_LOCK) {
            ACTIVE_SYNCS.remove(groupId);
        }
        try {
            SQLiteDatabase db = helper(context).getWritableDatabase();
            String account = accountKey(context);
            String where = "account=? AND group_id=?" + (TextUtils.isEmpty(syncId) ? "" : " AND sync_id=?");
            String[] args = TextUtils.isEmpty(syncId) ? new String[]{account, groupId}
                    : new String[]{account, groupId, syncId};
            db.delete(STAGE, where, args); db.delete(SYNC, where, args);
        } catch (Throwable ignored) { }
    }

    /** 旧调用兼容：仍走 staging，不产生 JSON。 */
    public static void putMembers(Context context, String groupId, List<GroupMember> members) {
        String id = beginSync(context, groupId);
        if (id.length() == 0 || !appendPage(context, groupId, id, members.size(), members)
                || !commitSync(context, groupId, id)) abortSync(context, groupId, id);
    }

    public static GroupMember getMember(Context context, String groupId, String ncuid) {
        List<String> one = new ArrayList<String>(); one.add(ncuid);
        List<GroupMember> rows = getMembersByNcuids(context, groupId, one);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 增量缓存：按 ncuid/uid 批量 upsert，不触发挥发性整群同步；新号进群优先用此方法缓存当前可见发送者。 */
    public static void upsertMembers(Context context, String groupId, List<GroupMember> members) {
        if (context == null || TextUtils.isEmpty(groupId) || members == null || members.isEmpty()) {
            return;
        }
        SQLiteDatabase db = null;
        SQLiteStatement insert = null;
        try {
            String account = accountKey(context);
            db = helper(context).getWritableDatabase();
            db.beginTransaction();
            insert = db.compileStatement("INSERT OR REPLACE INTO " + LIVE
                    + "(account,group_id,ncuid,uid,username,display_name,user_title,avatar_url,role,joined_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)");
            for (int i = 0; i < members.size(); i++) {
                GroupMember m = members.get(i);
                if (m == null || (TextUtils.isEmpty(m.ncuid) && TextUtils.isEmpty(m.uid))) continue;
                String ncuid = !TextUtils.isEmpty(m.ncuid) ? m.ncuid : m.uid;
                String uid = !TextUtils.isEmpty(m.uid) ? m.uid : m.ncuid;
                insert.clearBindings();
                bind(insert, 1, account); bind(insert, 2, groupId);
                bind(insert, 3, ncuid); bind(insert, 4, uid); bind(insert, 5, m.username);
                bind(insert, 6, m.display_name); bind(insert, 7, m.user_title);
                bind(insert, 8, m.avatar_url); insert.bindLong(9, m.role);
                insert.bindLong(10, m.joined_at); insert.executeInsert();

                String name = FriendNameResolver.resolve("", m.display_name, m.username, uid);
                if (name != null && !name.isEmpty()) {
                    if (!TextUtils.isEmpty(uid)) UserNameCache.put(context, uid, name);
                    if (!TextUtils.isEmpty(ncuid)) UserNameCache.put(context, ncuid, name);
                }
                if (!TextUtils.isEmpty(m.user_title)) {
                    if (!TextUtils.isEmpty(uid)) UserTitleCache.put(context, uid, m.user_title);
                    if (!TextUtils.isEmpty(ncuid)) UserTitleCache.put(context, ncuid, m.user_title);
                }
            }
            try {
                com.im.oldchat.util.GroupAvatarCache.updateFromMembers(context, members);
            } catch (Throwable ignored) {
            }
            SQLiteStatement touch = db.compileStatement("INSERT OR REPLACE INTO " + GROUPS
                    + "(account,group_id,updated_at) VALUES(?,?,?)");
            bind(touch, 1, account); bind(touch, 2, groupId);
            touch.bindLong(3, System.currentTimeMillis()); touch.executeInsert(); touch.close();
            db.setTransactionSuccessful();
        } catch (Throwable ignored) {
        } finally {
            if (insert != null) try { insert.close(); } catch (Throwable ignored) { }
            end(db);
        }
    }

    /** 强制更新单个成员资料（点击头像时调用）。 */
    public static void upsertMember(Context context, String groupId, GroupMember member) {
        if (member == null) return;
        ArrayList<GroupMember> one = new ArrayList<GroupMember>();
        one.add(member);
        upsertMembers(context, groupId, one);
    }

    public static List<GroupMember> getMembersByNcuids(Context context, String groupId,
                                                        List<String> ncuids) {
        ArrayList<GroupMember> out = new ArrayList<GroupMember>();
        if (context == null || TextUtils.isEmpty(groupId) || ncuids == null || ncuids.isEmpty()) return out;
        Set<String> unique = new HashSet<String>(ncuids);
        ArrayList<String> batch = new ArrayList<String>(80);
        for (String id : unique) {
            if (TextUtils.isEmpty(id)) continue;
            batch.add(id);
            if (batch.size() == 80) { queryIdentityBatch(context, groupId, batch, out); batch.clear(); }
        }
        if (!batch.isEmpty()) queryIdentityBatch(context, groupId, batch, out);
        return out;
    }

    public static List<GroupMember> queryMembers(Context context, String groupId, String query,
                                                  int limit, int offset) {
        ArrayList<GroupMember> out = new ArrayList<GroupMember>();
        if (context == null || TextUtils.isEmpty(groupId)) return out;
        limit = Math.max(1, Math.min(limit, 200)); offset = Math.max(0, offset);
        Cursor c = null;
        try {
            String account = accountKey(context); String q = query == null ? "" : query.trim();
            String where = "account=? AND group_id=?";
            ArrayList<String> args = new ArrayList<String>(); args.add(account); args.add(groupId);
            if (q.length() > 0) {
                where += " AND (display_name LIKE ? ESCAPE '\\' OR username LIKE ? ESCAPE '\\' OR uid LIKE ? ESCAPE '\\' OR ncuid LIKE ? ESCAPE '\\')";
                String like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
                args.add(like); args.add(like); args.add(like); args.add(like);
            }
            c = helper(context).getReadableDatabase().query(LIVE, columns(), where,
                    args.toArray(new String[args.size()]), null, null,
                    "role DESC,display_name COLLATE NOCASE,username COLLATE NOCASE,ncuid",
                    offset + "," + limit);
            while (c.moveToNext()) out.add(read(c));
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        return out;
    }

    public static List<GroupMember> getMembers(Context c, String g) {
        return queryMembers(c, g, "", 200, 0);
    }

    public static boolean isFresh(Context context, String groupId, long maxAgeMs) {
        if (context == null || TextUtils.isEmpty(groupId)) return false;
        Cursor c = null;
        try {
            c = helper(context).getReadableDatabase().query(GROUPS, new String[]{"updated_at"},
                    "account=? AND group_id=?", new String[]{accountKey(context), groupId},
                    null, null, null, "1");
            return c.moveToFirst() && System.currentTimeMillis() - c.getLong(0) <= maxAgeMs;
        } catch (Throwable ignored) {
            return false;
        } finally { if (c != null) c.close(); }
    }

    /** 本地缓存的群成员版本号；无记录返回 -1（表示从未同步，需拉取）。 */
    public static long getMemberVersion(Context context, String groupId) {
        if (context == null || TextUtils.isEmpty(groupId)) return -1L;
        Cursor c = null;
        try {
            c = helper(context).getReadableDatabase().query(GROUPS,
                    new String[]{"member_version"},
                    "account=? AND group_id=?", new String[]{accountKey(context), groupId},
                    null, null, null, "1");
            return c.moveToFirst() ? c.getLong(0) : -1L;
        } catch (Throwable ignored) {
            return -1L;
        } finally { if (c != null) c.close(); }
    }

    /** 同步完成后记录群成员版本号。 */
    public static void setMemberVersion(Context context, String groupId, long version) {
        if (context == null || TextUtils.isEmpty(groupId)) return;
        try {
            SQLiteDatabase db = helper(context).getWritableDatabase();
            SQLiteStatement st = db.compileStatement("UPDATE " + GROUPS
                    + " SET member_version=? WHERE account=? AND group_id=?");
            st.bindLong(1, version);
            st.bindString(2, accountKey(context));
            st.bindString(3, groupId);
            st.executeUpdateDelete();
            st.close();
        } catch (Throwable ignored) {
        }
    }

    public static GroupMember getMemberByIdentity(Context context, String groupId, String identity) {
        if (context == null || TextUtils.isEmpty(groupId) || TextUtils.isEmpty(identity)) return null;
        Cursor c = null;
        try {
            String account = accountKey(context);
            String where = "account=? AND group_id=? AND (ncuid=? OR uid=?)";
            c = helper(context).getReadableDatabase().query(LIVE, columns(), where,
                    new String[]{account, groupId, identity, identity}, null, null, null, "1");
            if (c.moveToFirst()) {
                return read(c);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static void queryIdentityBatch(Context context, String groupId, List<String> ids,
                                           List<GroupMember> out) {
        if (context == null || TextUtils.isEmpty(groupId) || ids == null || ids.isEmpty()) return;
        Cursor c = null;
        try {
            StringBuilder in = new StringBuilder();
            ArrayList<String> args = new ArrayList<String>();
            args.add(accountKey(context));
            args.add(groupId);
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) in.append(',');
                in.append('?');
                args.add(ids.get(i));
            }
            for (int i = 0; i < ids.size(); i++) {
                args.add(ids.get(i));
            }
            String where = "account=? AND group_id=? AND (ncuid IN (" + in + ") OR uid IN (" + in + "))";
            c = helper(context).getReadableDatabase().query(LIVE, columns(), where,
                    args.toArray(new String[args.size()]), null, null, null);
            while (c.moveToNext()) out.add(read(c));
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
    }

    private static String[] columns() { return new String[]{"uid","ncuid","username","display_name","user_title","avatar_url","role","joined_at"}; }
    private static GroupMember read(Cursor c) {
        GroupMember m = new GroupMember(); m.uid=c.getString(0); m.ncuid=c.getString(1);
        m.username=c.getString(2); m.display_name=c.getString(3); m.user_title=c.getString(4);
        m.avatar_url=c.getString(5); m.role=c.getInt(6); m.joined_at=c.getLong(7); return m;
    }
    private static void bind(SQLiteStatement s, int i, String v) { s.bindString(i, v == null ? "" : v); }
    private static void end(SQLiteDatabase db) { if (db != null && db.inTransaction()) try { db.endTransaction(); } catch (Throwable ignored) { } }

    private static void trimOldGroups(SQLiteDatabase db, String account) {
        Cursor c = null; ArrayList<String> remove = new ArrayList<String>();
        try {
            c = db.query(GROUPS,new String[]{"group_id"},"account=?",new String[]{account},null,null,"updated_at DESC");
            int i=0; while(c.moveToNext()) { if(++i>MAX_GROUP_CACHE) remove.add(c.getString(0)); }
        } finally { if(c!=null)c.close(); }
        for (int i=0;i<remove.size();i++) { String[] a={account,remove.get(i)};
            db.delete(LIVE,"account=? AND group_id=?",a); db.delete(GROUPS,"account=? AND group_id=?",a); }
    }
    private static DbHelper helper(Context c) { if(helper==null) synchronized(GroupMemberMetaCache.class){if(helper==null)helper=new DbHelper(c.getApplicationContext());} return helper; }
    private static String accountKey(Context c) { SharedPreferences p=c.getSharedPreferences("auth",Context.MODE_PRIVATE); String n=p.getString("my_ncuid",""); if(TextUtils.isEmpty(n))n=p.getString("my_uid",""); return TextUtils.isEmpty(n)?"guest":n; }

    private static final class DbHelper extends SQLiteOpenHelper {
        private final Context app;
        DbHelper(Context c){super(c,DB_NAME,null,DB_VERSION);app=c.getApplicationContext();}
        public void onCreate(SQLiteDatabase db){
            String cols="account TEXT NOT NULL,group_id TEXT NOT NULL,ncuid TEXT NOT NULL,uid TEXT,username TEXT,display_name TEXT,user_title TEXT,avatar_url TEXT,role INTEGER NOT NULL DEFAULT 0,joined_at INTEGER NOT NULL DEFAULT 0,";
            db.execSQL("CREATE TABLE "+LIVE+"("+cols+"PRIMARY KEY(account,group_id,ncuid))");
            db.execSQL("CREATE TABLE "+STAGE+"("+cols+"sync_id TEXT NOT NULL,PRIMARY KEY(account,group_id,sync_id,ncuid))");
            db.execSQL("CREATE TABLE "+SYNC+"(account TEXT NOT NULL,group_id TEXT NOT NULL,sync_id TEXT NOT NULL,expected_total INTEGER,received INTEGER,updated_at INTEGER,PRIMARY KEY(account,group_id))");
            db.execSQL("CREATE TABLE "+GROUPS+"(account TEXT NOT NULL,group_id TEXT NOT NULL,updated_at INTEGER,member_version INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account,group_id))");
            db.execSQL("CREATE INDEX idx_live_uid ON "+LIVE+"(account,group_id,uid)");
        }
        public void onUpgrade(SQLiteDatabase db,int o,int n){
            if (o < 3) {
                try { db.execSQL("ALTER TABLE "+GROUPS+" ADD COLUMN member_version INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) { }
            }
            db.execSQL("DROP TABLE IF EXISTS "+LIVE);db.execSQL("DROP TABLE IF EXISTS "+STAGE);db.execSQL("DROP TABLE IF EXISTS "+SYNC);onCreate(db);
        }
        public void onOpen(SQLiteDatabase db){super.onOpen(db);try{app.getSharedPreferences("group_member_meta_cache",Context.MODE_PRIVATE).edit().clear().apply();}catch(Throwable ignored){}}
    }
}
