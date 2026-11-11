package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.api.WSModels;
import com.im.oldchat.ui.ChatActivity;
import com.im.oldchat.ui.GroupChatActivity;
import com.im.oldchat.ui.NotificationChatActivity;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.GroupRecentChatCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class ChatsFragment extends Fragment implements PastelHomeUiController.Callbacks {
    private static final long RELOAD_DEBOUNCE_MS = 220L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingReload;
    private boolean loadRunning;
    private boolean reloadAfterLoad;
    private String token = "";
    private boolean wsConnected;
    private PastelHomeUiController ui;
    private PopupWindow quickMenuPopup;

    private final WSManager.Listener wsListener = new WSManager.Listener() {
        @Override public void onDirectMessage(WSModels.DirectMessage message) { scheduleReload(RELOAD_DEBOUNCE_MS); }
        @Override public void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt) { }
        @Override public void onDirectRecall(WSModels.DirectRecall recall) { scheduleReload(RELOAD_DEBOUNCE_MS); }
        @Override public void onGroupMessage(WSModels.GroupMessage message) { scheduleReload(RELOAD_DEBOUNCE_MS); }
        @Override public void onGroupRecall(WSModels.GroupRecall recall) { scheduleReload(RELOAD_DEBOUNCE_MS); }
        @Override public void onTyping(WSModels.TypingEvent event) { }
        @Override public void onPresence(WSModels.PresenceEvent event) {
            if (event != null && event.uid != null) {
                com.im.oldchat.util.UserPresenceCache.put(event.uid, event.isOnline, event.presenceStatus);
                scheduleReload(RELOAD_DEBOUNCE_MS);
            }
        }
        @Override public void onConnectionChanged(boolean connected) {
            wsConnected = connected;
            if (ui != null) {
                ui.setWsConnected(connected);
            }
            if (connected) {
                syncUnread();
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chats, container, false);
        if (com.im.oldchat.util.ThemeColorUtil.isMinimalDark(view.getContext())) {
            view.setBackgroundColor(0xFF08090A);
        }
        ui = new PastelHomeUiController(view, null, this);
        ui.setWsConnected(wsConnected);
        ArrayList<RecentItem> cachedNews = new ArrayList<RecentItem>();
        ArrayList<RecentItem> cachedChats = new ArrayList<RecentItem>();
        ArrayList<RecentItem> cachedFolded = new ArrayList<RecentItem>();
        Context appContext = view.getContext().getApplicationContext();
        List<RecentItem> cachedAll = RecentSnapshotStore.load(appContext);
        List<RecentCustomGroupStore.Group> cachedGroups = RecentCustomGroupStore.list(appContext);
        Map<String, List<RecentItem>> cachedCustom = newCustomBuckets(cachedGroups);
        splitSections(appContext, cachedAll,
                com.im.oldchat.util.SettingsPrefs.isHomeNewsEnabled(appContext),
                cachedNews, cachedChats, cachedFolded, cachedCustom);
        ui.renderSections(cachedNews, cachedChats, cachedFolded, cachedGroups, cachedCustom);
        View messageTitle = view.findViewById(R.id.tvHomeMessageTitle);
        if (messageTitle != null) {
            messageTitle.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmClearAllUnread();
                    return true;
                }
            });
        }
        View btnAdd = view.findViewById(R.id.btnChatHeaderAdd);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showQuickMenu(v);
                }
            });
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        wsConnected = WSManager.getInstance().isConnected();
        WSManager.getInstance().addListener(wsListener);
        if (ui != null) {
            ui.setWsConnected(wsConnected);
        }
        refreshRecents();
        syncUnread();
    }

    private static java.lang.ref.WeakReference<ChatsFragment> sInstance;

    public static void notifyRecentsUpdate() {
        if (sInstance != null) {
            ChatsFragment fragment = sInstance.get();
            if (fragment != null && fragment.isAdded()) {
                fragment.refreshRecents();
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new java.lang.ref.WeakReference<ChatsFragment>(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        WSManager.getInstance().removeListener(wsListener);
        dismissQuickMenu();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dismissQuickMenu();
        if (pendingReload != null) {
            mainHandler.removeCallbacks(pendingReload);
            pendingReload = null;
        }
    }

    public void refreshRecents() {
        if (!isAdded() || getActivity() == null) {
            return;
        }
        SharedPreferences prefs = getActivity().getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        scheduleReload(0L);
    }

    private void scheduleReload(long delayMs) {
        if (pendingReload != null) {
            mainHandler.removeCallbacks(pendingReload);
        }
        pendingReload = new Runnable() {
            @Override public void run() { pendingReload = null; loadRecentsAsync(); }
        };
        if (delayMs <= 0L) {
            mainHandler.post(pendingReload);
        } else {
            mainHandler.postDelayed(pendingReload, delayMs);
        }
    }

    private void loadRecentsAsync() {
        if (!isAdded() || getActivity() == null) {
            return;
        }
        if (loadRunning) {
            reloadAfterLoad = true;
            return;
        }
        loadRunning = true;
        final Context app = getActivity().getApplicationContext();
        com.im.oldchat.api.ChannelSyncManager.syncStates(app, token, false,
                new com.im.oldchat.api.ChannelSyncManager.Callback() {
                    @Override public void onComplete(boolean success) {
                        if (success && isAdded()) scheduleReload(0L);
                    }
                });
        final boolean newsEnabled = com.im.oldchat.util.SettingsPrefs.isHomeNewsEnabled(app);
        final int unreadNotifications = getUnreadNotificationCount();
        final long started = SystemClock.uptimeMillis();
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override public void run() {
                final List<RecentItem> all = RecentChatBuilder.buildCombinedList(app, unreadNotifications);
                RecentSnapshotStore.save(app, all);
                if (token != null && !token.isEmpty()) {
                    int preloadedCount = 0;
                    for (int i = 0; i < all.size() && preloadedCount < 5; i++) {
                        RecentItem it = all.get(i);
                        if (it != null && it.isGroup && it.id != null && !it.id.isEmpty()) {
                            com.im.oldchat.models.GroupMessagePreloadManager.preloadGroup(app, it.id, token, it.unreadCount);
                            preloadedCount++;
                        }
                    }
                }
                final List<RecentItem> news = new ArrayList<RecentItem>();
                final List<RecentItem> chats = new ArrayList<RecentItem>();
                final List<RecentItem> folded = new ArrayList<RecentItem>();
                final List<RecentCustomGroupStore.Group> customGroups = RecentCustomGroupStore.list(app);
                final Map<String, List<RecentItem>> customItems = newCustomBuckets(customGroups);
                splitSections(app, all, newsEnabled, news, chats, folded, customItems);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        boolean shouldReload = reloadAfterLoad;
                        reloadAfterLoad = false;
                        loadRunning = false;
                        if (!isAdded() || getActivity() == null || ui == null) {
                            return;
                        }
                        if (shouldReload) {
                            loadRecentsAsync();
                            return;
                        }
                        ui.renderSections(news, chats, folded, customGroups, customItems);
                        com.im.oldchat.util.ScreenPerfTracer.trace(getActivity(), "ChatsFragment", "recents_render", started,
                                "all=" + all.size() + " news=" + news.size() + " chats=" + chats.size() + " folded=" + folded.size());
                    }
                });
            }
        });
    }

    private void splitSections(Context context, List<RecentItem> all, boolean newsEnabled,
                               List<RecentItem> news, List<RecentItem> chats,
                               List<RecentItem> folded,
                               Map<String, List<RecentItem>> customItems) {
        for (int i = 0; i < all.size(); i++) {
            RecentItem one = all.get(i);
            if (one == null || one.isFoldedFolder) {
                continue;
            }
            String customGroupId = RecentCustomGroupStore.assignment(context, one.isGroup, one.id);
            List<RecentItem> customBucket = customItems.get(customGroupId);
            if (customBucket != null && !one.isSystemNotification) {
                customBucket.add(one);
                continue;
            }
            boolean hasUnreadSignal = one.unreadCount > 0 || one.mentionUnread;
            boolean hasNewsSignal = hasUnreadSignal && !one.muted;
            if (!newsEnabled) {
                if (one.folded) {
                    folded.add(one);
                } else if (one.isSystemNotification && !hasNewsSignal) {
                    folded.add(one);
                } else {
                    chats.add(one);
                }
                continue;
            }
            if (one.isSystemNotification) {
                if (hasNewsSignal) {
                    news.add(one);
                } else {
                    folded.add(one);
                }
                continue;
            }
            if (one.folded) {
                folded.add(one);
            } else if (hasNewsSignal) {
                news.add(one);
            } else {
                chats.add(one);
            }
        }
    }

    private Map<String, List<RecentItem>> newCustomBuckets(List<RecentCustomGroupStore.Group> groups) {
        Map<String, List<RecentItem>> result = new LinkedHashMap<String, List<RecentItem>>();
        for (int i = 0; i < groups.size(); i++) {
            result.put(groups.get(i).id, new ArrayList<RecentItem>());
        }
        return result;
    }

    @Override
    public void onRecentClick(RecentItem item) {
        onRecentClick(item, null);
    }

    @Override
    public void onRecentClick(RecentItem item, View avatarView) {
        if (item == null || getActivity() == null) {
            return;
        }
        if (item.isFoldedFolder || item.id == null || item.id.length() == 0) {
            return;
        }
        if (item.isSystemNotification) {
            Intent intent = new Intent(getActivity(), NotificationChatActivity.class);
            startActivity(intent);
            return;
        }
        if (item.isChannel) {
            Intent intent = new Intent(getActivity(), com.im.oldchat.ui.ChannelActivity.class);
            intent.putExtra(com.im.oldchat.ui.ChannelActivity.EXTRA_CHANNEL_ID, item.id);
            intent.putExtra(com.im.oldchat.ui.ChannelActivity.EXTRA_CHANNEL_NAME, item.title);
            intent.putExtra(com.im.oldchat.ui.ChannelActivity.EXTRA_CHANNEL_AVATAR, item.avatarUrl);
            startActivity(intent);
            return;
        }
        if (item.isGroup) {
            Intent intent = new Intent(getActivity(), GroupChatActivity.class);
            intent.putExtra("group_id", item.id);
            intent.putExtra("group_name", item.title);
            intent.putExtra("group_avatar", item.avatarUrl);
            intent.putExtra("group_role", item.groupRole);
            intent.putExtra("unread_count", item.unreadCount);
            startActivity(intent);
            return;
        }
        Intent intent = new Intent(getActivity(), ChatActivity.class);
        intent.putExtra("friend_uid", item.id);
        intent.putExtra("friend_ncuid", item.friendNcuid);
        intent.putExtra("friend_name", item.title);
        intent.putExtra("friend_avatar", item.avatarUrl);
        intent.putExtra("unread_count", item.unreadCount);
        startActivity(intent);
    }

    @Override
    public void onRecentLongClick(View anchor, RecentItem item) {
        if (item != null && item.isChannel) {
            android.widget.Toast.makeText(getActivity(), "频道设置将在下一阶段开放", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        PastelRecentActions.showMenu(this, anchor, item, token, refreshCallback());
    }

    @Override
    public void onCustomGroupLongClick(View anchor, RecentCustomGroupStore.Group group) {
        RecentCustomGroupDialogs.showManage(this, group, refreshCallback());
    }

    private PastelRecentActions.RefreshCallback refreshCallback() {
        return new PastelRecentActions.RefreshCallback() {
            @Override public void requestRefresh() { scheduleReload(0L); }
        };
    }

    private void syncUnread() {
        if (token != null && token.length() > 0 && getActivity() != null) {
            WSManager.getInstance().syncUnread(getActivity(), token);
            WSManager.getInstance().syncGroupUnread(getActivity(), token);
        }
    }

    private int getUnreadNotificationCount() {
        if (getActivity() == null) {
            return 0;
        }
        SharedPreferences prefs = getActivity().getSharedPreferences("notification", Context.MODE_PRIVATE);
        return prefs.getInt("unread_count", 0);
    }

    private void confirmClearAllUnread() {
        if (getActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.main_clear_unread)
                .setMessage(R.string.main_clear_unread_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearAllUnreadLocal();
                    }
                })
                .show();
    }

    private void clearAllUnreadLocal() {
        if (getActivity() == null) {
            return;
        }
        List<RecentChatCache.RecentChat> direct = RecentChatCache.getRecentChats(getActivity());
        for (int i = 0; i < direct.size(); i++) {
            RecentChatCache.RecentChat chat = direct.get(i);
            if (chat != null && chat.friendUID != null && chat.unreadCount > 0) {
                RecentChatCache.clearUnread(getActivity(), chat.friendUID);
            }
        }
        List<GroupRecentChatCache.RecentGroup> groups = GroupRecentChatCache.getRecentGroups(getActivity());
        for (int i = 0; i < groups.size(); i++) {
            GroupRecentChatCache.RecentGroup group = groups.get(i);
            if (group != null && group.groupId != null && (group.unreadCount > 0 || group.mentionUnread)) {
                GroupRecentChatCache.clearUnread(getActivity(), group.groupId);
                // 同步推进服务端已读水位，避免下一次未读同步把旧消息重新带回主页。
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("group_id", group.groupId);
                    HttpUtil.post("/groups/read", payload, token, null);
                } catch (Exception ignored) { }
            }
        }
        getActivity().getSharedPreferences("notification", Context.MODE_PRIVATE)
                .edit().putInt("unread_count", 0).apply();
        refreshRecents();
        Toast.makeText(getActivity(), R.string.main_clear_unread_done, Toast.LENGTH_SHORT).show();
    }

    private void showQuickMenu(View anchor) {
        dismissQuickMenu();
        quickMenuPopup = ChatsQuickMenuHelper.show(this, anchor,
                new ChatsQuickMenuHelper.CreateCustomGroupCallback() {
                    @Override public void onCreateCustomGroup() {
                        RecentCustomGroupDialogs.showCreate(ChatsFragment.this, refreshCallback());
                    }
                    @Override public void onDiscoverChannel() {
                        if (getActivity() != null) {
                            startActivity(new Intent(getActivity(), com.im.oldchat.ui.ChannelDiscoveryActivity.class));
                        }
                    }
                });
    }

    private void dismissQuickMenu() {
        ChatsQuickMenuHelper.dismiss(quickMenuPopup);
        quickMenuPopup = null;
    }
}
