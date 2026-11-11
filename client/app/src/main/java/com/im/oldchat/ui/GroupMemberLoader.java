package com.im.oldchat.ui;

import android.content.Context;
import android.os.AsyncTask;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMemberMetaCache;
import com.im.oldchat.util.AsyncTaskExecutionCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 群成员资料入口：仅走分页 SQLite 同步，不允许全量 JSON 请求。 */
final class GroupMemberLoader {
    private static final long CACHE_FRESH_MS = 5L * 60L * 1000L;
    private static final Object REQUEST_LOCK = new Object();
    private static final HashSet<String> REQUESTS = new HashSet<String>();
    private static final java.util.HashMap<String, GroupMemberPagedSync> ACTIVE_SYNCS =
            new java.util.HashMap<String, GroupMemberPagedSync>();

    private GroupMemberLoader() { }

    static void cancelSync(String groupId) {
        if (groupId == null || groupId.length() == 0) return;
        GroupMemberPagedSync sync = null;
        synchronized (REQUEST_LOCK) {
            sync = ACTIVE_SYNCS.remove(groupId);
            REQUESTS.remove(groupId);
        }
        if (sync != null) {
            sync.cancel();
        }
    }

    static void loadMemberNames(Context context, String token, String groupId,
                                GroupMessageAdapter adapter,
                                Map<String, String> nameMap,
                                Map<String, String> avatarMap,
                                Map<String, String> titleMap,
                                Map<String, Integer> roleMap,
                                List<GroupMember> members,
                                Runnable onLoaded) {
        if (context == null || groupId == null || groupId.length() == 0
                || token == null || token.length() == 0 || adapter == null) return;
        final Context app = context.getApplicationContext();
        final String gid = groupId;
        final String tokenFinal = token;
        final GroupMessageAdapter target = adapter;
        final Map<String, String> names = nameMap;
        final Map<String, String> avatars = avatarMap;
        final Map<String, String> titles = titleMap;
        final Map<String, Integer> roles = roleMap;
        final List<GroupMember> activeMembers = members;
        final Runnable done = onLoaded;

        // 查询/补缺路径允许并发重入（消息加载完成、头像 miss 等多点都会触发）；
        // 只有全量分页同步用 begin/end 防并发。
        applyActiveCacheAndFetchMissing(app, gid, tokenFinal, target,
                names, avatars, titles, roles, activeMembers, null);
        if (!begin(groupId)) {
            return;
        }
        fetchServerMemberVersion(app, tokenFinal, gid, new VersionCallback() {
            @Override
            public void onVersion(long serverVersion) {
                long localVersion = GroupMemberMetaCache.getMemberVersion(app, gid);
                if (localVersion >= 0 && localVersion == serverVersion) {
                    // 版本一致：本地缓存是最新的，无需全量分页拉取；
                    // 但仍对当前可见发送者做一次查库+lookup 补缺，保证缺的成员补上。
                    applyActiveCacheAndFetchMissing(app, gid, tokenFinal, target,
                            names, avatars, titles, roles, activeMembers,
                            finishCallback(gid, done));
                    return;
                }
                // 从未同步或版本已变：后台分页同步。
                GroupMemberPagedSync sync = new GroupMemberPagedSync(app, tokenFinal, gid, new GroupMemberPagedSync.Callback() {
                    @Override
                    public void onComplete(int total) {
                        end(gid);
                        applyActiveCacheAsync(app, gid, target, names, avatars, titles, roles,
                                activeMembers, finishCallback(gid, done));
                    }

                    @Override
                    public void onError() {
                        end(gid);
                        if (done != null) done.run();
                    }
                });
                synchronized (REQUEST_LOCK) {
                    if (!REQUESTS.contains(gid)) {
                        return;
                    }
                    ACTIVE_SYNCS.put(gid, sync);
                }
                sync.start();
            }

            @Override
            public void onError() {
                // 拿不到版本时保守处理：本地有数据且新鲜就用本地，否则分页同步。
                long localVersion = GroupMemberMetaCache.getMemberVersion(app, gid);
                if (localVersion >= 0 && GroupMemberMetaCache.isFresh(app, gid, CACHE_FRESH_MS)) {
                    applyActiveCacheAndFetchMissing(app, gid, tokenFinal, target,
                            names, avatars, titles, roles, activeMembers,
                            finishCallback(gid, done));
                    return;
                }
                GroupMemberPagedSync sync = new GroupMemberPagedSync(app, tokenFinal, gid, new GroupMemberPagedSync.Callback() {
                    @Override
                    public void onComplete(int total) {
                        end(gid);
                        applyActiveCacheAsync(app, gid, target, names, avatars, titles, roles,
                                activeMembers, finishCallback(gid, done));
                    }

                    @Override
                    public void onError() {
                        end(gid);
                        if (done != null) done.run();
                    }
                });
                synchronized (REQUEST_LOCK) {
                    if (!REQUESTS.contains(gid)) {
                        return;
                    }
                    ACTIVE_SYNCS.put(gid, sync);
                }
                sync.start();
            }
        });
    }

    private interface VersionCallback {
        void onVersion(long version);
        void onError();
    }

    /** 轻量请求群列表，取当前群 member_version（单次请求，不走全量分页）。 */
    private static void fetchServerMemberVersion(final Context context, final String token,
                                                  final String groupId,
                                                  final VersionCallback callback) {
        if (context == null || callback == null) return;
        HttpUtil.get("/groups/list", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String response) {
                android.os.AsyncTask<Void, Void, Long> task =
                        new android.os.AsyncTask<Void, Void, Long>() {
                            @Override
                            protected Long doInBackground(Void... params) {
                                try {
                                    org.json.JSONObject root = new org.json.JSONObject(response);
                                    org.json.JSONArray groups = root.optJSONArray("groups");
                                    if (groups == null) return Long.valueOf(-1L);
                                    for (int i = 0; i < groups.length(); i++) {
                                        org.json.JSONObject g = groups.optJSONObject(i);
                                        if (g == null) continue;
                                        if (groupId.equals(g.optString("group_id"))) {
                                            return Long.valueOf(g.optLong("member_version", -1L));
                                        }
                                    }
                                    return Long.valueOf(-1L);
                                } catch (Throwable ignored) {
                                    return Long.valueOf(-1L);
                                }
                            }

                            @Override
                            protected void onPostExecute(Long version) {
                                if (version.longValue() >= 0) {
                                    callback.onVersion(version.longValue());
                                } else {
                                    callback.onError();
                                }
                            }
                        };
                com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task);
            }

            @Override
            public void onError(int code, String error) {
                callback.onError();
            }
        });
    }

    /** 查 SQLite；未命中的 ncuid 走 lookup 网络补取并增量缓存，再刷新 UI。 */
    private static void applyActiveCacheAndFetchMissing(final Context context, final String groupId,
                                                         final String token,
                                                         final GroupMessageAdapter adapter,
                                                         final Map<String, String> nameMap,
                                                         final Map<String, String> avatarMap,
                                                         final Map<String, String> titleMap,
                                                         final Map<String, Integer> roleMap,
                                                         final List<GroupMember> members,
                                                         final Runnable callback) {
        // 主线程复制当前消息的 NCUID，后台线程不遍历正在变化的 ArrayList。
        final List<String> identities = adapter.getLoadedSenderNcuids();
        if (identities.isEmpty()) {
            // 消息可能还没加载完成（进群 500ms 时列表可能为空）：
            // 延迟重试一次，等消息加载完成后取到有效的发送者列表再查缓存。
            if (callback == null) {
                // 首次路径：延迟 900ms 后重试，避免在消息就绪前白白空跑。
                final Context retryApp = context.getApplicationContext();
                final String retryGid = groupId;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyActiveCacheAndFetchMissing(retryApp, retryGid, token,
                                adapter, nameMap, avatarMap, titleMap, roleMap, members, callback);
                    }
                }, 900L);
                return;
            }
            if (callback != null) callback.run();
            return;
        }
        AsyncTask<Void, Void, List<GroupMember>> task =
                new AsyncTask<Void, Void, List<GroupMember>>() {
            @Override
            protected List<GroupMember> doInBackground(Void... params) {
                return GroupMemberMetaCache.getMembersByNcuids(context, groupId, identities);
            }

            @Override
            protected void onPostExecute(List<GroupMember> rows) {
                if (rows == null) rows = new java.util.ArrayList<GroupMember>();
                // 收集未命中（SQLite 里没有）的 ncuid。
                HashSet<String> cached = new HashSet<String>();
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i) != null && rows.get(i).ncuid != null) {
                        cached.add(rows.get(i).ncuid);
                    }
                }
                ArrayList<String> missing = new ArrayList<String>();
                for (int i = 0; i < identities.size(); i++) {
                    String id = identities.get(i);
                    if (id != null && id.length() > 0 && !cached.contains(id)
                            && !missing.contains(id)) {
                        missing.add(id);
                    }
                }
                // 先应用已缓存的，立即出名字。
                for (int i = 0; i < rows.size(); i++) {
                    applyRow(rows.get(i), nameMap, avatarMap, titleMap, roleMap);
                }
                if (members != null) {
                    members.clear();
                    members.addAll(rows);
                }
                adapter.updateMemberMeta(nameMap, avatarMap, titleMap, roleMap, true);
                if (missing.isEmpty()) {
                    if (callback != null) callback.run();
                    return;
                }
                // 未命中的批量补取（≤50/批），写缓存后再次刷新 UI。
                final List<GroupMember> cachedRows = rows;
                fetchMissingInBatches(context, groupId, token, missing,
                        nameMap, avatarMap, titleMap, roleMap, members, adapter, callback);
            }
        };
        AsyncTaskExecutionCompat.execute(task);
    }

    /** 分批 lookup 未命中成员；每批写完缓存后刷新 UI，全部完成回调。 */
    private static void fetchMissingInBatches(final Context context, final String groupId,
                                               final String token, final List<String> missing,
                                               final Map<String, String> nameMap,
                                               final Map<String, String> avatarMap,
                                               final Map<String, String> titleMap,
                                               final Map<String, Integer> roleMap,
                                               final List<GroupMember> members,
                                               final GroupMessageAdapter adapter,
                                               final Runnable callback) {
        if (missing == null || missing.isEmpty()) {
            if (callback != null) callback.run();
            return;
        }
        int batchSize = Math.min(50, missing.size());
        final ArrayList<String> batch = new ArrayList<String>(missing.subList(0, batchSize));
        final ArrayList<String> rest = new ArrayList<String>(missing.subList(batchSize, missing.size()));
        GroupMemberLookup.lookup(context, groupId, token, batch, new GroupMemberLookup.Callback() {
            @Override
            public void onResult(List<GroupMember> fetched) {
                if (fetched != null) {
                    for (int i = 0; i < fetched.size(); i++) {
                        applyRow(fetched.get(i), nameMap, avatarMap, titleMap, roleMap);
                        if (members != null && !containsNcuid(members, fetched.get(i))) {
                            members.add(fetched.get(i));
                        }
                    }
                    adapter.updateMemberMeta(nameMap, avatarMap, titleMap, roleMap, true);
                }
                fetchMissingInBatches(context, groupId, token, rest,
                        nameMap, avatarMap, titleMap, roleMap, members, adapter, callback);
            }

            @Override
            public void onError() {
                fetchMissingInBatches(context, groupId, token, rest,
                        nameMap, avatarMap, titleMap, roleMap, members, adapter, callback);
            }
        });
    }

    private static boolean containsNcuid(List<GroupMember> list, GroupMember member) {
        if (list == null || member == null || member.ncuid == null) return false;
        for (int i = 0; i < list.size(); i++) {
            GroupMember m = list.get(i);
            if (m != null && m.ncuid != null && m.ncuid.equals(member.ncuid)) return true;
        }
        return false;
    }

    private static Runnable finishCallback(final String groupId, final Runnable callback) {
        return new Runnable() {
            @Override public void run() {
                end(groupId);
                if (callback != null) callback.run();
            }
        };
    }

    private static void applyActiveCacheAsync(final Context context, final String groupId,
                                               final GroupMessageAdapter adapter,
                                               final Map<String, String> nameMap,
                                               final Map<String, String> avatarMap,
                                               final Map<String, String> titleMap,
                                               final Map<String, Integer> roleMap,
                                               final List<GroupMember> members,
                                               final Runnable callback) {
        // 在主线程复制当前消息的 NCUID；后台线程不遍历正在变化的消息 ArrayList。
        final List<String> identities = adapter.getLoadedSenderNcuids();
        AsyncTask<Void, Void, List<GroupMember>> task =
                new AsyncTask<Void, Void, List<GroupMember>>() {
            @Override
            protected List<GroupMember> doInBackground(Void... params) {
                return GroupMemberMetaCache.getMembersByNcuids(context, groupId, identities);
            }

            @Override
            protected void onPostExecute(List<GroupMember> rows) {
                if (rows != null) {
                    for (int i = 0; i < rows.size(); i++) applyRow(rows.get(i),
                            nameMap, avatarMap, titleMap, roleMap);
                    if (members != null) {
                        members.clear();
                        members.addAll(rows);
                    }
                    adapter.updateMemberMeta(nameMap, avatarMap, titleMap, roleMap, true);
                }
                if (callback != null) callback.run();
            }
        };
        AsyncTaskExecutionCompat.execute(task);
    }

    private static void applyRow(GroupMember member,
                                 Map<String, String> names,
                                 Map<String, String> avatars,
                                 Map<String, String> titles,
                                 Map<String, Integer> roles) {
        if (member == null) return;
        String name = FriendNameResolver.resolve("", member.display_name,
                member.username, member.uid);
        if (member.ncuid != null && member.ncuid.length() > 0) {
            String key = member.ncuid;
            if (names != null && name.length() > 0) names.put(key, name);
            if (avatars != null && member.avatar_url != null && member.avatar_url.length() > 0)
                avatars.put(key, member.avatar_url);
            if (titles != null) titles.put(key,
                    member.user_title == null ? "" : member.user_title);
            if (roles != null && member.role != 0) roles.put(key, Integer.valueOf(member.role));
        }
        if (member.uid != null && member.uid.length() > 0) {
            String key = member.uid;
            if (names != null && name.length() > 0) names.put(key, name);
            if (avatars != null && member.avatar_url != null && member.avatar_url.length() > 0)
                avatars.put(key, member.avatar_url);
            if (titles != null) titles.put(key,
                    member.user_title == null ? "" : member.user_title);
            if (roles != null && member.role != 0) roles.put(key, Integer.valueOf(member.role));
        }
    }

    private static boolean begin(String groupId) {
        synchronized (REQUEST_LOCK) {
            return REQUESTS.add(groupId);
        }
    }

    private static void end(String groupId) {
        synchronized (REQUEST_LOCK) {
            REQUESTS.remove(groupId);
        }
    }
}
