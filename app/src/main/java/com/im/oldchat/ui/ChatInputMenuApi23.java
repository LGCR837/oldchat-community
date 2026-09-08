package com.im.oldchat.ui;

import android.annotation.TargetApi;
import android.widget.EditText;

/** 隔离 API 23 的插入光标菜单。 */
@TargetApi(23)
final class ChatInputMenuApi23 {
    private ChatInputMenuApi23() { }

    static void bindInsertion(EditText editText) {
        editText.setCustomInsertionActionModeCallback(ChatInputMenuApi11.buildCallback(editText));
    }
}
