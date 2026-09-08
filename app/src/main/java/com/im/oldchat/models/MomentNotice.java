package com.im.oldchat.models;

public class MomentNotice {
    public String id;
    public String momentId;
    public String momentBody;
    public String momentImage;
    public String ownerUid;
    public String type; // like/comment
    public int delta;
    public long createdAt;
}
