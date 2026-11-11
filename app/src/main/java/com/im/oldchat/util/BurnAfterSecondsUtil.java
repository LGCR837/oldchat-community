package com.im.oldchat.util;

public final class BurnAfterSecondsUtil {
    private BurnAfterSecondsUtil() {
    }

    public static int normalize(int seconds) {
        switch (seconds) {
            case 5:
            case 10:
            case 20:
            case 30:
            case 60:
            case 300:
                return seconds;
            default:
                return 0;
        }
    }

    public static boolean isEnabled(int seconds) {
        return normalize(seconds) > 0;
    }
}
