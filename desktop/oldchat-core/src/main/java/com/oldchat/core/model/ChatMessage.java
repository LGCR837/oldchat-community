package com.oldchat.core.model;

/** 单条聊天消息。 */
public class ChatMessage {
    public String id;
    public String conversationId;
    public String senderUid;
    public String text = "";
    public MessageType type = MessageType.TEXT;
    public long time;
    public boolean outgoing;
}
