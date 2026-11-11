package com.im.oldchat.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.data.ChatBackgroundStore;

final class UiSettingsBackgroundSupport {
    static final int REQ_PICK_GLOBAL_CHAT_BG = 4101;
    static final int REQ_PICK_GLOBAL_GROUP_BG = 4102;

    private UiSettingsBackgroundSupport() {}

    static void bind(final UiSettingsActivity activity, View rowChatBg, View rowGroupChatBg,
                     final TextView tvChatBgValue, final TextView tvGroupChatBgValue) {
        updateLabels(activity, tvChatBgValue, tvGroupChatBgValue);
        if (rowChatBg != null) {
            rowChatBg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDialog(activity, false, tvChatBgValue, tvGroupChatBgValue);
                }
            });
        }
        if (rowGroupChatBg != null) {
            rowGroupChatBg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDialog(activity, true, tvChatBgValue, tvGroupChatBgValue);
                }
            });
        }
    }

    static void showDialog(final UiSettingsActivity activity, final boolean isGroup,
                           final TextView tvChatBgValue, final TextView tvGroupChatBgValue) {
        boolean hasBg = ChatBackgroundStore.hasGlobalBackground(activity, isGroup);
        String[] items;
        if (hasBg) {
            items = new String[]{
                activity.getString(R.string.chat_background_set),
                activity.getString(R.string.chat_background_clear)
            };
        } else {
            items = new String[]{activity.getString(R.string.chat_background_set)};
        }
        int titleRes = isGroup ? R.string.chat_background_global_group_title : R.string.chat_background_global_title;
        new AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        pickImage(activity, isGroup);
                    } else {
                        ChatBackgroundStore.clearGlobalBackground(activity, isGroup);
                        updateLabels(activity, tvChatBgValue, tvGroupChatBgValue);
                        Toast.makeText(activity, R.string.chat_background_clear_success, Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .show();
    }

    private static void pickImage(Activity activity, boolean isGroup) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(Intent.createChooser(intent, activity.getString(R.string.chat_background_set)),
                    isGroup ? REQ_PICK_GLOBAL_GROUP_BG : REQ_PICK_GLOBAL_CHAT_BG);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.error_pick_image, Toast.LENGTH_SHORT).show();
        }
    }

    static void updateLabels(UiSettingsActivity activity, TextView tvChatBgValue, TextView tvGroupChatBgValue) {
        if (tvChatBgValue != null) {
            tvChatBgValue.setText(ChatBackgroundStore.hasGlobalBackground(activity, false)
                    ? R.string.chat_background_status_set
                    : R.string.chat_background_status_empty);
        }
        if (tvGroupChatBgValue != null) {
            tvGroupChatBgValue.setText(ChatBackgroundStore.hasGlobalBackground(activity, true)
                    ? R.string.chat_background_status_set
                    : R.string.chat_background_status_empty);
        }
    }

    static boolean handleActivityResult(UiSettingsActivity activity, int requestCode, int resultCode, Intent data,
                                        TextView tvChatBgValue, TextView tvGroupChatBgValue) {
        if (resultCode != Activity.RESULT_OK || data == null) return false;
        Uri uri = data.getData();
        if (uri == null) return false;
        boolean handled = false;
        if (requestCode == REQ_PICK_GLOBAL_CHAT_BG) {
            handled = ChatBackgroundStore.saveGlobalBackground(activity, false, uri);
        } else if (requestCode == REQ_PICK_GLOBAL_GROUP_BG) {
            handled = ChatBackgroundStore.saveGlobalBackground(activity, true, uri);
        }
        if (handled) {
            Toast.makeText(activity, R.string.chat_background_set_success, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_GLOBAL_CHAT_BG || requestCode == REQ_PICK_GLOBAL_GROUP_BG) {
            Toast.makeText(activity, R.string.error_save_image, Toast.LENGTH_SHORT).show();
        }
        updateLabels(activity, tvChatBgValue, tvGroupChatBgValue);
        return handled;
    }
}
