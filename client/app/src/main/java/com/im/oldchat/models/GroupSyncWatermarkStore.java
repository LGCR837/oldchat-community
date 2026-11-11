package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.Set;

/** 群消息可靠同步水位。实时 WS 消息不会直接推进，只有 HTTP 补偿页完整合并后才推进。 */
public final class GroupSyncWatermarkStore {
    private static final String PREF = "group_sync_watermarks";
    private static final String SEQ = "seq_";
    private static final String ANCHOR = "anchor_";

    private GroupSyncWatermarkStore() { }

    public static Watermark get(Context context, String groupId, Set<String> activeMessageIds) {
        if (context == null) return new Watermark(0L, "");
        String key = normalize(groupId);
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long seq = prefs.getLong(SEQ + key, 0L);
        String anchor = prefs.getString(ANCHOR + key, "");
        // 缓存被清理或写入尚未落盘时，不能信任旧水位，否则会永久跳过正文。
        if (seq > 0L && (anchor.length() == 0 || activeMessageIds == null || !activeMessageIds.contains(anchor))) {
            clear(context, groupId);
            return new Watermark(0L, "");
        }
        return new Watermark(seq, anchor);
    }

    public static void save(Context context, String groupId, long seq, String anchorMessageId) {
        if (context == null || seq <= 0L || anchorMessageId == null || anchorMessageId.length() == 0) return;
        String key = normalize(groupId);
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong(SEQ + key, seq).putString(ANCHOR + key, anchorMessageId).apply();
    }

    public static void clear(Context context, String groupId) {
        if (context == null) return;
        String key = normalize(groupId);
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .remove(SEQ + key).remove(ANCHOR + key).apply();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }

    public static final class Watermark {
        public final long seq;
        public final String anchorMessageId;
        Watermark(long seq, String anchorMessageId) { this.seq = seq; this.anchorMessageId = anchorMessageId; }
    }
}
