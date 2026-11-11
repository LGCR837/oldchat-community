package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 客户端稳定身份工具：资料关联只使用不可变 NCUID。 */
public final class NcuidIdentity {
    public static final String AUTH_PREFS = "auth";
    public static final String KEY_MY_NCUID = "my_ncuid";

    private NcuidIdentity() {
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.US);
    }

    public static String getMyNcuid(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        return normalize(prefs.getString(KEY_MY_NCUID, ""));
    }

    public static void captureMine(Context context, String currentUid, String ncuid) {
        if (context == null || normalize(ncuid).length() == 0) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        String myUid = normalize(prefs.getString("my_uid", ""));
        if (myUid.length() > 0 && myUid.equals(normalize(currentUid))) {
            prefs.edit().putString(KEY_MY_NCUID, normalize(ncuid)).apply();
        }
    }

    public static boolean isMine(Context context, String senderNcuid,
                                 String legacySenderUid, String legacyMyUid) {
        String mine = getMyNcuid(context);
        String sender = normalize(senderNcuid);
        if (mine.length() > 0 && sender.length() > 0) {
            return mine.equals(sender);
        }
        // 只为升级过程中尚未重新登录取得 NCUID 的本地数据保留一次旧 UID 兼容。
        return MyUidStore.isMyUid(context, legacySenderUid, legacyMyUid);
    }
}
