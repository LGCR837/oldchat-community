package com.im.oldchat.ui;

import android.content.Context;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;

import java.util.ArrayList;
import java.util.List;

final class ForwardMessageComposer {
    private static final int ITEM_PREVIEW_LIMIT = 48;

    private ForwardMessageComposer() {
    }

    static boolean hasForward(MessagePayload payload) {
        return payload != null
                && payload.forward != null
                && payload.forward.items != null
                && !payload.forward.items.isEmpty();
    }

    static MessagePayload.ForwardItem buildDirectItem(Context context,
                                                      Message message,
                                                      String myUid,
                                                      String friendUid,
                                                      String friendName,
                                                      String friendAvatar) {
        if (message == null) {
            return null;
        }
        MessagePayload payload = MessagePayload.fromBody(message.body);
        String type = normalizeType(message.msg_type);
        boolean isMine = MyUidStore.isMyUid(context, message.from_uid, myUid);
        MessagePayload.ForwardItem item = new MessagePayload.ForwardItem();
        if (isMine) {
            String senderUid = safe(MyUidStore.getCurrentUid(context));
            if (senderUid.length() == 0) {
                senderUid = safe(myUid);
            }
            if (senderUid.length() == 0) {
                senderUid = safe(message.from_uid);
            }
            item.fromUid = senderUid;
            item.fromName = resolveMyDisplayName(context, senderUid);
            item.fromAvatar = "";
        } else {
            String senderUid = safe(friendUid);
            if (senderUid.length() == 0) {
                senderUid = safe(message.from_uid);
            }
            item.fromUid = senderUid;
            item.fromName = resolveFriendDisplayName(context, senderUid, friendName);
            item.fromAvatar = safe(friendAvatar);
        }
        item.type = type;
        item.sourceMessageId = safe(message.id);
        item.mediaKind = payload == null ? "" : safe(payload.mediaKind);
        item.text = sanitizePreview(buildPreviewText(context, type, payload));
        return item;
    }

    static MessagePayload.ForwardItem buildGroupItem(Context context,
                                                     GroupMessage message,
                                                     String displayName,
                                                     String avatarUrl) {
        if (message == null) {
            return null;
        }
        MessagePayload payload = MessagePayload.fromBody(message.body);
        String type = normalizeType(message.msg_type);
        MessagePayload.ForwardItem item = new MessagePayload.ForwardItem();
        item.fromUid = safe(message.from_uid);
        item.fromName = safe(displayName);
        if (item.fromName.length() == 0) {
            item.fromName = item.fromUid;
        }
        item.fromAvatar = safe(avatarUrl);
        item.type = type;
        item.sourceMessageId = safe(message.id);
        item.mediaKind = payload == null ? "" : safe(payload.mediaKind);
        item.text = sanitizePreview(buildPreviewText(context, type, payload));
        return item;
    }

    static MessagePayload.ForwardBundle buildBundle(Context context, List<MessagePayload.ForwardItem> source) {
        MessagePayload.ForwardBundle bundle = new MessagePayload.ForwardBundle();
        bundle.title = context == null
                ? "聊天记录"
                : context.getString(R.string.forward_bundle_title);
        if (source == null) {
            return bundle;
        }
        for (int i = 0; i < source.size(); i++) {
            MessagePayload.ForwardItem src = source.get(i);
            if (src == null) {
                continue;
            }
            MessagePayload.ForwardItem cloned = new MessagePayload.ForwardItem();
            cloned.sourceMessageId = safe(src.sourceMessageId);
            cloned.fromUid = safe(src.fromUid);
            cloned.fromName = safe(src.fromName);
            if (cloned.fromName.length() == 0) {
                cloned.fromName = cloned.fromUid;
            }
            cloned.fromAvatar = safe(src.fromAvatar);
            cloned.type = normalizeType(src.type);
            cloned.mediaKind = safe(src.mediaKind);
            cloned.text = sanitizePreview(src.text);
            bundle.items.add(cloned);
        }
        return bundle;
    }

    static String buildForwardBody(Context context, List<MessagePayload.ForwardItem> source) {
        MessagePayload.ForwardBundle bundle = buildBundle(context, source);
        if (bundle.items == null || bundle.items.isEmpty()) {
            return "";
        }
        MessagePayload payload = new MessagePayload();
        payload.forward = bundle;
        payload.text = buildFallbackText(context, bundle.items);
        return payload.toJson();
    }

    static CharSequence buildBubbleText(Context context, MessagePayload.ForwardBundle bundle) {
        return ForwardBubbleTextBuilder.build(context, bundle);
    }

    static String buildFallbackText(Context context, List<MessagePayload.ForwardItem> items) {
        int count = items == null ? 0 : items.size();
        if (count <= 0) {
            return "";
        }
        if (context != null) {
            return context.getString(R.string.forward_fallback_count, count);
        }
        return "[聊天记录] 共" + count + "条";
    }

    static List<MessagePayload.ForwardItem> copyItems(List<MessagePayload.ForwardItem> source) {
        List<MessagePayload.ForwardItem> out = new ArrayList<MessagePayload.ForwardItem>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (int i = 0; i < source.size(); i++) {
            MessagePayload.ForwardItem one = source.get(i);
            if (one == null) {
                continue;
            }
            MessagePayload.ForwardItem cloned = new MessagePayload.ForwardItem();
            cloned.sourceMessageId = safe(one.sourceMessageId);
            cloned.fromUid = safe(one.fromUid);
            cloned.fromName = safe(one.fromName);
            cloned.fromAvatar = safe(one.fromAvatar);
            cloned.type = normalizeType(one.type);
            cloned.mediaKind = safe(one.mediaKind);
            cloned.text = sanitizePreview(one.text);
            out.add(cloned);
        }
        return out;
    }

    private static String resolveMyDisplayName(Context context, String myUid) {
        String name = safe(UserNameCache.getName(context, myUid));
        if (name.length() > 0) {
            return name;
        }
        if (context != null) {
            return context.getString(R.string.forward_sender_me);
        }
        return "我";
    }

    private static String resolveFriendDisplayName(Context context, String uid, String fallbackName) {
        String name = safe(fallbackName);
        if (name.length() > 0) {
            return name;
        }
        name = safe(UserNameCache.getName(context, uid));
        if (name.length() > 0) {
            return name;
        }
        return uid;
    }

    private static String buildPreviewText(Context context, String type, MessagePayload payload) {
        if ("text".equals(type)) {
            if (hasForward(payload)) {
                if (context != null) {
                    return context.getString(R.string.forward_preview_nested_bundle);
                }
                return "[聊天记录]";
            }
            String text = payload == null ? "" : safe(payload.text);
            if (text.length() > 0) {
                return text;
            }
        }
        if ("voice".equals(type) && payload != null) {
            String voiceText = safe(payload.voiceText);
            if (voiceText.length() > 0) {
                return voiceText;
            }
        }
        String mediaKind = payload == null ? "" : safe(payload.mediaKind);
        String text = payload == null ? "" : safe(payload.text);
        String preview = ChatMessageUtil.quotePreview(type, mediaKind, text);
        if (preview.length() > 0) {
            return preview;
        }
        if (context != null) {
            return context.getString(R.string.forward_preview_empty);
        }
        return "...";
    }

    private static String normalizeType(String value) {
        String out = safe(value).toLowerCase();
        if (out.length() == 0) {
            return "text";
        }
        return out;
    }

    private static String sanitizePreview(String value) {
        String out = safe(value).replace('\r', ' ').replace('\n', ' ').trim();
        if (out.length() > ITEM_PREVIEW_LIMIT) {
            out = out.substring(0, ITEM_PREVIEW_LIMIT) + "...";
        }
        return out;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
