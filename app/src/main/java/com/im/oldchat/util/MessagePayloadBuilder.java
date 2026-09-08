package com.im.oldchat.util;

import java.util.List;

public class MessagePayloadBuilder {
    private MessagePayloadBuilder() {
    }

    public static String buildBody(String text, MessagePayload.Quote quote,
                                   List<MessagePayload.Mention> mentions,
                                   String mediaKind) {
        MessagePayload payload = new MessagePayload();
        payload.text = text == null ? "" : text;
        if (mediaKind != null && !mediaKind.isEmpty()) {
            payload.mediaKind = mediaKind;
        }
        if (quote != null) {
            payload.quote = quote;
        }
        if (mentions != null && !mentions.isEmpty()) {
            payload.mentions.addAll(mentions);
        }
        if (!payload.hasExtras()) {
            return payload.text;
        }
        return payload.toJson();
    }
}
