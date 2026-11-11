package com.oldchat.core.model;

/** 用户模型（与 app/ 的 models/ 对齐，去 Android 依赖）。 */
public class User {
    public String uid;
    public String username;
    public String displayName;
    public String oldchatId;          // 旧聊号，如 LGCR837
    public String avatarColor = "#3a7afe";
    public String bio = "";
    public boolean online = false;

    public String initial() {
        String n = displayName != null ? displayName : username;
        return (n == null || n.isEmpty()) ? "?" : String.valueOf(n.charAt(0));
    }
}
