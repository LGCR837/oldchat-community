package com.im.oldchat.ui;
import android.app.Activity;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MessagePayloadBuilder;
import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.util.List;
public class GroupMessageSender {
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
    private final GroupChatListHelper listHelper;
    private final TextView btnLoadMore;
    private final EditText input;
    private final String token;
    private final String groupId;
    private final String groupName;
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
    public GroupMessageSender(Activity context,
                              GroupChatListHelper listHelper,
                              TextView btnLoadMore,
                              EditText input,
                              String token,
                              String groupId,
                              String groupName,
                              String myUid,
                              SendStateListener sendStateListener,
                              QuoteClearListener quoteClearListener) {
        this.context = context;
        this.listHelper = listHelper;
        this.btnLoadMore = btnLoadMore;
        this.input = input;
        this.token = token;
        this.groupId = groupId;
        this.groupName = groupName;
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
    public void sendText(String content, MessagePayload.Quote quoteDraft, List<MessagePayload.Mention> mentions) {
        sendTextInternal(content, quoteDraft, mentions, null);
    }

    /** 机器人 send_text 按钮：复用正常 pending 队列与发送状态动画。 */
    public void sendButtonPayload(MessagePayload.Button button) {
        if (button == null) return;
        if ("send_image".equals(button.action)) {
            sendMedia("image", button.data, button.data, 0, null);
            return;
        }
        sendTextInternal(button.data, null, null, button.tid);
    }

    private void sendTextInternal(String content, MessagePayload.Quote quoteDraft,
                                  List<MessagePayload.Mention> mentions, String replyTid) {
        String trimmed = content == null ? "" : content.trim();
        if ((trimmed.length() == 0 && quoteDraft == null) || groupId == null || groupId.length() == 0) {
            return;
        }
        final String body;
        if (replyTid != null && replyTid.length() > 0 && quoteDraft == null) {
            MessagePayload payload = new MessagePayload();
            payload.text = trimmed;
            payload.replyTid = replyTid;
            body = payload.toJson();
        } else {
            body = MessagePayloadBuilder.buildBody(trimmed, quoteDraft, mentions, null);
        }
        final int burnSecondsForSend = burnAfterSeconds;
        final String localId = GroupPendingMessageHelper.enqueueText(
                listHelper, myUid, body, burnSecondsForSend);
        if (input != null) {
            input.setText("");
        }
        if (quoteClearListener != null) {
            quoteClearListener.onClearQuote();
        }
        updateLoadMore();
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("body", body);
            json.put("msg_type", "text");
            if (burnSecondsForSend > 0) {
                json.put("burn_after_seconds", burnSecondsForSend);
            }
            HttpUtil.post("/groups/message/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    // 正常路径已在 pending 入列时更新最近会话，避免确认时再次复制并保存整表。
                    if (localId == null) {
                        GroupRecentChatCache.updateGroupOutgoing(context, groupId, groupName,
                                null, messagePreview("text", body, burnSecondsForSend), System.currentTimeMillis());
                    }
                    GroupMessage sent = listHelper == null ? null : listHelper.parseMessageFromResponse(response);
                    if (sent != null && listHelper != null) {
                        if (localId != null) {
                            boolean applied = GroupPendingMessageHelper.completeText(listHelper, localId, sent);
                            if (!applied && (sent.id == null || !listHelper.messageIds.contains(sent.id))) {
                                boolean atBottom = listHelper.isAtBottom();
                                listHelper.appendMessage(sent, atBottom);
                            }
                        } else {
                            boolean atBottom = listHelper.isAtBottom();
                            listHelper.appendMessage(sent, atBottom);
                        }
                    } else if (listHelper != null) {
                        scheduleFallbackRefresh();
                    }
                }
                @Override
                public void onError(int code, String error) {
                    if (localId != null) {
                        GroupPendingMessageHelper.failText(listHelper, localId);
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
                GroupPendingMessageHelper.failText(listHelper, localId);
            }
            Toast.makeText(context, "发送失败", Toast.LENGTH_SHORT).show();
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
        GroupPendingMediaHelper.enqueueImageSpinner(listHelper, myUid, localId, body, path, burnSecondsForSend);
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
                    GroupPendingMessageHelper.failText(listHelper, localId);
                    if (sendStateListener != null) {
                        sendStateListener.onSendState(false);
                    }
                    Toast.makeText(context, "发送表情失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                GroupPendingMediaHelper.markUploadReady(listHelper, localId, url, "", null);
                try {
                    sendMediaInternal("image", url, "", 0, body, burnSecondsForSend,
                            localId, true);
                } catch (Exception e) {
                    GroupPendingMessageHelper.failText(listHelper, localId);
                    if (sendStateListener != null) {
                        sendStateListener.onSendState(false);
                    }
                    Toast.makeText(context, "发送表情失败", Toast.LENGTH_SHORT).show();
                }
            }
            @Override
            public void onError(String message) {
                GroupPendingMessageHelper.failText(listHelper, localId);
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
                                   String body, final int burnSecondsForSend, final String pendingLocalId,
                                   boolean sendStateAlreadyStarted) {
        sendMediaInternal(type, url, thumbUrl, durationMs, body, burnSecondsForSend, pendingLocalId,
                sendStateAlreadyStarted, null);
    }

    private void sendMediaInternal(String type, String url, String thumbUrl, int durationMs,
                                   String body, final int burnSecondsForSend, final String pendingLocalId,
                                   boolean sendStateAlreadyStarted, final String originalUrl) {
        if (groupId == null || groupId.length() == 0 || type == null || type.length() == 0 || url == null || url.length() == 0) {
            if (pendingLocalId != null && pendingLocalId.length() > 0) {
                GroupPendingMessageHelper.failText(listHelper, pendingLocalId);
            }
            if (sendStateAlreadyStarted && sendStateListener != null) {
                sendStateListener.onSendState(false);
            }
            return;
        }
        final String typeFinal = type;
        final String bodyFinal = body;
        try {
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
            json.put("group_id", groupId);
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
            HttpUtil.post("/groups/message/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    GroupRecentChatCache.updateGroupOutgoing(context, groupId, groupName,
                            null, messagePreview(typeFinal, bodyFinal, burnSecondsForSend), System.currentTimeMillis());
                    updateLoadMore();
                    GroupMessage sent = listHelper == null ? null : listHelper.parseMessageFromResponse(response);
                    if (sent != null && listHelper != null) {
                        if (pendingLocalId != null && pendingLocalId.length() > 0) {
                            boolean applied = GroupPendingMessageHelper.completeText(listHelper, pendingLocalId, sent);
                            if (!applied && (sent.id == null || !listHelper.messageIds.contains(sent.id))) {
                                listHelper.appendMessage(sent, true);
                            }
                        } else {
                            listHelper.appendMessage(sent, true);
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
                        GroupPendingMessageHelper.failText(listHelper, pendingLocalId);
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
                GroupPendingMessageHelper.failText(listHelper, pendingLocalId);
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
        if (lower.contains("not_member")) {
            return "你已不在该群，无法发送消息";
        }
        if (lower.contains("group_muted")) {
            return "当前群已全员禁言";
        }
        if (lower.contains("user_banned")) {
            return "账号已被封禁，无法发送消息";
        }
        if (lower.contains("video_disabled")) {
            return "服务器禁用了视频/3GP上传，请联系管理员";
        }
        if (lower.contains("daily_message_limit_reached")) {
            return "您今日在官方群聊的发言次数已达上限（20条/天）";
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

    private String messagePreview(String type, String body, int burnSeconds) {
        return ChatMessageUtil.previewForType(type, body, burnSeconds);
    }
}
