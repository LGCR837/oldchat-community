package com.im.oldchat.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import com.im.oldchat.util.CryptoUtil;
import java.net.URLEncoder;
import java.util.List;
import java.util.Set;

final class WSManagerSupport {
    private static final String AUTH_PREFS = "auth";

    private WSManagerSupport() {
    }

    static WSIncomingHandler.Callback createIncomingCallback(final Handler mainHandler,
            final Set<WSManager.Listener> listeners) {
        return new WSIncomingHandler.Callback() {
            @Override
            public void onDirectMessage(WSModels.DirectMessage message) {
                WSDispatchHelper.dispatchDirectMessage(mainHandler, listeners, message);
            }

            @Override
            public void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt) {
                WSDispatchHelper.dispatchDirectRead(mainHandler, listeners, threadId, readerUid,
                        readerNcuid, readAt);
            }

            @Override
            public void onDirectRecall(WSModels.DirectRecall recall) {
                WSDispatchHelper.dispatchDirectRecall(mainHandler, listeners, recall);
            }

            @Override
            public void onGroupMessage(WSModels.GroupMessage message) {
                WSDispatchHelper.dispatchGroupMessage(mainHandler, listeners, message);
            }

            @Override
            public void onGroupRecall(WSModels.GroupRecall recall) {
                WSDispatchHelper.dispatchGroupRecall(mainHandler, listeners, recall);
            }

            @Override
            public void onTyping(WSModels.TypingEvent event) {
                WSDispatchHelper.dispatchTyping(mainHandler, listeners, event);
            }

            @Override
            public void onPresence(WSModels.PresenceEvent event) {
                WSDispatchHelper.dispatchPresence(mainHandler, listeners, event);
            }
        };
    }

    static WSUnreadSyncHandler.DirectMessageDispatcher createDirectUnreadDispatcher(
            final Handler mainHandler, final Set<WSManager.Listener> listeners) {
        return new WSUnreadSyncHandler.DirectMessageDispatcher() {
            @Override
            public void dispatch(WSModels.DirectMessage message) {
                WSDispatchHelper.dispatchDirectMessage(mainHandler, listeners, message);
            }
            @Override
            public void dispatchBatch(List<WSModels.DirectMessage> messages) {
                WSDispatchHelper.dispatchDirectMessagesBatch(mainHandler, listeners, messages);
            }
        };
    }

    static WSUnreadSyncHandler.GroupMessageDispatcher createGroupUnreadDispatcher(
            final Handler mainHandler, final Set<WSManager.Listener> listeners) {
        return new WSUnreadSyncHandler.GroupMessageDispatcher() {
            @Override
            public void dispatch(WSModels.GroupMessage message) {
                WSDispatchHelper.dispatchGroupMessage(mainHandler, listeners, message);
            }
            @Override
            public void dispatchBatch(List<WSModels.GroupMessage> messages) {
                WSDispatchHelper.dispatchGroupMessagesBatch(mainHandler, listeners, messages);
            }
        };
    }

    static String buildWsUrl(String token) throws Exception {
        String base = HttpUtil.BASE_URL;
        String wsBase;
        if (base.startsWith("https://")) {
            wsBase = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            wsBase = "ws://" + base.substring("http://".length());
        } else {
            wsBase = base;
        }
        StringBuilder query = new StringBuilder();
        query.append("token=").append(URLEncoder.encode(token, "UTF-8"));
        String sessionId = CryptoUtil.getSessionId();
        if (sessionId != null && sessionId.length() > 0) {
            query.append("&sid=").append(URLEncoder.encode(sessionId, "UTF-8"));
        }
        return wsBase + "/ws?" + query;
    }

    static String getToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        return prefs.getString("access_token", "");
    }
}
