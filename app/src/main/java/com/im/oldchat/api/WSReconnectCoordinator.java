package com.im.oldchat.api;

import android.content.Context;
import android.os.Handler;
import com.im.oldchat.OldChatApplication;

/**
 * WebSocket 指数退避重连与抖动调度器。
 */
class WSReconnectCoordinator {
    private static final long RECONNECT_BASE_MS = 1000L;
    private static final long RECONNECT_MAX_MS = 60000L;

    public interface Connector {
        boolean isDesiredRunning();
        void doConnect(String token);
    }

    public static long calculateDelay(int attempts) {
        int shift = Math.min(attempts, 16);
        long baseDelay = Math.min(RECONNECT_BASE_MS * (1L << shift), RECONNECT_MAX_MS);
        long spread = Math.max(1L, baseDelay / 5L);
        return baseDelay - spread + (long) (Math.random() * (spread * 2L + 1L));
    }

    public static Runnable createRunnable(final Handler handler, final Connector connector) {
        return new Runnable() {
            @Override
            public void run() {
                if (!connector.isDesiredRunning()) {
                    return;
                }
                Context ctx = OldChatApplication.getAppContext();
                if (ctx == null) {
                    return;
                }
                String token = WSManagerSupport.getToken(ctx);
                if (token != null && !token.isEmpty()) {
                    connector.doConnect(token);
                }
            }
        };
    }
}
