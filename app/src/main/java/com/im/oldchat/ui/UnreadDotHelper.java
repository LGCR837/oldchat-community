package com.im.oldchat.ui;

import android.content.Context;

import com.im.oldchat.api.WSManager;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.User;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class UnreadDotHelper {
    private UnreadDotHelper() {
    }

    static WSManager.Listener createBackDotListener(final Runnable onUnreadChanged) {
        return new WSManager.Listener() {
            @Override
            public void onDirectMessage(com.im.oldchat.api.WSModels.DirectMessage message) {
                notifyChanged(onUnreadChanged);
            }

            @Override
            public void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt) {
                notifyChanged(onUnreadChanged);
            }

            @Override
            public void onDirectRecall(com.im.oldchat.api.WSModels.DirectRecall recall) {
            }

            @Override
            public void onGroupMessage(com.im.oldchat.api.WSModels.GroupMessage message) {
                notifyChanged(onUnreadChanged);
            }

            @Override
            public void onGroupRecall(com.im.oldchat.api.WSModels.GroupRecall recall) {
            }

            @Override
            public void onTyping(com.im.oldchat.api.WSModels.TypingEvent event) {
            }

            @Override
            public void onPresence(com.im.oldchat.api.WSModels.PresenceEvent event) {
            }

            @Override
            public void onConnectionChanged(boolean connected) {
            }
        };
    }

    static boolean hasOtherUnread(Context context, String excludeDirectUid, String excludeGroupId) {
        if (context == null) {
            return false;
        }

        Set<String> friendKeys = new HashSet<String>();
        List<User> friends = FriendCache.getFriends(context);
        if (friends != null) {
            for (int i = 0; i < friends.size(); i++) {
                User user = friends.get(i);
                if (user == null) {
                    continue;
                }
                if (user.uid != null && user.uid.length() > 0) {
                    friendKeys.add(user.uid);
                }
                if (user.id != null && user.id.length() > 0) {
                    friendKeys.add(user.id);
                }
            }
        }

        Set<String> groupIds = new HashSet<String>();
        List<Group> groupsMeta = GroupCache.getGroups(context);
        if (groupsMeta != null) {
            for (int i = 0; i < groupsMeta.size(); i++) {
                Group group = groupsMeta.get(i);
                if (group != null && group.id != null && group.id.length() > 0) {
                    groupIds.add(group.id);
                }
            }
        }

        String excludeDirectAlias = "";
        if (excludeDirectUid != null && excludeDirectUid.length() > 0 && friends != null) {
            for (int i = 0; i < friends.size(); i++) {
                User user = friends.get(i);
                if (user == null || user.uid == null || user.uid.length() == 0) {
                    continue;
                }
                if (excludeDirectUid.equals(user.uid)) {
                    excludeDirectAlias = user.id == null ? "" : user.id;
                    break;
                }
            }
        }

        List<RecentChatCache.RecentChat> chats = RecentChatCache.getRecentChats(context);
        if (chats != null) {
            for (int i = 0; i < chats.size(); i++) {
                RecentChatCache.RecentChat c = chats.get(i);
                if (c == null || c.unreadCount <= 0) {
                    continue;
                }
                String key = c.friendUID != null && c.friendUID.length() > 0 ? c.friendUID : c.friendId;
                if (key == null || key.length() == 0) {
                    continue;
                }
                if (!friendKeys.isEmpty() && !friendKeys.contains(key)) {
                    continue;
                }
                if (excludeDirectUid != null && excludeDirectUid.length() > 0) {
                    if (excludeDirectUid.equals(key)) {
                        continue;
                    }
                    if (excludeDirectAlias.length() > 0 && excludeDirectAlias.equals(key)) {
                        continue;
                    }
                }
                return true;
            }
        }

        List<GroupRecentChatCache.RecentGroup> groups = GroupRecentChatCache.getRecentGroups(context);
        if (groups != null) {
            for (int i = 0; i < groups.size(); i++) {
                GroupRecentChatCache.RecentGroup g = groups.get(i);
                if (g == null) {
                    continue;
                }
                if (g.groupId == null || g.groupId.length() == 0) {
                    continue;
                }
                if (!groupIds.isEmpty() && !groupIds.contains(g.groupId)) {
                    continue;
                }
                if (excludeGroupId != null && excludeGroupId.equals(g.groupId)) {
                    continue;
                }
                if (g.unreadCount > 0 || g.mentionUnread) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void notifyChanged(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }
}
