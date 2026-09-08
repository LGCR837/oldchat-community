package com.im.oldchat.api;

import java.util.List;

/**
 * WebSocket 事件监听器。
 */
public interface WSListener {
    void onDirectMessage(WSModels.DirectMessage message);
    void onDirectRead(String threadId, String readerUid, String readerNcuid, long readAt);
    void onDirectRecall(WSModels.DirectRecall recall);
    void onGroupMessage(WSModels.GroupMessage message);
    void onGroupRecall(WSModels.GroupRecall recall);
    void onTyping(WSModels.TypingEvent event);
    void onPresence(WSModels.PresenceEvent event);
    void onConnectionChanged(boolean connected);
}
