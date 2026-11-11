package com.im.oldchat.api;

import android.os.Handler;

/**
 * WebSocket Ping 心跳保活定时器（25 秒一次），防止移动运营商 NAT 超时掉线。
 */
class WSHeartbeatPing {
    public static final long PING_INTERVAL_MS = 25000L;

    public static Runnable create(final Handler handler, final PingProvider provider) {
        return new Runnable() {
            @Override
            public void run() {
                if (provider.canPing()) {
                    try {
                        SimpleWebSocketClient client = provider.getClient();
                        if (client != null) {
                            client.sendPing();
                        }
                    } catch (Throwable ignored) {
                    }
                    handler.postDelayed(this, PING_INTERVAL_MS);
                }
            }
        };
    }

    public interface PingProvider {
        boolean canPing();
        SimpleWebSocketClient getClient();
    }
}
