package com.im.oldchat.ui;

import android.content.Intent;
import android.support.v7.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;

import org.json.JSONObject;

class GroupManageActivitySupport2 extends GroupManageActivitySupport {
    protected void openSearchMessages() {
        if (groupId == null || groupId.length() == 0) {
            Toast.makeText(this, "\u7FA4\u0049\u0044\u65E0\u6548", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ChatSearchActivity.class);
        intent.putExtra(ChatSearchActivity.EXTRA_MODE, ChatSearchActivity.MODE_GROUP);
        intent.putExtra(ChatSearchActivity.EXTRA_GROUP_ID, groupId);
        intent.putExtra(ChatSearchActivity.EXTRA_GROUP_NAME, groupName);
        intent.putExtra(ChatSearchActivity.EXTRA_GROUP_ROLE, myRole);
        startActivity(intent);
    }

    protected void showGroupReportDialog() {
        if (groupId == null || groupId.length() == 0) {
            Toast.makeText(this, "群ID无效", Toast.LENGTH_SHORT).show();
            return;
        }
        String avatar = com.im.oldchat.util.GroupAvatarCache.getCachedAvatar(this, groupId);
        ReportActivity.startForGroup(this, groupId, groupName, avatar,
                "【群聊信息】群名称: " + (groupName != null ? groupName : "") + "\n群ID: " + groupId,
                "来源：群聊设置 (" + groupId + ")");
    }
}
