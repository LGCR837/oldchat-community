package com.im.oldchat.ui;

import android.app.Activity;
import android.support.v7.app.AlertDialog;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Moment;

import org.json.JSONObject;

import java.util.List;

final class MomentsDeleteHelper {
    private MomentsDeleteHelper() {
    }

    static boolean isOwnMoment(String myUid, String myNcuid, Moment moment) {
        if (moment == null) return false;
        if (myNcuid != null && myNcuid.length() > 0
                && moment.from_ncuid != null && moment.from_ncuid.length() > 0) {
            return myNcuid.equalsIgnoreCase(moment.from_ncuid);
        }
        return myUid != null && myUid.length() > 0
                && myUid.equalsIgnoreCase(moment.from_uid);
    }

    static void showMomentOptions(final Activity activity, final String token,
                                  final String myUid, final String myNcuid,
                                  final Moment moment, final int index,
                                  final List<Moment> moments, final MomentAdapter adapter) {
        if (activity == null || moment == null) {
            return;
        }
        final boolean isOwn = isOwnMoment(myUid, myNcuid, moment);
        final String[] options = isOwn ? new String[]{"删除动态"} : new String[]{"举报动态"};

        new AlertDialog.Builder(activity)
                .setTitle("动态操作")
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (isOwn) {
                            confirmDeleteMoment(activity, token, moment, index, moments, adapter);
                        } else {
                            String evidence = moment.body != null && moment.body.length() > 0
                                    ? moment.body : "[图片动态]";
                            ReportActivity.start(activity, moment.from_uid, moment.from_ncuid,
                                    moment.from_name, moment.from_avatar, evidence, "moment:" + moment.id);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    static void confirmDeleteMoment(final Activity activity, final String token,
                                    final Moment moment, final int index,
                                    final List<Moment> moments, final MomentAdapter adapter) {
        final Moment target = moment;
        final int targetIndex = index;
        new AlertDialog.Builder(activity)
                .setTitle("删除动态")
                .setMessage("确定删除这条动态吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteMoment(activity, token, target, targetIndex, moments, adapter);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void deleteMoment(final Activity activity, String token,
                                     Moment moment, int index,
                                     final List<Moment> moments, final MomentAdapter adapter) {
        if (moment == null || moment.id == null || moment.id.isEmpty()) {
            return;
        }
        final int targetIndex = index;
        try {
            JSONObject json = new JSONObject();
            json.put("moment_id", moment.id);
            HttpUtil.post("/moments/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (targetIndex >= 0 && targetIndex < moments.size()) {
                        moments.remove(targetIndex);
                        adapter.notifyDataSetChanged();
                    }
                    Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(activity, "删除失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }
}
