package com.im.oldchat.ui;

import android.app.Activity;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MessagePayloadBuilder;
import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;

public class DirectMessageSender {
    private static final int MAX_VOICE_DURATION_MS = 60000;
    private static final long FALLBACK_REFRESH_DELAY_MS = 700L;
    private int burnAfterSeconds = 0;

    public interface SendStateListener {
        void onSendState(boolean sending);
    }

    public interface QuoteClearListener {
        void onClearQuote();
    }

    private final Activity context;
    private final DirectChatListHelper listHelper;
    private final TextView btnLoadMore;
    private final EditText input;
    private final String token;
    private final String friendUid;
    private final String friendNcuid;
    private final String myUid;
    private final SendStateListener sendStateListener;
    private final QuoteClearListener quoteClearListener;
    private final Runnable fallbackRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (listHelper == null || token == null || token.length() == 0) {
                return;
            }
            listHelper.loadMessages(token, false, 0, true);
        }
    };

    public DirectMessageSender(Activity context,
                               DirectChatListHelper listHelper,
                               TextView btnLoadMore,
                               EditText input,
                               String token,
                               String friendUid,
                               String friendNcuid,
                               String myUid,
                               SendStateListener sendStateListener,
                               QuoteClearListener quoteClearListener) {
        this.context = context;
        this.listHelper = listHelper;
        this.btnLoadMore = btnLoadMore;
        this.input = input;
        this.token = token;
        this.friendUid = friendUid;
        this.friendNcuid = friendNcuid;
        this.myUid = myUid;
        this.sendStateListener = sendStateListener;
        this.quoteClearListener = quoteClearListener;
    }

    public void setBurnAfterSeconds(int seconds) {
        burnAfterSeconds = normalizeBurnSeconds(seconds);
    }

    public int getBurnAfterSeconds() {
        return burnAfterSeconds;
    }

    public boolean isBurnModeEnabled() {
        return burnAfterSeconds > 0;
    }

    private int normalizeBurnSeconds(int seconds) {
        return BurnAfterSecondsUtil.normalize(seconds);
    }

    public void sendText(String content, MessagePayload.Quote quoteDraft) {
        sendTextInternal(content, quoteDraft, null);
    }

    /** 机器人 send_text 按钮：复用正常 pending 队列与发送状态动画。 */
    public void sendButtonPayload(MessagePayload.Button button) {
        if (button == null) return;
        if ("send_image".equals(button.action)) {
            sendMedia("image", button.data, button.data, 0, null);
            return;
        }
        sendTextInternal(button.data, null, button.tid);
    }

    private void sendTextInternal(String content, MessagePayload.Quote quoteDraft, String replyTid) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.length() == 0 && quoteDraft == null) {
            return;
        }
        String body;
        if (replyTid != null && replyTid.length() > 0 && quoteDraft == null) {
            MessagePayload payload = new MessagePayload();
            payload.text = trimmed;
            payload.replyTid = replyTid;
            body = payload.toJson();
        } else {
            body = MessagePayloadBuilder.buildBody(trimmed, quoteDraft, null, null);
        }
        final String localId = DirectPendingMessageHelper.enqueueText(listHelper, myUid, body, burnAfterSeconds);
        if (input != null) {
            input.setText("");
        }
        if (quoteClearListener != null) {
            quoteClearListener.onClearQuote();
        }
        updateLoadMore();
        try {
            JSONObject json = new JSONObject();
            json.put("to_uid", friendUid);
            if (friendNcuid != null && friendNcuid.length() > 0) {
                json.put("to_ncuid", friendNcuid);
            }
            json.put("body", body);
            json.put("msg_type", "text");
            if (burnAfterSeconds > 0) {
                json.put("burn_after_seconds", burnAfterSeconds);
            }
            HttpUtil.post("/direct/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Message sent = listHelper == null ? null : listHelper.parseMessageFromResponse(response);
                    if (sent != null && listHelper != null) {
                        if (localId != null) {
                            boolean applied = DirectPendingMessageHelper.completeText(listHelper, localId, sent);
                            if (!applied && (sent.id == null || !listHelper.messageIds.contains(sent.id))) {
                                listHelper.appendMessage(sent, true, token);
                            }
                        } else {
                            listHelper.appendMessage(sent, true, token);
                        }
                    } else if (listHelper != null) {
                        scheduleFallbackRefresh();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (localId != null) {
                        DirectPendingMessageHelper.failText(listHelper, localId);
                    }
                    if (code == 400 && quoteClearListener != null) {
                        quoteClearListener.onClearQuote();
                    }
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    String tip = resolveSendErrorTip(code, error);
                    if (tip != null) {
                        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(context, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            if (localId != null) {
                DirectPendingMessageHelper.failText(listHelper, localId);
            }
        }
    }

    public void sendMedia(String type, String url, String thumbUrl, int durationMs, MessagePayload.Quote quoteDraft) {
        sendMedia(type, url, thumbUrl, durationMs, quoteDraft, null);
    }

    public void sendMedia(String type, String url, String thumbUrl, int durationMs, MessagePayload.Quote quoteDraft,
                          String originalUrl) {
        String body = MessagePayloadBuilder.buildBody("", quoteDraft, null, null);
        sendMediaInternal(type, url, thumbUrl, durationMs, body, burnAfterSeconds, null, false, originalUrl);
    }

    public void sendPendingMedia(String type, String url, String thumbUrl, int durationMs,
                                 String body, int burnSecondsForSend, String pendingLocalId) {
        sendPendingMedia(type, url, thumbUrl, durationMs, body, burnSecondsForSend, pendingLocalId, null);
    }

    public void sendPendingMedia(String type, String url, String thumbUrl, int durationMs,
                                 String body, int burnSecondsForSend, String pendingLocalId, String originalUrl) {
        sendMediaInternal(type, url, thumbUrl, durationMs, body, burnSecondsForSend,
                pendingLocalId, false, originalUrl);
    }

    public void sendFileResource(String url, String fileName, long fileSize, MessagePayload.Quote quoteDraft,
                                 String pendingLocalId) {
        String text = FileUploadUiTextUtil.buildBody(fileName, fileSize, "");
        String body = MessagePayloadBuilder.buildBody(text, quoteDraft, null, null);
        sendMediaInternal("resource", url, "", 0, body, burnAfterSeconds, pendingLocalId, false, null);
    }

    public void sendEmojiFromPath(String path, boolean isGif, final MessagePayload.Quote quoteDraft) {
        if (path == null || path.length() == 0) {
            return;
        }
        final File file = new File(path);
        if (!file.exists()) {
            Toast.makeText(context, "表情文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        final int burnSecondsForSend = burnAfterSeconds;
        final String body = MessagePayloadBuilder.buildBody("", quoteDraft, null, "emoji");
        final String localId = buildLocalEmojiId();
        DirectPendingMediaHelper.enqueueImageSpinner(listHelper, myUid, localId, body, path, burnSecondsForSend);
        if (quoteClearListener != null) {
            quoteClearListener.onClearQuote();
        }
        updateLoadMore();
        if (sendStateListener != null) {
            sendStateListener.onSendState(true);
        }
        EmojiSendHelper.send(context, file, isGif, token, new EmojiSendHelper.Callback() {
            @Override
            public void onUploaded(String url) {
                if (url == null || url.length() == 0) {
                    DirectPendingMessageHelper.failText(listHelper, localId);
                    if (sendStateListener != null) {
                        sendStateListener.onSendState(false);
                    }
                    Toast.makeText(context, "发送表情失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                DirectPendingMediaHelper.markUploadReady(listHelper, localId, url, "", null);
                try {
                    sendMediaInternal("image", url, "", 0, body, burnSecondsForSend,
                            localId, true);
                } catch (Exception e) {
                    DirectPendingMessageHelper.failText(listHelper, localId);
                    if (sendStateListener != null) {
                        sendStateListener.onSendState(false);
                    }
                    Toast.makeText(context, "发送表情失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                DirectPendingMessageHelper.failText(listHelper, localId);
                if (sendStateListener != null) {
                    sendStateListener.onSendState(false);
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String buildLocalEmojiId() {
        return "local_emoji_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 1000));
    }

    private void sendMediaInternal(String type, String url, String thumbUrl, int durationMs,
                                   String body, int burnSecondsForSend, final String pendingLocalId,
                                   boolean sendStateAlreadyStarted) {
        sendMediaInternal(type, url, thumbUrl, durationMs, body, burnSecondsForSend, pendingLocalId,
                sendStateAlreadyStarted, null);
    }

    private void sendMediaInternal(String type, String url, String thumbUrl, int durationMs,
                                   String body, int burnSecondsForSend, final String pendingLocalId,
                                   boolean sendStateAlreadyStarted, final String originalUrl) {
        try {
            if (type == null || type.length() == 0 || url == null || url.length() == 0) {
                if (pendingLocalId != null && pendingLocalId.length() > 0) {
                    DirectPendingMessageHelper.failText(listHelper, pendingLocalId);
                }
                if (sendStateAlreadyStarted && sendStateListener != null) {
                    sendStateListener.onSendState(false);
                }
                return;
            }
            if ("voice".equals(type)) {
                if (durationMs <= 0) {
                    Toast.makeText(context, "语音时长无效", Toast.LENGTH_SHORT).show();
                    if (sendStateAlreadyStarted && sendStateListener != null) {
                        sendStateListener.onSendState(false);
                    }
                    return;
                }
                if (durationMs > MAX_VOICE_DURATION_MS) {
                    durationMs = MAX_VOICE_DURATION_MS;
                }
            }
            JSONObject json = new JSONObject();
            json.put("to_uid", friendUid);
            if (friendNcuid != null && friendNcuid.length() > 0) {
                json.put("to_ncuid", friendNcuid);
            }
            json.put("msg_type", type);
            json.put("media_url", url);
            if (thumbUrl != null && thumbUrl.length() > 0) {
                json.put("thumb_url", thumbUrl);
            }
            if (originalUrl != null && originalUrl.length() > 0) {
                json.put("original_url", originalUrl);
            }
            if (durationMs > 0) {
                json.put("duration_ms", durationMs);
            }
            if (body != null && body.length() > 0) {
                json.put("body", body);
            }
            if (burnSecondsForSend > 0) {
                json.put("burn_after_seconds", burnSecondsForSend);
            }
            if (!sendStateAlreadyStarted && sendStateListener != null) {
                sendStateListener.onSendState(true);
            }
            HttpUtil.post("/direct/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    updateLoadMore();
                    Message sent = listHelper == null ? null : listHelper.parseMessageFromResponse(response);
                    if (sent != null && listHelper != null) {
                        if (pendingLocalId != null && pendingLocalId.length() > 0) {
                            boolean applied = DirectPendingMessageHelper.completeText(listHelper, pendingLocalId, sent);
                            if (!applied && (sent.id == null || !listHelper.messageIds.contains(sent.id))) {
                                boolean atBottom = listHelper.isAtBottom();
                                listHelper.appendMessage(sent, atBottom, token);
                            }
                        } else {
                            boolean atBottom = listHelper.isAtBottom();
                            listHelper.appendMessage(sent, atBottom, token);
                        }
                    } else if (listHelper != null) {
                        scheduleFallbackRefresh();
                    }
                    if (quoteClearListener != null) {
                        quoteClearListener.onClearQuote();
                    }
                    if (sendStateListener != null) {
                        sendStateListener.onSendState(false);
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (pendingLocalId != null && pendingLocalId.length() > 0) {
                        DirectPendingMessageHelper.failText(listHelper, pendingLocalId);
                    }
                    if (sendStateListener != null) {
                        sendStateListener.onSendState(false);
                    }
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (error != null && error.contains("duration_too_long")) {
                        Toast.makeText(context, "语音不能超过60秒", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String tip = resolveSendErrorTip(code, error);
                    if (tip != null) {
                        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(context, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            if (pendingLocalId != null && pendingLocalId.length() > 0) {
                DirectPendingMessageHelper.failText(listHelper, pendingLocalId);
            }
            Toast.makeText(context, "发送失败", Toast.LENGTH_SHORT).show();
            if (sendStateListener != null) {
                sendStateListener.onSendState(false);
            }
        }
    }

    private String resolveSendErrorTip(int code, String error) {
        if (code != 403 || error == null) {
            return null;
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(error);
            String msg = obj.optString("error", "");
            if (msg.length() > 0 && !msg.equalsIgnoreCase("forbidden") && !msg.equalsIgnoreCase("internal error")) {
                return msg;
            }
        } catch (Exception ignored) {
        }
        String lower = error.toLowerCase();
        if (lower.contains("not_friends")) {
            return "你们还不是好友，不能发送消息";
        }
        if (lower.contains("user_banned")) {
            return "账号已被封禁，无法发送消息";
        }
        if (lower.contains("video_disabled")) {
            return "服务器禁用了视频/3GP上传，请联系管理员";
        }
        return "发送失败: 403";
    }


    private void updateLoadMore() {
        if (btnLoadMore != null) {
            btnLoadMore.setEnabled(true);
            btnLoadMore.setText(R.string.load_more_messages);
        }
    }

    private void scheduleFallbackRefresh() {
        if (listHelper == null || token == null || token.length() == 0) {
            return;
        }
        if (input != null) {
            input.removeCallbacks(fallbackRefreshRunnable);
            input.postDelayed(fallbackRefreshRunnable, FALLBACK_REFRESH_DELAY_MS);
            return;
        }
        if (btnLoadMore != null) {
            btnLoadMore.removeCallbacks(fallbackRefreshRunnable);
            btnLoadMore.postDelayed(fallbackRefreshRunnable, FALLBACK_REFRESH_DELAY_MS);
            return;
        }
        android.view.Window window = context == null ? null : context.getWindow();
        android.view.View decorView = window == null ? null : window.getDecorView();
        if (decorView != null) {
            decorView.removeCallbacks(fallbackRefreshRunnable);
            decorView.postDelayed(fallbackRefreshRunnable, FALLBACK_REFRESH_DELAY_MS);
        } else {
            listHelper.loadMessages(token, false, 0, true);
        }
    }
}
