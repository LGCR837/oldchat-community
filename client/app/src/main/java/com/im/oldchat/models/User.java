package com.im.oldchat.models;

public class User {
    public String id;
    public String uid;
    public String ncuid;
    public String username;
    public String display_name;
    public String remark_name;
    public String user_title;
    public String avatar_url;
    public String signature;
    public String cover_url;
    public long friend_added_at;
    public boolean is_online;
    public String presence_status;
    /** 服务端标记：为假表示对方还是迁移时建的占位账号。缺失时按已迁移处理。 */
    public Boolean migrated;

    public boolean isMigrated() {
        return migrated == null || migrated.booleanValue();
    }
}
