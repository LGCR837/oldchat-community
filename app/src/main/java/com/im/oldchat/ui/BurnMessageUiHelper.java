package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.BurnOnceStore;

import org.json.JSONException;
import org.json.JSONObject;

final class BurnMessageUiHelper {
    private static final String AUTH_PREFS = "auth";

    private BurnMessageUiHelper() {
    }

    static void prepareDirectMessage(Context context, Message msg, boolean isMine) {
        if (context == null || msg == null) {
            return;
        }
        if (!isBurnMessage(msg.msg_type, msg.burn_after_seconds)) {
            return;
        }
        if (TextUtils.isEmpty(msg.burn_preview_text)) {
            msg.burn_preview_text = msg.body == null ? "" : msg.body;
            msg.burn_preview_type = msg.msg_type == null ? "text" : msg.msg_type;
            msg.burn_preview_media_url = msg.media_url == null ? "" : msg.media_url;
            msg.burn_preview_thumb_url = msg.thumb_url == null ? "" : msg.thumb_url;
            if (TextUtils.isEmpty(msg.burn_preview_media_url) && !TextUtils.isEmpty(msg.localPreviewUri)) {
                msg.burn_preview_media_url = msg.localPreviewUri;
            }
        }
        if ("image".equalsIgnoreCase(msg.burn_preview_type)
                && TextUtils.isEmpty(msg.burn_preview_media_url) && !TextUtils.isEmpty(msg.media_url)) {
            msg.burn_preview_media_url = msg.media_url;
        }
        if (BurnOnceStore.isConsumed(context, msg.id)) {
            applyDirectConsumedState(context, msg, isMine);
            return;
        }
        msg.msg_type = "text";
        msg.media_url = "";
        msg.thumb_url = "";
        msg.duration_ms = 0;
        msg.body = context.getString(R.string.chat_burn_message_locked);
    }

    static void prepareGroupMessage(Context context, GroupMessage msg, boolean isMine) {
        if (context == null || msg == null) {
            return;
        }
        if (!isBurnMessage(msg.msg_type, msg.burn_after_seconds)) {
            return;
        }
        if (TextUtils.isEmpty(msg.burn_preview_text)) {
            msg.burn_preview_text = msg.body == null ? "" : msg.body;
            msg.burn_preview_type = msg.msg_type == null ? "text" : msg.msg_type;
            msg.burn_preview_media_url = msg.media_url == null ? "" : msg.media_url;
            msg.burn_preview_thumb_url = msg.thumb_url == null ? "" : msg.thumb_url;
            if (TextUtils.isEmpty(msg.burn_preview_media_url) && !TextUtils.isEmpty(msg.localPreviewUri)) {
                msg.burn_preview_media_url = msg.localPreviewUri;
            }
        }
        if ("image".equalsIgnoreCase(msg.burn_preview_type)
                && TextUtils.isEmpty(msg.burn_preview_media_url) && !TextUtils.isEmpty(msg.media_url)) {
            msg.burn_preview_media_url = msg.media_url;
        }
        if (BurnOnceStore.isConsumed(context, msg.id)) {
            applyGroupConsumedState(context, msg, isMine);
            return;
        }
        msg.msg_type = "text";
        msg.media_url = "";
        msg.thumb_url = "";
        msg.duration_ms = 0;
        msg.body = context.getString(R.string.chat_burn_message_locked);
    }

    static boolean onDirectBurnMessageClick(Context context, Message msg, boolean isMine) {
        if (context == null || msg == null) {
            return false;
        }
        if (!isBurnMessage(msg.msg_type, msg.burn_after_seconds)) {
            return false;
        }
        if (BurnOnceStore.isConsumed(context, msg.id)) {
            applyDirectConsumedState(context, msg, isMine);
            return false;
        }
        String preview = msg.burn_preview_text;
        if (preview == null) {
            preview = "";
        }
        BurnOnceStore.markConsumed(context, msg.id);
        notifyDirectBurnOpened(context, msg, isMine);
        applyDirectConsumedState(context, msg, isMine);
        BurnSecureViewActivity.start(context, msg.id, preview, msg.burn_preview_type,
                msg.burn_preview_media_url, msg.burn_preview_thumb_url, msg.burn_after_seconds);
        return true;
    }

    static boolean onGroupBurnMessageClick(Context context, GroupMessage msg, boolean isMine) {
        if (context == null || msg == null) {
            return false;
        }
        if (!isBurnMessage(msg.msg_type, msg.burn_after_seconds)) {
            return false;
        }
        if (BurnOnceStore.isConsumed(context, msg.id)) {
            applyGroupConsumedState(context, msg, isMine);
            return false;
        }
        String preview = msg.burn_preview_text;
        if (preview == null) {
            preview = "";
        }
        BurnOnceStore.markConsumed(context, msg.id);
        notifyGroupBurnOpened(context, msg, isMine);
        applyGroupConsumedState(context, msg, isMine);
        BurnSecureViewActivity.start(context, msg.id, preview, msg.burn_preview_type,
                msg.burn_preview_media_url, msg.burn_preview_thumb_url, msg.burn_after_seconds);
        return true;
    }

    private static void applyDirectConsumedState(Context context, Message msg, boolean isMine) {
        msg.msg_type = "recall";
        msg.body = isMine
                ? context.getString(R.string.message_recalled_self)
                : context.getString(R.string.message_recalled_other);
        msg.media_url = "";
        msg.thumb_url = "";
        msg.duration_ms = 0;
    }

    private static void applyGroupConsumedState(Context context, GroupMessage msg, boolean isMine) {
        msg.msg_type = "recall";
        if (isMine) {
            msg.body = context.getString(R.string.message_recalled_self);
        } else {
            String name = msg.from_uid == null || msg.from_uid.length() == 0 ? "成员" : msg.from_uid;
            msg.body = context.getString(R.string.message_recalled_member, name);
        }
        msg.media_url = "";
        msg.thumb_url = "";
        msg.duration_ms = 0;
    }

    private static boolean isBurnMessage(String msgType, int burnAfterSeconds) {
        if (!BurnAfterSecondsUtil.isEnabled(burnAfterSeconds)) {
            return false;
        }
        String type = msgType == null ? "text" : msgType.trim().toLowerCase();
        return !"recall".equals(type);
    }

    private static void notifyDirectBurnOpened(final Context context, final Message msg, boolean isMine) {
        if (isMine || context == null || msg == null) {
            return;
        }
        final String messageID = msg.id == null ? "" : msg.id.trim();
        if (TextUtils.isEmpty(messageID)) {
            return;
        }
        final String token = getToken(context);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("message_id", messageID);
                } catch (JSONException ignore) {
                    return;
                }
                HttpUtil.post("/direct/burn/open", payload, token, null);
            }
        }, "burn-open-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private static void notifyGroupBurnOpened(final Context context, final GroupMessage msg, boolean isMine) {
        if (isMine || context == null || msg == null) {
            return;
        }
        final String messageID = msg.id == null ? "" : msg.id.trim();
        if (TextUtils.isEmpty(messageID)) {
            return;
        }
        final String token = getToken(context);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("message_id", messageID);
                } catch (JSONException ignore) {
                    return;
                }
                HttpUtil.post("/groups/burn/open", payload, token, null);
            }
        }, "group-burn-open-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private static String getToken(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        if (sp == null) {
            return "";
        }
        String token = sp.getString("access_token", "");
        if (TextUtils.isEmpty(token)) token = sp.getString("token", "");
        return token == null ? "" : token;
    }
}
