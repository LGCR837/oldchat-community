package com.im.oldchat.ui;

import android.content.Intent;
import android.support.v7.app.AlertDialog;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.User;
import com.im.oldchat.util.MessagePayloadBuilder;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONObject;

abstract class OldViewVideoDetailSupport1 extends OldViewVideoDetailSupport2 {
    protected void openShareMenu() {
        if ((currentAid <= 0) && (currentBvid == null || currentBvid.length() == 0)) {
            Toast.makeText(this, "视频信息不足", Toast.LENGTH_SHORT).show();
            return;
        }
        token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "请先登录旧聊", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] actions = new CharSequence[]{"分享给好友", "分享到群聊"};
        new AlertDialog.Builder(this)
                .setTitle("分享视频")
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            pickFriendAndSend();
                        } else if (which == 1) {
                            pickGroupAndSend();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    protected void pickFriendAndSend() {
        final java.util.List<User> friends = FriendCache.getFriends(this);
        ShareTargetSearchPicker.pickFriend(this, friends, "选择好友", new ShareTargetSearchPicker.FriendSelectCallback() {
            @Override
            public void onSelected(User user) {
                if (user == null || user.uid == null || user.uid.length() == 0) {
                    return;
                }
                sendShareToFriend(user);
            }
        });
    }

    protected void pickGroupAndSend() {
        final java.util.List<Group> groups = GroupCache.getGroups(this);
        ShareTargetSearchPicker.pickGroup(this, groups, "选择群聊", new ShareTargetSearchPicker.GroupSelectCallback() {
            @Override
            public void onSelected(Group group) {
                if (group == null || group.id == null || group.id.length() == 0) {
                    return;
                }
                sendShareToGroup(group);
            }
        });
    }

    protected void sendShareToFriend(final User friend) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "请先登录旧聊", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String body = MessagePayloadBuilder.buildBody(buildShareText(), null, null, null);
            String link = buildShareUrl();
            JSONObject json = new JSONObject();
            json.put("to_uid", friend.uid);
            if (friend.ncuid != null && friend.ncuid.length() > 0) {
                json.put("to_ncuid", friend.ncuid);
            }
            json.put("msg_type", "resource");
            json.put("media_url", link);
            json.put("body", body);
            HttpUtil.post("/direct/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(OldViewVideoDetailSupport1.this, "已发送", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(OldViewVideoDetailSupport1.this, ChatActivity.class);
                    intent.putExtra("friend_uid", friend.uid);
                    intent.putExtra("friend_ncuid", friend.ncuid == null ? "" : friend.ncuid);
                    intent.putExtra("friend_name", FriendNameResolver.resolve(friend));
                    intent.putExtra("friend_avatar", friend.avatar_url);
                    startActivity(intent);
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(OldViewVideoDetailSupport1.this, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    protected void sendShareToGroup(final Group group) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "请先登录旧聊", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String body = MessagePayloadBuilder.buildBody(buildShareText(), null, null, null);
            String link = buildShareUrl();
            JSONObject json = new JSONObject();
            json.put("group_id", group.id);
            json.put("msg_type", "resource");
            json.put("media_url", link);
            json.put("body", body);
            HttpUtil.post("/groups/message/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(OldViewVideoDetailSupport1.this, "已发送", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(OldViewVideoDetailSupport1.this, GroupChatActivity.class);
                    intent.putExtra("group_id", group.id);
                    intent.putExtra("group_name", group.name);
                    intent.putExtra("group_avatar", group.avatar_url);
                    intent.putExtra("group_role", group.role);
                    startActivity(intent);
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(OldViewVideoDetailSupport1.this, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }
}
