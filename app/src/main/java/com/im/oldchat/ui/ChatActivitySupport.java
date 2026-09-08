package com.im.oldchat.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.ChatOpenPerfTracer;
import com.im.oldchat.util.TypingStatusManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

abstract class ChatActivitySupport extends BaseActivity {
    protected static final long RESUME_REFRESH_THRESHOLD_MS = 15000L;
    protected static final int REQ_PICK_EMOJI = 3101;
    protected static final int REQ_SEND_RED_PACKET = 3102;
    protected static final int REQ_PICK_CHAT_BG = 3004;
    protected static final int REQ_CHAT_SETTINGS = 3005;
    protected static final long TYPING_IDLE_MS = 3000;

    protected android.support.v7.widget.RecyclerView lvMessages;
    protected View chatOpenLoadingMask;
    protected ChatOpenLoadingController chatOpenLoadingController;
    protected ChatOpenPerfTracer chatOpenPerfTracer;
    protected EditText etInput;
    protected String lastDraft = "";
    protected Button btnSend;
    protected TextView btnLoadMore;
    protected ProgressBar pbSend;
    protected ProgressBar pbMessagesLoading;
    protected View inputArea;
    protected View quotePreview;
    protected TextView tvQuotePreview;
    protected View forwardMultiBar;
    protected TextView tvForwardMultiCount;
    protected TextView btnForwardMultiCancel;
    protected TextView btnForwardMultiSend;
    protected MessagePayload.Quote quoteDraft;
    protected int sendingCount = 0;
    protected ChatMediaHelper mediaHelper;
    protected MessageAdapter adapter;
    protected final List<Message> messageList = new ArrayList<>();
    protected final HashSet<String> messageIds = new HashSet<>();
    protected String friendUID;
    protected String friendNcuid;
    protected String friendName;
    protected String friendAvatar;
    protected ImageView ivChatAvatar;
    protected String myUID;
    protected String token;
    protected boolean needsRefreshOnResume = false;
    protected long lastPauseAtMs = 0L;
    protected ChatWsListener wsListener;
    protected View backDot;
    protected TextView btnNewMessage;
    protected TextView btnJumpToUnread;
    protected DirectChatListHelper listHelper;
    protected DirectMessageSender messageSender;
    protected ChatBackgroundHelper backgroundHelper;
    protected float inputBaseSizePx;
    protected float quotePreviewBaseSizePx;
    protected float lastAppliedChatFontScale = Float.NaN;
    protected boolean isTyping = false;
    protected TypingStatusManager typingStatusManager;
    protected final Handler typingHandler = new Handler(Looper.getMainLooper());
    protected final Runnable typingIdleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isTyping) {
                return;
            }
            if (typingStatusManager != null && friendUID != null && !friendUID.isEmpty()) {
                typingStatusManager.stopTyping(ChatActivitySupport.this, token, friendUID, false);
            }
            isTyping = false;
        }
    };
    protected final WSManager.Listener backDotListener = UnreadDotHelper.createBackDotListener(new Runnable() {
        @Override
        public void run() {
            updateBackDot();
        }
    });

    protected void setupLoadMoreHeader() {
        btnLoadMore = null;
    }

    protected void openFriendSpace() {
        if (friendUID == null || friendUID.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, UserSpaceActivity.class);
        if (friendNcuid != null && !friendNcuid.isEmpty()) {
            // 优先用不可变 NCUID，避免好友改 UID 后查不到资料。
            intent.putExtra("ncuid", friendNcuid);
        } else {
            intent.putExtra("uid", friendUID);
        }
        intent.putExtra("friend_avatar", friendAvatar);
        intent.putExtra("friend_name", friendName);
        startActivity(intent);
    }

    protected void openRedPacketSend() {
        if (mediaHelper != null) {
            mediaHelper.hideActionPanel();
        }
        if (friendUID == null || friendUID.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, RedPacketSendActivity.class);
        intent.putExtra(RedPacketSendActivity.EXTRA_TO_UID, friendUID);
        intent.putExtra(RedPacketSendActivity.EXTRA_TO_NCUID, friendNcuid);
        intent.putExtra(RedPacketSendActivity.EXTRA_TARGET_NAME, friendName == null ? "" : friendName);
        startActivityForResult(intent, REQ_SEND_RED_PACKET);
    }

    protected void setSending(boolean sending) {
        if (sending) {
            sendingCount++;
        } else {
            sendingCount = Math.max(0, sendingCount - 1);
        }
        if (pbSend != null) {
            pbSend.setVisibility(sendingCount > 0 ? View.VISIBLE : View.GONE);
        }
        ChatSendButtonUi.update(btnSend, etInput, sendingCount > 0,
                mediaHelper != null && mediaHelper.isVoiceMode);
    }

    protected void updateRecentFromLastMessage() {
        if (friendUID == null || friendUID.isEmpty() || messageList.isEmpty()) {
            return;
        }
        Message last = messageList.get(messageList.size() - 1);
        if (last == null) {
            return;
        }
        String name = friendName != null && !friendName.isEmpty() ? friendName : friendUID;
        String preview = ChatMessageUtil.previewForMessage(last);
        if (MyUidStore.isMyUid(this, last.from_uid, myUID)) {
            RecentChatCache.updateRecentChatOutgoing(this, friendUID, friendNcuid, name, friendAvatar, preview, last.created_at);
        } else {
            RecentChatCache.updateRecentChatIncoming(this, friendUID, friendNcuid, name, friendAvatar, preview, last.created_at, 0);
        }
        RecentChatCache.clearUnread(this, friendUID);
    }

    protected void quoteMessage(Message msg) {
        quoteDraft = DirectChatUiActions.buildQuote(msg, friendName);
        DirectChatUiActions.applyQuote(quotePreview, tvQuotePreview, quoteDraft);
    }

    protected void recallMessage(Message msg) {
        if (msg == null || msg.id == null) {
            return;
        }
        DirectChatUiActions.confirmRecall(asChatActivity(), token, friendUID, messageList, messageIds, adapter, msg.id);
    }

    protected void reEditRecalledMessage(Message msg) {
        if (msg == null || etInput == null) {
            return;
        }
        String type = msg.recall_edit_type == null ? "text" : msg.recall_edit_type.toLowerCase();
        String content = msg.recall_edit_text == null ? "" : msg.recall_edit_text.trim();
        // 修复旧缓存：历史版本可能把完整 MessagePayload JSON 存进重新编辑字段。
        if ("text".equals(type) && content.startsWith("{")) {
            String plainText = MessagePayload.fromBody(content).text;
            content = plainText == null ? "" : plainText.trim();
        }
        if (!"text".equals(type) || content.length() == 0) {
            Toast.makeText(this, "该消息暂不支持重新编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        etInput.requestFocus();
        etInput.setText(content);
        etInput.setSelection(content.length());
        if (quoteDraft != null) {
            clearQuoteDraft();
        }
    }

    protected void clearQuoteDraft() {
        quoteDraft = null;
        DirectChatUiActions.clearQuote(quotePreview, tvQuotePreview);
    }

    protected void setupForwardMultiSelectBar() {
        inputArea = findViewByIdCompat(R.id.input_area);
        forwardMultiBar = findViewByIdCompat(R.id.forward_multi_bar);
        tvForwardMultiCount = findViewByIdCompat(R.id.tvForwardMultiCount);
        btnForwardMultiCancel = findViewByIdCompat(R.id.btnForwardMultiCancel);
        btnForwardMultiSend = findViewByIdCompat(R.id.btnForwardMultiSend);
        if (btnForwardMultiCancel != null) {
            btnForwardMultiCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelForwardMultiSelect();
                }
            });
        }
        if (btnForwardMultiSend != null) {
            btnForwardMultiSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    forwardSelectedMessages();
                }
            });
        }
        updateForwardMultiSelectUi(false, 0);
    }

    protected void onForwardSelectionStateChanged(boolean enabled, int count) {
        updateForwardMultiSelectUi(enabled, count);
    }

    protected void forwardSingleMessage(Message message) {
        List<MessagePayload.ForwardItem> items = buildForwardItems(singletonMessageList(message));
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.forward_no_messages, Toast.LENGTH_SHORT).show();
            return;
        }
        ForwardMessageShareHelper.showTargetPicker(this, token, items, null);
    }

    protected void enterForwardMultiSelect(Message message) {
        if (adapter == null) {
            return;
        }
        adapter.enterSelectionMode(message);
    }

    protected void cancelForwardMultiSelect() {
        if (adapter != null) {
            adapter.exitSelectionMode();
        } else {
            updateForwardMultiSelectUi(false, 0);
        }
    }

    protected void forwardSelectedMessages() {
        if (adapter == null) {
            return;
        }
        List<Message> selected = adapter.getSelectedMessages();
        List<MessagePayload.ForwardItem> items = buildForwardItems(selected);
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.forward_no_messages, Toast.LENGTH_SHORT).show();
            return;
        }
        ForwardMessageShareHelper.showTargetPicker(this, token, items, new ForwardMessageShareHelper.Callback() {
            @Override
            public void onSuccess() {
                if (adapter != null) {
                    adapter.exitSelectionMode();
                } else {
                    updateForwardMultiSelectUi(false, 0);
                }
            }
        });
    }

    protected void openEmojiPicker() {
        Intent intent = new Intent(this, EmojiPickerActivity.class);
        startActivityForResult(intent, REQ_PICK_EMOJI);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false;
    }

    protected void openChatSettings() {
        Intent intent = new Intent(this, ChatSettingsActivity.class);
        intent.putExtra("friend_uid", friendUID);
        intent.putExtra("friend_ncuid", friendNcuid);
        intent.putExtra("friend_name", friendName);
        intent.putExtra("friend_avatar", friendAvatar);
        startActivityForResult(intent, REQ_CHAT_SETTINGS);
    }

    protected void applyFontScale() {
        float scale = SettingsPrefs.getFontScale(this);
        if (!Float.isNaN(lastAppliedChatFontScale) && lastAppliedChatFontScale == scale) {
            return;
        }
        if (etInput != null) {
            if (inputBaseSizePx == 0f) {
                inputBaseSizePx = etInput.getTextSize();
            }
            etInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, inputBaseSizePx * scale);
        }
        if (tvQuotePreview != null) {
            if (quotePreviewBaseSizePx == 0f) {
                quotePreviewBaseSizePx = tvQuotePreview.getTextSize();
            }
            tvQuotePreview.setTextSize(TypedValue.COMPLEX_UNIT_PX, quotePreviewBaseSizePx * scale);
        }
        if (adapter != null) {
            adapter.setFontScale(scale);
        }
        lastAppliedChatFontScale = scale;
    }

    protected void sendFromInput() {
        if (adapter != null && adapter.isSelectionMode()) {
            return;
        }
        if (mediaHelper != null) {
            mediaHelper.hideActionPanel();
        }
        if (messageSender != null && etInput != null) {
            String content = etInput.getText().toString();
            if (content.trim().length() == 0 && quoteDraft == null) {
                return;
            }
            messageSender.sendText(content, quoteDraft);
            lastDraft = ChatDraftHelper.clearDraft(this, getDraftKey());
        }
    }

    protected void setupTypingWatcher() {
        if (etInput == null) {
            return;
        }
        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ChatSendButtonUi.update(btnSend, etInput, sendingCount > 0,
                        mediaHelper != null && mediaHelper.isVoiceMode);
                if (friendUID == null || friendUID.isEmpty()) {
                    return;
                }
                if (!SettingsPrefs.isTypingIndicatorEnabled(ChatActivitySupport.this)) {
                    if (isTyping && typingStatusManager != null) {
                        typingStatusManager.stopTyping(ChatActivitySupport.this, token, friendUID, false);
                        isTyping = false;
                    }
                    typingHandler.removeCallbacks(typingIdleRunnable);
                    return;
                }
                String text = s == null ? "" : s.toString().trim();
                if (text.length() > 0 && !isTyping) {
                    isTyping = true;
                    if (typingStatusManager != null) {
                        typingStatusManager.startTyping(ChatActivitySupport.this, token, friendUID, false);
                    }
                    scheduleTypingIdleStop();
                } else if (text.length() == 0 && isTyping) {
                    isTyping = false;
                    if (typingStatusManager != null) {
                        typingStatusManager.stopTyping(ChatActivitySupport.this, token, friendUID, false);
                    }
                    typingHandler.removeCallbacks(typingIdleRunnable);
                } else if (text.length() > 0 && isTyping) {
                    scheduleTypingIdleStop();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                lastDraft = ChatDraftHelper.scheduleSaveDraft(ChatActivitySupport.this, getDraftKey(),
                        s == null ? "" : s.toString(), lastDraft);
            }
        });
    }

    protected String getDraftKey() {
        if (friendUID == null || friendUID.length() == 0) {
            return null;
        }
        String owner = myUID == null ? "" : myUID;
        return "direct:" + owner + ":" + friendUID;
    }

    protected void scheduleTypingIdleStop() {
        typingHandler.removeCallbacks(typingIdleRunnable);
        typingHandler.postDelayed(typingIdleRunnable, TYPING_IDLE_MS);
    }

    protected void setTypingIndicatorVisible(boolean visible) {
        boolean wasAtBottom = listHelper == null || listHelper.isAtBottom();
        if (adapter != null) {
            adapter.setTypingIndicatorVisible(visible);
        }
        if (visible && wasAtBottom && listHelper != null && lvMessages != null) {
            lvMessages.post(new Runnable() {
                @Override public void run() {
                    if (listHelper != null) listHelper.scrollToBottom();
                }
            });
        }
    }

    protected void refreshMyUID() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String current = prefs.getString("my_uid", "");
        if (current == null || current.isEmpty() || current.equals(myUID)) {
            return;
        }
        myUID = current;
        if (adapter != null) {
            adapter.setMyUID(myUID);
        }
        if (listHelper != null) {
            listHelper.setMyUID(myUID);
        }
        if (wsListener != null) {
            wsListener.setMyUID(myUID);
        }
        ChatMessageUtil.applyMessageStatus(this, messageList, myUID);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    protected boolean shouldRefreshMessagesOnResume() {
        if (messageList.isEmpty()) {
            return true;
        }
        if (lastPauseAtMs <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - lastPauseAtMs >= RESUME_REFRESH_THRESHOLD_MS;
    }

    protected void updateBackDot() {
        if (backDot == null) {
            return;
        }
        boolean hasUnread = UnreadDotHelper.hasOtherUnread(this, friendUID, null);
        backDot.setVisibility(hasUnread ? View.VISIBLE : View.GONE);
    }

    protected ChatActivity asChatActivity() {
        return (ChatActivity) this;
    }

    private List<MessagePayload.ForwardItem> buildForwardItems(List<Message> source) {
        List<MessagePayload.ForwardItem> out = new ArrayList<MessagePayload.ForwardItem>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (int i = 0; i < source.size(); i++) {
            Message one = source.get(i);
            if (!ChatMessageActionGuard.canQuoteOrForward(one)) {
                continue;
            }
            MessagePayload.ForwardItem item = ForwardMessageComposer.buildDirectItem(
                    this, one, myUID, friendUID, friendName, friendAvatar);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private List<Message> singletonMessageList(Message message) {
        ArrayList<Message> list = new ArrayList<Message>();
        if (message != null) {
            list.add(message);
        }
        return list;
    }

    private void updateForwardMultiSelectUi(boolean enabled, int count) {
        if (forwardMultiBar != null) {
            forwardMultiBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (inputArea != null) {
            inputArea.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
        if (enabled) {
            if (mediaHelper != null) {
                mediaHelper.hideActionPanel();
            }
            hideInputKeyboard();
            clearQuoteDraft();
        }
        if (tvForwardMultiCount != null) {
            tvForwardMultiCount.setText(getString(R.string.forward_multi_count, count));
        }
    }

    private void hideInputKeyboard() {
        if (etInput != null) {
            etInput.clearFocus();
        }
        Object service = getSystemService(INPUT_METHOD_SERVICE);
        if (!(service instanceof InputMethodManager) || etInput == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) service;
        imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
    }
}
