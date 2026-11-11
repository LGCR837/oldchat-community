package com.im.oldchat.ui;

import android.content.Intent;
import android.net.Uri;
import android.support.v7.app.AlertDialog;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.ResourceItem;
import com.im.oldchat.models.User;
import com.im.oldchat.util.ClipboardUtil;
import com.im.oldchat.util.MessagePayloadBuilder;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONObject;

abstract class ResourceSectionActivitySupport0 extends ResourceSectionActivitySupport1 {
    @Override
    public void onDownload(ResourceItem item) {
        if (item == null || item.url == null || item.url.isEmpty()) {
            return;
        }
        String resolved = resolveUrl(item.url);
        com.im.oldchat.service.FileDownloadService.enqueue(this, resolved, item.name,
                "application/octet-stream");
        Toast.makeText(this, "已加入后台下载，可在 我的 → 文件 查看", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onShare(final ResourceItem item) {
        Toast.makeText(this, "资源分享功能已下线", Toast.LENGTH_SHORT).show();
    }

    protected void pickFriendAndSend(final ResourceItem item) {
        final java.util.List<User> friends = FriendCache.getFriends(this);
        ShareTargetSearchPicker.pickFriend(this, friends, "选择好友", new ShareTargetSearchPicker.FriendSelectCallback() {
            @Override
            public void onSelected(User user) {
                if (user == null || user.uid == null || user.uid.length() == 0) {
                    return;
                }
                sendResourceToFriend(item, user);
            }
        });
    }

    protected void pickGroupAndSend(final ResourceItem item) {
        final java.util.List<Group> groups = GroupCache.getGroups(this);
        ShareTargetSearchPicker.pickGroup(this, groups, "选择群聊", new ShareTargetSearchPicker.GroupSelectCallback() {
            @Override
            public void onSelected(Group group) {
                if (group == null || group.id == null || group.id.length() == 0) {
                    return;
                }
                sendResourceToGroup(item, group);
            }
        });
    }

    protected void sendResourceToFriend(final ResourceItem item, final User friend) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String text = buildResourceShareText(item);
            String body = MessagePayloadBuilder.buildBody(text, null, null, null);
            JSONObject json = new JSONObject();
            json.put("to_uid", friend.uid);
            if (friend.ncuid != null && friend.ncuid.length() > 0) {
                json.put("to_ncuid", friend.ncuid);
            }
            json.put("msg_type", "resource");
            json.put("media_url", item.url);
            json.put("body", body);
            HttpUtil.post("/direct/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(ResourceSectionActivitySupport0.this, "已发送", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(ResourceSectionActivitySupport0.this, ChatActivity.class);
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
                    Toast.makeText(ResourceSectionActivitySupport0.this, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    protected void sendResourceToGroup(final ResourceItem item, final Group group) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String text = buildResourceShareText(item);
            String body = MessagePayloadBuilder.buildBody(text, null, null, null);
            JSONObject json = new JSONObject();
            json.put("group_id", group.id);
            json.put("msg_type", "resource");
            json.put("media_url", item.url);
            json.put("body", body);
            HttpUtil.post("/groups/message/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(ResourceSectionActivitySupport0.this, "已发送", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(ResourceSectionActivitySupport0.this, GroupChatActivity.class);
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
                    Toast.makeText(ResourceSectionActivitySupport0.this, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    protected String buildResourceShareText(ResourceItem item) {
        String name = item == null || item.name == null ? "" : item.name;
        String size = formatShareSize(item == null ? 0 : item.size_bytes);
        return "资源: " + name + "\n" + "大小: " + size + "\n点击气泡下载";
    }

    protected String formatShareSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            return "0B";
        }
        float size = sizeBytes;
        if (size < 1024) {
            return (int) size + "B";
        }
        size = size / 1024f;
        if (size < 1024) {
            return String.format(java.util.Locale.getDefault(), "%.1fKB", size);
        }
        size = size / 1024f;
        if (size < 1024) {
            return String.format(java.util.Locale.getDefault(), "%.1fMB", size);
        }
        size = size / 1024f;
        return String.format(java.util.Locale.getDefault(), "%.1fGB", size);
    }
}
