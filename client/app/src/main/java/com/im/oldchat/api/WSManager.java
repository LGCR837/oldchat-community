package com.im.oldchat.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.im.oldchat.OldChatApplication;
import com.im.oldchat.util.CryptoUtil;
import com.im.oldchat.util.NetworkStateManager;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WSManager {
    public interface Listener extends WSListener {
        default void onDirectMessagesBatch(List<WSModels.DirectMessage> m) { for (WSModels.DirectMessage x : m) onDirectMessage(x); }
        default void onGroupMessagesBatch(List<WSModels.GroupMessage> m) { for (WSModels.GroupMessage x : m) onGroupMessage(x); }
    }

    private static final long UNREAD_SYNC_DEBOUNCE_MS = 3000;
    private static WSManager instance;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WSUnreadSyncHandler unreadSyncHandler = new WSUnreadSyncHandler();
    private final WSIncomingHandler incomingHandler = new WSIncomingHandler();
    private final ThreadPoolExecutor incomingExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(256),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final WSIncomingHandler.Callback incomingCallback =
            WSManagerSupport.createIncomingCallback(mainHandler, listeners);
    private final WSUnreadSyncHandler.DirectMessageDispatcher directUnreadDispatcher =
            WSManagerSupport.createDirectUnreadDispatcher(mainHandler, listeners);
    private final WSUnreadSyncHandler.GroupMessageDispatcher groupUnreadDispatcher =
            WSManagerSupport.createGroupUnreadDispatcher(mainHandler, listeners);
    private final WSUnreadSyncCoordinator unreadSyncCoordinator =
            new WSUnreadSyncCoordinator(mainHandler, unreadSyncHandler,
                    directUnreadDispatcher, groupUnreadDispatcher);
    private volatile SimpleWebSocketClient client;
    private volatile boolean connecting = false;
    private volatile boolean connected = false;
    private volatile boolean desiredRunning = false;
    private volatile int connectionGeneration = 0;
    private Runnable reconnectRunnable;
    private boolean reconnectScheduled = false;
    private boolean needsUnreadSync = false;
    private volatile boolean checkingToken = false;
    private long checkingTokenStartedAt = 0L;
    private boolean authRefreshing = false;
    private int reconnectAttempts = 0;
    private boolean reconnectedInBackground = false;
    private long lastDirectUnreadSyncAtMs = 0L;
    private long lastGroupUnreadSyncAtMs = 0L;

    private final Runnable pingRunnable = WSHeartbeatPing.create(mainHandler, new WSHeartbeatPing.PingProvider() {
        @Override
        public boolean canPing() {
            return connected && client != null;
        }

        @Override
        public SimpleWebSocketClient getClient() {
            return client;
        }
    });

    public static synchronized WSManager getInstance() {
        if (instance == null) instance = new WSManager();
        return instance;
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void dispatchIncomingDirect(WSModels.DirectMessage message) {
        if (message != null) WSDispatchHelper.dispatchDirectMessage(mainHandler, listeners, message);
    }

    public void dispatchIncomingDirectRecall(WSModels.DirectRecall recall) {
        if (recall != null) WSDispatchHelper.dispatchDirectRecall(mainHandler, listeners, recall);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean consumeReconnectedInBackground() {
        boolean val = reconnectedInBackground;
        reconnectedInBackground = false;
        return val;
    }

    public synchronized void start(Context context) {
        if (context == null) return;
        desiredRunning = true;
        final Context appContext = context.getApplicationContext();
        final String token = WSManagerSupport.getToken(appContext);
        if (token == null || token.isEmpty() || connected || connecting) return;
        if (checkingToken && !WSStartupCoordinator.checkTokenTimeout(checkingTokenStartedAt)) return;
        checkingToken = true;
        checkingTokenStartedAt = System.currentTimeMillis();
        WSStartupCoordinator.checkAndStart(appContext, token, new WSStartupCoordinator.StartupCallback() {
            @Override
            public boolean isDesiredRunning() {
                checkingToken = false;
                checkingTokenStartedAt = 0L;
                return desiredRunning;
            }

            @Override
            public void syncUnreadData(Context ctx, String freshToken) {
                if (UpdatesManager.getInstance().getLocalPts() > 0L || UpdatesManager.getInstance().isRunning()) {
                    UpdatesManager.getInstance().fetchDifference();
                } else {
                    syncUnread(ctx, freshToken);
                }
                syncGroupUnread(ctx, freshToken);
            }

            @Override
            public void onStartConnect(String freshToken) {
                connect(freshToken);
            }

            @Override
            public void onDirectConnect(String fallbackToken) {
                connect(fallbackToken);
            }
        });
    }

    public synchronized void stop() {
        desiredRunning = false;
        connectionGeneration++;
        mainHandler.removeCallbacks(pingRunnable);
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        reconnectScheduled = false;
        incomingExecutor.getQueue().clear();
        SimpleWebSocketClient oldClient = client;
        client = null;
        if (oldClient != null) oldClient.close();
        connected = false;
        connecting = false;
        checkingToken = false;
        checkingTokenStartedAt = 0L;
    }

    public synchronized void connect(String token) {
        if (!desiredRunning || token == null || token.isEmpty() || connected || connecting) return;
        final String wsToken = token;
        if (CryptoUtil.isEcdhSupported() && !CryptoUtil.hasSession()) {
            connecting = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = HttpUtil.ensureSession();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            connecting = false;
                            if (!desiredRunning) return;
                            if (ok) connect(wsToken);
                            else scheduleReconnect();
                        }
                    });
                }
            }, "ws-handshake").start();
            return;
        }
        connecting = true;
        try {
            String wsUrl = WSManagerSupport.buildWsUrl(wsToken);
            final int generation;
            synchronized (this) {
                generation = ++connectionGeneration;
                if (reconnectRunnable != null) mainHandler.removeCallbacks(reconnectRunnable);
                reconnectScheduled = false;
                reconnectRunnable = null;
            }
            client = new SimpleWebSocketClient(new URI(wsUrl), null, new WSConnectionListener(
                    this, generation, mainHandler, listeners, incomingExecutor, incomingHandler,
                    incomingCallback, pingRunnable));
            client.connect();
        } catch (Exception e) {
            connecting = false;
            scheduleReconnect();
        }
    }

    synchronized void onDisconnectedCleanUp() {
        connected = false;
        connecting = false;
        mainHandler.removeCallbacks(pingRunnable);
        NetworkStateManager.getInstance().recordRequestFailure(-1);
        WSDispatchHelper.notifyConnection(mainHandler, listeners, false);
        if (!authRefreshing) scheduleReconnect();
    }

    synchronized void handleAuthRefreshFailure() {
        authRefreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String newToken = HttpAuthHelper.refreshAccessToken();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        authRefreshing = false;
                        if (!desiredRunning) return;
                        if (newToken != null && !newToken.isEmpty()) {
                            reconnectAttempts = 0;
                            needsUnreadSync = true;
                            connect(newToken);
                        } else {
                            scheduleReconnect();
                        }
                    }
                });
            }
        }, "ws-auth-refresh").start();
    }

    public synchronized void refreshChannelSubscriptions() {
        SimpleWebSocketClient current = client;
        if (connected && current != null) {
            try { current.send("{\"type\":\"channel_subscriptions_refresh\"}"); } catch (Throwable ignored) {}
        }
    }

    public synchronized void syncUnread(Context context, String token) {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastDirectUnreadSyncAtMs != 0L && now - lastDirectUnreadSyncAtMs < UNREAD_SYNC_DEBOUNCE_MS) return;
        lastDirectUnreadSyncAtMs = now;
        unreadSyncCoordinator.syncDirect(context, token);
    }

    public synchronized void syncGroupUnread(Context context, String token) {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastGroupUnreadSyncAtMs != 0L && now - lastGroupUnreadSyncAtMs < UNREAD_SYNC_DEBOUNCE_MS) return;
        lastGroupUnreadSyncAtMs = now;
        unreadSyncCoordinator.syncGroup(context, token);
    }

    synchronized boolean isCurrentGeneration(int generation) {
        return desiredRunning && generation == connectionGeneration;
    }

    synchronized boolean claimDisconnect(int generation) {
        if (!desiredRunning || generation != connectionGeneration) return false;
        connectionGeneration++;
        client = null;
        return true;
    }

    synchronized void setConnecting(boolean val) { connecting = val; }
    synchronized void setConnected(boolean val) { connected = val; }
    synchronized void resetReconnectAttempts() { reconnectAttempts = 0; }
    synchronized boolean consumeNeedsUnreadSync() {
        boolean v = needsUnreadSync;
        needsUnreadSync = false;
        return v;
    }
    synchronized void setReconnectedInBackground(boolean val) { reconnectedInBackground = val; }

    synchronized void scheduleReconnect() {
        if (!desiredRunning || reconnectScheduled) return;
        needsUnreadSync = true;
        long delay = WSReconnectCoordinator.calculateDelay(reconnectAttempts++);
        reconnectScheduled = true;
        reconnectRunnable = WSReconnectCoordinator.createRunnable(mainHandler, new WSReconnectCoordinator.Connector() {
            @Override
            public boolean isDesiredRunning() {
                synchronized (WSManager.this) {
                    reconnectScheduled = false;
                    reconnectRunnable = null;
                    return desiredRunning;
                }
            }

            @Override
            public void doConnect(String token) {
                connect(token);
            }
        });
        mainHandler.postDelayed(reconnectRunnable, delay);
    }
}
