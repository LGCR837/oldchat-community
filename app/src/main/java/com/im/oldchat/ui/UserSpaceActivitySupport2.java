package com.im.oldchat.ui;

import android.content.Intent;
import android.support.v7.app.AlertDialog;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Moment;

import org.json.JSONObject;

abstract class UserSpaceActivitySupport2 extends UserSpaceActivitySupport1 {
    @Override
    public void onLike(Moment moment) {
        if (moment == null || moment.id == null) {
            return;
        }
        final Moment momentFinal = moment;
        try {
            JSONObject json = new JSONObject();
            json.put("moment_id", momentFinal.id);
            String path = momentFinal.liked ? "/moments/unlike" : "/moments/like";
            HttpUtil.post(path, json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        momentFinal.liked = obj.optBoolean("liked", momentFinal.liked);
                        momentFinal.likes = obj.optInt("likes", momentFinal.likes);
                        adapter.notifyDataSetChanged();
                    } catch (Exception e) {
                        adapter.notifyDataSetChanged();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(UserSpaceActivitySupport2.this, "操作失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(UserSpaceActivitySupport2.this, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onComment(Moment moment) {
        if (moment == null || moment.id == null) {
            return;
        }
        Intent intent = new Intent(this, MomentCommentsActivity.class);
        intent.putExtra("moment_id", moment.id);
        intent.putExtra("moment_owner_uid", moment.from_uid);
        startActivity(intent);
    }

    @Override
    public void onAvatar(Moment moment) {
        if (moment == null || moment.from_uid == null || moment.from_uid.isEmpty()) {
            return;
        }
        boolean sameProfile = profileNcuid != null && profileNcuid.length() > 0
                && moment.from_ncuid != null && moment.from_ncuid.length() > 0
                ? profileNcuid.equalsIgnoreCase(moment.from_ncuid)
                : moment.from_uid.equalsIgnoreCase(profileUid);
        if (sameProfile) {
            return;
        }
        Intent intent = new Intent(this, UserSpaceActivity.class);
        intent.putExtra("ncuid", moment.from_ncuid);
        startActivity(intent);
    }

    @Override
    protected boolean canDeleteMoment(Moment moment) {
        if (!isSelf || moment == null) {
            return false;
        }
        if (myNcuid != null && myNcuid.length() > 0
                && moment.from_ncuid != null && moment.from_ncuid.length() > 0) {
            return myNcuid.equalsIgnoreCase(moment.from_ncuid);
        }
        return myUid != null && myUid.equalsIgnoreCase(moment.from_uid);
    }

    @Override
    protected void confirmDeleteMoment(Moment moment, int index) {
        final Moment momentFinal = moment;
        final int indexFinal = index;
        new AlertDialog.Builder(this)
                .setTitle("删除动态")
                .setMessage("确定删除这条动态吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteMoment(momentFinal, indexFinal);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    protected void deleteMoment(Moment moment, int index) {
        if (moment == null || moment.id == null || moment.id.isEmpty()) {
            return;
        }
        final int indexFinal = index;
        try {
            JSONObject json = new JSONObject();
            json.put("moment_id", moment.id);
            HttpUtil.post("/moments/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (indexFinal >= 0 && indexFinal < moments.size()) {
                        moments.remove(indexFinal);
                        // 删除最后一条时重置游标，避免翻页漏数据
                        if (momentsBeforeCursor > 0L) {
                            if (moments.isEmpty()) {
                                momentsBeforeCursor = 0L;
                            } else {
                                momentsBeforeCursor = moments.get(moments.size() - 1).created_at;
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }
                    Toast.makeText(UserSpaceActivitySupport2.this, "已删除", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(UserSpaceActivitySupport2.this, "删除失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }
}
