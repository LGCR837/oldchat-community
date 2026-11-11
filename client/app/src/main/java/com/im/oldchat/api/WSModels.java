package com.im.oldchat.api;

public final class WSModels {
    private WSModels() {
    }

    public static class DirectMessage {
        public String id;
        public String threadId;
        public String fromUid;
        public String fromNcuid;
        public String peerUid;
        public String peerNcuid;
        public String body;
        public String msgType;
        public String mediaUrl;
        public String thumbUrl;
        public String originalUrl;
        public int durationMs;
        public int burnAfterSeconds;
        public long burnStartAt;
        public long createdAt;
        public long sortSeq;
    }

    public static class DirectRecall {
        public String messageId;
        public String threadId;
        public String fromUid;
    }

    public static class GroupMessage {
        public String id;
        public String groupId;
        public String fromUid;
        public String fromNcuid;
        public String fromName;
        public String body;
        public String msgType;
        public String mediaUrl;
        public String thumbUrl;
        public String originalUrl;
        public int durationMs;
        public int burnAfterSeconds;
        public long createdAt;
        public long sortSeq;
        public long groupSeq;
    }

    public static class GroupRecall {
        public String messageId;
        public String groupId;
        public String fromUid;
    }

    public static class TypingEvent {
        public String chatId;
        public String uid;
        public String ncuid;
        public String avatarUrl;
        public boolean isGroup;
        public boolean isTyping;
    }

    public static class PresenceEvent {
        public String uid;
        public boolean isOnline;
        public String presenceStatus;
    }
}
