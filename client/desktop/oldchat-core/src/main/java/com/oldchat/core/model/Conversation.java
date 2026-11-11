package com.oldchat.core.model;

/** 会话（单聊或群聊）。 */
public class Conversation {
    public String id;
    public String peerUid;            // 单聊对方 uid；群聊为群 id
    public String title;
    public String avatarColor = "#3a7afe";
    public String lastMessage = "";
    public long lastTime;
    public int unread;
    public boolean isGroup;
}
