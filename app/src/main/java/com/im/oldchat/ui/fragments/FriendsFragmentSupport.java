package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.support.v4.app.Fragment;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewPropertyAnimatorListenerAdapter;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.im.oldchat.MainActivity;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.data.FriendRequestStore;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.User;
import com.im.oldchat.ui.FriendAdapter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

abstract class FriendsFragmentSupport extends Fragment {
    private static final long MENU_ANIM_DURATION_MS = 120L;
    protected ListView lvFriends;
    protected EditText etFriendSearch;
    protected View friendsSearchField;
    protected View btnExpandFriendSearch;
    protected View btnClearFriendSearch;
    protected View fabMenu;
    protected View fabMenuAddFriend;
    protected View fabMenuCreateGroup;
    protected ProgressBar pbFriendsLoading;
    protected boolean isMenuOpen = false;
    protected String token;
    protected FriendAdapter adapter;
    protected List<User> recentFriends = new ArrayList<>();
    protected List<User> otherFriends = new ArrayList<>();
    protected List<Group> groups = new ArrayList<>();
    protected List<FriendAdapter.FriendRequestItem> pendingRequests = new ArrayList<>();
    protected boolean groupsLoaded = false;
    protected boolean friendsLoaded = false;
    protected boolean requestsLoaded = false;
    private boolean requestActionLoading = false;
    private String processingRequestId = "";

    protected void hideKeyboard(View view) {
        if (view == null || getActivity() == null) {
            return;
        }
        try {
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    protected void toggleMenu() {
        isMenuOpen = !isMenuOpen;
        if (isMenuOpen) {
            fabMenu.setVisibility(View.VISIBLE);
            ViewCompat.setAlpha(fabMenu, 0f);
            ViewCompat.animate(fabMenu).alpha(1f).setDuration(MENU_ANIM_DURATION_MS).setListener(null).start();
        } else {
            ViewCompat.animate(fabMenu).alpha(0f).setDuration(MENU_ANIM_DURATION_MS)
                    .setListener(new ViewPropertyAnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(View view) {
                            if (fabMenu != null) {
                                fabMenu.setVisibility(View.GONE);
                            }
                        }
                    }).start();
        }
    }

    protected void splitFriends(List<User> friends) {
        recentFriends.clear();
        otherFriends.clear();
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (User u : friends) {
            long addedMs = u.friend_added_at > 0 ? u.friend_added_at * 1000L : 0;
            if (addedMs > 0 && addedMs >= cutoff) {
                recentFriends.add(u);
            } else {
                otherFriends.add(u);
            }
        }
    }

    protected void updateAdapter() {
        if (adapter == null) {
            return;
        }
        adapter.setData(groups, recentFriends, otherFriends, pendingRequests);
        showLoading(!(groupsLoaded && friendsLoaded && requestsLoaded));
    }

    protected void loadCachedData() {
        if (getActivity() == null) {
            return;
        }
        List<Group> cachedGroups = GroupCache.getGroups(getActivity());
        List<User> cachedFriends = FriendCache.getFriends(getActivity());
        groups.clear();
        recentFriends.clear();
        otherFriends.clear();
        if (!cachedGroups.isEmpty()) {
            groups.addAll(cachedGroups);
        }
        if (!cachedFriends.isEmpty()) {
            splitFriends(cachedFriends);
        }
        updateAdapter();
    }

    protected void showLoading(boolean loading) {
        if (pbFriendsLoading != null) {
            pbFriendsLoading.setVisibility((loading || requestActionLoading) ? View.VISIBLE : View.GONE);
        }
    }

    protected void respondToRequest(String requestId, boolean accept) {
        if (requestId == null || requestId.length() == 0 || getActivity() == null) {
            return;
        }
        if (requestActionLoading) {
            if (requestId.equals(processingRequestId)) {
                return;
            }
            Toast.makeText(getActivity(), "正在处理上一条申请，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(getActivity(), "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean acceptFinal = accept;
        final String requestIdFinal = requestId;
        final FriendAdapter.FriendRequestItem request = findPendingRequest(requestId);
        final boolean groupInvite = request != null && request.isGroupInvite();
        setRequestActionLoading(true, requestIdFinal);
        try {
            JSONObject json = new JSONObject();
            json.put("request_id", requestId);
            json.put("accept", acceptFinal);
            HttpUtil.post(groupInvite ? "/groups/invitations/respond" : "/friends/respond", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (getActivity() == null) {
                        setRequestActionLoading(false, null);
                        return;
                    }
                    FriendAdapter.FriendRequestItem removed = removePendingRequestLocal(requestIdFinal);
                    boolean added = false;
                    if (acceptFinal) {
                        added = groupInvite ? addAcceptedGroupLocal(removed) : addAcceptedFriendLocal(removed);
                    }
                    if (removed != null || added) {
                        updateAdapter();
                    }
                    String msg = groupInvite ? (acceptFinal ? "已加入群聊" : "已拒绝群聊邀请")
                            : (acceptFinal ? "已接受好友申请" : "已拒绝好友申请");
                    Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
                    setRequestActionLoading(false, null);
                }

                @Override
                public void onError(int code, String error) {
                    if (getActivity() == null) {
                        setRequestActionLoading(false, null);
                        return;
                    }
                    setRequestActionLoading(false, null);
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(getActivity(), buildRequestActionErrorMessage(code, error), Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            setRequestActionLoading(false, null);
        }
    }

    private void setRequestActionLoading(boolean loading, String requestId) {
        requestActionLoading = loading;
        processingRequestId = loading && requestId != null ? requestId : "";
        showLoading(!(groupsLoaded && friendsLoaded && requestsLoaded));
    }

    private String buildRequestActionErrorMessage(int code, String error) {
        String detail = "";
        if (error != null) {
            String trimmed = error.trim();
            if (trimmed.length() > 0 && !"null".equalsIgnoreCase(trimmed)) {
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        JSONObject obj = new JSONObject(trimmed);
                        detail = obj.optString("error", "").trim();
                        if (detail.length() == 0) detail = obj.optString("message", "").trim();
                        if (detail.length() == 0) detail = obj.optString("msg", "").trim();
                    } catch (Exception ignored) {
                    }
                } else {
                    detail = trimmed;
                }
            }
        }
        if (detail.length() > 64) detail = detail.substring(0, 64);
        if (detail.length() > 0) return code > 0 ? "处理失败（" + code + "）：" + detail : "处理失败：" + detail;
        return code > 0 ? "处理失败（" + code + "）" : "处理失败，请稍后重试";
    }

    private FriendAdapter.FriendRequestItem removePendingRequestLocal(String requestId) {
        if (requestId == null || requestId.length() == 0 || pendingRequests == null) {
            return null;
        }
        boolean changed = false;
        FriendAdapter.FriendRequestItem removed = null;
        for (int i = pendingRequests.size() - 1; i >= 0; i--) {
            FriendAdapter.FriendRequestItem item = pendingRequests.get(i);
            if (item != null && requestId.equals(item.id)) {
                if (removed == null) {
                    removed = item;
                }
                pendingRequests.remove(i);
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        if (getActivity() != null) {
            int count = pendingRequests.size();
            FriendRequestStore.setPendingCount(getActivity(), count);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).updateFriendsBadge(count);
            }
        }
        return removed;
    }

    private FriendAdapter.FriendRequestItem findPendingRequest(String requestId) {
        if (requestId == null || pendingRequests == null) return null;
        for (int i = 0; i < pendingRequests.size(); i++) {
            FriendAdapter.FriendRequestItem item = pendingRequests.get(i);
            if (item != null && requestId.equals(item.id)) return item;
        }
        return null;
    }

    private boolean addAcceptedGroupLocal(FriendAdapter.FriendRequestItem request) {
        if (request == null || request.groupId == null || request.groupId.length() == 0) return false;
        for (int i = 0; i < groups.size(); i++) {
            Group existing = groups.get(i);
            if (existing != null && request.groupId.equals(existing.id)) return false;
        }
        Group group = new Group();
        group.id = request.groupId;
        group.name = request.groupName;
        group.avatar_url = request.avatarUrl;
        group.role = 0;
        group.member_count = 1;
        groups.add(0, group);
        if (getActivity() != null) GroupCache.saveGroups(getActivity(), groups);
        return true;
    }

    private boolean addAcceptedFriendLocal(FriendAdapter.FriendRequestItem request) {
        if (request == null || request.fromUID == null || request.fromUID.length() == 0) {
            return false;
        }
        String uid = request.fromUID;
        if (containsUid(recentFriends, uid) || containsUid(otherFriends, uid)) {
            return false;
        }
        User user = new User();
        user.uid = uid;
        user.display_name = request.fromName;
        user.username = request.fromName;
        user.user_title = request.fromTitle;
        user.avatar_url = request.avatarUrl;
        user.friend_added_at = System.currentTimeMillis() / 1000L;
        recentFriends.add(0, user);
        persistFriendsLocal();
        return true;
    }

    private boolean containsUid(List<User> list, String uid) {
        if (list == null || uid == null || uid.length() == 0) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            User user = list.get(i);
            if (user != null && uid.equals(user.uid)) {
                return true;
            }
        }
        return false;
    }

    private void persistFriendsLocal() {
        if (getActivity() == null) {
            return;
        }
        List<User> all = new ArrayList<User>();
        all.addAll(recentFriends);
        all.addAll(otherFriends);
        FriendCache.saveFriends(getActivity(), all);
    }

    protected abstract void loadFriends();

    protected abstract void loadRequests();
}
