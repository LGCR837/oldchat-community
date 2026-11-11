package com.im.oldchat.api;

import android.content.Context;
import com.im.oldchat.OldChatApplication;

/**
 * WebSocket 启动检查与令牌校验协调器。
 */
class WSStartupCoordinator {
    private static final long TOKEN_CHECK_TIMEOUT_MS = 25000L;

    public interface StartupCallback {
        boolean isDesiredRunning();
        void onStartConnect(String freshToken);
        void onDirectConnect(String token);
        void syncUnreadData(Context context, String token);
    }

    public static boolean checkTokenTimeout(long checkingStartedAt) {
        return System.currentTimeMillis() - checkingStartedAt >= TOKEN_CHECK_TIMEOUT_MS;
    }

    public static void checkAndStart(final Context appContext, final String token, final StartupCallback callback) {
        HttpUtil.get("/me", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                if (!callback.isDesiredRunning()) return;
                String freshToken = WSManagerSupport.getToken(appContext);
                if (freshToken == null || freshToken.isEmpty()) return;
                callback.syncUnreadData(appContext, freshToken);
                callback.onStartConnect(freshToken);
            }

            @Override
            public void onError(int code, String error) {
                if (callback.isDesiredRunning()) {
                    callback.onDirectConnect(token);
                }
            }
        });
    }
}
