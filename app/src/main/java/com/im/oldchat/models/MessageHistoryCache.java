package com.im.oldchat.models;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.im.oldchat.data.MessageDbStore;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageHistoryCache {
    private static final String PREF_NAME = "message_history_cache";
    private static final String KEY_DIRECT_PREFIX = "direct_";
    private static final String KEY_DIRECT_NCUID_PREFIX = "direct_ncuid_";
    private static final String KEY_GROUP_PREFIX = "group_";
    private static final int MAX_MESSAGES = 320;
    // 与聊天页内存窗口 MAX_ACTIVE_WINDOW_MESSAGES(320) 对齐：用户翻页看到的历史
    // 也能完整落盘，杀进程重启后不会因 200 条截断而丢失翻页进度。
    // 40 会话 × 320 条约 1.28 万条 Gson 对象（旧设备约 1-5MB），可接受。
    private static final Gson GSON = new Gson();
    private static volatile boolean savePaused = false;
    private static final Object SAVE_LOCK = new Object();
    private static final Map<String, Integer> SAVE_GENERATIONS = new HashMap<String, Integer>();
    private static final Map<String, List<?>> LATEST_SNAPSHOTS = new HashMap<String, List<?>>();
    private static final Map<String, Context> LATEST_CONTEXTS = new HashMap<String, Context>();
    private static final Set<String> SCHEDULED_KEYS = new HashSet<String>();
    // 读侧内存缓存：避免每次打开聊天页都全量反序列化 200 条消息。
    // 旧设备上 SharedPreferences 首次加载 + Gson 全量解析会阻塞主线程数百毫秒。
    // 写盘成功 / 删除 / 清空时同步维护，保证与磁盘一致。
    // LinkedHashMap（插入序）+ removeEldestEntry：超过上限时自动淘汰最旧插入的键。
    private static final int READ_CACHE_MAX_KEYS = 40;
    private static final Map<String, List<?>> READ_CACHE = new LinkedHashMap<String, List<?>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<?>> eldest) {
            return size() > READ_CACHE_MAX_KEYS;
        }
    };

    public static void saveDirectMessages(Context context, String uid, List<Message> messages) {
        saveDirectMessages(context, uid, "", messages);
    }

    public static void saveDirectMessages(Context context, String uid, String ncuid, List<Message> messages) {
        String identity = ncuid == null || ncuid.length() == 0 ? uid : ncuid;
        if (context == null || identity == null || identity.isEmpty() || messages == null) return;
        final Context appContext = context.getApplicationContext();
        final String key = (ncuid == null || ncuid.length() == 0 ? KEY_DIRECT_PREFIX : KEY_DIRECT_NCUID_PREFIX) + identity;
        // 上传/发送任务只存在于当前进程，不能把“发送中”气泡写入磁盘；否则进程
        // 被杀后它会永久停留，并可能与服务端正式消息重复。
        final List<Message> snapshot = snapshotDirectTailWithoutPending(messages);
        enqueueLatestSnapshot(appContext, key, snapshot);
    }

    public static List<Message> getDirectMessages(Context context, String uid) {
        return getDirectMessages(context, uid, "");
    }

    public static List<Message> getDirectMessages(Context context, String uid, String ncuid) {
        String identity = ncuid == null || ncuid.length() == 0 ? uid : ncuid;
        if (context == null || identity == null || identity.isEmpty()) return new ArrayList<Message>();
        String key = (ncuid == null || ncuid.length() == 0 ? KEY_DIRECT_PREFIX : KEY_DIRECT_NCUID_PREFIX) + identity;
        List<Message> cached = read(context, key,
                TypeToken.getParameterized(List.class, Message.class).getType());
        if (cached.isEmpty() && ncuid != null && ncuid.length() > 0 && uid != null && uid.length() > 0) {
            // 首次使用稳定键时迁移旧 UID 会话缓存。
            cached = read(context, KEY_DIRECT_PREFIX + uid,
                    TypeToken.getParameterized(List.class, Message.class).getType());
            if (!cached.isEmpty()) {
                saveDirectMessages(context, uid, ncuid, cached);
            }
        }
        if (removeStaleDirectPending(cached)) {
            saveDirectMessages(context, uid, ncuid, cached);
        }
        return cached;
    }

    public static void saveGroupMessages(Context context, String groupId, List<GroupMessage> messages) {
        if (context == null || groupId == null || groupId.isEmpty() || messages == null) return;
        final Context appContext = context.getApplicationContext();
        final String key = KEY_GROUP_PREFIX + groupId;
        final List<GroupMessage> snapshot = snapshotGroupTailWithoutPending(messages);
        enqueueLatestSnapshot(appContext, key, snapshot);
    }

    public static List<GroupMessage> getGroupMessages(Context context, String groupId) {
        if (context == null || groupId == null || groupId.isEmpty()) return new ArrayList<>();
        List<GroupMessage> cached = read(context, KEY_GROUP_PREFIX + groupId,
                TypeToken.getParameterized(List.class, GroupMessage.class).getType());
        if (removeStaleGroupPending(cached)) {
            saveGroupMessages(context, groupId, cached);
        }
        return cached;
    }

    public static void removeGroupMessages(Context context, String groupId) {
        if (context == null || groupId == null || groupId.isEmpty()) return;
        String key = KEY_GROUP_PREFIX + groupId;
        synchronized (SAVE_LOCK) {
            Integer old = SAVE_GENERATIONS.get(key);
            SAVE_GENERATIONS.put(key, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
            LATEST_SNAPSHOTS.remove(key);
            LATEST_CONTEXTS.remove(key);
        }
        removeReadCache(key);
        MessageDbStore.removeGroup(context, groupId);
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().remove(key).apply();
        GroupSyncWatermarkStore.clear(context, groupId);
    }

    public static void clearAll(Context context) {
        if (context == null) return;
        synchronized (SAVE_LOCK) {
            LATEST_SNAPSHOTS.clear();
            LATEST_CONTEXTS.clear();
            for (String key : new ArrayList<String>(SAVE_GENERATIONS.keySet())) {
                Integer old = SAVE_GENERATIONS.get(key);
                SAVE_GENERATIONS.put(key, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
            }
        }
        clearReadCache();
        MessageDbStore.clearAll(context);
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        context.getSharedPreferences("group_sync_watermarks", Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static void pauseSave() {
        synchronized (SAVE_LOCK) {
            savePaused = true;
        }
    }

    public static void resumeSave() {
        List<String> keys = new ArrayList<String>();
        List<Context> contexts = new ArrayList<Context>();
        synchronized (SAVE_LOCK) {
            savePaused = false;
            for (String key : LATEST_SNAPSHOTS.keySet()) {
                if (SCHEDULED_KEYS.contains(key)) continue;
                Context context = LATEST_CONTEXTS.get(key);
                if (context == null) continue;
                SCHEDULED_KEYS.add(key);
                keys.add(key);
                contexts.add(context);
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            startWorker(contexts.get(i), keys.get(i));
        }
    }

    public static List<String> getDirectChatIds(Context context) {
        return getChatIds(context, KEY_DIRECT_PREFIX);
    }

    public static List<String> getDirectChatNcuids(Context context) {
        return getChatIds(context, KEY_DIRECT_NCUID_PREFIX);
    }

    public static void migrateDirectMessages(Context context, String oldUid, String newUid, String ncuid) {
        if (context == null || oldUid == null || oldUid.length() == 0
                || ncuid == null || ncuid.length() == 0) return;
        if (newUid != null && oldUid.equalsIgnoreCase(newUid)) return;
        Type type = TypeToken.getParameterized(List.class, Message.class).getType();
        List<Message> stable = read(context, KEY_DIRECT_NCUID_PREFIX + ncuid, type);
        if (!stable.isEmpty()) return;
        List<Message> legacy = read(context, KEY_DIRECT_PREFIX + oldUid, type);
        if (!legacy.isEmpty()) {
            saveDirectMessages(context, newUid, ncuid, legacy);
        }
    }

    public static List<String> getGroupChatIds(Context context) {
        return getChatIds(context, KEY_GROUP_PREFIX);
    }

    private static List<String> getChatIds(Context context, String prefix) {
        if (context == null) return new ArrayList<>();
        List<String> ids = new ArrayList<>();
        // SQLite 行级存储：查 distinct peer/group。
        try {
            if (KEY_GROUP_PREFIX.equals(prefix)) {
                ids = com.im.oldchat.data.MessageDbStore.listGroupIds(context);
            } else {
                ids = com.im.oldchat.data.MessageDbStore.listDirectPeerKeys(context);
            }
        } catch (Exception ignored) {
        }
        // 兼容旧 JSON 键（迁移完成前仍可能读到）。
        if (ids.isEmpty()) {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            java.util.Map<String, ?> all = prefs.getAll();
            for (String key : all.keySet()) {
                if (key != null && key.startsWith(prefix)
                        && !(KEY_DIRECT_PREFIX.equals(prefix) && key.startsWith(KEY_DIRECT_NCUID_PREFIX))) {
                    String id = key.substring(prefix.length());
                    if (!id.isEmpty()) ids.add(id);
                }
            }
        }
        return ids;
    }

    private static List<Message> snapshotDirectTailWithoutPending(List<Message> source) {
        List<Message> stable = new ArrayList<Message>();
        for (Message message : source) {
            if (message != null && !message.localPending) {
                stable.add(message);
            }
        }
        return snapshotTail(stable);
    }

    private static List<GroupMessage> snapshotGroupTailWithoutPending(List<GroupMessage> source) {
        List<GroupMessage> stable = new ArrayList<GroupMessage>();
        for (GroupMessage message : source) {
            if (message != null && !message.localPending) {
                stable.add(message);
            }
        }
        return snapshotTail(stable);
    }

    private static boolean removeStaleDirectPending(List<Message> messages) {
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && message.localPending) {
                messages.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean removeStaleGroupPending(List<GroupMessage> messages) {
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            GroupMessage message = messages.get(i);
            if (message != null && message.localPending) {
                messages.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    private static <T> List<T> snapshotTail(List<T> source) {
        int start = source.size() > MAX_MESSAGES ? source.size() - MAX_MESSAGES : 0;
        return new ArrayList<T>(source.subList(start, source.size()));
    }

    private static <T> void enqueueLatestSnapshot(final Context context, final String key,
                                                   final List<T> snapshot) {
        synchronized (SAVE_LOCK) {
            Integer old = SAVE_GENERATIONS.get(key);
            SAVE_GENERATIONS.put(key, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
            LATEST_SNAPSHOTS.put(key, snapshot);
            LATEST_CONTEXTS.put(key, context);
            // 写盘入口同步更新读缓存：保证任何时刻 read() 返回的都是最新快照，
            // 即使异步写盘尚未完成也不会读到旧数据（不丢消息）。
            putReadCache(key, snapshot);
            // 滚动期间仍保留最新快照，只暂停序列化；恢复后自动继续，不能直接丢弃保存请求。
            if (savePaused || SCHEDULED_KEYS.contains(key)) return;
            SCHEDULED_KEYS.add(key);
        }
        startWorker(context, key);
    }

    private static void startWorker(final Context context, final String key) {
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeSerial(new Runnable() {
            @Override public void run() {
                while (true) {
                    List<?> latest;
                    int generation;
                    synchronized (SAVE_LOCK) {
                        if (savePaused) {
                            SCHEDULED_KEYS.remove(key);
                            return;
                        }
                        latest = LATEST_SNAPSHOTS.remove(key);
                        Integer current = SAVE_GENERATIONS.get(key);
                        generation = current == null ? 0 : current.intValue();
                        if (latest == null) {
                            LATEST_CONTEXTS.remove(key);
                            SCHEDULED_KEYS.remove(key);
                            return;
                        }
                    }
                    writeSnapshot(context, key, latest, generation);
                }
            }
        });
    }

    private static void writeSnapshot(Context context, String key, List<?> snapshot, int generation) {
        // 锁内快速校验代际：防止陈旧快照覆盖新快照。
        synchronized (SAVE_LOCK) {
            Integer current = SAVE_GENERATIONS.get(key);
            if (current == null || current.intValue() != generation) return;
        }
        // 锁外执行慢 IO（SQLite 整会话 DELETE+INSERT）：避免阻塞主线程的
        // enqueueLatestSnapshot（它持有 SAVE_LOCK 仅做入队，不能等磁盘写入）。
        // 单线程 SAVE_EXECUTOR 保证同一/不同会话的写盘串行，无并发覆盖风险。
        persistToDb(context, key, snapshot);
        // 写盘成功后同步更新读缓存，保证下次读取直接命中最新数据。
        putReadCache(key, snapshot);
        // 迁移完成后清理旧 JSON 键（幂等）。
        removeLegacyJsonKey(context, key);
    }

    /** 按 key 前缀路由到 SQLite 行存储。 */
    @SuppressWarnings("unchecked")
    private static void persistToDb(Context context, String key, List<?> snapshot) {
        try {
            if (key.startsWith(KEY_GROUP_PREFIX)) {
                String groupId = key.substring(KEY_GROUP_PREFIX.length());
                MessageDbStore.saveGroup(context, groupId, (List<GroupMessage>) snapshot);
            } else if (key.startsWith(KEY_DIRECT_NCUID_PREFIX)) {
                String peerKey = key.substring(KEY_DIRECT_NCUID_PREFIX.length());
                MessageDbStore.saveDirect(context, peerKey, (List<Message>) snapshot);
            } else if (key.startsWith(KEY_DIRECT_PREFIX)) {
                String peerKey = key.substring(KEY_DIRECT_PREFIX.length());
                MessageDbStore.saveDirect(context, peerKey, (List<Message>) snapshot);
            }
        } catch (Exception ignored) {
        }
    }

    /** 旧 JSON 键迁移后删除（仅当 SQLite 已成功写入）。 */
    private static void removeLegacyJsonKey(Context context, String key) {
        try {
            if (context != null) {
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().remove(key).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static <T> List<T> read(Context context, String key, Type type) {
        // 优先读内存缓存，避免重复全量反序列化。
        synchronized (READ_CACHE) {
            List<?> hit = READ_CACHE.get(key);
            if (hit != null) {
                @SuppressWarnings("unchecked")
                List<T> copy = copyListSafe((List<T>) hit);
                return copy;
            }
        }
        List<T> list = readFromDb(context, key, type);
        if (list.isEmpty()) {
            // SQLite 无数据：回退旧 JSON（迁移路径），读到后写 SQLite 并删旧键。
            List<T> legacy = readFromDisk(context, key, type);
            if (!legacy.isEmpty()) {
                list = legacy;
                putReadCache(key, legacy);
                persistToDb(context, key, legacy);
                removeLegacyJsonKey(context, key);
            }
        } else if (!list.isEmpty()) {
            putReadCache(key, list);
        }
        return list;
    }

    /** 从 SQLite 行存储读取（按 created_at 升序）。 */
    @SuppressWarnings("unchecked")
    private static <T> List<T> readFromDb(Context context, String key, Type type) {
        if (context == null) {
            return new ArrayList<T>();
        }
        try {
            if (key.startsWith(KEY_GROUP_PREFIX)) {
                String groupId = key.substring(KEY_GROUP_PREFIX.length());
                return (List<T>) MessageDbStore.getGroup(context, groupId);
            }
            String peerKey = key.startsWith(KEY_DIRECT_NCUID_PREFIX)
                    ? key.substring(KEY_DIRECT_NCUID_PREFIX.length())
                    : key.substring(KEY_DIRECT_PREFIX.length());
            return (List<T>) MessageDbStore.getDirect(context, peerKey);
        } catch (Exception e) {
            return new ArrayList<T>();
        }
    }

    /** 旧 SharedPreferences JSON 读取（仅迁移路径使用）。 */
    @SuppressWarnings("unchecked")
    private static <T> List<T> readFromDisk(Context context, String key, Type type) {
        String json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(key, null);
        if (json == null) return new ArrayList<>();
        try {
            List<T> list = (List<T>) GSON.fromJson(json, type);
            return list == null ? new ArrayList<T>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void putReadCache(String key, List<?> list) {
        synchronized (READ_CACHE) {
            // 存列表副本，避免与 LATEST_SNAPSHOTS 共享同一 List 对象：
            // 写盘线程序列化期间若调用方修改列表结构，不会污染待写快照。
            READ_CACHE.put(key, new ArrayList<Object>(list));
        }
    }

    private static void removeReadCache(String key) {
        synchronized (READ_CACHE) {
            READ_CACHE.remove(key);
        }
    }

    private static void clearReadCache() {
        synchronized (READ_CACHE) {
            READ_CACHE.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> copyListSafe(List<T> source) {
        if (source == null) {
            return new ArrayList<T>();
        }
        return new ArrayList<T>(source);
    }
}
