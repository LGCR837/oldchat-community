package com.im.oldchat.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMemberMetaCache;
import com.im.oldchat.util.ThreadPoolManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** 群成员每页 200 条同步到 SQLite staging，完整后原子提交。 */
final class GroupMemberPagedSync {
    private static final int PAGE_SIZE = 200;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    interface Callback {
        void onComplete(int total);
        void onError();
    }

    private final Context context;
    private final String token;
    private final String groupId;
    private final Callback callback;
    private String syncId;
    private volatile boolean finished;
    private volatile boolean cancelled;

    GroupMemberPagedSync(Context context, String token, String groupId, Callback callback) {
        this.context = context == null ? null : context.getApplicationContext();
        this.token = token;
        this.groupId = groupId;
        this.callback = callback;
    }

    void start() {
        if (context == null || token == null || token.length() == 0
                || groupId == null || groupId.length() == 0) {
            fail();
            return;
        }
        syncId = GroupMemberMetaCache.beginSync(context, groupId);
        if (syncId == null || syncId.length() == 0) {
            fail();
            return;
        }
        requestPage(0);
    }

    void cancel() {
        if (finished || cancelled) return;
        cancelled = true;
        finished = true;
        GroupMemberMetaCache.abortSync(context, groupId, syncId);
    }

    private void requestPage(final int offset) {
        if (finished || cancelled) return;
        String encoded;
        try {
            encoded = URLEncoder.encode(groupId, "UTF-8");
        } catch (Exception e) {
            encoded = groupId;
        }
        String path = "/groups/members?group_id=" + encoded
                + "&limit=" + PAGE_SIZE + "&offset=" + offset;
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String response) {
                if (finished || cancelled) return;
                ThreadPoolManager.getInstance().executeIo(new Runnable() {
                    @Override
                    public void run() {
                        if (finished || cancelled) return;
                        try {
                            final PageResult page = parsePage(response, offset);
                            if (finished || cancelled) return;
                            if (!GroupMemberMetaCache.appendPage(context, groupId, syncId,
                                    page.total, page.members)) {
                                MAIN_HANDLER.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        fail();
                                    }
                                });
                                return;
                            }
                            MAIN_HANDLER.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (finished || cancelled) return;
                                    int next = page.offset + page.rawCount;
                                    if (page.hasMore && next < page.total && page.rawCount > 0) {
                                        requestPage(next);
                                        return;
                                    }
                                    if (GroupMemberMetaCache.commitSync(context, groupId, syncId)) {
                                        GroupMemberMetaCache.setMemberVersion(context, groupId, page.memberVersion);
                                        finished = true;
                                        if (callback != null) callback.onComplete(page.total);
                                    } else {
                                        fail();
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                            MAIN_HANDLER.post(new Runnable() {
                                @Override
                                public void run() {
                                    fail();
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void onError(int code, String error) {
                fail();
            }
        });
    }

    private PageResult parsePage(String response, int requestedOffset) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray array = root.optJSONArray("members");
        if (array == null) throw new Exception("members missing");
        PageResult result = new PageResult();
        result.offset = root.optInt("offset", requestedOffset);
        result.rawCount = array.length();
        result.total = root.optInt("total", result.offset + array.length());
        result.hasMore = root.optBoolean("has_more",
                result.offset + array.length() < result.total);
        result.memberVersion = root.optLong("member_version", -1L);
        result.members = new ArrayList<GroupMember>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String ncuid = obj.optString("ncuid", "");
            if (ncuid.length() == 0) continue;
            GroupMember member = new GroupMember();
            member.uid = obj.optString("uid", "");
            member.ncuid = ncuid;
            member.username = obj.optString("username", "");
            member.display_name = obj.optString("display_name", "");
            member.user_title = obj.optString("user_title", "");
            member.avatar_url = obj.optString("avatar_url", "");
            member.role = obj.optInt("role", 0);
            member.joined_at = obj.optLong("joined_at", 0L);
            result.members.add(member);
        }
        return result;
    }

    private void fail() {
        if (finished) return;
        finished = true;
        GroupMemberMetaCache.abortSync(context, groupId, syncId);
        if (callback != null) callback.onError();
    }

    private static final class PageResult {
        int offset;
        int total;
        int rawCount;
        long memberVersion;
        boolean hasMore;
        List<GroupMember> members;
    }
}
