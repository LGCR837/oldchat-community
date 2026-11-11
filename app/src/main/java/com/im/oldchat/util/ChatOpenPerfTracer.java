package com.im.oldchat.util;

import android.content.Context;

public final class ChatOpenPerfTracer {
    private final Context appContext;
    private final String space;
    private final String targetKey;
    private final long openedAtMs;
    private boolean finished;

    public ChatOpenPerfTracer(Context context, String space, String targetKey) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.space = space == null ? "" : space;
        this.targetKey = sanitize(targetKey);
        this.openedAtMs = ScreenPerfTracer.now();
        trace("open_begin", null, openedAtMs);
    }

    public long now() {
        return ScreenPerfTracer.now();
    }

    public void trace(String stage, String detail) {
        trace(stage, detail, openedAtMs);
    }

    public void traceFrom(String stage, String detail, long startedAtMs) {
        trace(stage, detail, startedAtMs);
    }

    public void finish(String detail) {
        if (finished) {
            return;
        }
        finished = true;
        trace("open_ready", detail, openedAtMs);
    }

    private void trace(String stage, String detail, long startedAtMs) {
        StringBuilder sb = new StringBuilder();
        if (targetKey.length() > 0) {
            sb.append("target=").append(targetKey);
        }
        if (detail != null && detail.length() > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(detail);
        }
        ScreenPerfTracer.trace(appContext, space, stage, startedAtMs, sb.toString());
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        if (out.length() == 0) {
            return "";
        }
        return out.replace(' ', '_');
    }
}
