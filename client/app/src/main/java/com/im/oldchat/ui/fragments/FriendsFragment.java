package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.EditText;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.data.FriendRequestStore;
import com.im.oldchat.MainActivity;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.models.User;
import com.im.oldchat.util.NetworkStateManager;
import com.im.oldchat.ui.AddFriendActivity;
import com.im.oldchat.ui.ChatActivity;
import com.im.oldchat.ui.FriendAdapter;
import com.im.oldchat.ui.GroupChatActivity;
import com.im.oldchat.ui.GroupCreateActivity;
import com.im.oldchat.ui.NotificationChatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class FriendsFragment extends FriendsFragmentSupport {
    private static final int FRIENDS_RETRY_TIMES = 2;
    private static final long FRIENDS_RETRY_DELAY_MS = 900L;
    private static final long RESUME_TOAST_SUPPRESS_MS = 2400L;
    private static final long RESUME_RECOVERY_WINDOW_MS = 15000L;
    private static final long FRIENDS_AUTO_RECOVER_DELAY_MS = 1800L;
    private static final long FRIENDS_AUTO_RECOVER_MIN_INTERVAL_MS = 12000L;
    private static final long FRIENDS_AUTO_RECOVER_MIN_INTERVAL_RESUME_MS = 2500L;
    private static final long PRESENCE_REFRESH_DELAY_MS = 140L;
    private static final long FULL_REFRESH_MIN_INTERVAL_MS = 12000L;
    private static final long QUICK_RESUME_SKIP_MS = 1500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean cacheSnapshotLoaded;
    private boolean viewCreated;
    private boolean pendingVisibleRefresh;
    private long lastFullRefreshMs;
    private boolean wsListenerRegistered;
    private final Runnable presenceRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            presenceRefreshScheduled = false;
            if (!isAdded() || adapter == null) {
                return;
            }
            if (lvFriends != null) {
                lvFriends.invalidateViews();
            } else {
                adapter.notifyDataSetChanged();
            }
        }
    };
    private long toastSuppressUntilMs;
    private long resumeRecoveryUntilMs;
    private long lastAutoRecoverRetryMs;
    private int friendsRequestToken;
    private boolean presenceRefreshScheduled;
    private final com.im.oldchat.api.WSManager.Listener wsListener = new com.im.oldchat.api.WSManager.Listener() {
        public void onDirectMessage(com.im.oldchat.api.WSModels.DirectMessage message) {}
        public void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt) {}
        public void onDirectRecall(com.im.oldchat.api.WSModels.DirectRecall recall) {}
        public void onGroupMessage(com.im.oldchat.api.WSModels.GroupMessage message) {}
        public void onGroupRecall(com.im.oldchat.api.WSModels.GroupRecall recall) {}
        public void onTyping(com.im.oldchat.api.WSModels.TypingEvent event) {}
        public void onPresence(com.im.oldchat.api.WSModels.PresenceEvent event) {
            if (event == null || event.uid == null) return;
            com.im.oldchat.util.UserPresenceCache.put(event.uid, event.isOnline, event.presenceStatus);
            schedulePresenceRefresh();
        }
        public void onConnectionChanged(boolean connected) {}
    };


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_friends, container, false);
        if (com.im.oldchat.util.ThemeColorUtil.isMinimalDark(view.getContext())) {
            view.setBackgroundColor(0xFF08090A);
        }
        lvFriends = view.findViewById(R.id.lvFriends);
        etFriendSearch = view.findViewById(R.id.etFriendSearch);
        friendsSearchField = view.findViewById(R.id.friendsSearchField);
        btnExpandFriendSearch = view.findViewById(R.id.btnExpandFriendSearch);
        btnClearFriendSearch = view.findViewById(R.id.btnClearFriendSearch);
        fabMenu = view.findViewById(R.id.fabMenu);
        fabMenuAddFriend = view.findViewById(R.id.fabMenuAddFriend);
        fabMenuCreateGroup = view.findViewById(R.id.fabMenuCreateGroup);
        pbFriendsLoading = view.findViewById(R.id.pbFriendsLoading);
        if (com.im.oldchat.util.ThemeColorUtil.isMinimalDark(view.getContext())) {
            view.setBackgroundColor(0xFF08090A);
            if (lvFriends != null) lvFriends.setBackgroundColor(0xFF08090A);
        }
        View searchBar = view.findViewById(R.id.friendsSearchBar);
        if (searchBar != null) searchBar.setBackgroundResource(R.drawable.bg_seamless_row);
        int[] seamlessIds = new int[]{R.id.friendsSearchField,
                R.id.fabMenuAddFriend, R.id.fabMenuCreateGroup};
        for (int i = 0; i < seamlessIds.length; i++) {
            View control = view.findViewById(seamlessIds[i]);
            if (control != null) control.setBackgroundResource(R.drawable.bg_transparent_row);
        }
        disableTiltRecursively(view);

        SharedPreferences prefs = getActivity().getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        adapter = new FriendAdapter(getActivity(), new FriendAdapter.OnFriendClickListener() {
            @Override
            public void onFriendClick(User friend) {
                Intent intent = new Intent(getActivity(), ChatActivity.class);
                intent.putExtra("friend_id", friend.id);
                intent.putExtra("friend_uid", friend.uid);
                intent.putExtra("friend_ncuid", friend.ncuid);
                intent.putExtra("friend_name", FriendNameResolver.resolve(friend));
                intent.putExtra("friend_avatar", friend.avatar_url);
                startActivity(intent);
            }

            @Override
            public void onAvatarClick(User friend) {
                Intent intent = new Intent(getActivity(), com.im.oldchat.ui.UserSpaceActivity.class);
                intent.putExtra("ncuid", friend.ncuid);
                startActivity(intent);
            }

            @Override
            public void onRequestAccept(String requestId) {
                respondToRequest(requestId, true);
            }

            @Override
            public void onRequestReject(String requestId) {
                respondToRequest(requestId, false);
            }

            @Override
            public void onGroupClick(Group group) {
                Intent intent = new Intent(getActivity(), GroupChatActivity.class);
                intent.putExtra("group_id", group.id);
                intent.putExtra("group_name", group.name);
                intent.putExtra("group_avatar", group.avatar_url);
                intent.putExtra("group_role", group.role);
                startActivity(intent);
            }

            @Override
            public void onSystemNotificationClick() {
                startActivity(new Intent(getActivity(), NotificationChatActivity.class));
            }
        });

        lvFriends.setAdapter(adapter);
        if (!groups.isEmpty() || !recentFriends.isEmpty() || !otherFriends.isEmpty() || !pendingRequests.isEmpty()) {
            updateAdapter();
        }

        if (etFriendSearch != null) {
            com.im.oldchat.util.AutofillCompatApi26.disableAutofill(etFriendSearch);
            etFriendSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            etFriendSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String q = s == null ? "" : s.toString();
                    if (adapter != null) {
                        adapter.filter(q);
                    }
                    if (isMenuOpen) {
                        toggleMenu();
                    }
                }
            });

            etFriendSearch.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus && isMenuOpen) {
                        toggleMenu();
                    }
                }
            });
        }

        if (btnExpandFriendSearch != null) {
            btnExpandFriendSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setFriendSearchExpanded(true);
                }
            });
            btnExpandFriendSearch.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    toggleMenu();
                    return true;
                }
            });
        }
        if (btnClearFriendSearch != null) {
            btnClearFriendSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (etFriendSearch != null && etFriendSearch.getText().length() > 0) {
                        etFriendSearch.setText("");
                    } else {
                        setFriendSearchExpanded(false);
                    }
                }
            });
        }

        // 浮动按钮菜单：原加号入口已移除（与主页重复），添加联系人/创建群聊仍可通过
        // 搜索按钮长按呼出，保证功能不丢。
        if (fabMenuAddFriend != null) {
            fabMenuAddFriend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleMenu();
                    startActivity(new Intent(getActivity(), AddFriendActivity.class));
                }
            });
        }

        // 创建群聊
        if (fabMenuCreateGroup != null) {
            fabMenuCreateGroup.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleMenu();
                    startActivity(new Intent(getActivity(), GroupCreateActivity.class));
                }
            });
        }

        // 点击列表关闭菜单
        lvFriends.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                if (scrollState != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE && isMenuOpen) {
                    toggleMenu();
                }
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            }
        });

        viewCreated = true;
        if (getUserVisibleHint()) {
            ensureDataLoaded(true);
        } else {
            pendingVisibleRefresh = true;
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewCreated = false;
        cacheSnapshotLoaded = false;
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!getUserVisibleHint()) {
            pendingVisibleRefresh = true;
            return;
        }
        ensureDataLoaded(false);
        ensureWsListenerRegistered();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser) {
            if (viewCreated) {
                ensureDataLoaded(false);
                if (isResumed()) {
                    ensureWsListenerRegistered();
                }
            } else {
                pendingVisibleRefresh = true;
            }
        }
    }

    private void ensureDataLoaded(boolean firstVisibleLoad) {
        if (!isAdded() || getActivity() == null) {
            pendingVisibleRefresh = true;
            return;
        }
        SharedPreferences prefs = getActivity().getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        long now = System.currentTimeMillis();
        toastSuppressUntilMs = now + RESUME_TOAST_SUPPRESS_MS;
        resumeRecoveryUntilMs = now + RESUME_RECOVERY_WINDOW_MS;
        
        // 1. 优先同步加载缓存并立即显示（无网络也能看到内容）
        if (!cacheSnapshotLoaded || shouldReloadCachedSnapshot()) {
            loadCachedDataSync();
            cacheSnapshotLoaded = true;
        }
        
        boolean hasData = adapter != null && adapter.getCount() > 0;
        
        // 2. 检查是否需要跳过网络刷新
        if (!firstVisibleLoad && lastFullRefreshMs > 0L && now - lastFullRefreshMs < QUICK_RESUME_SKIP_MS) {
            pendingVisibleRefresh = false;
            showLoading(false);
            return;
        }
        
        boolean shouldFullRefresh = firstVisibleLoad || !hasData
                || now - lastFullRefreshMs >= FULL_REFRESH_MIN_INTERVAL_MS;
        if (!shouldFullRefresh) {
            pendingVisibleRefresh = false;
            showLoading(false);
            return;
        }
        
        pendingVisibleRefresh = false;
        lastFullRefreshMs = now;
        lastAutoRecoverRetryMs = 0L;
        mainHandler.removeCallbacksAndMessages(null);
        groupsLoaded = false;
        friendsLoaded = false;
        requestsLoaded = false;
        
        // 3. 有数据就不显示加载圈，后台静默刷新
        showLoading(!hasData);
        
        // 4. 延迟一点再发起网络请求，让UI先渲染缓存数据
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) {
                    return;
                }
                loadGroups();
                loadFriends();
            }
        }, hasData ? 200 : 0); // 有缓存时延迟200ms，无缓存时立即加载
    }
    
    private void loadCachedDataSync() {
        // 同步加载所有缓存数据并立即显示
        boolean hasCache = false;
        
        // 加载群组缓存
        if (applyCachedGroups()) {
            hasCache = true;
        }
        
        // 加载好友缓存
        List<User> cachedFriends = getCachedFriendsSafe();
        if (cachedFriends != null && !cachedFriends.isEmpty()) {
            splitFriends(cachedFriends);
            hasCache = true;
        }
        
        // 加载好友请求缓存
        if (applyCachedRequests()) {
            hasCache = true;
        }
        
        // 立即更新界面显示缓存数据
        if (hasCache) {
            groupsLoaded = true;
            friendsLoaded = true;
            requestsLoaded = true;
            updateAdapter();
        }
    }
    
    private boolean applyCachedRequests() {
        if (!isAdded() || getActivity() == null) {
            return false;
        }
        int count = FriendRequestStore.getPendingCount(getActivity());
        if (count > 0 && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateFriendsBadge(count);
        }
        // 好友请求没有详细缓存，返回是否有记录
        return count >= 0;
    }

    private void loadGroups() {
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            // 无网络时缓存已在 ensureDataLoaded 中加载，这里只需标记完成
            groupsLoaded = true;
            showLoading(false);
            updateAdapter();
            return;
        }
        HttpUtil.get("/groups/list", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                if (!isAdded() || getActivity() == null) {
                    return;
                }
                boolean parsedOk = false;
                List<Group> parsedGroups = null;
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("groups");
                    if (arr != null) {
                        parsedGroups = FriendsApiParser.parseGroups(arr);
                        parsedOk = true;
                    }
                } catch (Exception e) {
                    parsedOk = false;
                }
                if (parsedOk && parsedGroups != null) {
                    boolean keptCacheDuringRecovery = false;
                    if (parsedGroups.isEmpty() && isInResumeRecoveryWindow()) {
                        keptCacheDuringRecovery = applyCachedGroups();
                    }
                    if (!keptCacheDuringRecovery) {
                        groups.clear();
                        groups.addAll(parsedGroups);
                        GroupCache.saveGroups(getActivity(), groups);
                        GroupRecentChatCache.mergeGroupInfo(getActivity(), groups);
                    }
                } else {
                    applyCachedGroups();
                }
                groupsLoaded = true;
                showLoading(false);
                updateAdapter();
            }

            @Override
            public void onError(int code, String error) {
                if (!isAdded() || getActivity() == null) {
                    return;
                }
                boolean hasCache = applyCachedGroups();
                if (!hasCache && isInResumeRecoveryWindow()
                        && shouldAutoRecoverRetry(code, FRIENDS_RETRY_TIMES)) {
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isAdded()) {
                                return;
                            }
                            loadGroups();
                        }
                    }, FRIENDS_AUTO_RECOVER_DELAY_MS);
                }
                groupsLoaded = true;
                showLoading(false);
                updateAdapter();
            }
        });
    }

    private boolean applyCachedGroups() {
        if (!isAdded() || getActivity() == null) {
            return false;
        }
        List<Group> cached = GroupCache.getGroups(getActivity());
        if (cached == null || cached.isEmpty()) {
            return false;
        }
        groups.clear();
        groups.addAll(cached);
        GroupRecentChatCache.mergeGroupInfo(getActivity(), groups);
        return true;
    }

    private void setFriendSearchExpanded(boolean expanded) {
        if (friendsSearchField == null || etFriendSearch == null) {
            return;
        }
        friendsSearchField.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (!expanded) {
            etFriendSearch.setText("");
            etFriendSearch.clearFocus();
            hideKeyboard(etFriendSearch);
            return;
        }
        etFriendSearch.requestFocus();
        etFriendSearch.post(new Runnable() {
            @Override
            public void run() {
                if (getActivity() == null) {
                    return;
                }
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getActivity()
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etFriendSearch,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
        if (isMenuOpen) {
            toggleMenu();
        }
    }

    private boolean shouldReloadCachedSnapshot() {
        if (adapter == null || adapter.getCount() == 0) {
            return true;
        }
        return groups.isEmpty() && recentFriends.isEmpty() && otherFriends.isEmpty() && pendingRequests.isEmpty();
    }

    @Override
    protected void loadFriends() {
        final int requestToken = ++friendsRequestToken;
        loadFriendsInternal(requestToken, 0);
    }

    private void loadFriendsInternal(final int requestToken, final int retryCount) {
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            // 无网络时缓存已在 ensureDataLoaded 中加载，这里只需标记完成
            friendsLoaded = true;
            requestsLoaded = true;
            showLoading(false);
            updateAdapter();
            return;
        }
        HttpUtil.get("/friends", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                if (requestToken != friendsRequestToken || !isAdded() || getActivity() == null) {
                    return;
                }
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("friends");
                    List<User> friends = FriendsApiParser.parseFriends(arr);
                    for (User u : friends) {
                        if (u != null && u.uid != null) {
                            com.im.oldchat.util.UserPresenceCache.put(u.uid, u.is_online, u.presence_status);
                        }
                    }
                    splitFriends(friends);
                    FriendCache.saveFriends(getActivity(), friends);
                    RecentChatCache.mergeFriendInfo(getActivity(), friends);
                    RecentChatCache.cleanupInvalidChats(getActivity(), friends);
                    UserNameCache.mergeUsers(getActivity(), friends);
                    com.im.oldchat.models.UserTitleCache.mergeUsers(getActivity(), friends);
                    resumeRecoveryUntilMs = 0L;
                    friendsLoaded = true;
                    showLoading(false);
                    loadRequests();
                } catch (Exception e) {
                    if (getActivity() != null && !isInResumeRecoveryWindow()) {
                        Toast.makeText(getActivity(), "解析好友列表失败", Toast.LENGTH_SHORT).show();
                    }
                    friendsLoaded = true;
                    requestsLoaded = true;
                    showLoading(false);
                    updateAdapter();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (requestToken != friendsRequestToken || !isAdded() || getActivity() == null) {
                    return;
                }
                boolean suppress = HttpUtil.shouldSuppressAuthToast(code, error);
                List<User> cached = getCachedFriendsSafe();
                boolean hasCache = cached != null && !cached.isEmpty();
                if (hasCache) {
                    splitFriends(cached);
                }

                if (isTransientError(code) && retryCount < FRIENDS_RETRY_TIMES) {
                    final int nextRetry = retryCount + 1;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isAdded() || requestToken != friendsRequestToken) {
                                return;
                            }
                            loadFriendsInternal(requestToken, nextRetry);
                        }
                    }, FRIENDS_RETRY_DELAY_MS);
                }

                boolean autoRecoverScheduled = false;
                if (shouldAutoRecoverRetry(code, retryCount)) {
                    autoRecoverScheduled = true;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isAdded()) {
                                return;
                            }
                            loadFriends();
                        }
                    }, FRIENDS_AUTO_RECOVER_DELAY_MS);
                }

                if (!suppress && !autoRecoverScheduled && shouldShowLoadFailToast(code, hasCache, retryCount)) {
                    Toast.makeText(getActivity(), "加载好友失败: " + code, Toast.LENGTH_SHORT).show();
                }
                friendsLoaded = true;
                requestsLoaded = true;
                showLoading(false);
                updateAdapter();
            }
        });
    }

    private boolean shouldShowLoadFailToast(int code, boolean hasCache, int retryCount) {
        if (isInResumeRecoveryWindow()) {
            return false;
        }
        if (hasCache) {
            return false;
        }
        if (retryCount < FRIENDS_RETRY_TIMES && isTransientError(code)) {
            return false;
        }
        return System.currentTimeMillis() > toastSuppressUntilMs;
    }

    private boolean shouldAutoRecoverRetry(int code, int retryCount) {
        if (!isTransientError(code) || retryCount < FRIENDS_RETRY_TIMES) {
            return false;
        }
        long now = System.currentTimeMillis();
        long minInterval = isInResumeRecoveryWindow()
                ? FRIENDS_AUTO_RECOVER_MIN_INTERVAL_RESUME_MS
                : FRIENDS_AUTO_RECOVER_MIN_INTERVAL_MS;
        if (now - lastAutoRecoverRetryMs < minInterval) {
            return false;
        }
        lastAutoRecoverRetryMs = now;
        return true;
    }

    private boolean isInResumeRecoveryWindow() {
        return System.currentTimeMillis() <= resumeRecoveryUntilMs;
    }

    private boolean isTransientError(int code) {
        return code <= 0
                || code == 408
                || code == 429
                || code == 500
                || code == 502
                || code == 503
                || code == 504;
    }

    private List<User> getCachedFriendsSafe() {
        if (!isAdded() || getActivity() == null) {
            return new ArrayList<User>();
        }
        List<User> cached = FriendCache.getFriends(getActivity());
        if (cached == null) {
            return new ArrayList<User>();
        }
        return cached;
    }

    @Override
    public void onPause() {
        presenceRefreshScheduled = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (wsListenerRegistered) {
            com.im.oldchat.api.WSManager.getInstance().removeListener(wsListener);
            wsListenerRegistered = false;
        }
        super.onPause();
    }

    private void ensureWsListenerRegistered() {
        if (wsListenerRegistered) {
            return;
        }
        com.im.oldchat.api.WSManager.getInstance().addListener(wsListener);
        wsListenerRegistered = true;
    }

    private void schedulePresenceRefresh() {
        if (adapter == null) {
            return;
        }
        if (presenceRefreshScheduled) {
            return;
        }
        presenceRefreshScheduled = true;
        mainHandler.postDelayed(presenceRefreshRunnable, PRESENCE_REFRESH_DELAY_MS);
    }

    @Override
    protected void loadRequests() {
        HttpUtil.get("/friends/requests", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("requests");
                    List<FriendAdapter.FriendRequestItem> requests = FriendsApiParser.parseRequests(arr);
                    loadGroupInvitationRequests(requests);
                } catch (Exception e) {
                    loadGroupInvitationRequests(new ArrayList<FriendAdapter.FriendRequestItem>());
                }
            }

            @Override
            public void onError(int code, String error) {
                loadGroupInvitationRequests(new ArrayList<FriendAdapter.FriendRequestItem>());
            }
        });
    }

    private void loadGroupInvitationRequests(final List<FriendAdapter.FriendRequestItem> friendRequests) {
        HttpUtil.get("/groups/invitations", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                List<FriendAdapter.FriendRequestItem> merged = new ArrayList<FriendAdapter.FriendRequestItem>(friendRequests);
                try {
                    JSONArray arr = new JSONObject(response).optJSONArray("requests");
                    if (arr != null) merged.addAll(FriendsApiParser.parseGroupInvitations(arr));
                } catch (Exception ignored) {
                }
                applyLoadedRequests(merged);
            }

            @Override
            public void onError(int code, String error) {
                applyLoadedRequests(friendRequests);
            }
        });
    }

    private void disableTiltRecursively(View view) {
        if (view == null) return;
        if (view instanceof com.im.oldchat.ui.widget.TiltFrameLayout) {
            ((com.im.oldchat.ui.widget.TiltFrameLayout) view).setTiltEnabled(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableTiltRecursively(group.getChildAt(i));
            }
        }
    }

    private void applyLoadedRequests(List<FriendAdapter.FriendRequestItem> requests) {
        if (!isAdded() || getActivity() == null) return;
        pendingRequests = requests == null ? new ArrayList<FriendAdapter.FriendRequestItem>() : requests;
        FriendRequestStore.setPendingCount(getActivity(), pendingRequests.size());
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateFriendsBadge(pendingRequests.size());
        }
        requestsLoaded = true;
        showLoading(false);
        updateAdapter();
    }
}
