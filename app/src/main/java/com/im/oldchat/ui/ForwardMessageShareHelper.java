package com.im.oldchat.ui;

import android.app.Activity;
import android.support.v7.app.AlertDialog;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.User;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONObject;

import java.util.List;

final class ForwardMessageShareHelper {
    interface Callback {
        void onSuccess();
    }

    private ForwardMessageShareHelper() {
    }

    static void showTargetPicker(final Activity activity,
                                 final String token,
                                 final List<MessagePayload.ForwardItem> sourceItems,
                                 final Callback callback) {
        if (activity == null) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(activity, R.string.forward_not_login, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<MessagePayload.ForwardItem> items = ForwardMessageComposer.copyItems(sourceItems);
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.forward_no_messages, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] options = new CharSequence[]{
                activity.getString(R.string.forward_target_friend),
                activity.getString(R.string.forward_target_group)
        };
        new AlertDialog.Builder(activity, R.style.AppDialogTheme)
                .setTitle(R.string.forward_pick_target_title)
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            pickFriend(activity, token, items, callback);
                        } else {
                            pickGroup(activity, token, items, callback);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void pickFriend(final Activity activity,
                                   final String token,
                                   final List<MessagePayload.ForwardItem> items,
                                   final Callback callback) {
        List<User> friends = FriendCache.getFriends(activity);
        ShareTargetSearchPicker.pickFriend(activity, friends,
                activity.getString(R.string.forward_pick_friend_title),
                new ShareTargetSearchPicker.FriendSelectCallback() {
                    @Override
                    public void onSelected(User user) {
                        if (user == null || user.uid == null || user.uid.length() == 0) {
                            return;
                        }
                        sendToFriend(activity, token, items, user.uid, user.ncuid, callback);
                    }
                });
    }

    private static void pickGroup(final Activity activity,
                                  final String token,
                                  final List<MessagePayload.ForwardItem> items,
                                  final Callback callback) {
        List<Group> groups = GroupCache.getGroups(activity);
        ShareTargetSearchPicker.pickGroup(activity, groups,
                activity.getString(R.string.forward_pick_group_title),
                new ShareTargetSearchPicker.GroupSelectCallback() {
                    @Override
                    public void onSelected(Group group) {
                        if (group == null || group.id == null || group.id.length() == 0) {
                            return;
                        }
                        sendToGroup(activity, token, items, group.id, callback);
                    }
                });
    }

    private static void sendToFriend(final Activity activity,
                                     String token,
                                     List<MessagePayload.ForwardItem> items,
                                     String toUid,
                                     String toNcuid,
                                     final Callback callback) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(activity, R.string.forward_network_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("to_uid", toUid);
            if (toNcuid != null && toNcuid.length() > 0) {
                json.put("to_ncuid", toNcuid);
            }
            json.put("msg_type", "text");
            json.put("body", ForwardMessageComposer.buildForwardBody(activity, items));
            HttpUtil.post("/direct/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(activity, R.string.forward_success, Toast.LENGTH_SHORT).show();
                    if (callback != null) {
                        callback.onSuccess();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(activity, R.string.forward_failed, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, R.string.forward_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void sendToGroup(final Activity activity,
                                    String token,
                                    List<MessagePayload.ForwardItem> items,
                                    String groupId,
                                    final Callback callback) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(activity, R.string.forward_network_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("msg_type", "text");
            json.put("body", ForwardMessageComposer.buildForwardBody(activity, items));
            HttpUtil.post("/groups/message/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(activity, R.string.forward_success, Toast.LENGTH_SHORT).show();
                    if (callback != null) {
                        callback.onSuccess();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(activity, R.string.forward_failed, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, R.string.forward_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
