package com.im.oldchat.ui;

import android.content.Context;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRoleCache;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.models.UserTitleCache;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.GroupAvatarCache;
import com.im.oldchat.util.MessageFieldRepair;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.UidAliasStore;
import com.im.oldchat.util.ChatOpenPerfTracer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class GroupMessageSyncHelper {

    static final class MemberMetaSnapshot {
        final int version;
        final java.util.HashMap<String, String> names;
        final java.util.HashMap<String, String> avatars;
        final java.util.HashMap<String, String> titles;
        final java.util.HashMap<String, Integer> roles;

        private MemberMetaSnapshot(int version,
                                   java.util.HashMap<String, String> names,
                                   java.util.HashMap<String, String> avatars,
                                   java.util.HashMap<String, String> titles,
                                   java.util.HashMap<String, Integer> roles) {
            this.version = version <= 0 ? 1 : version;
            this.names = names;
            this.avatars = avatars;
            this.titles = titles;
            this.roles = roles;
        }

        static MemberMetaSnapshot create(int version,
                                         Map<String, String> names,
                                         Map<String, String> avatars,
                                         Map<String, String> titles,
                                         Map<String, Integer> roles) {
            return new MemberMetaSnapshot(version,
                    names == null ? new java.util.HashMap<String, String>() : new java.util.HashMap<String, String>(names),
                    avatars == null ? new java.util.HashMap<String, String>() : new java.util.HashMap<String, String>(avatars),
                    titles == null ? new java.util.HashMap<String, String>() : new java.util.HashMap<String, String>(titles),
                    roles == null ? new java.util.HashMap<String, Integer>() : new java.util.HashMap<String, Integer>(roles));
        }
    }


    static int parseEffectiveOffset(String response, int fallback) {
        try {
            JSONObject obj = new JSONObject(response);
            int value = obj.optInt("effective_offset", fallback);
            if (value < 0) {
                return 0;
            }
            return value;
        } catch (Exception e) {
            if (fallback < 0) {
                return 0;
            }
            return fallback;
        }
    }

    static final int MAX_ACTIVE_WINDOW_MESSAGES = 320;

    private GroupMessageSyncHelper() {
    }

    public static List<GroupMessage> parseIncoming(String response) throws Exception {
        JSONObject obj = new JSONObject(response);
        JSONArray arr = obj.getJSONArray("messages");
        List<GroupMessage> incoming = new ArrayList<GroupMessage>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject mObj = arr.getJSONObject(i);
            GroupMessage m = new GroupMessage();
            m.id = mObj.optString("id");
            m.group_id = mObj.optString("group_id");
            m.from_uid = mObj.optString("from_uid");
            m.sender_ncuid = mObj.optString("from_ncuid");
            m.body = mObj.optString("body");
            m.msg_type = mObj.optString("msg_type", "text");
            m.media_url = mObj.optString("media_url");
            m.thumb_url = mObj.optString("thumb_url");
            m.original_url = mObj.optString("original_url");
            m.duration_ms = mObj.optInt("duration_ms", 0);
            m.burn_after_seconds = BurnAfterSecondsUtil.normalize(mObj.optInt("burn_after_seconds", 0));
            m.created_at = ChatMessageUtil.sanitizeTimestamp(mObj.optLong("created_at", 0));
            m.sort_seq = mObj.optLong("sort_seq", 0L);
            m.group_seq = mObj.optLong("group_seq", 0L);
            m.read_count = mObj.optInt("read_count", 0);
            MessageFieldRepair.repairGroup(m);
            incoming.add(m);
        }
        Collections.reverse(incoming);
        return incoming;
    }

    static GroupMessageLoadResult parseLoadResult(String response, int fallbackOffset) {
        return parseLoadResult(response, fallbackOffset, null, null, null, 0L);
    }

    static GroupMessageLoadResult parseLoadResult(String response, int fallbackOffset,
                                                  Context context,
                                                  MemberMetaSnapshot memberMetaSnapshot,
                                                  ChatOpenPerfTracer perfTracer,
                                                  long parseStartedAtMs) {
        GroupMessageParseTrace parseTrace = new GroupMessageParseTrace(perfTracer, parseStartedAtMs);
        try {
            parseTrace.markJsonStart();
            JSONObject obj = new JSONObject(response);
            JSONArray arr = obj.getJSONArray("messages");
            parseTrace.markLoopStart();
            List<GroupMessage> incoming = new ArrayList<GroupMessage>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mObj = arr.getJSONObject(i);
                GroupMessage m = new GroupMessage();
                m.id = mObj.optString("id");
                m.group_id = mObj.optString("group_id");
                m.from_uid = mObj.optString("from_uid");
                m.sender_ncuid = mObj.optString("from_ncuid");
                m.body = mObj.optString("body");
                m.msg_type = mObj.optString("msg_type", "text");
                m.media_url = mObj.optString("media_url");
                m.thumb_url = mObj.optString("thumb_url");
                m.original_url = mObj.optString("original_url");
                m.duration_ms = mObj.optInt("duration_ms", 0);
                m.burn_after_seconds = BurnAfterSecondsUtil.normalize(mObj.optInt("burn_after_seconds", 0));
                m.created_at = ChatMessageUtil.sanitizeTimestamp(mObj.optLong("created_at", 0));
                m.sort_seq = mObj.optLong("sort_seq", 0L);
            m.group_seq = mObj.optLong("group_seq", 0L);
                m.read_count = mObj.optInt("read_count", 0);
                long repairStartedAtMs = android.os.SystemClock.uptimeMillis();
                MessageFieldRepair.repairGroup(m);
                parseTrace.addRepairCost(android.os.SystemClock.uptimeMillis() - repairStartedAtMs);
                long metaStartedAtMs = android.os.SystemClock.uptimeMillis();
                prewarmMemberMetaFast(context, m, memberMetaSnapshot, parseTrace);
                parseTrace.addMetaCost(android.os.SystemClock.uptimeMillis() - metaStartedAtMs);
                incoming.add(m);
            }
            Collections.reverse(incoming);
            int effectiveOffset = obj.optInt("effective_offset", fallbackOffset);
            if (effectiveOffset < 0) {
                effectiveOffset = 0;
            }
            Boolean hasMore = obj.has("has_more")
                    ? Boolean.valueOf(obj.optBoolean("has_more", false)) : null;
            parseTrace.finish(incoming.size());
            return new GroupMessageLoadResult(incoming, effectiveOffset, hasMore, null);
        } catch (Exception e) {
            return new GroupMessageLoadResult(null, Math.max(0, fallbackOffset), null, e);
        }
    }

    static int countIncomingForBubble(Context context, String myUid, List<GroupMessage> incoming,
                                      HashSet<String> messageIds) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        int count = 0;
        HashSet<String> existingIds = new HashSet<String>(messageIds);
        for (GroupMessage msg : incoming) {
            if (msg.id == null || existingIds.contains(msg.id)) {
                continue;
            }
            if (!com.im.oldchat.util.NcuidIdentity.isMine(
                    context, msg.sender_ncuid, msg.from_uid, myUid)) {
                count++;
            }
        }
        return count;
    }

    static int trimOldestInMemory(List<GroupMessage> messageList, HashSet<String> messageIds, int maxCount) {
        if (messageList == null || messageIds == null || maxCount <= 0) {
            return 0;
        }
        int size = messageList.size();
        if (size <= maxCount) {
            return 0;
        }
        int removeCount = size - maxCount;
        List<GroupMessage> removed = new ArrayList<GroupMessage>(messageList.subList(0, removeCount));
        messageList.subList(0, removeCount).clear();
        for (GroupMessage msg : removed) {
            if (msg != null && msg.id != null && msg.id.length() > 0) {
                messageIds.remove(msg.id);
            }
        }
        return removeCount;
    }

    public static void sortMessages(List<GroupMessage> messageList) {
        if (messageList == null || messageList.size() <= 1) {
            return;
        }
        Collections.sort(messageList, new Comparator<GroupMessage>() {
            @Override
            public int compare(GroupMessage m1, GroupMessage m2) {
                return compareGroupMessage(m1, m2);
            }
        });
    }

    static GroupMessageMergeResult mergeAppend(List<GroupMessage> messageList, HashSet<String> messageIds,
                                               List<GroupMessage> incoming, int currentOffset,
                                               int pageLimit) {
        List<GroupMessage> toAdd = new ArrayList<GroupMessage>();
        for (GroupMessage msg : incoming) {
            if (msg.id == null || !messageIds.contains(msg.id)) {
                toAdd.add(msg);
            }
        }
        if (!toAdd.isEmpty()) {
            messageList.addAll(0, toAdd);
            for (GroupMessage msg : toAdd) {
                if (msg.id != null) {
                    messageIds.add(msg.id);
                }
            }
        }
        int nextOffset = currentOffset + incoming.size();
        // OFFSET 分页遇到实时新消息时可能整页重复；只要服务端仍返回满页，
        // 就继续推进 offset，不能因为本页没有新增项而错误地关闭“加载更多”。
        boolean hasMore = incoming.size() >= pageLimit;
        return new GroupMessageMergeResult(nextOffset, hasMore, false,
                false, toAdd.size(), false);
    }

    static GroupMessageMergeResult mergeRefresh(List<GroupMessage> messageList, HashSet<String> messageIds,
                                                List<GroupMessage> incoming, int pageLimit,
                                                boolean hasMore) {
        if (incoming == null || incoming.isEmpty()) {
            int currentOffset = messageList == null ? 0 : messageList.size();
            boolean nextHasMore = currentOffset > 0 && hasMore;
            return new GroupMessageMergeResult(currentOffset, nextHasMore, false,
                    false, 0, false);
        }
        boolean hasOverlap = false;
        if (!messageList.isEmpty()) {
            for (GroupMessage newMsg : incoming) {
                if (newMsg.id != null && messageIds.contains(newMsg.id)) {
                    hasOverlap = true;
                    break;
                }
            }
        } else {
            hasOverlap = true;
        }
        boolean gapDetected = !hasOverlap && incoming.size() >= pageLimit;
        if (gapDetected) {
            // 不再直接清空：保留当前列表中不在 incoming 里的消息，避免 WS 插入的消息丢失。
            java.util.HashSet<String> incomingIds = new java.util.HashSet<String>();
            for (GroupMessage msg : incoming) {
                if (msg != null && msg.id != null) incomingIds.add(msg.id);
            }
            java.util.List<GroupMessage> preserved = new java.util.ArrayList<GroupMessage>();
            for (GroupMessage msg : messageList) {
                if (msg != null && msg.id != null && !incomingIds.contains(msg.id)) {
                    preserved.add(msg);
                }
            }
            messageList.clear();
            messageIds.clear();
            messageList.addAll(preserved);
            messageList.addAll(incoming);
            for (GroupMessage msg : messageList) {
                if (msg != null && msg.id != null) {
                    messageIds.add(msg.id);
                }
            }
            return new GroupMessageMergeResult(messageList.size(), true, true,
                    false, incoming.size(), false);
        }
        boolean appendedTailOnly = canAppendTailOnly(messageList, messageIds, incoming);
        boolean updatedExisting = false;
        int insertedCount = 0;
        for (GroupMessage msg : incoming) {
            if (msg.id != null && messageIds.contains(msg.id)) {
                for (GroupMessage existing : messageList) {
                    if (existing.id != null && existing.id.equals(msg.id)) {
                        existing.from_uid = msg.from_uid;
                        existing.sender_ncuid = msg.sender_ncuid;
                        existing.body = msg.body;
                        existing.msg_type = msg.msg_type;
                        existing.thumb_url = msg.thumb_url;
                        existing.original_url = msg.original_url;
                        existing.duration_ms = msg.duration_ms;
                        existing.burn_after_seconds = msg.burn_after_seconds;
                        existing.burn_preview_text = msg.burn_preview_text;
                        existing.created_at = msg.created_at;
                        existing.read_count = msg.read_count;
                        existing.media_url = msg.media_url;
                        existing.sort_seq = msg.sort_seq;
                        existing.group_seq = msg.group_seq;
                        copyPrewarmedMeta(existing, msg);
                        updatedExisting = true;
                        break;
                    }
                }
            } else {
                messageList.add(msg);
                insertedCount++;
                if (msg.id != null) {
                    messageIds.add(msg.id);
                }
            }
        }
        if (!appendedTailOnly) {
            sortMessages(messageList);
        }
        return new GroupMessageMergeResult(messageList.size(), hasMore, false,
                appendedTailOnly, insertedCount, updatedExisting);
    }

    private static boolean canAppendTailOnly(List<GroupMessage> messageList,
                                             HashSet<String> messageIds,
                                             List<GroupMessage> incoming) {
        if (messageList == null || messageList.isEmpty()) {
            return false;
        }
        GroupMessage tail = messageList.get(messageList.size() - 1);
        for (int i = 0; i < incoming.size(); i++) {
            GroupMessage msg = incoming.get(i);
            if (msg == null) {
                continue;
            }
            if (msg.id != null && messageIds.contains(msg.id)) {
                continue;
            }
            if (compareGroupMessage(msg, tail) < 0) {
                return false;
            }
            tail = msg;
        }
        return true;
    }

    private static int compareGroupMessage(GroupMessage m1, GroupMessage m2) {
        if (m1 == m2) {
            return 0;
        }
        if (m1 == null) {
            return -1;
        }
        if (m2 == null) {
            return 1;
        }
        if (m1.created_at < m2.created_at) {
            return -1;
        }
        if (m1.created_at > m2.created_at) {
            return 1;
        }
        long seq1 = m1.sort_seq;
        long seq2 = m2.sort_seq;
        if (seq1 > 0L && seq2 > 0L) {
            if (seq1 < seq2) {
                return -1;
            }
            if (seq1 > seq2) {
                return 1;
            }
        }
        int idCmp = safeString(m1.id).compareTo(safeString(m2.id));
        if (idCmp != 0) {
            return idCmp;
        }
        return safeString(m1.from_uid).compareTo(safeString(m2.from_uid));
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    static void prewarmMessages(Context context, List<GroupMessage> messages,
                                MemberMetaSnapshot memberMetaSnapshot) {
        if (messages == null || messages.isEmpty() || memberMetaSnapshot == null) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            prewarmMemberMeta(context, messages.get(i), memberMetaSnapshot);
        }
    }

    private static void prewarmMemberMetaFast(Context context, GroupMessage message,
                                              MemberMetaSnapshot memberMetaSnapshot,
                                              GroupMessageParseTrace parseTrace) {
        if (message == null || memberMetaSnapshot == null) {
            return;
        }
        String rawUid = safeString(message.from_uid);
        String canonicalUid = rawUid;
        if (context != null && rawUid.length() > 0) {
            String resolved = parseTrace == null
                    ? UidAliasStore.resolve(context, rawUid)
                    : parseTrace.resolveCanonicalUid(context, rawUid);
            if (resolved != null && resolved.length() > 0) {
                canonicalUid = resolved;
            }
        }
        String name = firstNonEmpty(memberMetaSnapshot.names.get(canonicalUid),
                memberMetaSnapshot.names.get(rawUid), canonicalUid, rawUid);
        String title = firstNonEmpty(memberMetaSnapshot.titles.get(canonicalUid),
                memberMetaSnapshot.titles.get(rawUid), "", "");
        Integer role = memberMetaSnapshot.roles.get(canonicalUid);
        if (role == null) {
            role = memberMetaSnapshot.roles.get(rawUid);
        }
        String avatarUrl = firstNonEmpty(memberMetaSnapshot.avatars.get(canonicalUid),
                memberMetaSnapshot.avatars.get(rawUid), "", "");
        applyPrewarmedMeta(message, memberMetaSnapshot.version, rawUid, canonicalUid,
                name, title, role, avatarUrl);
    }

    private static void prewarmMemberMeta(Context context, GroupMessage message,
                                          MemberMetaSnapshot memberMetaSnapshot) {
        if (message == null || memberMetaSnapshot == null) {
            return;
        }
        String rawUid = !safeString(message.sender_ncuid).isEmpty()
                ? safeString(message.sender_ncuid) : safeString(message.from_uid);
        String canonicalUid = rawUid;
        if (context != null && rawUid.length() > 0) {
            String resolved = UidAliasStore.resolve(context, rawUid);
            if (resolved != null && resolved.length() > 0) {
                canonicalUid = resolved;
            }
        }
        String name = memberMetaSnapshot.names.get(canonicalUid);
        if (name == null || name.length() == 0) {
            name = memberMetaSnapshot.names.get(rawUid);
        }
        if ((name == null || name.length() == 0) && message.from_uid != null) {
            name = memberMetaSnapshot.names.get(message.from_uid);
        }
        if ((name == null || name.length() == 0) && context != null) {
            name = UserNameCache.getName(context, canonicalUid);
        }
        if ((name == null || name.length() == 0) && context != null) {
            name = UserNameCache.getName(context, rawUid);
        }
        if ((name == null || name.length() == 0) && context != null && message.from_uid != null) {
            name = UserNameCache.getName(context, message.from_uid);
        }
        if ((name == null || name.length() == 0) && context != null) {
            com.im.oldchat.models.GroupMember m = com.im.oldchat.models.GroupMemberMetaCache.getMemberByIdentity(context, message.group_id, rawUid);
            if (m == null && message.from_uid != null) {
                m = com.im.oldchat.models.GroupMemberMetaCache.getMemberByIdentity(context, message.group_id, message.from_uid);
            }
            if (m != null) {
                name = com.im.oldchat.models.FriendNameResolver.resolve("", m.display_name, m.username, m.uid);
            }
        }
        if (name == null || name.length() == 0) {
            name = canonicalUid;
        }
        String title = memberMetaSnapshot.titles.get(canonicalUid);
        if (title == null || title.length() == 0) {
            title = memberMetaSnapshot.titles.get(rawUid);
        }
        if ((title == null || title.length() == 0) && context != null) {
            title = UserTitleCache.getTitle(context, canonicalUid);
        }
        if ((title == null || title.length() == 0) && context != null) {
            title = UserTitleCache.getTitle(context, rawUid);
        }
        Integer role = memberMetaSnapshot.roles.get(canonicalUid);
        if (role == null) {
            role = memberMetaSnapshot.roles.get(rawUid);
        }
        if (role == null && context != null) {
            int cachedRole = GroupRoleCache.getRole(context, message.group_id, canonicalUid);
            if (cachedRole > 0) {
                role = Integer.valueOf(cachedRole);
            }
        }
        if (role == null && context != null) {
            int cachedRole = GroupRoleCache.getRole(context, message.group_id, rawUid);
            if (cachedRole > 0) {
                role = Integer.valueOf(cachedRole);
            }
        }
        String avatarUrl = memberMetaSnapshot.avatars.get(canonicalUid);
        if (avatarUrl == null || avatarUrl.length() == 0) {
            avatarUrl = memberMetaSnapshot.avatars.get(rawUid);
        }
        if ((avatarUrl == null || avatarUrl.length() == 0) && context != null) {
            avatarUrl = GroupAvatarCache.getCachedAvatar(context, canonicalUid);
        }
        if ((avatarUrl == null || avatarUrl.length() == 0) && context != null) {
            avatarUrl = GroupAvatarCache.getCachedAvatar(context, rawUid);
        }
        applyPrewarmedMeta(message, memberMetaSnapshot.version, rawUid, canonicalUid,
                name, title, role, avatarUrl);
    }

    private static void applyPrewarmedMeta(GroupMessage message, int version,
                                           String rawUid, String canonicalUid,
                                           String name, String title,
                                           Integer role, String avatarUrl) {
        message.cachedMemberMetaVersion = version;
        message.cachedMemberMetaSourceUid = rawUid;
        message.cachedCanonicalUid = canonicalUid;
        message.cachedSenderName = name == null ? "" : name;
        message.cachedSenderTitle = title == null ? "" : title;
        message.cachedSenderRole = role == null ? 0 : role.intValue();
        message.cachedAvatarSourceUid = canonicalUid;
        message.cachedAvatarUrl = avatarUrl == null ? "" : avatarUrl;
    }

    private static String firstNonEmpty(String first, String second, String third, String fourth) {
        if (first != null && first.length() > 0) {
            return first;
        }
        if (second != null && second.length() > 0) {
            return second;
        }
        if (third != null && third.length() > 0) {
            return third;
        }
        if (fourth != null && fourth.length() > 0) {
            return fourth;
        }
        return "";
    }

    private static void copyPrewarmedMeta(GroupMessage target, GroupMessage source) {
        if (target == null || source == null) {
            return;
        }
        target.cachedMemberMetaVersion = source.cachedMemberMetaVersion;
        target.cachedMemberMetaSourceUid = source.cachedMemberMetaSourceUid;
        target.cachedCanonicalUid = source.cachedCanonicalUid;
        target.cachedSenderName = source.cachedSenderName;
        target.cachedSenderTitle = source.cachedSenderTitle;
        target.cachedSenderRole = source.cachedSenderRole;
        target.cachedAvatarSourceUid = source.cachedAvatarSourceUid;
        target.cachedAvatarUrl = source.cachedAvatarUrl;
    }

    static final class GroupMessageLoadResult {
        final List<GroupMessage> incoming;
        final int effectiveOffset;
        final Boolean hasMore;
        final Exception error;
        List<GroupMessage> preSortedList;
        java.util.HashSet<String> preSortedIds;
        int newMessageCount;
        // 后台 diff 结果
        boolean isAppendOnly;
        int insertPosition;
        int insertCount;
        int oldSize;

        GroupMessageLoadResult(List<GroupMessage> incoming, int effectiveOffset,
                               Boolean hasMore, Exception error) {
            this.incoming = incoming;
            this.effectiveOffset = effectiveOffset;
            this.hasMore = hasMore;
            this.error = error;
        }
    }
}
