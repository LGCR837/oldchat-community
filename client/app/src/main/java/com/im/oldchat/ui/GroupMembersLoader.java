package com.im.oldchat.ui;

import android.os.AsyncTask;
import android.os.Build;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupMember;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

class GroupMembersLoader {
    static final int DEFAULT_PAGE_SIZE = 100;

    interface Callback {
        void onLoaded(List<GroupMember> members, int total, boolean hasMore);

        void onParseFailed();

        void onHttpError(int code, String error);
    }

    private AsyncTask<Void, Void, PageResult> parseTask;
    private int requestVersion = 0;
    private boolean loading = false;

    synchronized void load(String token, String groupId, final Callback callback) {
        loadPage(token, groupId, 0, DEFAULT_PAGE_SIZE, callback);
    }

    synchronized void loadPage(String token, String groupId, int offset, int limit, final Callback callback) {
        if (loading) {
            return;
        }
        loading = true;
        final int version = ++requestVersion;
        String path = "/groups/members?group_id=" + groupId + "&limit=" + limit + "&offset=" + offset;
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                parseMembersAsync(response, version, callback);
            }

            @Override
            public void onError(int code, String error) {
                loading = false;
                if (version != requestVersion || callback == null) {
                    return;
                }
                callback.onHttpError(code, error);
            }
        });
    }

    synchronized void cancel() {
        loading = false;
        requestVersion++;
        if (parseTask != null) {
            parseTask.cancel(true);
            parseTask = null;
        }
    }

    private void parseMembersAsync(final String response, final int version, final Callback callback) {
        if (parseTask != null) {
            parseTask.cancel(true);
            parseTask = null;
        }
        parseTask = new AsyncTask<Void, Void, PageResult>() {
            private boolean parseFailed;

            @Override
            protected PageResult doInBackground(Void... params) {
                PageResult result = new PageResult();
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("members");
                    for (int i = 0; i < arr.length(); i++) {
                        if (isCancelled()) {
                            break;
                        }
                        JSONObject mObj = arr.getJSONObject(i);
                        GroupMember member = new GroupMember();
                        member.uid = mObj.optString("uid");
                        member.ncuid = mObj.optString("ncuid");
                        member.username = mObj.optString("username");
                        member.display_name = mObj.optString("display_name");
                        member.user_title = mObj.optString("user_title");
                        member.avatar_url = mObj.optString("avatar_url");
                        member.role = mObj.optInt("role", 0);
                        member.joined_at = mObj.optLong("joined_at", 0);
                        result.members.add(member);
                    }
                    result.total = Math.max(result.members.size(), obj.optInt("total", result.members.size()));
                    result.hasMore = obj.optBoolean("has_more", false);
                } catch (Exception e) {
                    parseFailed = true;
                }
                return result;
            }

            @Override
            protected void onPostExecute(PageResult result) {
                loading = false;
                if (isCancelled() || callback == null || version != requestVersion) {
                    return;
                }
                parseTask = null;
                if (parseFailed) {
                    callback.onParseFailed();
                    return;
                }
                callback.onLoaded(result.members, result.total, result.hasMore);
            }

            @Override
            protected void onCancelled() {
                loading = false;
            }
        };
        executeTaskCompat(parseTask);
    }

    private static void executeTaskCompat(AsyncTask<Void, Void, PageResult> task) {
        if (task == null) {
            return;
        }
        com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task, (Void[]) null);
    }

    private static class PageResult {
        final List<GroupMember> members = new ArrayList<GroupMember>();
        int total;
        boolean hasMore;
    }
}
