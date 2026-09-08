package com.im.oldchat.ui;

import android.app.Activity;
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

import java.util.List;
import java.util.Locale;

final class MusicShareActionHelper {

    static final class ShareItem {
        String id;
        String name;
        String songUrl;
        String coverUrl;
        String lyricsUrl;
        String ownerUid;
        String ownerName;
        int durationMs;
    }

    private MusicShareActionHelper() {
    }

    static void showShareDialog(final Activity activity, final String token, final ShareItem item) {
        if (activity == null || item == null) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(activity, "未登录", Toast.LENGTH_SHORT).show();
            return;
        }
        String songUrl = trim(item.songUrl);
        if (songUrl.length() == 0) {
            Toast.makeText(activity, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] options = new CharSequence[]{"分享给好友", "分享到群聊"};
        new AlertDialog.Builder(activity)
                .setTitle("分享音乐")
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            pickFriendAndSend(activity, token, item);
                        } else {
                            pickGroupAndSend(activity, token, item);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void pickFriendAndSend(final Activity activity, final String token, final ShareItem item) {
        final List<User> friends = FriendCache.getFriends(activity);
        ShareTargetSearchPicker.pickFriend(activity, friends, "选择好友", new ShareTargetSearchPicker.FriendSelectCallback() {
            @Override
            public void onSelected(User user) {
                if (user == null || user.uid == null || user.uid.length() == 0) {
                    return;
                }
                sendToFriend(activity, token, item, user);
            }
        });
    }

    private static void pickGroupAndSend(final Activity activity, final String token, final ShareItem item) {
        final List<Group> groups = GroupCache.getGroups(activity);
        ShareTargetSearchPicker.pickGroup(activity, groups, "选择群聊", new ShareTargetSearchPicker.GroupSelectCallback() {
            @Override
            public void onSelected(Group group) {
                if (group == null || group.id == null || group.id.length() == 0) {
                    return;
                }
                sendToGroup(activity, token, item, group);
            }
        });
    }

    private static void sendToFriend(final Activity activity, String token, ShareItem item, final User friend) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(activity, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = buildSendPayload(item);
            json.put("to_uid", friend.uid);
            if (friend.ncuid != null && friend.ncuid.length() > 0) {
                json.put("to_ncuid", friend.ncuid);
            }
            HttpUtil.post("/direct/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(activity, "已发送", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(activity, ChatActivity.class);
                    intent.putExtra("friend_uid", friend.uid);
                    intent.putExtra("friend_ncuid", friend.ncuid == null ? "" : friend.ncuid);
                    intent.putExtra("friend_name", FriendNameResolver.resolve(friend));
                    intent.putExtra("friend_avatar", friend.avatar_url);
                    activity.startActivity(intent);
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(activity, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static void sendToGroup(final Activity activity, String token, ShareItem item, final Group group) {
        if (!NetworkStateManager.getInstance().isServerAvailable()) {
            Toast.makeText(activity, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = buildSendPayload(item);
            json.put("group_id", group.id);
            HttpUtil.post("/groups/message/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(activity, "已发送", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(activity, GroupChatActivity.class);
                    intent.putExtra("group_id", group.id);
                    intent.putExtra("group_name", group.name);
                    intent.putExtra("group_avatar", group.avatar_url);
                    intent.putExtra("group_role", group.role);
                    activity.startActivity(intent);
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(activity, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static JSONObject buildSendPayload(ShareItem item) throws Exception {
        JSONObject json = new JSONObject();
        json.put("msg_type", "resource");
        json.put("media_url", trim(item.songUrl));
        String cover = normalizeMusicCoverUrl(item == null ? null : item.coverUrl);
        if (cover.length() > 0) {
            json.put("thumb_url", cover);
        }
        String body = MessagePayloadBuilder.buildBody(buildShareText(item), null, null, "music");
        json.put("body", body);
        return json;
    }

    private static String buildShareText(ShareItem item) {
        String title = trim(item == null ? null : item.name);
        if (title.length() == 0) {
            title = "音乐分享";
        }
        String owner = trim(item == null ? null : item.ownerName);
        if (owner.length() == 0) {
            owner = trim(item == null ? null : item.ownerUid);
        }
        if (owner.length() == 0) {
            owner = "未知歌手";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("歌曲: ").append(title);
        sb.append("\n歌手: ").append(owner);
        String duration = formatDuration(item == null ? 0 : item.durationMs);
        if (duration.length() > 0) {
            sb.append("\n时长: ").append(duration);
        }
        String cover = normalizeMusicCoverUrl(item == null ? null : item.coverUrl);
        if (cover.length() > 0) {
            sb.append("\n封面: ").append(cover);
        }
        String itemId = trim(item == null ? null : item.id);
        if (itemId.length() > 0) {
            sb.append("\n歌曲ID: ").append(itemId);
        }
        String lyrics = trim(item == null ? null : item.lyricsUrl);
        if (lyrics.length() > 0) {
            sb.append("\n歌词: ").append(lyrics);
        }
        String ownerUid = trim(item == null ? null : item.ownerUid);
        if (ownerUid.length() > 0) {
            sb.append("\n发布者UID: ").append(ownerUid);
        }
        sb.append("\n点击播放");
        return sb.toString();
    }

    private static String formatDuration(int durationMs) {
        if (durationMs <= 0) {
            return "";
        }
        int totalSeconds = durationMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 99) {
            minutes = 99;
        }
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static String normalizeMusicCoverUrl(String raw) {
        String out = trim(raw);
        if (out.length() == 0) {
            return "";
        }
        if (out.length() > 1) {
            if ((out.startsWith("\"") && out.endsWith("\"")) || (out.startsWith("'") && out.endsWith("'"))) {
                out = out.substring(1, out.length() - 1).trim();
            }
        }
        out = out.replace("\\u0026", "&");
        out = out.replace("\\/", "/");
        if (out.startsWith("v1/") || out.startsWith("music/") || out.startsWith("uploads/")) {
            out = "/" + out;
        }
        if (out.startsWith("/v1/uploads/media/")) {
            String name = out.substring("/v1/uploads/media/".length()).trim();
            if (name.length() > 0 && name.indexOf('/') < 0 && name.indexOf('\\') < 0) {
                return "/v1/music/cover/" + name;
            }
        } else if (out.startsWith("/uploads/media/")) {
            String name = out.substring("/uploads/media/".length()).trim();
            if (name.length() > 0 && name.indexOf('/') < 0 && name.indexOf('\\') < 0) {
                return "/v1/music/cover/" + name;
            }
        } else if (out.startsWith("/music/cover/")) {
            return "/v1" + out;
        }
        return out;
    }
}
