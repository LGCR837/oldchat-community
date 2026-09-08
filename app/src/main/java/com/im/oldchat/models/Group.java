package com.im.oldchat.models;

public class Group {
    public String id;
    public String name;
    public String avatar_url;
    public boolean join_approval;
    public boolean global_mute;
    public int role;
    public String announcement;
    public int announcement_mode;
    public long announcement_updated_at;
    public long announcement_read_at;
    public int member_count;
    /** 服务端每群唯一消息水位；变化时按 group_seq 增量拉取。 */
    public long message_seq;
    /** 服务端每群唯一成员版本；变化时分页刷新成员缓存。 */
    public long member_version;
}
