package com.im.oldchat.ui.fragments;

import com.im.oldchat.models.Group;
import com.im.oldchat.models.User;
import com.im.oldchat.ui.FriendAdapter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class FriendsApiParser {
    public static List<Group> parseGroups(JSONArray arr) throws Exception {
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject gObj = arr.getJSONObject(i);
            Group g = new Group();
            g.id = gObj.optString("group_id");
            g.name = gObj.optString("name");
            g.avatar_url = gObj.optString("avatar_url");
            g.join_approval = gObj.optBoolean("join_approval", false);
            g.global_mute = gObj.optBoolean("global_mute", false);
            g.role = gObj.optInt("role", 0);
            g.announcement = gObj.optString("announcement", "");
            g.announcement_mode = gObj.optInt("announcement_mode", 0);
            g.announcement_updated_at = gObj.optLong("announcement_updated_at", 0);
            g.announcement_read_at = gObj.optLong("announcement_read_at", 0);
            g.member_count = gObj.optInt("member_count", 0);
            g.message_seq = gObj.optLong("message_seq", 0L);
            g.member_version = gObj.optLong("member_version", 0L);
            groups.add(g);
        }
        return groups;
    }

    public static List<User> parseFriends(JSONArray arr) throws Exception {
        List<User> friends = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject uObj = arr.getJSONObject(i);
            User u = new User();
            u.id = uObj.optString("id", uObj.optString("user_id", ""));
            u.uid = uObj.optString("uid", "");
            u.ncuid = uObj.optString("ncuid", "");
            u.username = uObj.optString("username", "");
            u.display_name = uObj.optString("display_name");
            u.remark_name = uObj.optString("remark_name", "");
            u.user_title = uObj.optString("user_title");
            u.avatar_url = uObj.optString("avatar_url");
            u.friend_added_at = uObj.optLong("friend_added_at", 0);
            u.is_online = uObj.optBoolean("is_online", false);
            u.presence_status = uObj.optString("presence_status", "online");
            if (u.uid != null && !u.uid.isEmpty()) {
                friends.add(u);
            }
        }
        return friends;
    }

    public static List<FriendAdapter.FriendRequestItem> parseRequests(JSONArray arr) throws Exception {
        List<FriendAdapter.FriendRequestItem> requests = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rObj = arr.getJSONObject(i);
            if (rObj.getInt("status") == 0) {
                String requestId = rObj.optString("id", "");
                if (requestId.isEmpty()) {
                    continue;
                }
                String fromUid = rObj.optString("from_uid", "");
                String fromUsername = rObj.optString("from_username", "");
                String fromDisplayName = rObj.optString("from_display_name", "");
                String fromTitle = rObj.optString("from_title", "");
                String avatarUrl = rObj.optString("avatar_url", "");
                JSONObject fromUser = rObj.optJSONObject("from_user");
                if (fromUser != null) {
                    if (fromUid.isEmpty()) {
                        fromUid = fromUser.optString("uid", "");
                    }
                    if (fromUsername.isEmpty()) {
                        fromUsername = fromUser.optString("username", "");
                    }
                    if (fromDisplayName.isEmpty()) {
                        fromDisplayName = fromUser.optString("display_name", "");
                    }
                    if (fromTitle.isEmpty()) {
                        fromTitle = fromUser.optString("user_title", "");
                    }
                    if (avatarUrl.isEmpty()) {
                        avatarUrl = fromUser.optString("avatar_url", "");
                    }
                }
                String name = !fromDisplayName.isEmpty() ? fromDisplayName :
                        (!fromUsername.isEmpty() ? fromUsername : fromUid);
                requests.add(new FriendAdapter.FriendRequestItem(
                        requestId,
                        fromUid,
                        name,
                        fromTitle,
                        avatarUrl
                ));
            }
        }
        return requests;
    }

    public static List<FriendAdapter.FriendRequestItem> parseGroupInvitations(JSONArray arr) throws Exception {
        List<FriendAdapter.FriendRequestItem> requests = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String id = obj.optString("id", "");
            if (id.length() == 0 || obj.optInt("status", 0) != 0) continue;
            requests.add(FriendAdapter.FriendRequestItem.groupInvite(
                    id, obj.optString("group_id", ""), obj.optString("group_name", ""),
                    obj.optString("group_avatar_url", ""), obj.optString("inviter_uid", ""),
                    obj.optString("inviter_display_name", obj.optString("inviter_uid", ""))));
        }
        return requests;
    }
}
