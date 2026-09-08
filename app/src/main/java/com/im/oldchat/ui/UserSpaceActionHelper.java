package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.User;
import org.json.JSONObject;
import java.util.List;

class UserSpaceActionHelper {
    private UserSpaceActionHelper() {
    }

    static boolean isFriend(Context context, String uid, String ncuid) {
        if (context == null) {
            return false;
        }
        List<User> friends = FriendCache.getFriends(context);
        for (User u : friends) {
            if (ncuid != null && ncuid.length() > 0 && u.ncuid != null
                    && ncuid.equalsIgnoreCase(u.ncuid)) {
                return true;
            }
            if ((ncuid == null || ncuid.length() == 0 || u.ncuid == null || u.ncuid.length() == 0)
                    && uid != null && uid.equalsIgnoreCase(u.uid)) {
                return true;
            }
        }
        return false;
    }

    static void bindActions(UserSpaceActivity activity, View btnPrimary, View btnReport,
                            boolean isFriend, String profileUid, String profileNcuid,
                            String profileName, String profileAvatar, String token) {
        if (activity == null) {
            return;
        }
        final UserSpaceActivity activityFinal = activity;
        final String profileUidFinal = profileUid;
        final String profileNcuidFinal = profileNcuid;
        final String profileNameFinal = profileName;
        final String profileAvatarFinal = profileAvatar;
        final String tokenFinal = token;
        if (btnPrimary != null) {
            if (isFriend) {
                if (btnPrimary instanceof TextView) {
                    ((TextView) btnPrimary).setText("发消息");
                }
                btnPrimary.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openChat(activityFinal, profileUidFinal, profileNcuidFinal, profileNameFinal, profileAvatarFinal);
                    }
                });
            } else {
                if (btnPrimary instanceof TextView) {
                    ((TextView) btnPrimary).setText("加好友");
                }
                btnPrimary.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendFriendRequest(activityFinal, tokenFinal, profileUidFinal, profileNcuidFinal);
                    }
                });
            }
        }
        if (btnReport != null) {
            btnReport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showReportDialog(activityFinal, profileUidFinal, profileNcuidFinal, profileNameFinal, profileAvatarFinal);
                }
            });
        }
    }

    private static void openChat(Context context, String uid, String ncuid, String name, String avatar) {
        // 当前私聊本地缓存和部分旧页面仍需要 UID；资料接口返回前不提前进入半初始化会话。
        if (context == null || uid == null || uid.isEmpty()) {
            return;
        }
        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra("friend_uid", uid);
        intent.putExtra("friend_ncuid", ncuid == null ? "" : ncuid);
        intent.putExtra("friend_name", name);
        intent.putExtra("friend_avatar", avatar);
        context.startActivity(intent);
    }

    private static void sendFriendRequest(Context context, String token, String uid, String ncuid) {
        if (context == null || ((uid == null || uid.isEmpty()) && (ncuid == null || ncuid.isEmpty()))) {
            return;
        }
        final Context contextFinal = context;
        try {
            JSONObject json = new JSONObject();
            json.put("to_uid", uid);
            if (ncuid != null && ncuid.length() > 0) {
                json.put("to_ncuid", ncuid);
            }
            HttpUtil.post("/friends/request", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(contextFinal, R.string.friend_request_sent, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (code == 409) {
                        if (FriendRequestErrorHelper.isPending(error)) {
                            Toast.makeText(contextFinal, R.string.friend_request_pending_processing, Toast.LENGTH_SHORT).show();
                        } else if (FriendRequestErrorHelper.isAlreadyFriend(error)) {
                            if (contextFinal instanceof UserSpaceActivity) {
                                ((UserSpaceActivity) contextFinal).markFriendConfirmed();
                            }
                            Toast.makeText(contextFinal, R.string.friend_request_conflict, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(contextFinal, R.string.friend_request_conflict, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(contextFinal, R.string.friend_request_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            Toast.makeText(contextFinal, R.string.friend_request_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void showReportDialog(UserSpaceActivity activity, String targetUid, String targetNcuid,
                                         String targetName, String targetAvatar) {
        if (activity == null || ((targetUid == null || targetUid.isEmpty())
                && (targetNcuid == null || targetNcuid.isEmpty()))) {
            return;
        }
        String displayName = targetName;
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = !TextUtils.isEmpty(targetUid) ? targetUid : targetNcuid;
        }
        String evidence = "【用户主页举报】对该用户的个人空间资料、昵称、签名或空间动态违规提请法庭审理。";
        String sourceTag = "来源：个人空间主页";
        ReportActivity.start(activity, targetUid, targetNcuid, displayName, targetAvatar, evidence, sourceTag);
    }
}
