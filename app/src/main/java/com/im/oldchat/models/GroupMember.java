package com.im.oldchat.models;

public class GroupMember {
    // uid 仅用于公开展示和旧服务端操作；客户端身份关联统一使用不可变 ncuid。
    public String uid;
    public String ncuid;
    public String username;
    public String display_name;
    public String user_title;
    public String avatar_url;
    public int role;
    public long joined_at;
}
