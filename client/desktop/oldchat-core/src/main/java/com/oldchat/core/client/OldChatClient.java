package com.oldchat.core.client;

import com.oldchat.core.model.ChatMessage;
import com.oldchat.core.model.Conversation;
import com.oldchat.core.model.User;
import com.oldchat.core.model.UserProfile;

import java.util.List;
import java.util.function.Consumer;

/** 客户端协议接口：UI 层只依赖此接口，不关心 Android / 网络实现。 */
public interface OldChatClient {
    void login(String account, String password) throws Exception;

    List<Conversation> getConversations();

    List<User> getFriends();

    List<ChatMessage> getMessages(String conversationId);

    void sendMessage(String conversationId, String text);

    UserProfile getUserProfile(String uid);

    void setIncomingListener(Consumer<ChatMessage> listener);
}
