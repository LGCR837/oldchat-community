package com.im.oldchat.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

final class ChatInlineEmojiPanelDataLoader {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    interface Callback {
        void onLoaded(List<EmojiStore.EmojiItem> loaded);

        void onError(Throwable error);
    }

    private ChatInlineEmojiPanelDataLoader() {
    }

    static void load(final Activity activity, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<EmojiStore.EmojiItem> loaded = EmojiStore.load(activity);
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onLoaded(loaded);
                            }
                        }
                    });
                } catch (final Throwable error) {
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onError(error);
                            }
                        }
                    });
                }
            }
        }, "chat-emoji-load").start();
    }
}
