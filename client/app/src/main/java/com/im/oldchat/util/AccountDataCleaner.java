package com.im.oldchat.util;

import android.content.Context;
import com.im.oldchat.data.FriendRequestStore;
import com.im.oldchat.data.MomentNoticeStore;
import com.im.oldchat.data.NotificationReadStore;
import com.im.oldchat.models.ChatCache;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.MessageHistoryCache;
import com.im.oldchat.models.MomentCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.UserNameCache;

public class AccountDataCleaner {
    private AccountDataCleaner() {
    }

    public static void clearAll(Context context) {
        if (context == null) {
            return;
        }
        RecentChatCache.clearAll(context);
        GroupRecentChatCache.clearAll(context);
        MessageHistoryCache.clearAll(context);
        ChatCache.clearAll(context);
        FriendCache.clearAll(context);
        GroupCache.clearAll(context);
        UserNameCache.clearAll(context);
        MomentCache.clearAll(context);
        MomentNoticeStore.clearAll(context);
        NotificationReadStore.clearAll(context);
        FriendRequestStore.clearAll(context);
        AvatarSyncManager.clearAll(context);
    }
}
