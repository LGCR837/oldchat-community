package com.im.oldchat.models;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.ui.GroupMessageSyncHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 群聊消息差额预拉取与 L1 内存预热池管理器。
 * 遵循 Telegram X / 微信增序去重合并与版本世代号规范。
 */
public final class GroupMessagePreloadManager {
    private static final String TAG = "GroupMsgPreload";
    private static final int PRELOAD_LIMIT = 20;
    private static final int MAX_PRELOADED_GROUPS = 8;
    private static final long PRELOAD_COOLDOWN_MS = 10000L; // 10秒防抖冷却

    // L1 内存预取池：LinkedHashMap 淘汰最近最少使用
    private static final Map<String, List<GroupMessage>> PRELOAD_CACHE =
            new LinkedHashMap<String, List<GroupMessage>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<GroupMessage>> eldest) {
                    return size() > MAX_PRELOADED_GROUPS;
                }
            };

    // 冷却时间记录
    private static final Map<String, Long> LAST_PRELOAD_TIMES = new ConcurrentHashMap<String, Long>();
    // 正在拉取中的 GroupId 集合，防止并发重复拉取
    private static final Set<String> IN_FLIGHT_GROUPS = Collections.synchronizedSet(new HashSet<String>());
    private static final Object LOCK = new Object();
    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor();

    private GroupMessagePreloadManager() {
    }

    /**
     * 触发特定群聊的后台差额预拉取
     */
    public static void preloadGroup(final Context context, final String groupId, final String token, final int unreadCount) {
        if (context == null || groupId == null || groupId.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            return;
        }
        final String normalizedGroupId = groupId.trim().toUpperCase(Locale.US);
        final Context appContext = context.getApplicationContext();
        final long now = SystemClock.elapsedRealtime();

        Long lastTime = LAST_PRELOAD_TIMES.get(normalizedGroupId);
        if (lastTime != null && (now - lastTime.longValue()) < PRELOAD_COOLDOWN_MS && unreadCount <= 0) {
            // 冷却期内且无新增未读，跳过
            return;
        }

        if (IN_FLIGHT_GROUPS.contains(normalizedGroupId)) {
            return;
        }

        IN_FLIGHT_GROUPS.add(normalizedGroupId);
        LAST_PRELOAD_TIMES.put(normalizedGroupId, now);

        PRELOAD_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doPreload(appContext, normalizedGroupId, token, unreadCount);
                } catch (Exception e) {
                    Log.e(TAG, "Preload failed for group " + normalizedGroupId, e);
                } finally {
                    IN_FLIGHT_GROUPS.remove(normalizedGroupId);
                }
            }
        });
    }

    private static void doPreload(final Context context, final String groupId, final String token, final int unreadCount) {
        StringBuilder path = new StringBuilder("/v2/groups/messages/v2?group_id=");
        path.append(groupId);
        path.append("&limit=").append(PRELOAD_LIMIT);
        path.append("&offset=0");
        path.append("&mark_read=0");

        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(final String response) {
                if (response == null || response.isEmpty()) return;
                PRELOAD_EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            List<GroupMessage> incoming = GroupMessageSyncHelper.parseIncoming(response);
                            if (incoming == null || incoming.isEmpty()) {
                                return;
                            }
                            // 增序去重合并入本地缓存
                            List<GroupMessage> merged = mergeWithLocal(context, groupId, incoming);
                            // 存入 L1 预热池
                            synchronized (LOCK) {
                                PRELOAD_CACHE.put(groupId, new ArrayList<GroupMessage>(merged));
                            }
                            // 异步保存到 L2 磁盘缓存
                            MessageHistoryCache.saveGroupMessages(context, groupId, merged);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to parse preload response", e);
                        }
                    }
                });
            }

            @Override
            public void onError(int code, String message) {
            }
        });
    }

    /**
     * 增序去重合并算法（Sorted-Deduplicated Merge）
     * 保证无论何时到来的新消息，绝不被冲掉，且保持 created_at 严格单调递增
     */
    public static List<GroupMessage> mergeWithLocal(Context context, String groupId, List<GroupMessage> incoming) {
        List<GroupMessage> existing = MessageHistoryCache.getGroupMessages(context, groupId);
        Map<String, GroupMessage> map = new LinkedHashMap<String, GroupMessage>();
        if (existing != null) {
            for (int i = 0; i < existing.size(); i++) {
                GroupMessage m = existing.get(i);
                if (m != null && m.id != null && !m.id.isEmpty()) {
                    map.put(m.id, m);
                }
            }
        }
        if (incoming != null) {
            for (int i = 0; i < incoming.size(); i++) {
                GroupMessage m = incoming.get(i);
                if (m != null && m.id != null && !m.id.isEmpty()) {
                    map.put(m.id, m);
                }
            }
        }
        List<GroupMessage> result = new ArrayList<GroupMessage>(map.values());
        GroupMessageSyncHelper.sortMessages(result);
        if (result.size() > 320) {
            result = new ArrayList<GroupMessage>(result.subList(result.size() - 320, result.size()));
        }
        return result;
    }

    /**
     * 同步查看 L1 预取池中的消息（不清除）
     */
    public static List<GroupMessage> peekPreloaded(String groupId) {
        if (groupId == null) return null;
        String key = groupId.trim().toUpperCase(Locale.US);
        synchronized (LOCK) {
            List<GroupMessage> list = PRELOAD_CACHE.get(key);
            if (list != null && !list.isEmpty()) {
                return new ArrayList<GroupMessage>(list);
            }
        }
        return null;
    }

    /**
     * 同步消费并移除 L1 预取池中的消息
     */
    public static List<GroupMessage> consumePreloaded(String groupId) {
        if (groupId == null) return null;
        String key = groupId.trim().toUpperCase(Locale.US);
        synchronized (LOCK) {
            List<GroupMessage> list = PRELOAD_CACHE.remove(key);
            if (list != null && !list.isEmpty()) {
                return new ArrayList<GroupMessage>(list);
            }
        }
        return null;
    }

    /**
     * 清理所有预取缓存
     */
    public static void clearAll() {
        synchronized (LOCK) {
            PRELOAD_CACHE.clear();
            LAST_PRELOAD_TIMES.clear();
            IN_FLIGHT_GROUPS.clear();
        }
    }
}
