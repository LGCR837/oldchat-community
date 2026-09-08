package com.im.oldchat.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

final class ImageLoadPerfLogger {
    private static final String TAG = "OldChatImagePerf";
    private static final long SLOW_LOAD_MS = 1200L;
    private static final long SLOW_DECODE_MS = 450L;
    private static final long LOG_THROTTLE_MS = 8000L;
    private static final int MAX_RECENT_LOGS = 128;
    private static final Object LOG_LOCK = new Object();
    private static final Map<String, Long> RECENT_LOGS = new LinkedHashMap<String, Long>(MAX_RECENT_LOGS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_RECENT_LOGS;
        }
    };

    private ImageLoadPerfLogger() {
    }

    static void logLoad(Context context, String stage, String url, String source,
                        long startedAtMs, long byteCount, int maxSize, int maxBytes,
                        boolean success) {
        long costMs = startedAtMs > 0L ? SystemClock.uptimeMillis() - startedAtMs : -1L;
        if (success && costMs >= 0L && costMs < SLOW_LOAD_MS) {
            return;
        }
        String result = success ? "ok" : "fail";
        String key = stage + '|' + result + '|' + normalizeUrlKey(url) + '|' + source;
        if (!shouldLog(key)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("stage=").append(stage);
        sb.append(" result=").append(result);
        if (costMs >= 0L) {
            sb.append(" cost=").append(costMs).append("ms");
        }
        appendSource(sb, source);
        appendUrl(sb, url);
        appendNumber(sb, "bytes", byteCount);
        appendPositive(sb, "max", maxSize);
        appendPositive(sb, "maxBytes", maxBytes);
        write(context, success ? "I" : "W", sb.toString(), null);
    }

    static void logFailure(Context context, String stage, String url, Throwable error) {
        String key = stage + "|error|" + normalizeUrlKey(url);
        if (!shouldLog(key)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("stage=").append(stage);
        sb.append(" result=error");
        appendUrl(sb, url);
        if (error != null) {
            sb.append(" error=").append(error.getClass().getSimpleName());
        }
        write(context, "E", sb.toString(), error instanceof OutOfMemoryError ? null : error);
    }

    static void logDecode(Context context, String stage, String url, long costMs,
                          int width, int height, int maxSize, int maxBytes,
                          int sample, Bitmap.Config config, int attempt,
                          String result, Throwable error) {
        boolean important = !"ok".equals(result) || attempt > 1 || costMs >= SLOW_DECODE_MS;
        if (!important) {
            return;
        }
        String key = stage + '|' + result + '|' + normalizeUrlKey(url) + '|' + sample + '|' + attempt;
        if (!shouldLog(key)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("stage=").append(stage);
        sb.append(" result=").append(result);
        if (costMs >= 0L) {
            sb.append(" cost=").append(costMs).append("ms");
        }
        appendUrl(sb, url);
        appendDims(sb, width, height);
        appendPositive(sb, "max", maxSize);
        appendPositive(sb, "maxBytes", maxBytes);
        appendPositive(sb, "sample", sample);
        if (config != null) {
            sb.append(" config=").append(config.name());
        }
        appendPositive(sb, "attempt", attempt);
        if (error != null) {
            sb.append(" error=").append(error.getClass().getSimpleName());
        }
        String level = "ok".equals(result) ? "I" : ("oom".equals(result) ? "E" : "W");
        write(context, level, sb.toString(), error instanceof OutOfMemoryError ? null : error);
    }

    private static boolean shouldLog(String key) {
        long now = SystemClock.uptimeMillis();
        synchronized (LOG_LOCK) {
            Long lastAt = RECENT_LOGS.get(key);
            if (lastAt != null && now - lastAt.longValue() < LOG_THROTTLE_MS) {
                return false;
            }
            RECENT_LOGS.put(key, Long.valueOf(now));
            return true;
        }
    }

    private static String normalizeUrlKey(String url) {
        if (url == null || url.length() == 0) {
            return "";
        }
        int queryIndex = url.indexOf('?');
        String normalized = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        if (normalized.length() <= 96) {
            return normalized;
        }
        return normalized.substring(normalized.length() - 96);
    }

    private static void appendUrl(StringBuilder sb, String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        String compact = url;
        int queryIndex = compact.indexOf('?');
        if (queryIndex >= 0) {
            compact = compact.substring(0, queryIndex);
        }
        if (compact.length() > 120) {
            compact = compact.substring(0, 72) + "..." + compact.substring(compact.length() - 32);
        }
        sb.append(" url=").append(compact);
    }

    private static void appendSource(StringBuilder sb, String source) {
        if (source == null || source.length() == 0) {
            return;
        }
        sb.append(" source=").append(source);
    }

    private static void appendDims(StringBuilder sb, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        sb.append(" dims=").append(width).append('x').append(height);
    }

    private static void appendPositive(StringBuilder sb, String key, int value) {
        if (value <= 0) {
            return;
        }
        sb.append(' ').append(key).append('=').append(value);
    }

    private static void appendNumber(StringBuilder sb, String key, long value) {
        if (value <= 0L) {
            return;
        }
        sb.append(' ').append(key).append('=').append(value);
    }

    private static void write(Context context, String level, String message, Throwable error) {
        try {
            if (context != null) {
                LogManager logManager = LogManager.getInstance(context.getApplicationContext());
                if ("E".equals(level)) {
                    logManager.e(TAG, message, error);
                } else if ("W".equals(level)) {
                    logManager.w(TAG, message);
                } else {
                    logManager.i(TAG, message);
                }
                return;
            }
        } catch (Throwable ignored) {
        }
        if ("E".equals(level)) {
            Log.e(TAG, message, error);
        } else if ("W".equals(level)) {
            Log.w(TAG, message);
        } else {
            Log.i(TAG, message);
        }
    }
}
