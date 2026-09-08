package com.im.oldchat.ui;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;

final class ChatMessageActionGuard {
    private ChatMessageActionGuard() {
    }

    static boolean isLocalOnly(Message message) {
        return message != null && (message.localPending || message.localFailed);
    }

    static boolean isLocalOnly(GroupMessage message) {
        return message != null && (message.localPending || message.localFailed);
    }

    static boolean canQuoteOrForward(Message message) {
        return message != null && !isLocalOnly(message);
    }

    static boolean canQuoteOrForward(GroupMessage message) {
        return message != null && !isLocalOnly(message);
    }
}
