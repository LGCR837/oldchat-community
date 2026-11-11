package com.im.oldchat.ui;

import android.content.Context;
import android.widget.Toast;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupMember;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class GroupManageApi {
    public interface GroupInfoCallback {
        void onLoaded(Group group);
    }

    public interface MembersCallback {
        void onLoaded(List<GroupMember> members);
    }

    public void loadGroupInfo(final Context context, String token, final String groupId, final GroupInfoCallback callback) {
        HttpUtil.get("/groups/list", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("groups");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject gObj = arr.getJSONObject(i);
                        if (!groupId.equals(gObj.optString("group_id"))) {
                            continue;
                        }
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
                        if (callback != null) {
                            callback.onLoaded(g);
                        }
                        break;
                    }
                } catch (Exception e) {
                }
            }

            @Override
            public void onError(int code, String error) {
            }
        });
    }

    public void loadMembers(final Context context, String token, final String groupId, final MembersCallback callback) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        HttpUtil.get("/groups/members?group_id=" + groupId, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("members");
                    List<GroupMember> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject mObj = arr.getJSONObject(i);
                        GroupMember m = new GroupMember();
                        m.uid = mObj.optString("uid");
                        m.username = mObj.optString("username");
                        m.display_name = mObj.optString("display_name");
                        m.user_title = mObj.optString("user_title");
                        m.avatar_url = mObj.optString("avatar_url");
                        m.role = mObj.optInt("role", 0);
                        m.joined_at = mObj.optLong("joined_at", 0);
                        list.add(m);
                    }
                    if (callback != null) {
                        callback.onLoaded(list);
                    }
                } catch (Exception e) {
                    Toast.makeText(context, "加载成员失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(context, "加载成员失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void updateSettings(final Context context, String token, String groupId, boolean joinApproval, boolean globalMute) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("join_approval", joinApproval);
            json.put("global_mute", globalMute);
            HttpUtil.post("/groups/settings", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(context, "更新失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(context, "更新失败", Toast.LENGTH_SHORT).show();
        }
    }

    public void updateAnnouncement(final Context context, String token, String groupId, String announcement, int mode,
                                   final Runnable onSuccess, final Runnable onError) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("announcement", announcement == null ? "" : announcement);
            json.put("announcement_mode", mode);
            HttpUtil.post("/groups/announcement", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (onError != null) {
                        onError.run();
                    }
                }
            });
        } catch (Exception e) {
            if (onError != null) {
                onError.run();
            }
        }
    }

    public void updateGroupName(final Context context, String token, String groupId, String name,
                                final Runnable onSuccess, final Runnable onError) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("name", name == null ? "" : name);
            HttpUtil.post("/groups/name", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (onError != null) {
                        onError.run();
                    } else if (context != null) {
                        Toast.makeText(context, "修改失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            if (onError != null) {
                onError.run();
            } else if (context != null) {
                Toast.makeText(context, "修改失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void markAnnouncementRead(final Context context, String token, String groupId,
                                     final Runnable onSuccess, final Runnable onError) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            HttpUtil.post("/groups/announcement/read", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (onError != null) {
                        onError.run();
                    }
                }
            });
        } catch (Exception e) {
            if (onError != null) {
                onError.run();
            }
        }
    }

    public void kickMember(final Context context, String token, final String groupId, GroupMember member, final Runnable onSuccess) {
        if (groupId == null || member == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("user_uid", member.uid);
            if (member.ncuid != null && member.ncuid.length() > 0) {
                json.put("user_ncuid", member.ncuid);
            }
            HttpUtil.post("/groups/kick", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(context, "操作失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    public void setAdmin(final Context context, String token, final String groupId, GroupMember member, boolean makeAdmin,
                         final Runnable onSuccess) {
        if (groupId == null || member == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("user_uid", member.uid);
            if (member.ncuid != null && member.ncuid.length() > 0) {
                json.put("user_ncuid", member.ncuid);
            }
            json.put("admin", makeAdmin);
            HttpUtil.post("/groups/admin", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(context, "操作失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }
}
