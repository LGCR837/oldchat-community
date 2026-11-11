package com.oldchat.fx.view;

import com.oldchat.core.client.OldChatClient;
import com.oldchat.core.model.Moment;
import com.oldchat.core.model.UserProfile;
import com.oldchat.fx.FxApp;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.List;

/** 用户空间：封面 + 叠回头像 + 资料 + 动态流。1:1 还原 header_user_space。 */
public class UserSpaceView {
    private final FxApp app;
    private final OldChatClient client;
    private final UserProfile prof;
    public final ScrollPane root = new ScrollPane();
    private final boolean isSelf;

    public UserSpaceView(FxApp app, OldChatClient client, String uid) {
        this.app = app;
        this.client = client;
        this.prof = client.getUserProfile(uid);
        this.isSelf = "u_me".equals(uid);

        VBox content = new VBox();
        content.getChildren().addAll(header(), momentsList());
        root.setContent(content);
        root.setFitToWidth(true);
    }

    private VBox header() {
        VBox h = new VBox(0);

        // 封面
        StackPane cover = new StackPane();
        cover.getStyleClass().add("space-cover");
        cover.setPrefHeight(150);
        StackPane backBtn = new StackPane();
        backBtn.getStyleClass().add("space-back");
        Text btxt = new Text("‹");
        btxt.setFill(Color.WHITE);
        btxt.setFont(Font.font(20));
        backBtn.getChildren().add(btxt);
        backBtn.setPadding(new Insets(8));
        StackPane.setAlignment(backBtn, Pos.TOP_LEFT);
        backBtn.setOnMouseClicked(e -> app.back());
        cover.getChildren().add(backBtn);

        // 头像 + 按钮行
        HBox avatarRow = new HBox();
        avatarRow.setAlignment(Pos.CENTER_LEFT);
        Node av = Widgets.avatar(prof.user.initial(), prof.user.avatarColor, 88);
        VBox.setMargin(av, new Insets(-44, 0, 0, 16));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        avatarRow.getChildren().addAll(av, grow, isSelf ? selfBtns() : otherBtns());

        // 资料文字
        VBox info = new VBox(4);
        info.setPadding(new Insets(12, 16, 12, 16));
        Label name = new Label(prof.user.displayName);
        name.getStyleClass().add("space-name");
        Label uid = new Label("旧聊号: " + prof.user.oldchatId);
        uid.getStyleClass().add("space-uid");
        Label bio = new Label(prof.user.bio.isEmpty() ? "这个人很懒，什么都没写" : prof.user.bio);
        bio.getStyleClass().add("space-bio");
        Label meta = new Label(prof.moments.size() + " 条动态    |    历史封禁: 0 次");
        meta.getStyleClass().add("space-meta");
        info.getChildren().addAll(name, uid, bio, meta);

        // 动态标题
        Label sec = new Label("动态");
        sec.getStyleClass().add("space-section");

        h.getChildren().addAll(cover, avatarRow, info, sec);
        return h;
    }

    private HBox selfBtns() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(8, 12, 0, 0));
        Label post = new Label("发布动态");
        post.getStyleClass().add("btn-primary");
        Label edit = new Label("编辑资料");
        edit.getStyleClass().add("btn-outline");
        box.getChildren().addAll(post, edit);
        return box;
    }

    private HBox otherBtns() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(8, 12, 0, 0));
        Label report = new Label("举报");
        report.getStyleClass().add("btn-danger");
        Label add = new Label("加好友");
        add.getStyleClass().add("btn-primary");
        add.setOnMouseClicked(e -> add.setText("已发送"));
        box.getChildren().addAll(report, add);
        return box;
    }

    private VBox momentsList() {
        VBox list = new VBox();
        List<Moment> ms = prof.moments;
        if (ms.isEmpty()) {
            Label empty = new Label("还没有动态");
            empty.getStyleClass().add("space-meta");
            empty.setPadding(new Insets(24));
            empty.setAlignment(Pos.CENTER);
            list.getChildren().add(empty);
        } else {
            for (Moment m : ms) list.getChildren().add(momentCard(m));
        }
        return list;
    }

    private VBox momentCard(Moment m) {
        VBox c = new VBox(6);
        c.getStyleClass().add("moment-card");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        Node av = Widgets.avatar(prof.user.initial(), prof.user.avatarColor, 40);
        VBox tx = new VBox(2);
        Label nm = new Label(prof.user.displayName);
        nm.getStyleClass().add("moment-name");
        Label tm = new Label(Widgets.formatTime(m.time));
        tm.getStyleClass().add("moment-time");
        tx.getChildren().addAll(nm, tm);
        top.getChildren().addAll(av, tx);

        Label txt = new Label(m.text);
        txt.getStyleClass().add("moment-text");

        c.getChildren().addAll(top, txt);
        if (!m.imageColors.isEmpty()) {
            HBox imgs = new HBox(6);
            imgs.setPadding(new Insets(4, 0, 0, 0));
            int cols = Math.min(3, m.imageColors.size());
            for (int i = 0; i < cols; i++) {
                Region r = new Region();
                r.setPrefSize(96, 96);
                r.getStyleClass().add("moment-img");
                r.setStyle("-fx-background-color:" + m.imageColors.get(i) + ";");
                imgs.getChildren().add(r);
            }
            c.getChildren().add(imgs);
        }
        return c;
    }
}
