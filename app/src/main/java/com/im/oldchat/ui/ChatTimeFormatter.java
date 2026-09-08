package com.im.oldchat.ui;

public class ChatTimeFormatter {
    private static final ThreadLocal<java.text.SimpleDateFormat> SDF_MM_DD_HH_MM = new ThreadLocal<java.text.SimpleDateFormat>() {
        @Override
        protected java.text.SimpleDateFormat initialValue() {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        }
    };

    private static final ThreadLocal<java.text.SimpleDateFormat> SDF_HH_MM = new ThreadLocal<java.text.SimpleDateFormat>() {
        @Override
        protected java.text.SimpleDateFormat initialValue() {
            return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        }
    };

    private static final ThreadLocal<java.text.SimpleDateFormat> SDF_YYYY_MM_DD_HH_MM = new ThreadLocal<java.text.SimpleDateFormat>() {
        @Override
        protected java.text.SimpleDateFormat initialValue() {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
        }
    };

    private static final ThreadLocal<java.text.SimpleDateFormat> SDF_DAY_KEY = new ThreadLocal<java.text.SimpleDateFormat>() {
        @Override
        protected java.text.SimpleDateFormat initialValue() {
            return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        }
    };

    private static final ThreadLocal<java.text.SimpleDateFormat> SDF_CHINESE_DAY = new ThreadLocal<java.text.SimpleDateFormat>() {
        @Override
        protected java.text.SimpleDateFormat initialValue() {
            return new java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.getDefault());
        }
    };

    public static String formatMonthDayHourMinute(long millis) {
        if (millis <= 0) return "";
        return SDF_MM_DD_HH_MM.get().format(new java.util.Date(millis));
    }

    public static String formatHourMinute(long millis) {
        if (millis <= 0) return "";
        return SDF_HH_MM.get().format(new java.util.Date(millis));
    }

    public static String formatYearMonthDayHourMinute(long millis) {
        if (millis <= 0) return "";
        return SDF_YYYY_MM_DD_HH_MM.get().format(new java.util.Date(millis));
    }

    public static String formatDayKey(long millis) {
        if (millis <= 0) return "";
        return SDF_DAY_KEY.get().format(new java.util.Date(millis));
    }

    public static String formatChineseDay(long millis) {
        if (millis <= 0) return "";
        return SDF_CHINESE_DAY.get().format(new java.util.Date(millis));
    }

    private ChatTimeFormatter() {
    }

    public static boolean shouldShowTime(long currentTimestamp, long prevTimestamp) {
        long currentTs = ChatMessageUtil.normalizeTimestamp(currentTimestamp);
        long prevTs = ChatMessageUtil.normalizeTimestamp(prevTimestamp);
        long diff = Math.abs(currentTs - prevTs);
        return diff > 5 * 60 * 1000;
    }

    public static String formatTime(long timestamp) {
        long normalized = ChatMessageUtil.normalizeTimestamp(timestamp);
        if (normalized <= 0) {
            return "";
        }
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(normalized);
        java.util.Calendar now = java.util.Calendar.getInstance();

        if (isSameDay(cal, now)) {
            return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE));
        } else if (isYesterday(cal, now)) {
            return "昨天 " + String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE));
        } else if (isSameYear(cal, now)) {
            return String.format("%d月%d日 %02d:%02d",
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH),
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE));
        } else {
            return String.format("%d年%d月%d日",
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH));
        }
    }

    public static String formatMessageFooterTime(long timestamp) {
        long normalized = ChatMessageUtil.normalizeTimestamp(timestamp);
        if (normalized <= 0) {
            return "";
        }
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(normalized);
        java.util.Calendar now = java.util.Calendar.getInstance();
        String time = twoDigit(cal.get(java.util.Calendar.HOUR_OF_DAY)) + ":"
                + twoDigit(cal.get(java.util.Calendar.MINUTE));
        if (isSameDay(cal, now)) {
            return time;
        }
        if (isYesterday(cal, now)) {
            return "昨天-" + time;
        }
        if (isDayBeforeYesterday(cal, now)) {
            return "前天-" + time;
        }
        if (isSameYear(cal, now)) {
            return (cal.get(java.util.Calendar.MONTH) + 1) + "-"
                    + cal.get(java.util.Calendar.DAY_OF_MONTH) + "-" + time;
        }
        return cal.get(java.util.Calendar.YEAR) + "-"
                + (cal.get(java.util.Calendar.MONTH) + 1) + "-" + time;
    }

    private static boolean isSameDay(java.util.Calendar cal1, java.util.Calendar cal2) {
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
                cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static boolean isYesterday(java.util.Calendar cal, java.util.Calendar now) {
        java.util.Calendar yesterday = (java.util.Calendar) now.clone();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        return isSameDay(cal, yesterday);
    }

    private static boolean isDayBeforeYesterday(java.util.Calendar cal, java.util.Calendar now) {
        java.util.Calendar dayBeforeYesterday = (java.util.Calendar) now.clone();
        dayBeforeYesterday.add(java.util.Calendar.DAY_OF_YEAR, -2);
        return isSameDay(cal, dayBeforeYesterday);
    }

    private static boolean isSameYear(java.util.Calendar cal1, java.util.Calendar cal2) {
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR);
    }

    private static String twoDigit(int value) {
        if (value < 10) {
            return "0" + value;
        }
        return String.valueOf(value);
    }
}
