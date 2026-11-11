package com.im.oldchat.ui.fragments;

import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.PopupMenu;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.models.User;
import com.im.oldchat.models.UserNameCache;

import org.json.JSONObject;

import java.util.List;

final class PastelRecentActions {
    interface RefreshCallback {
        void requestRefresh();
    }

    private PastelRecentActions() {
    }

    static void showMenu(final Fragment fragment, View anchor, final RecentItem item, final String token, final RefreshCallback cb) {
        if (fragment == null || fragment.getActivity() == null || item == null || item.id == null || item.id.isEmpty()) {
            return;
        }
        if (item.isSystemNotification || item.isFoldedFolder) {
            return;
        }
        if (!fragment.isAdded() || fragment.getActivity().isFinishing()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu(fragment.getActivity(), anchor);
            final boolean willPin = !item.pinned;
            final boolean willFold = !item.folded;
            menu.getMenu().add(0, 1, 0, willPin ? "\u7F6E\u9876" : "\u53D6\u6D88\u7F6E\u9876");
            if (!item.isGroup) {
                menu.getMenu().add(0, 2, 1, "\u8BBE\u7F6E\u5907\u6CE8");
            }
            menu.getMenu().add(0, 3, 2, willFold ? "\u6298\u53E0" : "\u53D6\u6D88\u6298\u53E0");
            List<RecentCustomGroupStore.Group> customGroups = RecentCustomGroupStore.list(fragment.getActivity());
            String currentGroup = RecentCustomGroupStore.assignment(fragment.getActivity(), item.isGroup, item.id);
            if (!customGroups.isEmpty() || currentGroup.length() > 0) {
                menu.getMenu().add(0, 4, 3, "\u79FB\u52A8\u5230\u5206\u7EC4");
            }
            menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                    if (fragment.getActivity() == null || fragment.getActivity().isFinishing()) {
                        return false;
                    }
                    if (menuItem.getItemId() == 1) {
                        RecentPinStore.setPinned(fragment.getActivity(), item.isGroup, item.id, item.friendNcuid, willPin);
                        Toast.makeText(fragment.getActivity(), willPin ? "\u5DF2\u7F6E\u9876" : "\u5DF2\u53D6\u6D88\u7F6E\u9876", Toast.LENGTH_SHORT).show();
                        if (cb != null) {
                            cb.requestRefresh();
                        }
                        return true;
                    }
                    if (menuItem.getItemId() == 2) {
                        showRemarkDialog(fragment, item, token, cb);
                        return true;
                    }
                    if (menuItem.getItemId() == 3) {
                        RecentFoldStore.setFolded(fragment.getActivity(), item.isGroup, item.id, item.friendNcuid, willFold);
                        Toast.makeText(fragment.getActivity(), willFold ? "\u5DF2\u6298\u53E0" : "\u5DF2\u53D6\u6D88\u6298\u53E0", Toast.LENGTH_SHORT).show();
                        if (cb != null) {
                            cb.requestRefresh();
                        }
                        return true;
                    }
                    if (menuItem.getItemId() == 4) {
                        RecentCustomGroupDialogs.showMove(fragment, item, cb);
                        return true;
                    }
                    return false;
                }
            });
            menu.show();
        } catch (android.view.WindowManager.BadTokenException e) {
            // Activity was finishing when trying to show popup
        }
    }

    private static void showRemarkDialog(final Fragment fragment, final RecentItem item, final String token, final RefreshCallback cb) {
        if (fragment.getActivity() == null) {
            return;
        }
        final EditText input = new EditText(fragment.getActivity());
        input.setHint(fragment.getString(R.string.remark_hint));
        User friend = findFriendByUid(fragment, item.id);
        if (friend != null && friend.remark_name != null && friend.remark_name.trim().length() > 0) {
            String current = friend.remark_name.trim();
            input.setText(current);
            input.setSelection(current.length());
        }
        new AlertDialog.Builder(fragment.getActivity())
                .setTitle(R.string.remark_set_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String remark = input.getText() == null ? "" : input.getText().toString().trim();
                        submitRemark(fragment, item.id, remark, token, cb);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void submitRemark(final Fragment fragment, final String uid, final String remark, String token, final RefreshCallback cb) {
        if (fragment.getActivity() == null || token == null || token.isEmpty()) {
            return;
        }
        if (remark != null && remark.length() > 32) {
            Toast.makeText(fragment.getActivity(), "\u5907\u6CE8\u6700\u591A32\u4E2A\u5B57", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("friend_uid", uid);
            json.put("remark_name", remark == null ? "" : remark);
            HttpUtil.post("/friends/remark", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (fragment.getActivity() == null) {
                        return;
                    }
                    User friend = findFriendByUid(fragment, uid);
                    String display = friend != null ? friend.display_name : "";
                    String username = friend != null ? friend.username : "";
                    String avatar = friend != null ? friend.avatar_url : "";
                    String merged = FriendNameResolver.resolve(remark, display, username, uid);
                    updateLocalRemark(fragment, uid, remark);
                    RecentChatCache.touchRecentChat(fragment.getActivity(), uid, merged, avatar);
                    UserNameCache.put(fragment.getActivity(), uid, merged);
                    Toast.makeText(fragment.getActivity(),
                            (remark == null || remark.length() == 0) ? "\u5DF2\u6E05\u9664\u5907\u6CE8" : "\u5907\u6CE8\u5DF2\u66F4\u65B0",
                            Toast.LENGTH_SHORT).show();
                    if (cb != null) {
                        cb.requestRefresh();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (fragment.getActivity() == null || HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (error != null && error.contains("remark_too_long")) {
                        Toast.makeText(fragment.getActivity(), "\u5907\u6CE8\u6700\u591A32\u4E2A\u5B57", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(fragment.getActivity(), "\u8BBE\u7F6E\u5907\u6CE8\u5931\u8D25", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            if (fragment.getActivity() != null) {
                Toast.makeText(fragment.getActivity(), "\u8BBE\u7F6E\u5907\u6CE8\u5931\u8D25", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static User findFriendByUid(Fragment fragment, String uid) {
        if (fragment == null || fragment.getActivity() == null || uid == null || uid.isEmpty()) {
            return null;
        }
        List<User> users = FriendCache.getFriends(fragment.getActivity());
        if (users == null || users.isEmpty()) {
            return null;
        }
        for (User user : users) {
            if (user != null && uid.equals(user.uid)) {
                return user;
            }
        }
        return null;
    }

    private static void updateLocalRemark(Fragment fragment, String uid, String remark) {
        if (fragment == null || fragment.getActivity() == null || uid == null || uid.isEmpty()) {
            return;
        }
        List<User> users = FriendCache.getFriends(fragment.getActivity());
        if (users == null || users.isEmpty()) {
            return;
        }
        String safeRemark = remark == null ? "" : remark;
        boolean changed = false;
        for (User user : users) {
            if (user == null || user.uid == null || !uid.equals(user.uid)) {
                continue;
            }
            if (safeRemark.equals(user.remark_name == null ? "" : user.remark_name)) {
                return;
            }
            user.remark_name = safeRemark;
            changed = true;
            break;
        }
        if (changed) {
            FriendCache.saveFriends(fragment.getActivity(), users);
        }
    }
}
