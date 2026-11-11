package com.im.oldchat.api;

import android.content.Context;
import android.os.Handler;
import com.im.oldchat.OldChatApplication;
import com.im.oldchat.util.CryptoUtil;
import com.im.oldchat.util.NetworkStateManager;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * WebSocket 连接回调与事件分发监听器。
 */
class WSConnectionListener implements SimpleWebSocketClient.Listener {
    private final WSManager manager;
    private final int generation;
    private final Handler mainHandler;
    private final Set<WSManager.Listener> listeners;
    private final ThreadPoolExecutor incomingExecutor;
    private final WSIncomingHandler incomingHandler;
    private final WSIncomingHandler.Callback incomingCallback;
    private final Runnable pingRunnable;

    public WSConnectionListener(WSManager manager, int generation, Handler mainHandler,
                                Set<WSManager.Listener> listeners, ThreadPoolExecutor incomingExecutor,
                                WSIncomingHandler incomingHandler, WSIncomingHandler.Callback incomingCallback,
                                Runnable pingRunnable) {
        this.manager = manager;
        this.generation = generation;
        this.mainHandler = mainHandler;
        this.listeners = listeners;
        this.incomingExecutor = incomingExecutor;
        this.incomingHandler = incomingHandler;
        this.incomingCallback = incomingCallback;
        this.pingRunnable = pingRunnable;
    }

    @Override
    public void onOpen() {
        if (!manager.isCurrentGeneration(generation)) return;
        manager.setConnecting(false);
        manager.setConnected(true);
        manager.resetReconnectAttempts();
        mainHandler.removeCallbacks(pingRunnable);
        mainHandler.postDelayed(pingRunnable, WSHeartbeatPing.PING_INTERVAL_MS);
        NetworkStateManager.getInstance().recordRequestSuccess();
        WSDispatchHelper.notifyConnection(mainHandler, listeners, true);
        manager.refreshChannelSubscriptions();
        if (manager.consumeNeedsUnreadSync()) {
            manager.setReconnectedInBackground(true);
            Context ctx = OldChatApplication.getAppContext();
            if (ctx != null) {
                String t = WSManagerSupport.getToken(ctx);
                if (t != null) {
                    if (UpdatesManager.getInstance().getLocalPts() > 0L || UpdatesManager.getInstance().isRunning()) {
                        UpdatesManager.getInstance().fetchDifference();
                    } else {
                        manager.syncUnread(ctx, t);
                    }
                    manager.syncGroupUnread(ctx, t);
                }
            }
        }
    }

    @Override
    public void onMessage(String message) {
        if (!manager.isCurrentGeneration(generation)) return;
        String decrypted = CryptoUtil.decryptIfNeeded(message);
        final String raw = decrypted == null ? message : decrypted;
        final Context ctx = OldChatApplication.getAppContext();
        incomingExecutor.execute(new Runnable() {
            @Override public void run() {
                incomingHandler.handleMessage(ctx, raw, incomingCallback);
            }
        });
    }

    @Override
    public void onClose(int code, String reason) {
        if (!manager.claimDisconnect(generation)) return;
        manager.onDisconnectedCleanUp();
    }

    @Override
    public void onError(Exception ex) {
        if (!manager.claimDisconnect(generation)) return;
        manager.onDisconnectedCleanUp();
        String msg = ex != null ? ex.getMessage() : "";
        if (msg != null && (msg.contains("403") || msg.contains("401"))) {
            manager.handleAuthRefreshFailure();
        }
    }
}
