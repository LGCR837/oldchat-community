package com.im.oldchat.ui;

import android.content.Context;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMemberMetaCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 按 ncuid 批量向服务端查询群成员资料，并增量写入 SQLite。
 * 用于：进群时优先缓存当前可见发送者、滚动加载未命中成员、点击头像强制刷新。
 */
final class GroupMemberLookup {
    private static final int MAX_BATCH = 50;

    interface Callback {
        void onResult(List<GroupMember> members);
        void onError();
    }

    private GroupMemberLookup() { }

    /** 批量查询（≤50 个），成功后 upsert 到 SQLite，UI 线程回调。 */
    static void lookup(final Context context, final String groupId, final String token,
                       final List<String> ncuids, final Callback callback) {
        if (context == null || groupId == null || groupId.length() == 0
                || token == null || token.length() == 0
                || ncuids == null || ncuids.isEmpty()) {
            fail(callback);
            return;
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(groupId, "UTF-8");
        } catch (Exception e) {
            encoded = groupId;
        }
        StringBuilder path = new StringBuilder("/v2/groups/members/lookup?group_id=")
                .append(encoded).append("&ncuids=");
        for (int i = 0; i < ncuids.size(); i++) {
            if (i > 0) path.append(',');
            try {
                path.append(URLEncoder.encode(ncuids.get(i), "UTF-8"));
            } catch (Exception e) {
                path.append(ncuids.get(i));
            }
        }
        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String response) {
                final Context app = context.getApplicationContext();
                final String gid = groupId;
                android.os.AsyncTask<Void, Void, List<GroupMember>> task =
                        new android.os.AsyncTask<Void, Void, List<GroupMember>>() {
                            @Override
                            protected List<GroupMember> doInBackground(Void... params) {
                                try {
                                    List<GroupMember> parsed = parse(response);
                                    GroupMemberMetaCache.upsertMembers(app, gid, parsed);
                                    return parsed;
                                } catch (Throwable ignored) {
                                    return null;
                                }
                            }

                            @Override
                            protected void onPostExecute(List<GroupMember> members) {
                                if (callback != null) callback.onResult(members);
                            }
                        };
                com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task, (Void[]) null);
            }

            @Override
            public void onError(int code, String error) {
                fail(callback);
            }
        });
    }

    private static List<GroupMember> parse(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray array = root.optJSONArray("members");
        ArrayList<GroupMember> out = new ArrayList<GroupMember>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String ncuid = obj.optString("ncuid", "");
            if (ncuid.length() == 0) continue;
            GroupMember m = new GroupMember();
            m.uid = obj.optString("uid", "");
            m.ncuid = ncuid;
            m.username = obj.optString("username", "");
            m.display_name = obj.optString("display_name", "");
            m.user_title = obj.optString("user_title", "");
            m.avatar_url = obj.optString("avatar_url", "");
            m.role = obj.optInt("role", 0);
            m.joined_at = obj.optLong("joined_at", 0L);
            out.add(m);
        }
        return out;
    }

    private static void fail(Callback callback) {
        if (callback != null) callback.onError();
    }
}
