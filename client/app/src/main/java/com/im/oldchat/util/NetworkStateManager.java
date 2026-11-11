package com.im.oldchat.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

public class NetworkStateManager {
    private static NetworkStateManager instance;
    private final List<NetworkStateListener> listeners = new ArrayList<>();
    private final long appStartMs = System.currentTimeMillis();
    private boolean isServerAvailable = true;
    private int consecutiveFailures = 0;
    private static final int FAILURE_THRESHOLD = 3;

    public interface NetworkStateListener {
        void onServerStateChanged(boolean available);
    }

    private NetworkStateManager() {
    }

    public static synchronized NetworkStateManager getInstance() {
        if (instance == null) {
            instance = new NetworkStateManager();
        }
        return instance;
    }

    public void addListener(NetworkStateListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(NetworkStateListener listener) {
        listeners.remove(listener);
    }

    public void recordRequestFailure(int code) {
        // 网络错误或连接超时
        if (code == -1 || code == 0) {
            consecutiveFailures++;
            if (consecutiveFailures >= FAILURE_THRESHOLD && isServerAvailable) {
                setServerAvailable(false);
            }
        }
    }

    public void recordRequestSuccess() {
        consecutiveFailures = 0;
        if (!isServerAvailable) {
            setServerAvailable(true);
        }
    }

    private void setServerAvailable(boolean available) {
        if (isServerAvailable != available) {
            isServerAvailable = available;
            notifyListeners();
        }
    }

    private void notifyListeners() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (NetworkStateListener listener : listeners) {
                    listener.onServerStateChanged(isServerAvailable);
                }
            }
        });
    }

    public boolean isServerAvailable() {
        return isServerAvailable;
    }

    public long getStartupGraceRemaining(long graceMs) {
        long elapsed = System.currentTimeMillis() - appStartMs;
        long remaining = graceMs - elapsed;
        return remaining > 0 ? remaining : 0;
    }
}
