package com.im.oldchat.ui;

final class ImagePreviewReturnTracker {
    private static final Object GUARD = new Object();
    private static final long RECENT_RETURN_WINDOW_MS = 2500L;
    private static boolean launchPending = false;
    private static long lastClosedAtMs = 0L;

    private ImagePreviewReturnTracker() {
    }

    static void markLaunchRequested() {
        synchronized (GUARD) {
            launchPending = true;
        }
    }

    static void markOpened() {
        synchronized (GUARD) {
            launchPending = false;
        }
    }

    static void markClosed() {
        synchronized (GUARD) {
            launchPending = false;
            lastClosedAtMs = System.currentTimeMillis();
        }
    }

    static boolean consumeRecentReturn() {
        synchronized (GUARD) {
            if (lastClosedAtMs <= 0L) {
                return false;
            }
            long now = System.currentTimeMillis();
            boolean recent = now - lastClosedAtMs <= RECENT_RETURN_WINDOW_MS;
            if (recent) {
                lastClosedAtMs = 0L;
            }
            return recent;
        }
    }

    static boolean hasRecentReturn() {
        synchronized (GUARD) {
            if (lastClosedAtMs <= 0L) {
                return false;
            }
            long now = System.currentTimeMillis();
            return now - lastClosedAtMs <= RECENT_RETURN_WINDOW_MS;
        }
    }

    static boolean isLaunchPending() {
        synchronized (GUARD) {
            return launchPending;
        }
    }
}
