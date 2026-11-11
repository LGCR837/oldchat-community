package com.oldchat.core.client;

import com.oldchat.core.model.ChatMessage;
import com.oldchat.core.model.Conversation;
import com.oldchat.core.model.MessageType;
import com.oldchat.core.model.User;
import com.oldchat.core.model.UserProfile;
import com.oldchat.core.platform.Scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** 示例客户端：用 SampleData 提供数据，并模拟对端回包（经 Scheduler 回到 UI 线程）。 */
public class MockOldChatClient implements OldChatClient {
    private final Scheduler scheduler;
    private final Map<String, List<ChatMessage>> msgs = new HashMap<>();
    private Consumer<ChatMessage> listener;

    public MockOldChatClient(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void login(String account, String password) throws Exception {
        // mock：无网络
    }

    @Override
    public List<Conversation> getConversations() {
        return SampleData.conversations();
    }

    @Override
    public List<ChatMessage> getMessages(String conversationId) {
        msgs.computeIfAbsent(conversationId, SampleData::messages);
        return new ArrayList<>(msgs.get(conversationId));
    }

    @Override
    public void sendMessage(String conversationId, String text) {
        ChatMessage out = new ChatMessage();
        out.id = UUID.randomUUID().toString();
        out.conversationId = conversationId;
        out.senderUid = "u_me";
        out.text = text;
        out.type = MessageType.TEXT;
        out.time = System.currentTimeMillis();
        out.outgoing = true;
        msgs.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(out);

        // 模拟对端回复
        String peer = peerOf(conversationId);
        scheduler.runLater(() -> {
            if (listener != null) {
                ChatMessage reply = new ChatMessage();
                reply.id = UUID.randomUUID().toString();
                reply.conversationId = conversationId;
                reply.senderUid = peer;
                reply.text = "收到：" + text;
                reply.type = MessageType.TEXT;
                reply.time = System.currentTimeMillis();
                reply.outgoing = false;
                msgs.get(conversationId).add(reply);
                listener.accept(reply);
            }
        });
    }

    @Override
    public List<User> getFriends() {
        return SampleData.friends();
    }

    @Override
    public UserProfile getUserProfile(String uid) {
        return SampleData.profile(uid);
    }

    @Override
    public void setIncomingListener(Consumer<ChatMessage> listener) {
        this.listener = listener;
    }

    private String peerOf(String cid) {
        for (Conversation c : SampleData.conversations()) {
            if (c.id.equals(cid)) return c.peerUid;
        }
        return "u_1001";
    }
}
