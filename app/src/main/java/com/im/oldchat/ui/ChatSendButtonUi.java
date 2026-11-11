package com.im.oldchat.ui;

import android.support.v4.view.ViewCompat;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

final class ChatSendButtonUi {
    private static final float ENABLED_ALPHA = 1f;
    private static final float DISABLED_ALPHA = 0.58f;

    private ChatSendButtonUi() {
    }

    static void update(Button button, EditText input, boolean sending, boolean voiceMode) {
        if (button == null) {
            return;
        }
        button.setVisibility(voiceMode ? View.GONE : View.VISIBLE);
        boolean enabled = !voiceMode && hasInputText(input);
        button.setEnabled(enabled);
        ViewCompat.setAlpha(button, enabled ? ENABLED_ALPHA : DISABLED_ALPHA);
    }

    private static boolean hasInputText(EditText input) {
        if (input == null) {
            return false;
        }
        CharSequence text = input.getText();
        return text != null && text.toString().trim().length() > 0;
    }
}
