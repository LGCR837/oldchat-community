package com.im.oldchat.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
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
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.util.ChatOpenPerfTracer;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

abstract class GroupChatActivitySupport0 extends BaseActivity {
    protected static final int REQ_PICK_EMOJI = 3201;
    protected static final int REQ_SEND_RED_PACKET = 3202;
    protected static final int REQ_PICK_CHAT_BG = 3004;
    protected static final int REQ_GROUP_MANAGE = 3005;
    protected static final int ANNOUNCEMENT_MODE_OPTIONAL = 0;
    protected static final int ANNOUNCEMENT_MODE_REQUIRED = 1;
    protected static final long TYPING_IDLE_MS = 3000;
    protected static final long RESUME_REFRESH_THRESHOLD_MS = 15000L;
    protected static final long ANNOUNCEMENT_REFRESH_THROTTLE_MS = 5000L;

    protected GroupMessageRecyclerView lvMessages;
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
    protected View btnQuoteClose;
    protected View chatBackground;
    protected TextView tvTitle;
    protected MessagePayload.Quote quoteDraft;
    protected int sendingCount = 0;
    protected ChatMediaHelper mediaHelper;
    protected GroupMessageAdapter adapter;
    protected final List<GroupMessage> messageList = new ArrayList<>();
    protected final HashSet<String> messageIds = new HashSet<>();
    protected final Map<String, String> nameMap = new HashMap<>();
    protected final Map<String, String> avatarMap = new HashMap<>();
    protected final Map<String, String> titleMap = new HashMap<>();
    protected final Map<String, Integer> roleMap = new HashMap<>();
    protected final List<GroupMember> mentionMembers = new ArrayList<>();
    protected final List<MessagePayload.Mention> mentionDrafts = new ArrayList<>();
    protected String groupId;
    protected String groupName;
    protected String groupAvatar;
    protected String token;
    protected String myUID;
    protected int myRole = 0;
    protected boolean needsRefreshOnResume = false;
    protected WSManager.Listener wsListener;
    protected View backDot;
    protected TextView btnNewMessage;
    protected TextView btnJumpToUnread;
    protected GroupChatListHelper listHelper;
    protected GroupMessageSender messageSender;
    protected ChatBackgroundHelper backgroundHelper;
    protected GroupManageApi manageApi;
    protected View announcementBanner;
    protected TextView tvAnnouncementBanner;
    protected String announcementText = "";
    protected int announcementMode = ANNOUNCEMENT_MODE_OPTIONAL;
    protected long announcementUpdatedAt = 0;
    protected long announcementReadAt = 0;
    protected long lastPauseAtMs = 0L;
    protected long lastAnnouncementRefreshAtMs = 0L;
    protected boolean announcementDialogShowing = false;
    protected float inputBaseSizePx;
    protected float quotePreviewBaseSizePx;
    protected float lastAppliedChatFontScale = Float.NaN;
    protected android.support.v7.app.AlertDialog mentionDialog;
    protected GroupMentionAdapter mentionAdapter;
    protected boolean suppressMentionTrigger = false;
    protected boolean isTyping = false;
    protected final LinkedHashSet<String> typingUsers = new LinkedHashSet<>();
    protected com.im.oldchat.util.TypingStatusManager typingStatusManager;
    protected final Handler typingHandler = new Handler(Looper.getMainLooper());
    protected final Runnable typingIdleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isTyping) {
                return;
            }
            if (typingStatusManager != null && groupId != null && !groupId.isEmpty()) {
                typingStatusManager.stopTyping(GroupChatActivitySupport0.this, token, groupId, true);
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
        View header = getLayoutInflater().inflate(R.layout.list_load_more, lvMessages, false);
        btnLoadMore = header.findViewById(R.id.btnLoadMore);
        btnLoadMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listHelper != null && listHelper.canLoadMore()) {
                    long before = listHelper.getOldestTimestamp();
                    listHelper.loadMessages(token, true, before, false);
                }
            }
        });
        lvMessages.addHeaderView(header);
    }

    protected void openManage() {
        Intent intent = new Intent(this, GroupManageActivity.class);
        intent.putExtra("group_id", groupId);
        intent.putExtra("group_name", groupName);
        intent.putExtra("group_avatar", groupAvatar);
        intent.putExtra("group_role", myRole);
        startActivityForResult(intent, REQ_GROUP_MANAGE);
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

    protected void openEmojiPicker() {
        Intent intent = new Intent(this, EmojiPickerActivity.class);
        startActivityForResult(intent, REQ_PICK_EMOJI);
    }

    protected void openRedPacketSend() {
        if (mediaHelper != null) {
            mediaHelper.hideActionPanel();
        }
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, RedPacketSendActivity.class);
        intent.putExtra(RedPacketSendActivity.EXTRA_GROUP_ID, groupId);
        intent.putExtra(RedPacketSendActivity.EXTRA_TARGET_NAME, groupName == null ? "" : groupName);
        startActivityForResult(intent, REQ_SEND_RED_PACKET);
    }

    protected String messagePreview(String type, String body, int burnAfterSeconds) {
        return ChatMessageUtil.previewForType(type, body, burnAfterSeconds);
    }

    protected void updateRecentFromLastMessage() {
        if (groupId == null || groupId.isEmpty() || messageList.isEmpty()) {
            return;
        }
        GroupMessage last = messageList.get(messageList.size() - 1);
        if (last == null) {
            return;
        }
        String name = groupName != null && !groupName.isEmpty() ? groupName : groupId;
        String preview = messagePreview(last.msg_type, last.body, last.burn_after_seconds);
        if (com.im.oldchat.util.NcuidIdentity.isMine(
                this, last.sender_ncuid, last.from_uid, myUID)) {
            GroupRecentChatCache.updateGroupOutgoing(this, groupId, name, groupAvatar, preview, last.created_at);
        } else {
            GroupRecentChatCache.updateGroupIncoming(this, groupId, name, groupAvatar, preview, last.created_at, 0);
        }
        GroupRecentChatCache.clearUnread(this, groupId);
    }

    protected void quoteMessage(GroupMessage msg, String displayName) {
        // displayName 可能为空（成员信息未加载/缓存缺失），此时用群聊显示名解析器兜底，
        // 避免回复框显示 UID。
        if ((displayName == null || displayName.isEmpty()) && msg != null) {
            displayName = resolveDisplayName(msg.from_uid);
        }
        quoteDraft = GroupChatUiActions.buildQuote(msg, displayName);
        GroupChatUiActions.applyQuote(quotePreview, tvQuotePreview, quoteDraft);
    }

    protected void recallMessage(GroupMessage msg) {
        if (msg == null || msg.id == null) {
            return;
        }
        GroupChatUiActions.confirmRecall(asGroupChatActivity(), token, messageList, messageIds, adapter, msg.id,
                resolveDisplayName(msg.from_uid));
    }

    protected void reEditRecalledMessage(GroupMessage msg) {
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
        GroupChatUiActions.clearQuote(quotePreview, tvQuotePreview);
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

    protected void forwardSingleMessage(GroupMessage message) {
        List<MessagePayload.ForwardItem> items = buildForwardItems(singletonGroupMessageList(message));
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.forward_no_messages, Toast.LENGTH_SHORT).show();
            return;
        }
        ForwardMessageShareHelper.showTargetPicker(this, token, items, null);
    }

    protected void enterForwardMultiSelect(GroupMessage message) {
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
        List<GroupMessage> selected = adapter.getSelectedMessages();
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

    protected void showChatBackgroundDialog() {
        if (backgroundHelper != null) {
            backgroundHelper.showBackgroundDialog(REQ_PICK_CHAT_BG);
        }
    }

    protected void pickChatBackground() {
        if (backgroundHelper != null) {
            backgroundHelper.pickBackground(REQ_PICK_CHAT_BG);
        }
    }

    protected void clearChatBackground() {
        if (backgroundHelper != null) {
            backgroundHelper.clearBackground();
        }
    }

    protected void applyChatBackground() {
        if (backgroundHelper != null) {
            backgroundHelper.applyBackground();
        }
    }

    protected void applyFontScale() {
        float scale = com.im.oldchat.util.SettingsPrefs.getFontScale(this);
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
        if (messageSender != null) {
            String content = etInput == null ? "" : etInput.getText().toString();
            if ((content == null || content.trim().length() == 0) && quoteDraft == null) {
                return;
            }
            messageSender.sendText(content, quoteDraft, collectMentions(content));
            lastDraft = ChatDraftHelper.clearDraft(this, getDraftKey());
        }
    }

    protected String getDraftKey() {
        if (groupId == null || groupId.length() == 0) {
            return null;
        }
        String owner = myUID == null ? "" : myUID;
        return "group:" + owner + ":" + groupId;
    }

    protected String resolveDisplayName(String uid) {
        if (uid == null || uid.isEmpty()) {
            return "成员";
        }
        String name = nameMap.get(uid);
        if (name == null || name.isEmpty()) {
            name = UserNameCache.getName(this, uid);
        }
        if (name == null || name.isEmpty()) {
            // 名字未加载完成时显示通用名，避免回复框/操作菜单暴露 UID。
            name = "成员";
        }
        return name;
    }

    protected void updateBackDot() {
        if (backDot == null) {
            return;
        }
        boolean hasUnread = UnreadDotHelper.hasOtherUnread(this, null, groupId);
        backDot.setVisibility(hasUnread ? View.VISIBLE : View.GONE);
    }

    protected GroupChatActivity asGroupChatActivity() {
        return (GroupChatActivity) this;
    }

    protected abstract List<MessagePayload.Mention> collectMentions(String content);

    private List<MessagePayload.ForwardItem> buildForwardItems(List<GroupMessage> source) {
        List<MessagePayload.ForwardItem> out = new ArrayList<MessagePayload.ForwardItem>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (int i = 0; i < source.size(); i++) {
            GroupMessage one = source.get(i);
            if (!ChatMessageActionGuard.canQuoteOrForward(one)) {
                continue;
            }
            String senderUid = one == null ? "" : one.from_uid;
            String senderName = resolveDisplayName(senderUid);
            String senderAvatar = senderUid == null ? "" : avatarMap.get(senderUid);
            MessagePayload.ForwardItem item = ForwardMessageComposer.buildGroupItem(
                    this, one, senderName, senderAvatar);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private List<GroupMessage> singletonGroupMessageList(GroupMessage message) {
        ArrayList<GroupMessage> list = new ArrayList<GroupMessage>();
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
            if (mentionDialog != null && mentionDialog.isShowing()) {
                mentionDialog.dismiss();
            }
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

    protected boolean shouldRefreshMessagesOnResume() {
        if (messageList.isEmpty()) {
            return true;
        }
        if (lastPauseAtMs <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - lastPauseAtMs >= RESUME_REFRESH_THRESHOLD_MS;
    }
}
