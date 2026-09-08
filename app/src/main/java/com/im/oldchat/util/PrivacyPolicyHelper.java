package com.im.oldchat.util;

import android.app.Activity;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import android.support.v7.app.AlertDialog;
import com.im.oldchat.R;

public final class PrivacyPolicyHelper {
    private PrivacyPolicyHelper() {
    }

    public static void showPolicyDialog(final Activity activity, final Runnable onAgree) {
        if (activity == null) {
            return;
        }
        ScrollView scroll = new ScrollView(activity);
        TextView tv = new TextView(activity);
        int pad = dp(activity, 16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        try {
            tv.setTextColor(activity.getResources().getColor(R.color.color_text_primary));
        } catch (Exception e) {
            // ignore
        }
        tv.setText(R.string.privacy_policy_text);
        scroll.addView(tv);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.privacy_policy_title)
                .setView(scroll)
                .setPositiveButton(R.string.privacy_policy_agree, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (onAgree != null) {
                            onAgree.run();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int dp(Activity activity, int dp) {
        if (activity == null) {
            return dp;
        }
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                activity.getResources().getDisplayMetrics());
    }
}
