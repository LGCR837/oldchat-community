package com.im.oldchat.ui;

import android.content.Intent;
import android.net.Uri;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.RecentChatCache;

abstract class ChatActivityCallbacks extends ChatActivitySupport {
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (v == etInput) {
            ChatInputMenuHelper.fillContextMenu(menu);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (ChatInputMenuHelper.handleContextItem(etInput, item)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (mediaHelper != null && mediaHelper.handlePermissionsResult(requestCode, grantResults)) {
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHAT_SETTINGS && resultCode == RESULT_OK) {
            if (data != null && data.getBooleanExtra("friend_deleted", false)) {
                finish();
                return;
            }
            if (data != null) {
                String newName = data.getStringExtra("friend_name");
                if (newName != null && newName.length() > 0) {
                    friendName = newName;
                    TextView tvTitle = findViewByIdCompat(R.id.tvChatTitle);
                    if (tvTitle != null) {
                        tvTitle.setText(friendName);
                    }
                }
            }
            if (friendUID != null && friendUID.length() > 0) {
                String name = friendName != null && friendName.length() > 0 ? friendName : friendUID;
                RecentChatCache.touchRecentChat(this, friendUID, name, friendAvatar);
            }
            if (listHelper != null) {
                listHelper.loadMessages(token, false, 0, false);
            }
            return;
        }
        if (requestCode == REQ_PICK_EMOJI && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(EmojiPickerActivity.EXTRA_EMOJI_PATH);
            boolean isGif = data.getBooleanExtra(EmojiPickerActivity.EXTRA_EMOJI_IS_GIF, false);
            if (messageSender != null) {
                messageSender.sendEmojiFromPath(path, isGif, quoteDraft);
            }
            return;
        }
        if (requestCode == REQ_PICK_CHAT_BG && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (backgroundHelper != null) {
                backgroundHelper.handlePickResult(uri);
            }
            return;
        }
        if (requestCode == REQ_SEND_RED_PACKET && resultCode == RESULT_OK && data != null) {
            String payload = data.getStringExtra(RedPacketSendActivity.EXTRA_MESSAGE_JSON);
            if (payload != null && !payload.isEmpty() && listHelper != null) {
                Message sent = listHelper.parseMessageFromResponse(payload);
                if (sent != null) {
                    listHelper.appendMessage(sent, true, token);
                } else {
                    listHelper.loadMessages(token, false, 0, false);
                }
            }
            return;
        }
        if (mediaHelper != null && mediaHelper.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
    }
}
