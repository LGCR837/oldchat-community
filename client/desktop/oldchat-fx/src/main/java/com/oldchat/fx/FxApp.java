package com.oldchat.fx;

import com.oldchat.core.client.MockOldChatClient;
import com.oldchat.core.client.OldChatClient;
import com.oldchat.core.model.Conversation;
import com.oldchat.fx.view.ChatView;
import com.oldchat.fx.view.MainView;
import com.oldchat.fx.view.UserSpaceView;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.ArrayDeque;
import java.util.Deque;

/** 应用入口 + 导航器：手机式全屏切换（主界面 / 聊天 / 用户空间）。 */
public class FxApp extends Application {
    private OldChatClient client;
    private Scene scene;
    private final Deque<Parent> backStack = new ArrayDeque<>();

    @Override
    public void start(Stage stage) {
        client = new MockOldChatClient(new FxScheduler());
        try {
            client.login("demo", "demo");
        } catch (Exception ignored) {
        }

        scene = new Scene(new MainView(this).root, 420, 760);
        scene.getStylesheets().add(
                FxApp.class.getResource("/com/oldchat/fx/style.css").toExternalForm());

        stage.setTitle("旧聊 Windows");
        stage.setMinWidth(340);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();
    }

    public OldChatClient client() {
        return client;
    }

    public void goMain() {
        push();
        scene.setRoot(new MainView(this).root);
    }

    public void goChat(Conversation c) {
        push();
        scene.setRoot(new ChatView(this, client, c).root);
    }

    public void goSpace(String uid) {
        push();
        scene.setRoot(new UserSpaceView(this, client, uid).root);
    }

    public void back() {
        if (!backStack.isEmpty()) {
            scene.setRoot(backStack.pop());
        } else {
            scene.setRoot(new MainView(this).root);
        }
    }

    private void push() {
        backStack.push(scene.getRoot());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
