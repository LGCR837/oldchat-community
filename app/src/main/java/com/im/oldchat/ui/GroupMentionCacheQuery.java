package com.im.oldchat.ui;

import android.content.Context;
import android.os.AsyncTask;

import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMemberMetaCache;
import com.im.oldchat.util.AsyncTaskExecutionCompat;

import java.util.List;

/** @成员选择器的磁盘分页查询，避免主线程读取和全量 List。 */
final class GroupMentionCacheQuery {
    interface Callback {
        void onResult(String query, List<GroupMember> members);
    }

    private GroupMentionCacheQuery() { }

    static void load(final Context context, final String groupId, final String query,
                     final Callback callback) {
        AsyncTask<Void, Void, List<GroupMember>> task =
                new AsyncTask<Void, Void, List<GroupMember>>() {
            @Override
            protected List<GroupMember> doInBackground(Void... params) {
                return GroupMemberMetaCache.queryMembers(context, groupId, query, 200, 0);
            }

            @Override
            protected void onPostExecute(List<GroupMember> rows) {
                if (callback != null) callback.onResult(query, rows);
            }
        };
        AsyncTaskExecutionCompat.execute(task);
    }
}
