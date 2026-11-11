package com.oldchat.core.client;

import com.oldchat.core.model.ChatMessage;
import com.oldchat.core.model.Conversation;
import com.oldchat.core.model.MessageType;
import com.oldchat.core.model.Moment;
import com.oldchat.core.model.User;
import com.oldchat.core.model.UserProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 示例数据（前端联调用，后续由真实 OldChatClient 替换）。 */
public final class SampleData {
    private static final Map<String, User> U = new HashMap<>();

    static {
        U.put("u_me", u("u_me", "我", "LGCR837", "#3a7afe", "", true));
        U.put("u_1001", u("u_1001", "小莉", "LILY2024", "#ff7a90", "热爱生活 ☕", true));
        U.put("u_1002", u("u_1002", "阿ken", "KEN998", "#3ad0a0", "代码改变世界", false));
    }

    private static User u(String uid, String name, String oid, String col, String bio, boolean on) {
        User x = new User();
        x.uid = uid;
        x.username = name;
        x.displayName = name;
        x.oldchatId = oid;
        x.avatarColor = col;
        x.bio = bio;
        x.online = on;
        return x;
    }

    public static List<User> friends() {
        List<User> r = new ArrayList<>();
        r.add(U.get("u_1001"));
        r.add(U.get("u_1002"));
        return r;
    }

    public static List<Conversation> conversations() {
        List<Conversation> r = new ArrayList<>();
        r.add(cv("cv_1", "u_1001", "小莉", "#ff7a90", "在吗？周末一起看电影呀", System.currentTimeMillis() - 60000, 2, false));
        r.add(cv("cv_2", "u_1002", "阿ken", "#3ad0a0", "[图片]", System.currentTimeMillis() - 3600000, 0, false));
        r.add(cv("cv_3", "g_1", "旧聊开发组", "#f0a020", "欢迎新成员～", System.currentTimeMillis() - 86400000, 5, true));
        return r;
    }

    private static Conversation cv(String id, String peer, String title, String col, String last, long t, int un, boolean grp) {
        Conversation c = new Conversation();
        c.id = id;
        c.peerUid = peer;
        c.title = title;
        c.avatarColor = col;
        c.lastMessage = last;
        c.lastTime = t;
        c.unread = un;
        c.isGroup = grp;
        return c;
    }

    public static List<ChatMessage> messages(String cid) {
        List<ChatMessage> r = new ArrayList<>();
        if ("cv_1".equals(cid)) {
            r.add(m(cid, "u_1001", "在吗？周末一起看电影呀", false, System.currentTimeMillis() - 120000));
            r.add(m(cid, "u_me", "好呀，看什么？", true, System.currentTimeMillis() - 100000));
            r.add(m(cid, "u_1001", "新上的科幻片，听说不错", false, System.currentTimeMillis() - 60000));
        } else if ("cv_2".equals(cid)) {
            r.add(m(cid, "u_1001", "[图片]", false, System.currentTimeMillis() - 3600000));
            r.add(m(cid, "u_me", "好看！", true, System.currentTimeMillis() - 3500000));
        } else {
            r.add(m(cid, "g_1", "欢迎新成员～", false, System.currentTimeMillis() - 86400000));
            r.add(m(cid, "u_1002", "谢谢群主", false, System.currentTimeMillis() - 86000000));
        }
        return r;
    }

    private static ChatMessage m(String cid, String sender, String text, boolean out, long t) {
        ChatMessage x = new ChatMessage();
        x.id = UUID.randomUUID().toString();
        x.conversationId = cid;
        x.senderUid = sender;
        x.text = text;
        x.type = MessageType.TEXT;
        x.time = t;
        x.outgoing = out;
        return x;
    }

    public static UserProfile profile(String uid) {
        UserProfile p = new UserProfile();
        p.user = U.getOrDefault(uid, U.get("u_me"));
        p.friendsCount = 128;
        p.moments.add(moment(p.user.uid, "今天天气真好，出去走了走 ☀️",
                Arrays.asList("#ffb86b", "#8be9fd", "#a6e3a1")));
        p.moments.add(moment(p.user.uid, "调试了一下午终于跑通了 🎉", new ArrayList<>()));
        p.moments.add(moment(p.user.uid, "推荐一本好书《深入理解计算机系统》", new ArrayList<>()));
        return p;
    }

    private static Moment moment(String uid, String text, List<String> cols) {
        Moment x = new Moment();
        x.id = UUID.randomUUID().toString();
        x.authorUid = uid;
        x.text = text;
        x.time = System.currentTimeMillis();
        x.imageColors = cols;
        return x;
    }
}
