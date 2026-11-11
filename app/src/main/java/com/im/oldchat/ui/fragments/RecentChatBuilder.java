package com.im.oldchat.ui.fragments;

import android.content.Context;
import com.im.oldchat.data.ChannelCacheStore;
import com.im.oldchat.models.ChannelState;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.User;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.models.UserTitleCache;
import com.im.oldchat.ui.ChatMessageUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RecentChatBuilder {
    private static final String SYSTEM_NOTIFICATION_UID = "SYSTEM";

    public static List<RecentItem> buildCombinedList(Context ctx, int unreadNotificationCount) {
        List<RecentItem> items = new ArrayList<>();
        if (ctx == null) {
            return items;
        }

        RecentItem sysNotif = new RecentItem();
        sysNotif.isSystemNotification = true;
        sysNotif.id = SYSTEM_NOTIFICATION_UID;
        sysNotif.title = "系统通知";
        sysNotif.subtitle = "官方公告和通知";
        sysNotif.timestamp = System.currentTimeMillis();
        sysNotif.unreadCount = unreadNotificationCount;

        Map<String, RecentItem> map = new HashMap<>();

        List<User> friends = FriendCache.getFriends(ctx);
        Map<String, User> friendByUid = new HashMap<>();
        Map<String, User> friendByNcuid = new HashMap<>();
        Map<String, String> friendIdToUid = new HashMap<>();
        Set<String> friendUids = new HashSet<>();
        Set<String> friendIds = new HashSet<>();
        for (User u : friends) {
            if (u == null) {
                continue;
            }
            if (u.uid != null && !u.uid.isEmpty()) {
                friendByUid.put(u.uid, u);
                friendUids.add(u.uid);
            }
            if (u.ncuid != null && !u.ncuid.isEmpty()) {
                friendByNcuid.put(u.ncuid.trim().toUpperCase(Locale.US), u);
            }
            if (u.id != null && !u.id.isEmpty()) {
                friendIds.add(u.id);
                if (u.uid != null && !u.uid.isEmpty()) {
                    friendIdToUid.put(u.id, u.uid);
                }
            }
        }

        List<RecentChatCache.RecentChat> direct = RecentChatCache.getRecentChats(ctx);
        for (RecentChatCache.RecentChat chat : direct) {
            String id = chat.friendUID != null && !chat.friendUID.isEmpty() ? chat.friendUID : chat.friendId;
            if (id == null || id.isEmpty()) {
                continue;
            }
            String chatNcuid = chat.friendNcuid == null ? "" : chat.friendNcuid;
            if (!RecentChatStateBinder.isKnownFriend(id, chatNcuid, friendUids, friendIds, friendByNcuid)) {
                continue;
            }
            String key = "u:" + id;
            RecentItem item = map.get(key);
            if (item == null) {
                item = new RecentItem();
                item.isGroup = false;
                item.id = id;
                item.friendNcuid = chatNcuid;
                item.timestamp = 0;
                map.put(key, item);
            } else if (item.friendNcuid == null || item.friendNcuid.isEmpty()) {
                item.friendNcuid = chatNcuid;
            }
            if (item.title == null || item.title.isEmpty()) {
                item.title = chat.friendName != null && !chat.friendName.isEmpty() ? chat.friendName : id;
            }
            if (FriendNameResolver.isLikelyMediaUrl(item.title)) {
                item.title = "";
            }
            if (item.userTitle == null || item.userTitle.isEmpty()) {
                User u = friendByUid.get(id);
                if (u == null) {
                    String aliasUid = friendIdToUid.get(id);
                    if (aliasUid != null && aliasUid.length() > 0) {
                        u = friendByUid.get(aliasUid);
                    }
                }
                if (u == null && chatNcuid.length() > 0) {
                    u = friendByNcuid.get(chatNcuid.trim().toUpperCase(Locale.US));
                }
                if (u != null && u.user_title != null && !u.user_title.isEmpty()) {
                    item.userTitle = u.user_title;
                } else {
                    item.userTitle = UserTitleCache.getTitle(ctx, id);
                }
            }
            if (item.avatarUrl == null || item.avatarUrl.isEmpty()) {
                item.avatarUrl = chat.avatarUrl;
            }
            if ((item.title == null || item.title.isEmpty()) || (item.avatarUrl == null || item.avatarUrl.isEmpty())) {
                User u = friendByUid.get(id);
                if (u == null) {
                    String aliasUid = friendIdToUid.get(id);
                    if (aliasUid != null && aliasUid.length() > 0) {
                        u = friendByUid.get(aliasUid);
                    }
                }
                if (u == null && chatNcuid.length() > 0) {
                    u = friendByNcuid.get(chatNcuid.trim().toUpperCase(Locale.US));
                }
                if (u != null) {
                    String canonicalName = FriendNameResolver.resolve(u);
                    if (canonicalName != null && !canonicalName.isEmpty()) {
                        // 好友资料是权威来源，覆盖可能已损坏的最近会话缓存标题。
                        item.title = canonicalName;
                    }
                    if (item.avatarUrl == null || item.avatarUrl.isEmpty()) {
                        item.avatarUrl = u.avatar_url;
                    }
                }
            }
            if (item.title == null || item.title.isEmpty() || FriendNameResolver.isLikelyMediaUrl(item.title)) {
                item.title = id;
            }
            com.im.oldchat.util.UserPresenceCache.Presence p = com.im.oldchat.util.UserPresenceCache.getPresence(id);
            item.isOnline = p.isOnline;
            item.presenceStatus = p.presenceStatus;
            if (chat.timestamp > item.timestamp) {
                item.timestamp = chat.timestamp;
                item.subtitle = chat.lastMessage;
            } else if (item.subtitle == null || item.subtitle.isEmpty()) {
                item.subtitle = chat.lastMessage;
            }
            if (chat.unreadCount > item.unreadCount) {
                item.unreadCount = chat.unreadCount;
            }
        }

        List<Group> groupList = GroupCache.getGroups(ctx);
        Map<String, Group> groupById = new HashMap<>();
        for (Group g : groupList) {
            if (g == null || g.id == null || g.id.isEmpty()) {
                continue;
            }
            groupById.put(normalizeGroupId(g.id), g);
        }

        List<GroupRecentChatCache.RecentGroup> groups = GroupRecentChatCache.getRecentGroups(ctx);
        for (GroupRecentChatCache.RecentGroup g : groups) {
            if (g.groupId == null || g.groupId.isEmpty()) {
                continue;
            }
            String normalizedGroupId = normalizeGroupId(g.groupId);
            if (!groupById.containsKey(normalizedGroupId)) {
                continue;
            }
            String key = "g:" + normalizedGroupId;
            RecentItem item = map.get(key);
            if (item == null) {
                item = new RecentItem();
                item.isGroup = true;
                item.id = g.groupId;
                item.timestamp = 0;
                map.put(key, item);
            }
            if (item.title == null || item.title.isEmpty()) {
                item.title = g.groupName != null && !g.groupName.isEmpty() ? g.groupName : g.groupId;
            }
            if (item.avatarUrl == null || item.avatarUrl.isEmpty()) {
                item.avatarUrl = g.avatarUrl;
            }
            if (item.groupMemberCount <= 0 && g.memberCount > 0) {
                item.groupMemberCount = g.memberCount;
            }
            if (g.timestamp > item.timestamp) {
                item.timestamp = g.timestamp;
                item.subtitle = g.lastMessage;
            } else if (item.subtitle == null || item.subtitle.isEmpty()) {
                item.subtitle = g.lastMessage;
            }
            if (g.unreadCount > item.unreadCount) {
                item.unreadCount = g.unreadCount;
            }
            item.groupRole = g.role;
            item.mentionUnread = g.mentionUnread;
        }

        List<String> directIds = MessageHistoryCache.getDirectChatIds(ctx);
        for (String uid : directIds) {
            if (uid == null || uid.isEmpty()) {
                continue;
            }
            if (!RecentChatStateBinder.isKnownFriend(uid, friendUids, friendIds)) {
                continue;
            }
            String key = "u:" + uid;
            if (map.containsKey(key)) {
                continue;
            }
            List<Message> history = MessageHistoryCache.getDirectMessages(ctx, uid);
            if (history.isEmpty()) {
                continue;
            }
            Message last = history.get(history.size() - 1);
            RecentItem item = new RecentItem();
            item.isGroup = false;
            item.id = uid;
            User u = friendByUid.get(uid);
            if (u != null) {
                item.title = FriendNameResolver.resolve(u);
                item.avatarUrl = u.avatar_url;
                item.friendNcuid = u.ncuid;
            }
            if (item.title == null || item.title.isEmpty()) {
                String cachedName = UserNameCache.getName(ctx, uid);
                item.title = cachedName != null && !cachedName.isEmpty() ? cachedName : uid;
            }
            item.subtitle = ChatMessageUtil.previewForMessage(last);
            item.timestamp = ChatMessageUtil.normalizeTimestamp(last.created_at);
            item.unreadCount = 0;
            com.im.oldchat.util.UserPresenceCache.Presence p = com.im.oldchat.util.UserPresenceCache.getPresence(uid);
            item.isOnline = p.isOnline;
            item.presenceStatus = p.presenceStatus;
            map.put(key, item);
        }

        // 最近会话缓存丢失时，也能从新的 NCUID 历史键恢复会话。
        List<String> directNcuids = MessageHistoryCache.getDirectChatNcuids(ctx);
        for (String ncuid : directNcuids) {
            if (ncuid == null || ncuid.isEmpty()) continue;
            User u = friendByNcuid.get(ncuid.trim().toUpperCase(Locale.US));
            if (u == null || u.uid == null || u.uid.isEmpty()) continue;
            String uid = u.uid;
            String key = "u:" + uid;
            if (map.containsKey(key)) continue;
            List<Message> history = MessageHistoryCache.getDirectMessages(ctx, uid, ncuid);
            if (history.isEmpty()) continue;
            Message last = history.get(history.size() - 1);
            RecentItem item = new RecentItem();
            item.isGroup = false;
            item.id = uid;
            item.friendNcuid = ncuid;
            item.title = FriendNameResolver.resolve(u);
            item.avatarUrl = u.avatar_url;
            item.subtitle = ChatMessageUtil.previewForMessage(last);
            item.timestamp = ChatMessageUtil.normalizeTimestamp(last.created_at);
            com.im.oldchat.util.UserPresenceCache.Presence p =
                    com.im.oldchat.util.UserPresenceCache.getPresence(uid);
            item.isOnline = p.isOnline;
            item.presenceStatus = p.presenceStatus;
            map.put(key, item);
        }

        List<String> groupIds = MessageHistoryCache.getGroupChatIds(ctx);
        for (String groupId : groupIds) {
            if (groupId == null || groupId.isEmpty()) {
                continue;
            }
            String normalizedGroupId = normalizeGroupId(groupId);
            if (!groupById.containsKey(normalizedGroupId)) {
                continue;
            }
            String key = "g:" + normalizedGroupId;
            if (map.containsKey(key)) {
                continue;
            }
            List<GroupMessage> history = MessageHistoryCache.getGroupMessages(ctx, groupId);
            if (history.isEmpty()) {
                continue;
            }
            GroupMessage last = history.get(history.size() - 1);
            RecentItem item = new RecentItem();
            item.isGroup = true;
            item.id = groupId;
            Group g = groupById.get(normalizedGroupId);
            if (g != null) {
                item.title = g.name;
                item.avatarUrl = g.avatar_url;
                item.groupRole = g.role;
                item.groupMemberCount = g.member_count;
            }
            if (item.title == null || item.title.isEmpty()) {
                item.title = groupId;
            }
            item.subtitle = ChatMessageUtil.previewForType(last.msg_type, last.body, last.burn_after_seconds);
            item.timestamp = ChatMessageUtil.normalizeTimestamp(last.created_at);
            item.unreadCount = 0;
            map.put(key, item);
        }

        List<ChannelState> channels = ChannelCacheStore.get(ctx).listStates(ctx);
        for (int i = 0; i < channels.size(); i++) {
            ChannelState channel = channels.get(i);
            if (channel == null || channel.channelId == null || channel.channelId.length() == 0) continue;
            RecentItem item = new RecentItem();
            item.isChannel = true;
            item.id = channel.channelId;
            item.title = channel.name == null || channel.name.length() == 0 ? channel.channelId : channel.name;
            item.subtitle = channel.latestText == null ? "暂无帖子" : channel.latestText;
            item.avatarUrl = channel.avatarUrl;
            item.timestamp = channel.latestTimestamp;
            item.unreadCount = channel.unreadCount;
            map.put("c:" + channel.channelId, item);
        }

        RecentChatStateBinder.bind(ctx, map, friendIdToUid);

        items.add(sysNotif);
        items.addAll(map.values());
        RecentChatStateBinder.sort(items);
        return items;
    }

    private static String normalizeGroupId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }
}
