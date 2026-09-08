package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.TextView;
import android.widget.Toast;

final class ProfileQrNavigator {
    private ProfileQrNavigator() {
    }

    static void openMyQrCard(Activity activity, String currentUid, String currentDisplayName, TextView tvUser) {
        if (activity == null) {
            return;
        }
        String uid = currentUid == null ? "" : currentUid.trim();
        if (uid.length() == 0) {
            SharedPreferences prefs = activity.getSharedPreferences("auth", Activity.MODE_PRIVATE);
            uid = prefs.getString("my_uid", "");
            uid = uid == null ? "" : uid.trim();
        }
        if (uid.length() == 0) {
            Toast.makeText(activity, "\u6682\u65E0\u53EF\u7528\u7684\u0055\u0049\u0044", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = currentDisplayName;
        if (name == null || name.trim().length() == 0) {
            name = tvUser == null || tvUser.getText() == null ? "" : tvUser.getText().toString();
        }
        Intent intent = new Intent(activity, QrCardActivity.class);
        intent.putExtra(QrCardActivity.EXTRA_MODE, com.im.oldchat.util.QrPayloadUtil.TYPE_USER);
        intent.putExtra(QrCardActivity.EXTRA_ID, uid);
        intent.putExtra(QrCardActivity.EXTRA_NAME, name == null ? "" : name.trim());
        activity.startActivity(intent);
    }
}
