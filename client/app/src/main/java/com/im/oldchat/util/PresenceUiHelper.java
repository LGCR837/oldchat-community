package com.im.oldchat.util;

import com.im.oldchat.R;

public class PresenceUiHelper {
    public static int resolveDotDrawable(boolean isOnline, String presenceStatus) {
        if ("invisible".equals(presenceStatus)) {
            return R.drawable.bg_presence_busy;
        }
        return isOnline ? R.drawable.bg_online_dot : R.drawable.bg_offline_dot;
    }

    public static String resolveStatusText(boolean isOnline, String presenceStatus) {
        if ("invisible".equals(presenceStatus)) {
            return "忙碌";
        }
        return isOnline ? "在线" : "离线";
    }
}
