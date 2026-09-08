package com.im.oldchat.api;

import android.os.Handler;

import java.util.List;
import java.util.Set;

final class WSDispatchHelper {
    private WSDispatchHelper() {
    }

    static void dispatchDirectMessage(Handler handler, Set<WSManager.Listener> listeners, WSModels.DirectMessage message) {
        final WSModels.DirectMessage msg = message;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listeners) {
                    listener.onDirectMessage(msg);
                }
            }
        });
    }

    static void dispatchDirectMessagesBatch(Handler handler, Set<WSManager.Listener> listeners,
                                            List<WSModels.DirectMessage> messages) {
        final List<WSModels.DirectMessage> msgs = new java.util.ArrayList<WSModels.DirectMessage>(messages);
        final Set<WSManager.Listener> listenersCopy = new java.util.HashSet<WSManager.Listener>(listeners);
        handler.post(new Runnable() {
            @Override public void run() {
                for (WSManager.Listener listener : listenersCopy) {
                    listener.onDirectMessagesBatch(msgs);
                }
            }
        });
    }

    static void dispatchDirectRead(Handler handler, Set<WSManager.Listener> listeners, String threadId,
                                   String readerUid, String readerNcuid, long readAt) {
        final String finalThreadId = threadId;
        final String finalReaderUid = readerUid;
        final String finalReaderNcuid = readerNcuid;
        final long finalReadAt = readAt;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listeners) {
                    listener.onDirectRead(finalThreadId, finalReaderUid, finalReaderNcuid, finalReadAt);
                }
            }
        });
    }

    static void dispatchDirectRecall(Handler handler, Set<WSManager.Listener> listeners, WSModels.DirectRecall recall) {
        final WSModels.DirectRecall msg = recall;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listeners) {
                    listener.onDirectRecall(msg);
                }
            }
        });
    }

    static void dispatchGroupMessage(Handler handler, Set<WSManager.Listener> listeners, WSModels.GroupMessage message) {
        final WSModels.GroupMessage msg = message;
        final Set<WSManager.Listener> listenersCopy = new java.util.HashSet<>(listeners);
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listenersCopy) {
                    listener.onGroupMessage(msg);
                }
            }
        });
    }

    /**
     * 批量 dispatch：一次 post 处理所有消息，避免 N 次独立主线程操作
     */
    static void dispatchGroupMessagesBatch(Handler handler, Set<WSManager.Listener> listeners,
                                            List<WSModels.GroupMessage> messages) {
        final List<WSModels.GroupMessage> msgs = new java.util.ArrayList<>(messages);
        final Set<WSManager.Listener> listenersCopy = new java.util.HashSet<>(listeners);
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listenersCopy) {
                    listener.onGroupMessagesBatch(msgs);
                }
            }
        });
    }

    static void dispatchGroupRecall(Handler handler, Set<WSManager.Listener> listeners, WSModels.GroupRecall recall) {
        final WSModels.GroupRecall msg = recall;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listeners) {
                    listener.onGroupRecall(msg);
                }
            }
        });
    }

    static void dispatchTyping(Handler handler, Set<WSManager.Listener> listeners, WSModels.TypingEvent event) {
        final WSModels.TypingEvent msg = event;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listeners) {
                    listener.onTyping(msg);
                }
            }
        });
    }

    static void dispatchPresence(Handler handler, Set<WSManager.Listener> listeners, WSModels.PresenceEvent event) {
        final WSModels.PresenceEvent msg = event;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listeners) {
                    listener.onPresence(msg);
                }
            }
        });
    }

    static void notifyConnection(Handler handler, Set<WSManager.Listener> listeners, boolean isConnected) {
        final boolean connectedState = isConnected;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (WSManager.Listener listener : listeners) {
                    listener.onConnectionChanged(connectedState);
                }
            }
        });
    }
}
