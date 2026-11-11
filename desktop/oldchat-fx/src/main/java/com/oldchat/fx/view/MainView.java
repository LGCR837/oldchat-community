package com.oldchat.fx.view;

import com.oldchat.core.client.OldChatClient;
import com.oldchat.core.model.Conversation;
import com.oldchat.core.model.Moment;
import com.oldchat.core.model.User;
import com.oldchat.fx.FxApp;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** 主界面 = PastelHome（Oldchat 标题 + ＋ / 重点·会话·折叠 区块 / 会话行）+ 底部 4 tab。 */
public class MainView {
    public final BorderPane root = new BorderPane();
    private final FxApp app;
    private final OldChatClient client;
    private final StackPane center = new StackPane();
    private final HBox tabBar = new HBox();
    private final StackPane[] tabs = new StackPane[4];
    private int current = 0;

    public MainView(FxApp app) {
        this.app = app;
        this.client = app.client();
        root.setCenter(center);
        root.setBottom(tabBar);
        buildTabs();
        showTab(0);
    }

    private void buildTabs() {
        tabBar.getStyleClass().add("tabbar");
        tabBar.setPrefHeight(56);
        String[][] icons = {
                {"ic_tab_chat_alt_normal", "ic_tab_chat_alt_selected"},
                {"ic_tab_friends_normal", "ic_tab_friends_selected"},
                {"news_normal", "news_selected"},
                {"ic_tab_profile_normal", "ic_tab_profile_selected"}};
        String[] labels = {"聊天", "好友", "发现", "我"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabs[i] = Widgets.tabItem(icons[i][0], icons[i][1], labels[i], i == 0, () -> showTab(idx));
            HBox.setHgrow(tabs[i], Priority.ALWAYS);
            tabBar.getChildren().add(tabs[i]);
        }
    }

    private void showTab(int idx) {
        current = idx;
        for (int i = 0; i < 4; i++) {
            final int j = i;
            // 重新构建 tab 以刷新选中态（图标/文字色）
            String[][] icons = {
                    {"ic_tab_chat_alt_normal", "ic_tab_chat_alt_selected"},
                    {"ic_tab_friends_normal", "ic_tab_friends_selected"},
                    {"news_normal", "news_selected"},
                    {"ic_tab_profile_normal", "ic_tab_profile_selected"}};
            String[] labels = {"聊天", "好友", "发现", "我"};
            HBox.setHgrow(tabs[j], Priority.ALWAYS);
            tabBar.getChildren().set(j, Widgets.tabItem(icons[j][0], icons[j][1], labels[j], j == idx, () -> showTab(j)));
            tabs[j] = (StackPane) tabBar.getChildren().get(j);
        }
        switch (idx) {
            case 0 -> center.getChildren().setAll(pastelHome());
            case 1 -> center.getChildren().setAll(friendsPage());
            case 2 -> center.getChildren().setAll(discoverPage());
            default -> center.getChildren().setAll(mePage());
        }
    }

    // ===== PastelHome（聊天 tab）=====
    private ScrollPane pastelHome() {
        VBox home = new VBox(0);
        home.getStyleClass().add("pastel-home");

        // 头部：Oldchat + ＋
        HBox header = new HBox();
        header.setPrefHeight(36);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Oldchat");
        title.getStyleClass().add("home-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        StackPane add = Widgets.plusIcon(28);
        add.setCursor(javafx.scene.Cursor.HAND);
        header.getChildren().addAll(title, sp, add);

        VBox chats = section("会话", client.getConversations().size(), true);
        for (Conversation c : client.getConversations()) chats.getChildren().add(convRow(c));

        VBox news = section("重点", client.getUserProfile("u_me").moments.size(), true);
        List<Moment> ms = client.getUserProfile("u_me").moments;
        if (ms.isEmpty()) {
            Label empty = new Label("暂无动态");
            empty.getStyleClass().add("home-empty");
            news.getChildren().add(empty);
        } else {
            for (Moment m : ms.subList(0, Math.min(2, ms.size()))) news.getChildren().add(momentMini(m));
        }

        VBox folded = section("折叠", 0, false);

        home.getChildren().addAll(header, chats, news, folded);
        ScrollPane sp2 = new ScrollPane(home);
        sp2.setFitToWidth(true);
        sp2.getStyleClass().add("home-scroll");
        return sp2;
    }

    private VBox section(String title, int count, boolean open) {
        VBox sec = new VBox(0);
        sec.getStyleClass().add("home-section");
        HBox h = new HBox(10);
        h.setPrefHeight(44);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(0, 12, 0, 12));
        h.getStyleClass().add("home-section-header");
        Region dot = new Region();
        dot.setPrefSize(10, 10);
        dot.setStyle("-fx-background-color:" + (open ? "#22C55E" : "#9CA3AF")
                + ";-fx-background-radius:5;");
        Label t = new Label(title);
        t.getStyleClass().add("home-section-title");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        h.getChildren().addAll(dot, t, grow);
        if (count > 0) {
            Label pill = new Label(String.valueOf(count));
            pill.getStyleClass().add("home-count-pill");
            h.getChildren().add(pill);
        }
        sec.getChildren().add(h);
        return sec;
    }

    private HBox convRow(Conversation c) {
        HBox row = new HBox(12);
        row.getStyleClass().add("conv-row");
        row.setPrefHeight(72);
        row.setPadding(new Insets(10, 16, 10, 16));
        row.setAlignment(Pos.CENTER_LEFT);

        StackPane avWrap = new StackPane();
        avWrap.setPrefSize(46, 46);
        avWrap.getChildren().add(Widgets.avatarSquare(c.title.isEmpty() ? "?" : c.title.substring(0, 1), c.avatarColor, 46));
        User peer = client.getUserProfile(c.peerUid).user;
        if (peer.online) {
            StackPane dot = Widgets.onlineDot(10);
            StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(dot, new Insets(0, 2, 2, 0));
            avWrap.getChildren().add(dot);
        }

        VBox mid = new VBox(4);
        HBox titleRow = new HBox(6);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(c.title);
        name.getStyleClass().add("conv-title");
        titleRow.getChildren().add(name);
        Label sub = new Label(c.lastMessage);
        sub.getStyleClass().add("conv-sub");
        mid.getChildren().addAll(titleRow, sub);

        VBox right = new VBox(4);
        right.setAlignment(Pos.TOP_RIGHT);
        Label time = new Label(Widgets.formatTime(c.lastTime));
        time.getStyleClass().add("conv-time");
        right.getChildren().add(time);
        if (c.unread > 0) {
            Label un = new Label(c.unread > 99 ? "99+" : String.valueOf(c.unread));
            un.getStyleClass().add("conv-unread");
            right.getChildren().add(un);
        }

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        row.getChildren().addAll(avWrap, mid, grow, right);
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> app.goChat(c));
        return row;
    }

    private HBox momentMini(Moment m) {
        HBox row = new HBox(10);
        row.setPadding(new Insets(8, 16, 8, 16));
        row.setAlignment(Pos.CENTER_LEFT);
        Node av = Widgets.avatar(m.text.isEmpty() ? "?" : m.text.substring(0, 1), "#6B7280", 36);
        VBox v = new VBox(2);
        Label nm = new Label("动态");
        nm.getStyleClass().add("conv-title");
        Label tx = new Label(m.text.length() > 24 ? m.text.substring(0, 24) + "…" : m.text);
        tx.getStyleClass().add("conv-sub");
        v.getChildren().addAll(nm, tx);
        row.getChildren().addAll(av, v);
        return row;
    }

    // ===== 好友 tab =====
    private ScrollPane friendsPage() {
        VBox box = new VBox(0);
        box.getStyleClass().add("pastel-home");
        VBox sec = section("好友", client.getFriends().size(), true);
        for (User u : client.getFriends()) {
            HBox row = new HBox(12);
            row.getStyleClass().add("conv-row");
            row.setPrefHeight(72);
            row.setPadding(new Insets(10, 16, 10, 16));
            row.setAlignment(Pos.CENTER_LEFT);
            StackPane avWrap = new StackPane();
            avWrap.setPrefSize(46, 46);
            avWrap.getChildren().add(Widgets.avatar(u.initial(), u.avatarColor, 46));
            if (u.online) {
                StackPane dot = Widgets.onlineDot(10);
                StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
                StackPane.setMargin(dot, new Insets(0, 2, 2, 0));
                avWrap.getChildren().add(dot);
            }
            VBox mid = new VBox(4);
            Label name = new Label(u.displayName);
            name.getStyleClass().add("conv-title");
            Label sub = new Label("旧聊号: " + u.oldchatId);
            sub.getStyleClass().add("conv-sub");
            mid.getChildren().addAll(name, sub);
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            row.getChildren().addAll(avWrap, mid, grow);
            row.setCursor(javafx.scene.Cursor.HAND);
            row.setOnMouseClicked(e -> app.goSpace(u.uid));
            sec.getChildren().add(row);
        }
        box.getChildren().add(sec);
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("home-scroll");
        return sp;
    }

    // ===== 发现 tab（交际圈预览）=====
    private ScrollPane discoverPage() {
        VBox box = new VBox(0);
        box.getStyleClass().add("pastel-home");
        VBox sec = section("交际圈", 0, true);
        Label tip = new Label("在这里浏览好友动态与公开内容");
        tip.getStyleClass().add("home-empty");
        sec.getChildren().add(tip);
        box.getChildren().add(sec);
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("home-scroll");
        return sp;
    }

    // ===== 我 tab =====
    private ScrollPane mePage() {
        User me = client.getUserProfile("u_me").user;
        VBox box = new VBox(0);
        box.getStyleClass().add("pastel-home");
        VBox sec = section("我", 0, true);
        HBox row = new HBox(12);
        row.setPrefHeight(72);
        row.setPadding(new Insets(10, 16, 10, 16));
        row.setAlignment(Pos.CENTER_LEFT);
        Node av = Widgets.avatar(me.initial(), me.avatarColor, 46);
        VBox mid = new VBox(4);
        Label name = new Label(me.displayName);
        name.getStyleClass().add("conv-title");
        Label sub = new Label("旧聊号: " + me.oldchatId);
        sub.getStyleClass().add("conv-sub");
        mid.getChildren().addAll(name, sub);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        row.getChildren().addAll(av, mid, grow);
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> app.goSpace("u_me"));
        sec.getChildren().add(row);
        box.getChildren().add(sec);
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("home-scroll");
        return sp;
    }
}
