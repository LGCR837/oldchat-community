package com.im.oldchat.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

abstract class GroupMessageAdapterSupport0 extends GroupMessageAdapterSupport1 {
    protected static final long JUMP_HIGHLIGHT_MS = 1200L;
    protected static final int JUMP_HIGHLIGHT_COLOR = 0x2AFFD54F;
    protected static final int SELECTION_HIGHLIGHT_COLOR = 0x223B82F6;
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected String jumpHighlightMessageId;
    protected long jumpHighlightUntilMs;
    protected final Runnable clearJumpHighlightRunnable = new Runnable() {
        
        public void run() {
            if (jumpHighlightMessageId == null) {
                return;
            }
            jumpHighlightMessageId = null;
            jumpHighlightUntilMs = 0L;
            refreshVisibleRows();
        }
    };
    protected final LinkedHashSet<String> selectedMessageKeys = new LinkedHashSet<String>();
    protected boolean selectionMode = false;
    protected GroupMessageAdapter.SelectionModeListener selectionModeListener;
    GroupMessageAdapterSupport0(android.content.Context context, List<GroupMessage> messages, String myUID) {
        super(context, messages, myUID);
    }

    public void setActionListener(GroupMessageAdapter.GroupMessageActionListener listener) {
        this.actionListener = listener;
    }

    public void setQuoteClickListener(GroupMessageAdapter.QuoteClickListener listener) {
        this.quoteClickListener = listener;
    }

    public void setSelectionModeListener(GroupMessageAdapter.SelectionModeListener listener) {
        this.selectionModeListener = listener;
    }

    public void setMyUID(String myUID) {
        this.myUID = myUID;
        clearResolvedMemberMetaCache();
        refreshVisibleRows();
    }

    public void setFontScale(float scale) {
        if (scale <= 0f) {
            scale = 1.0f;
        }
        this.fontScale = scale;
        refreshVisibleRows();
    }

    public void highlightJumpMessage(String messageId) {
        if (messageId == null || messageId.length() == 0) {
            return;
        }
        jumpHighlightMessageId = messageId;
        jumpHighlightUntilMs = System.currentTimeMillis() + JUMP_HIGHLIGHT_MS;
        mainHandler.removeCallbacks(clearJumpHighlightRunnable);
        mainHandler.postDelayed(clearJumpHighlightRunnable, JUMP_HIGHLIGHT_MS + 80L);
        refreshVisibleRows();
    }

    protected void bindJumpHighlight(View rowView, String messageId) {
        if (rowView == null) {
            return;
        }
        if (shouldHighlightMessage(messageId)) {
            rowView.setBackgroundColor(JUMP_HIGHLIGHT_COLOR);
        } else {
            rowView.setBackgroundColor(0x00000000);
        }
    }

    protected boolean shouldHighlightMessage(String messageId) {
        if (messageId == null || messageId.length() == 0) {
            return false;
        }
        if (jumpHighlightMessageId == null || !messageId.equals(jumpHighlightMessageId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now > jumpHighlightUntilMs) {
            jumpHighlightMessageId = null;
            jumpHighlightUntilMs = 0L;
            return false;
        }
        return true;
    }

    public void stopVoice() {
        if (voicePlayer != null) {
            voicePlayer.stop();
        }
        mainHandler.removeCallbacks(clearJumpHighlightRunnable);
    }

    public void enterSelectionMode(GroupMessage seed) {
        selectionMode = true;
        selectedMessageKeys.clear();
        if (seed != null) {
            String key = selectionKey(seed);
            if (key.length() > 0) {
                selectedMessageKeys.add(key);
            }
        }
        dispatchSelectionStateChanged();
        refreshVisibleRows();
    }

    public void exitSelectionMode() {
        if (!selectionMode && selectedMessageKeys.isEmpty()) {
            return;
        }
        selectionMode = false;
        selectedMessageKeys.clear();
        dispatchSelectionStateChanged();
        refreshVisibleRows();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void toggleSelection(GroupMessage message) {
        if (message == null) {
            return;
        }
        if (ChatMessageActionGuard.isLocalOnly(message)) {
            return;
        }
        if (!selectionMode) {
            enterSelectionMode(message);
            return;
        }
        String key = selectionKey(message);
        if (key.length() == 0) {
            return;
        }
        if (selectedMessageKeys.contains(key)) {
            selectedMessageKeys.remove(key);
        } else {
            selectedMessageKeys.add(key);
        }
        if (selectedMessageKeys.isEmpty()) {
            selectionMode = false;
        }
        dispatchSelectionStateChanged();
        refreshVisibleRows();
    }

    public boolean isSelected(GroupMessage message) {
        if (message == null) {
            return false;
        }
        String key = selectionKey(message);
        return key.length() > 0 && selectedMessageKeys.contains(key);
    }

    public int getSelectedCount() {
        return selectedMessageKeys.size();
    }

    public List<GroupMessage> getSelectedMessages() {
        ArrayList<GroupMessage> out = new ArrayList<GroupMessage>();
        if (selectedMessageKeys.isEmpty()) {
            return out;
        }
        // Limit search to last 1000 messages for performance
        int searchLimit = Math.min(messages.size(), 1000);
        int startIdx = Math.max(0, messages.size() - searchLimit);
        for (int i = startIdx; i < messages.size(); i++) {
            GroupMessage one = messages.get(i);
            if (isSelected(one)) {
                out.add(one);
            }
        }
        return out;
    }

    public void updateNameMap(Map<String, String> map) {
        if (map != null) {
            nameMap = map;
            clearResolvedMemberMetaCache();
            refreshVisibleRows();
        }
    }

    public void updateAvatarMap(Map<String, String> map) {
        if (map != null) {
            avatarMap = map;
            clearResolvedMemberMetaCache();
            refreshVisibleRows();
        }
    }

    public void updateTitleMap(Map<String, String> map) {
        if (map != null) {
            titleMap = map;
            clearResolvedMemberMetaCache();
            refreshVisibleRows();
        }
    }

    public void updateRoleMap(Map<String, Integer> map) {
        if (map != null) {
            roleMap = map;
            clearResolvedMemberMetaCache();
            refreshVisibleRows();
        }
    }

    public void setMyRole(int role) {
        if (myRole != role) {
            myRole = role;
            refreshVisibleRows();
        }
    }

    public void setTypingIndicators(Collection<String> uids) {
        LinkedHashSet<String> next = new LinkedHashSet<String>();
        if (uids != null) {
            for (String uid : uids) {
                if (uid != null && !uid.isEmpty()) {
                    next.add(uid);
                }
            }
        }
        if (next.equals(typingUids)) {
            return;
        }
        typingUids.clear();
        typingUids.addAll(next);
        notifyDataSetChanged();
    }

    public void setTypingIndicator(String uid) {
        if (uid == null || uid.isEmpty()) {
            clearTypingIndicator();
            return;
        }
        LinkedHashSet<String> next = new LinkedHashSet<String>();
        next.add(uid);
        if (next.equals(typingUids)) {
            return;
        }
        typingUids.clear();
        typingUids.add(uid);
        notifyDataSetChanged();
    }

    public boolean isTypingIndicatorVisible() {
        return !getDisplayTypingUids().isEmpty();
    }

    public boolean startTypingTransition(GroupMessage message) {
        if (message == null || message.from_uid == null || message.from_uid.isEmpty()) {
            return false;
        }
        if (!typingUids.contains(message.from_uid)) {
            return false;
        }
        if (transitionRunning) {
            return false;
        }
        transitionMessage = message;
        transitionRunning = true;
        transitionUid = message.from_uid;
        notifyDataSetChanged();
        return true;
    }

    public void clearTypingIndicator() {
        if (typingUids.isEmpty()) {
            return;
        }
        typingUids.clear();
        notifyDataSetChanged();
    }

    public void markMessageAnimating(GroupMessage message) {
        String key = animationKeyForMessage(message);
        if (key != null && !key.isEmpty()) {
            pendingSendAnimations.add(key);
        }
    }

    public void markIncomingMediaAnimating(GroupMessage message) {
        String key = animationKeyForMessage(message);
        if (key == null || key.length() == 0) {
            return;
        }
        if (shouldAnimateIncomingMedia(message)) {
            pendingIncomingMediaAnimations.add(key);
        }
    }

    @Override
    public int getCount() {
        if (selectionMode) {
            pruneSelectionKeys();
        }
        int count = messages.size();
        return count + (isTypingIndicatorVisible() ? 1 : 0);
    }

    @Override
    public Object getItem(int position) {
        if (isTypingPosition(position) || (transitionRunning && position == messages.size() - 1)) {
            return null;
        }
        if (position >= 0 && position < messages.size()) {
            return messages.get(position);
        }
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        if (transitionRunning && position == messages.size() - 1) {
            return VIEW_TYPE_TYPING;
        }
        if (isTypingPosition(position)) {
            return VIEW_TYPE_TYPING;
        }
        return VIEW_TYPE_MESSAGE;
    }

    protected void bindItemActions(View rowView, ViewHolder holderFinal, final GroupMessage target, MessagePayload payload) {
        if (selectionMode) {
            bindSelectionActions(rowView, holderFinal, target);
            return;
        }
        clearSelectionActions(rowView, holderFinal);
        GroupMessageActionBinder.clear(holderFinal.bubble, holderFinal.text, holderFinal.image,
                holderFinal.voiceRow, holderFinal.voiceTranscript, holderFinal.avatar);
        final boolean isMine = com.im.oldchat.util.NcuidIdentity.isMine(
                context, target.sender_ncuid, target.from_uid, myUID);
        GroupMessageActionBinder.bind(context,
                holderFinal.bubble,
                holderFinal.text,
                holderFinal.image,
                holderFinal.voiceRow,
                holderFinal.voiceTranscript,
                holderFinal.avatar,
                target,
                payload,
                isMine,
                actionListener,
                new GroupMessageActionBinder.NameResolver() {
                    @Override
                    public String resolve(String uid) {
                        return resolveName(uid);
                    }
                },
                new GroupMessageActionBinder.RecallChecker() {
                    @Override
                    public boolean canRecall(GroupMessage msg) {
                        return canRecallMessage(msg);
                    }
                });
        ChatSwipeQuoteBinder.bindGroup(context, selectionMode, holderFinal, target, payload,
                resolveName(target.from_uid), actionListener);
        maybeAnimateSend(holderFinal, target, isMine);
    }

    protected void bindSelectionRowState(View rowView, GroupMessage target) {
        if (rowView == null || target == null) {
            return;
        }
        if (selectionMode && isSelected(target)) {
            rowView.setBackgroundColor(SELECTION_HIGHLIGHT_COLOR);
            return;
        }
        if (!shouldHighlightMessage(target.id)) {
            rowView.setBackgroundColor(0x00000000);
        }
    }

    private void bindSelectionActions(View rowView, ViewHolder holder, final GroupMessage message) {
        if (holder == null || message == null) {
            return;
        }
        GroupMessageActionBinder.clear(holder.bubble, holder.text, holder.image, holder.voiceRow,
                holder.voiceTranscript, holder.avatar);
        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelection(message);
            }
        };
        View.OnLongClickListener longClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                toggleSelection(message);
                return true;
            }
        };
        bindToggle(rowView, click, longClick);
        bindToggle(holder.bubble, click, longClick);
        bindToggle(holder.text, click, longClick);
        bindToggle(holder.image, click, longClick);
        bindToggle(holder.voiceRow, click, longClick);
        bindToggle(holder.voiceTranscript, click, longClick);
        bindToggle(holder.resourceCard, click, longClick);
        bindToggle(holder.resourceAction, click, longClick);
        bindToggle(holder.redPacketContainer, click, longClick);
        bindToggle(holder.quoteContainer, click, longClick);
        bindToggle(holder.avatar, click, longClick);
        bindToggle(holder.avatarRight, click, longClick);
        bindSelectionRowState(rowView, message);
    }

    private void clearSelectionActions(View rowView, ViewHolder holder) {
        clearToggle(rowView);
    }

    private void bindToggle(View view, View.OnClickListener click, View.OnLongClickListener longClick) {
        if (view == null) {
            return;
        }
        view.setOnClickListener(click);
        view.setOnLongClickListener(longClick);
    }

    private void clearToggle(View view) {
        if (view == null) {
            return;
        }
        view.setOnClickListener(null);
        view.setOnLongClickListener(null);
    }

    private void dispatchSelectionStateChanged() {
        if (selectionModeListener != null) {
            selectionModeListener.onSelectionModeChanged(selectionMode, selectedMessageKeys.size());
        }
    }

    protected void pruneSelectionKeys() {
        if (selectedMessageKeys.isEmpty()) {
            return;
        }
        boolean changed = false;
        // Limit iteration to avoid performance issues with large message lists
        int checkLimit = Math.min(messages.size(), 1000);
        int startIdx = Math.max(0, messages.size() - checkLimit);
        HashSet<String> valid = new HashSet<String>(checkLimit);
        for (int i = startIdx; i < messages.size(); i++) {
            valid.add(selectionKey(messages.get(i)));
        }
        for (java.util.Iterator<String> it = selectedMessageKeys.iterator(); it.hasNext(); ) {
            String key = it.next();
            if (!valid.contains(key)) {
                it.remove();
                changed = true;
            }
        }
        if (selectedMessageKeys.isEmpty()) {
            if (selectionMode) {
                selectionMode = false;
                changed = true;
            }
        }
        if (changed) {
            dispatchSelectionStateChanged();
        }
    }

    private String selectionKey(GroupMessage message) {
        if (message == null) {
            return "";
        }
        if (message.id != null && message.id.length() > 0) {
            return "id:" + message.id;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ts:").append(message.created_at);
        sb.append("|uid:").append(message.from_uid == null ? "" : message.from_uid);
        sb.append("|type:").append(message.msg_type == null ? "" : message.msg_type);
        sb.append("|body:").append(message.body == null ? "" : message.body);
        return sb.toString();
    }
}
