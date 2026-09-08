package com.im.oldchat.ui;

import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import com.im.oldchat.BaseActivity;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.ImageView;
import android.support.v7.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.data.ChatBackgroundStore;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.User;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.ImageLoader;
import org.json.JSONObject;

import java.util.List;

public class ChatSettingsActivity extends BaseActivity {
    private static final int REQ_PICK_CHAT_BG = 3001;

    private ImageView ivFriendAvatar;
    private TextView tvFriendName;
    private TextView tvFriendUid;
    private SwitchCompat switchMuteNotify;
    private View btnChatBackground;
    private View btnSetRemark;
    private View btnDeleteFriend;
    private View btnSearchMessages;
    private View friendCard;

    private String token;
    private String friendUID;
    private String friendNcuid;
    private String friendName;
    private String friendAvatar;
    private boolean loadingSettings = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_settings);

        ivFriendAvatar = findViewByIdCompat(R.id.ivFriendAvatar);
        tvFriendName = findViewByIdCompat(R.id.tvFriendName);
        tvFriendUid = findViewByIdCompat(R.id.tvFriendUid);
        switchMuteNotify = findViewByIdCompat(R.id.switchMuteNotify);
        btnChatBackground = findViewByIdCompat(R.id.btnChatBackground);
        btnSetRemark = findViewByIdCompat(R.id.btnSetRemark);
        btnDeleteFriend = findViewByIdCompat(R.id.btnDeleteFriend);
        btnSearchMessages = findViewByIdCompat(R.id.btnSearchMessages);
        friendCard = findViewByIdCompat(R.id.friend_card);

        View btnBack = (View) findViewByIdCompat(R.id.btnBack);
        if (btnBack instanceof ImageView) {
            ((ImageView) btnBack).setColorFilter(getResources().getColor(R.color.color_text_primary));
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        friendUID = getIntent().getStringExtra("friend_uid");
        friendNcuid = getIntent().getStringExtra("friend_ncuid");
        friendName = getIntent().getStringExtra("friend_name");
        friendAvatar = getIntent().getStringExtra("friend_avatar");

        if (friendAvatar == null || friendAvatar.isEmpty() || friendName == null || friendName.isEmpty()) {
            com.im.oldchat.models.User cachedFriend = com.im.oldchat.models.FriendCache.getFriend(this, friendUID);
            if (cachedFriend != null) {
                if (friendName == null || friendName.isEmpty()) {
                    if (cachedFriend.remark_name != null && !cachedFriend.remark_name.isEmpty()) {
                        friendName = cachedFriend.remark_name;
                    } else if (cachedFriend.display_name != null && !cachedFriend.display_name.isEmpty()) {
                        friendName = cachedFriend.display_name;
                    } else {
                        friendName = cachedFriend.username;
                    }
                }
                if (friendAvatar == null || friendAvatar.isEmpty()) {
                    friendAvatar = cachedFriend.avatar_url;
                }
            }
        }

        tvFriendName.setText(friendName == null ? "" : friendName);
        tvFriendUid.setText(friendUID == null ? "" : friendUID);
        setTitle(friendName == null ? "" : friendName);
        if (friendAvatar != null && !friendAvatar.isEmpty()) {
            ImageLoader.loadAvatarCacheFirstTryOnce(ivFriendAvatar, friendAvatar);
        } else {
            ivFriendAvatar.setImageResource(R.drawable.default_user);
        }

        com.im.oldchat.util.ChatHeaderEnterAnimHelper.runEnterAnimation(this, ivFriendAvatar, tvFriendName, btnBack, null);

        friendCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFriendSpace();
            }
        });

        loadingSettings = true;
        String stableConversationId = friendNcuid == null || friendNcuid.length() == 0 ? friendUID : friendNcuid;
        switchMuteNotify.setChecked(SettingsStore.isConversationMuted(this, stableConversationId, friendUID, false));
        loadingSettings = false;

        switchMuteNotify.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (!loadingSettings) {
                    String stableId = friendNcuid == null || friendNcuid.length() == 0 ? friendUID : friendNcuid;
                    SettingsStore.setConversationMuted(ChatSettingsActivity.this, stableId,
                            friendUID, false, isChecked);
                    Toast.makeText(ChatSettingsActivity.this, isChecked ? R.string.chat_muted : R.string.chat_unmuted, Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnChatBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChatBackgroundDialog();
            }
        });
        if (btnSetRemark != null) {
            btnSetRemark.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSetRemarkDialog();
                }
            });
        }
        btnDeleteFriend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteFriend();
            }
        });
        if (btnSearchMessages != null) {
            btnSearchMessages.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSearchMessages();
                }
            });
        }
    }

    private void openFriendSpace() {
        if (friendUID == null || friendUID.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, UserSpaceActivity.class);
        if (friendNcuid != null && !friendNcuid.isEmpty()) {
            // 优先用不可变 NCUID，避免好友改 UID 后查不到资料。
            intent.putExtra("ncuid", friendNcuid);
        } else {
            intent.putExtra("uid", friendUID);
        }
        startActivity(intent);
    }

    private void showChatBackgroundDialog() {
        if (friendUID == null) {
            return;
        }
        boolean hasBg = ChatBackgroundStore.hasBackground(this, friendUID, false);
        String[] items;
        if (hasBg) {
            items = new String[]{
                getString(R.string.chat_background_set),
                getString(R.string.chat_background_clear)
            };
        } else {
            items = new String[]{getString(R.string.chat_background_set)};
        }
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(R.string.chat_background_title)
            .setItems(items, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    if (which == 0) {
                        pickChatBackground();
                    } else {
                        clearChatBackground();
                    }
                }
            })
            .show();
    }

    private void pickChatBackground() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.chat_background_set)), REQ_PICK_CHAT_BG);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_pick_image, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearChatBackground() {
        ChatBackgroundStore.clearBackground(this, friendUID, false);
        Toast.makeText(this, R.string.chat_background_clear_success, Toast.LENGTH_SHORT).show();
    }

    private void openSearchMessages() {
        if (friendUID == null || friendUID.isEmpty()) {
            Toast.makeText(this, "好友ID无效", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ChatSearchActivity.class);
        intent.putExtra(ChatSearchActivity.EXTRA_MODE, ChatSearchActivity.MODE_DIRECT);
        intent.putExtra(ChatSearchActivity.EXTRA_FRIEND_UID, friendUID);
        intent.putExtra(ChatSearchActivity.EXTRA_FRIEND_NCUID, friendNcuid == null ? "" : friendNcuid);
        intent.putExtra(ChatSearchActivity.EXTRA_FRIEND_NAME, friendName);
        intent.putExtra(ChatSearchActivity.EXTRA_FRIEND_AVATAR, friendAvatar);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_CHAT_BG && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null && friendUID != null) {
                boolean success = ChatBackgroundStore.saveBackground(this, friendUID, false, uri);
                if (success) {
                    Toast.makeText(this, R.string.chat_background_set_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.error_save_image, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void confirmDeleteFriend() {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setMessage(R.string.chat_delete_friend_confirm)
            .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    deleteFriend();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void deleteFriend() {
        if (friendUID == null || friendUID.isEmpty()) {
            return;
        }
        final ProgressDialog progress = ProgressDialog.show(this, null,
            getString(R.string.chat_deleting), true, false);
        try {
            JSONObject json = new JSONObject();
            json.put("friend_uid", friendUID);
            if (friendNcuid != null && friendNcuid.length() > 0) {
                json.put("friend_ncuid", friendNcuid);
            }
            HttpUtil.post("/friends/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    progress.dismiss();
                    RecentChatCache.remove(ChatSettingsActivity.this, friendUID);
                    Toast.makeText(ChatSettingsActivity.this, R.string.chat_delete_friend_done, Toast.LENGTH_SHORT).show();
                    Intent result = new Intent();
                    result.putExtra("friend_deleted", true);
                    setResult(RESULT_OK, result);
                    finish();
                }

                @Override
                public void onError(int code, String error) {
                    progress.dismiss();
                    Toast.makeText(ChatSettingsActivity.this, R.string.error_network, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            progress.dismiss();
            Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
        }
    }

    private void showSetRemarkDialog() {
        if (friendUID == null || friendUID.isEmpty()) {
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(getString(R.string.remark_hint));
        String currentRemark = loadCurrentRemark(friendUID);
        if (currentRemark != null && currentRemark.length() > 0) {
            input.setText(currentRemark);
            input.setSelection(currentRemark.length());
        }
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.remark_set_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        submitRemark(input.getText() == null ? "" : input.getText().toString().trim());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String loadCurrentRemark(String uid) {
        List<User> users = FriendCache.getFriends(this);
        if (users == null || users.isEmpty()) {
            return "";
        }
        for (User user : users) {
            if (user == null || user.uid == null) {
                continue;
            }
            if (uid.equals(user.uid)) {
                return user.remark_name == null ? "" : user.remark_name;
            }
        }
        return "";
    }

    private void submitRemark(final String remark) {
        if (remark != null && remark.length() > 32) {
            Toast.makeText(this, "备注最多32个字", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProgressDialog progress = ProgressDialog.show(this, null, "保存中...", true, false);
        try {
            JSONObject json = new JSONObject();
            json.put("friend_uid", friendUID);
            if (friendNcuid != null && friendNcuid.length() > 0) {
                json.put("friend_ncuid", friendNcuid);
            }
            json.put("remark_name", remark == null ? "" : remark);
            HttpUtil.post("/friends/remark", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    progress.dismiss();
                    String name = updateLocalRemark(friendUID, remark);
                    if (name == null || name.length() == 0) {
                        name = friendUID;
                    }
                    friendName = name;
                    tvFriendName.setText(name == null ? "" : name);
                    setTitle(friendName == null ? "" : friendName);
                    Intent result = new Intent();
                    result.putExtra("friend_name", friendName);
                    setResult(RESULT_OK, result);
                    Toast.makeText(ChatSettingsActivity.this,
                            (remark == null || remark.length() == 0) ? "已清除备注" : "备注已更新",
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    progress.dismiss();
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (error != null && error.contains("remark_too_long")) {
                        Toast.makeText(ChatSettingsActivity.this, "备注最多32个字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(ChatSettingsActivity.this, "设置备注失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            progress.dismiss();
            Toast.makeText(this, "设置备注失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String updateLocalRemark(String uid, String remark) {
        List<User> users = FriendCache.getFriends(this);
        if (users == null || users.isEmpty()) {
            String resolved = FriendNameResolver.resolve(remark, friendName, "", uid);
            RecentChatCache.touchRecentChat(this, uid, resolved, friendAvatar);
            com.im.oldchat.models.UserNameCache.put(this, uid, resolved);
            return resolved;
        }
        boolean changed = false;
        String safeRemark = remark == null ? "" : remark;
        String mergedName = "";
        for (User user : users) {
            if (user == null || user.uid == null) {
                continue;
            }
            if (!uid.equals(user.uid)) {
                continue;
            }
            user.remark_name = safeRemark;
            mergedName = FriendNameResolver.resolve(user);
            if (mergedName == null || mergedName.length() == 0) {
                mergedName = uid;
            }
            changed = true;
            break;
        }
        if (!changed) {
            String resolved = FriendNameResolver.resolve(remark, friendName, "", uid);
            RecentChatCache.touchRecentChat(this, uid, resolved, friendAvatar);
            com.im.oldchat.models.UserNameCache.put(this, uid, resolved);
            return resolved;
        }
        FriendCache.saveFriends(this, users);
        RecentChatCache.touchRecentChat(this, uid, mergedName, friendAvatar);
        com.im.oldchat.models.UserNameCache.put(this, uid, mergedName);
        return mergedName;
    }
}
