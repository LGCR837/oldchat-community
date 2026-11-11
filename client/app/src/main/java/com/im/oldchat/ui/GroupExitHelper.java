package com.im.oldchat.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.support.v7.app.AlertDialog;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.MessageHistoryCache;
import org.json.JSONObject;

public class GroupExitHelper {
    public void confirmLeaveGroup(final Activity activity, final String groupId, final String token) {
        new AlertDialog.Builder(activity, R.style.AppDialogTheme)
                .setTitle(R.string.group_manage_exit)
                .setMessage(R.string.group_manage_exit_confirm)
                .setPositiveButton(R.string.group_manage_exit_action, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        leaveGroup(activity, groupId, token);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void confirmDissolveGroup(final Activity activity, final String groupId, final String token, int myRole) {
        if (myRole != 2) {
            Toast.makeText(activity, R.string.group_manage_owner_only, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity, R.style.AppDialogTheme)
                .setTitle(R.string.group_manage_dissolve)
                .setMessage(R.string.group_manage_dissolve_confirm)
                .setPositiveButton(R.string.group_manage_dissolve_action, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dissolveGroup(activity, groupId, token);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void leaveGroup(final Activity activity, final String groupId, String token) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        final ProgressDialog progress = ProgressDialog.show(activity, null,
                activity.getString(R.string.group_manage_exit_loading), true, false);
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            HttpUtil.post("/groups/leave", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    progress.dismiss();
                    GroupRecentChatCache.remove(activity, groupId);
                    GroupCache.remove(activity, groupId);
                    MessageHistoryCache.removeGroupMessages(activity, groupId);
                    Toast.makeText(activity, R.string.group_manage_exit_success, Toast.LENGTH_SHORT).show();
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }

                @Override
                public void onError(int code, String error) {
                    progress.dismiss();
                    if (error != null && error.contains("owner_cannot_leave")) {
                        Toast.makeText(activity, R.string.group_manage_exit_owner, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity, R.string.group_manage_exit_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            progress.dismiss();
            Toast.makeText(activity, R.string.group_manage_exit_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void dissolveGroup(final Activity activity, final String groupId, String token) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        final ProgressDialog progress = ProgressDialog.show(activity, null,
                activity.getString(R.string.group_manage_dissolve_loading), true, false);
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            HttpUtil.post("/groups/dissolve", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    progress.dismiss();
                    GroupRecentChatCache.remove(activity, groupId);
                    GroupCache.remove(activity, groupId);
                    MessageHistoryCache.removeGroupMessages(activity, groupId);
                    Toast.makeText(activity, R.string.group_manage_dissolve_success, Toast.LENGTH_SHORT).show();
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }

                @Override
                public void onError(int code, String error) {
                    progress.dismiss();
                    Toast.makeText(activity, R.string.group_manage_dissolve_failed, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            progress.dismiss();
            Toast.makeText(activity, R.string.group_manage_dissolve_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
