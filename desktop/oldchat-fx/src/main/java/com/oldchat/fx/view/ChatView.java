package com.oldchat.fx.view;

import com.oldchat.core.client.OldChatClient;
import com.oldchat.core.model.ChatMessage;
import com.oldchat.core.model.Conversation;
import com.oldchat.core.model.User;
import com.oldchat.fx.FxApp;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** 聊天界面：对齐 activity_chat（Toolbar 48dp + 消息流 + 输入栏）。 */
public class ChatView {
    public final BorderPane root = new BorderPane();
    private final FxApp app;
    private final OldChatClient client;
    private final Conversation conv;
    private final VBox messages = new VBox(0);
    private final ScrollPane scroll = new ScrollPane();
    private long lastTimeShown = 0;

    public ChatView(FxApp app, OldChatClient client, Conversation conv) {
        this.app = app;
        this.client = client;
        this.conv = conv;

        // ===== Toolbar（48dp, 白底）=====
        HBox bar = new HBox(4);
        bar.getStyleClass().add("chat-toolbar");
        bar.setPrefHeight(48);
        bar.setAlignment(Pos.CENTER_LEFT);

        StackPane back = Widgets.iconButton("←", 40, app::goMain);

        StackPane av = Widgets.avatar(conv.title.isEmpty() ? "?" : conv.title.substring(0, 1), conv.avatarColor, 36);
        av.setCursor(javafx.scene.Cursor.HAND);
        av.setOnMouseClicked(e -> app.goSpace(conv.peerUid));

        VBox titleBox = new VBox(1);
        Label title = new Label(conv.title);
        title.getStyleClass().add("chat-title");
        titleBox.getChildren().add(title);
        User peer = client.getUserProfile(conv.peerUid).user;
        if (!conv.isGroup && peer.online) {
            HBox conn = new HBox(5);
            conn.setAlignment(Pos.CENTER_LEFT);
            Region dot = new Region();
            dot.setPrefSize(7, 7);
            dot.setStyle("-fx-background-color:#22C55E;-fx-background-radius:3.5;");
            Label connT = new Label("在线");
            connT.getStyleClass().add("chat-conn");
            conn.getChildren().addAll(dot, connT);
            titleBox.getChildren().add(conn);
        }

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        ImageView menu = Widgets.iconImage("ic_more_three_dots", 24);
        menu.setCursor(javafx.scene.Cursor.HAND);
        menu.setOnMouseClicked(e -> app.goSpace(conv.peerUid));
        bar.getChildren().addAll(back, av, titleBox, sp, menu);

        // ===== 消息流 =====
        scroll.setContent(messages);
        scroll.getStyleClass().add("chat-scroll");
        scroll.setFitToWidth(true);
        messages.setPadding(new Insets(6, 2, 6, 2));
        List<ChatMessage> msgs = client.getMessages(conv.id);
        for (ChatMessage m : msgs) {
            maybeTimePill(m.time);
            messages.getChildren().add(bubble(m));
        }
        scrollToBottom();

        client.setIncomingListener(m -> {
            if (m.conversationId.equals(conv.id)) {
                Platform.runLater(() -> {
                    maybeTimePill(m.time);
                    messages.getChildren().add(bubble(m));
                    scrollToBottom();
                });
            }
        });

        // ===== 输入栏 =====
        VBox bottom = new VBox(0);
        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setStyle("-fx-background-color:#E5E7EB;");
        HBox input = new HBox(8);
        input.getStyleClass().add("chat-input");
        input.setAlignment(Pos.CENTER_LEFT);
        input.setPadding(new Insets(8));

        ImageView voice = Widgets.iconImage("chatting_setmode_voice_btn_normal", 28);
        voice.setCursor(javafx.scene.Cursor.HAND);

        TextField tf = new TextField();
        tf.setPromptText("输入消息...");
        tf.getStyleClass().add("text-field");
        HBox.setHgrow(tf, Priority.ALWAYS);

        StackPane plus = Widgets.plusIcon(28);
        plus.setPrefSize(36, 36);
        plus.setCursor(javafx.scene.Cursor.HAND);

        Label send = new Label("➤");
        send.getStyleClass().add("btn-send");
        send.setPrefSize(40, 40);
        send.setAlignment(Pos.CENTER);
        send.setOnMouseClicked(e -> doSend(tf));
        tf.setOnAction(e -> doSend(tf));

        input.getChildren().addAll(voice, tf, plus, send);
        bottom.getChildren().addAll(divider, input);

        root.setTop(bar);
        root.setCenter(scroll);
        root.setBottom(bottom);
    }

    private void doSend(TextField tf) {
        String t = tf.getText().trim();
        if (t.isEmpty()) return;
        client.sendMessage(conv.id, t);
        tf.clear();
        scrollToBottom();
    }

    private void maybeTimePill(long time) {
        if (lastTimeShown == 0 || time - lastTimeShown > 5 * 60 * 1000) {
            Label pill = new Label(Widgets.formatTime(time));
            pill.getStyleClass().add("msg-time-pill");
            HBox p = new HBox(pill);
            p.setAlignment(Pos.CENTER);
            p.setPadding(new Insets(4, 0, 4, 0));
            messages.getChildren().add(p);
            lastTimeShown = time;
        }
    }

    private HBox bubble(ChatMessage m) {
        HBox row = new HBox();
        row.setPadding(new Insets(2, 4, 2, 4));
        row.setAlignment(m.outgoing ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        VBox bubble = new VBox();
        bubble.getStyleClass().addAll("bubble", m.outgoing ? "bubble-out" : "bubble-in");
        bubble.setPadding(new Insets(7, 12, 7, 12));
        Label t = new Label(m.text);
        t.getStyleClass().add("msg-text");
        t.setWrapText(true);
        t.setMaxWidth(280);
        bubble.getChildren().add(t);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        if (m.outgoing) row.getChildren().addAll(grow, bubble);
        else row.getChildren().addAll(bubble, grow);
        return row;
    }

    private void scrollToBottom() {
        Platform.runLater(() -> scroll.setVvalue(1.0));
    }
}
