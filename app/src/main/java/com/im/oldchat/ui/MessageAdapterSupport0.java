package com.im.oldchat.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

abstract class MessageAdapterSupport0 extends MessageAdapterSupport1 {
    protected static final int VIEW_TYPE_MESSAGE = 0;
    protected static final int VIEW_TYPE_TYPING = 1;
    protected static final int MAX_VOICE_SECONDS = 60;
    protected static final int VOICE_MIN_DP = 80;
    protected static final int VOICE_MAX_DP = 200;
    protected static final long JUMP_HIGHLIGHT_MS = 1200L;
    protected static final int JUMP_HIGHLIGHT_COLOR = 0x2AFFD54F;
    protected static final long SEND_ANIM_DURATION_MS = 120L;
    protected static final long SEND_MEDIA_ANIM_DURATION_MS = 180L;
    protected static final long RECEIVE_MEDIA_ANIM_DURATION_MS = 170L;
    protected static final int SELECTION_HIGHLIGHT_COLOR = 0x223B82F6;
    protected final HashSet<String> pendingSendAnimations = new HashSet<String>();
    protected final java.util.WeakHashMap<Message, Boolean> playedSendAnimations =
            new java.util.WeakHashMap<Message, Boolean>();
    protected final HashSet<String> pendingIncomingMediaAnimations = new HashSet<String>();
    protected final LinkedHashSet<String> selectedMessageKeys = new LinkedHashSet<String>();
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected String jumpHighlightMessageId;
    protected long jumpHighlightUntilMs;
    protected String lastVoicePlaybackKey;
    protected final Runnable clearJumpHighlightRunnable = new Runnable() {
        
        public void run() {
            String oldId = jumpHighlightMessageId;
            if (oldId == null) return;
            int changedIndex = -1;
            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                if (message != null && oldId.equals(message.id)) {
                    changedIndex = i;
                    break;
                }
            }
            jumpHighlightMessageId = null;
            jumpHighlightUntilMs = 0L;
            if (changedIndex >= 0) notifyItemChanged(changedIndex);
        }
    };
    protected MessageAdapter.MessageActionListener actionListener;
    protected MessageAdapter.SelectionModeListener selectionModeListener;
    protected boolean selectionMode = false;

    MessageAdapterSupport0(Context context, List<Message> messages, String myUID) {
        this.context = context;
        this.messages = messages;
        this.myUID = myUID;
        setHasStableIds(true);
        this.voicePlayer = new MessageVoicePlayer(context, new MessageVoicePlayer.PlaybackListener() {
            @Override
            public void onPlaybackStateChanged() {
                notifyVoicePlaybackStateChanged();
            }
        });
    }

    protected void notifyVoicePlaybackStateChanged() {
        String currentKey = voicePlayer == null ? null : voicePlayer.getPlayingMessageKey();
        notifyVoiceRowByKey(lastVoicePlaybackKey);
        if (currentKey != null && !currentKey.equals(lastVoicePlaybackKey)) {
            notifyVoiceRowByKey(currentKey);
        }
        lastVoicePlaybackKey = currentKey;
    }

    private boolean notifyVoiceRowByKey(String key) {
        if (key == null || key.length() == 0) {
            return false;
        }
        long createdAtFallback = parseVoiceCreatedAtKey(key);
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (!"voice".equals(msg == null ? null : msg.msg_type)) {
                continue;
            }
            if (key.equals(messageIdentityKey(msg, i))
                    || (createdAtFallback > 0 && msg != null && msg.created_at == createdAtFallback)) {
                notifyItemChanged(i);
                return true;
            }
        }
        return false;
    }

    private long parseVoiceCreatedAtKey(String key) {
        if (key == null || !key.startsWith("ts:")) {
            return 0L;
        }
        int end = key.indexOf('|');
        String raw = end > 0 ? key.substring(3, end) : key.substring(3);
        try {
            return Long.parseLong(raw);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public void setActionListener(MessageAdapter.MessageActionListener listener) {
        this.actionListener = listener;
    }

    public void setQuoteClickListener(MessageAdapter.QuoteClickListener listener) {
        this.quoteClickListener = listener;
    }

    public void setSelectionModeListener(MessageAdapter.SelectionModeListener listener) {
        this.selectionModeListener = listener;
    }

    // Android 2.3+ compatible optimized method for setting myUID 
    public void setMyUID(String myUID) {
        if (this.myUID != null && this.myUID.equals(myUID)) {
            return;
        }
        this.myUID = myUID;
        // Don't call notifyDataSetChanged() for the entire list when only one attribute changes  
        for (int i = 0; i < messages.size(); i++) {
            notifyItemChanged(i);
        }
    }

    public void setFontScale(float scale) {
        if (scale <= 0f) {
            scale = 1.0f;
        }
        if (Math.abs(this.fontScale - scale) < 0.01f) {
            return;
        }
        this.fontScale = scale;
        // Optimized refresh only affected elements, not the entire list
        for (int i = 0; i < messages.size(); i++) {
            notifyItemChanged(i);
        }
    }

    public void highlightJumpMessage(String messageId) {
        if (messageId == null || messageId.length() == 0) {
            return;
        }
        // Find and only affect the specific item
        String oldJumpHighlightMessageId = jumpHighlightMessageId;
        int oldIndex = -1, newIndex = -1;
        
        if (oldJumpHighlightMessageId != null) {
            // Find old highlighted item
            for (int i = 0; i < messages.size(); i++) {
                if (oldJumpHighlightMessageId.equals(messages.get(i).id)) {
                    oldIndex = i;
                    break;
                }
            }
        }
        
        jumpHighlightMessageId = messageId;
        // Find new highlighted item
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).id)) {
                newIndex = i;
                break;
            }
        }
        jumpHighlightUntilMs = System.currentTimeMillis() + JUMP_HIGHLIGHT_MS;
        mainHandler.removeCallbacks(clearJumpHighlightRunnable);
        mainHandler.postDelayed(clearJumpHighlightRunnable, JUMP_HIGHLIGHT_MS + 80L);
        
        // Only notify items that actually changed
        if (oldIndex >= 0 && oldIndex != newIndex) {
            notifyItemChanged(oldIndex);
        }
        if (newIndex >= 0) {
            notifyItemChanged(newIndex);
        }
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

    public void updateMessages(List<Message> newMessages) {
        if (newMessages == null) {
            return;
        }
        int oldSize = messages.size();
        messages.clear();
        messages.addAll(newMessages);
        pruneSelectionKeys();
        
        if (newMessages.size() > oldSize && oldSize > 0) {
            notifyItemRangeInserted(oldSize, newMessages.size() - oldSize);
        } else if (newMessages.size() < oldSize) {
            notifyItemRangeRemoved(newMessages.size(), oldSize - newMessages.size());
        } else if (oldSize > 0) {
            // For same size, update each item individually
            for (int i = 0; i < newMessages.size(); i++) {
                notifyItemChanged(i);
            }
        } else {
            notifyDataSetChanged();
        }
    }

    public void addMessage(Message message) {
        if (message == null) {
            return;
        }
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void enterSelectionMode(Message seed) {
        selectionMode = true;
        selectedMessageKeys.clear();
        if (seed != null) {
            String key = selectionKey(seed);
            if (key.length() > 0) {
                selectedMessageKeys.add(key);
            }
        }
        dispatchSelectionStateChanged();
        // Only refresh the items that have changed
        notifyDataSetChanged();
    }

    public void exitSelectionMode() {
        if (!selectionMode && selectedMessageKeys.isEmpty()) {
            return;
        }
        selectionMode = false;
        selectedMessageKeys.clear();
        dispatchSelectionStateChanged();
        // Only refresh items that have changed
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String key = selectionKey(msg);
            if (key.length() > 0 && selectedMessageKeys.contains(key)) {
                notifyItemChanged(i);
            }
        }
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void toggleSelection(Message message) {
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
        boolean changed = false;
        if (selectedMessageKeys.contains(key)) {
            selectedMessageKeys.remove(key);
            changed = true;
        } else {
            selectedMessageKeys.add(key);
            changed = true;
        }
        if (selectedMessageKeys.isEmpty()) {
            selectionMode = false;
        }
        dispatchSelectionStateChanged();
        if (changed) {
            // Only update the affected item
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) == message) { // Same object reference
                    notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    public boolean isSelected(Message message) {
        if (message == null) {
            return false;
        }
        String key = selectionKey(message);
        return key.length() > 0 && selectedMessageKeys.contains(key);
    }

    public int getSelectedCount() {
        return selectedMessageKeys.size();
    }

    public List<Message> getSelectedMessages() {
        ArrayList<Message> out = new ArrayList<Message>();
        if (selectedMessageKeys.isEmpty()) {
            return out;
        }
        for (int i = 0; i < messages.size(); i++) {
            Message one = messages.get(i);
            if (isSelected(one)) {
                out.add(one);
            }
        }
        return out;
    }

    public void markMessageAnimating(Message message) {
        String key = animationKeyForMessage(message);
        if (key != null && !key.isEmpty()) {
            pendingSendAnimations.add(key);
        }
    }

    public void markIncomingMediaAnimating(Message message) {
        String key = animationKeyForMessage(message);
        if (key == null || key.length() == 0) {
            return;
        }
        if (shouldAnimateIncomingMedia(message)) {
            pendingIncomingMediaAnimations.add(key);
        }
    }

    public void updateMessageStatus(String messageId, int status) {
        if (messageId == null) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (messageId.equals(msg.id)) {
                msg.status = status;
                notifyItemChanged(i);
                break;
            }
        }
    }

    public void setTypingIndicatorVisible(boolean visible) {
        if (this.typingIndicatorVisible == visible) {
            return;
        }
        this.typingIndicatorVisible = visible;
        // Only affect the item that actually changed, instead of the whole list
        if (visible) {
            notifyItemInserted(getItemCount()-1); // Assuming typing indicator is added at end
        } else {
            notifyItemRemoved(getItemCount()); // Assuming typing indicator is removed from end
        }
    }

    public boolean isTypingIndicatorVisible() {
        return typingIndicatorVisible;
    }

    public void clearTypingForDataReset() {
        typingIndicatorVisible = false;
        transitionRunning = false;
        transitionMessage = null;
    }

    public boolean startTypingTransition(Message message) {
        if (message == null) {
            return false;
        }
        if (!typingIndicatorVisible && !transitionRunning) {
            return false;
        }
        transitionMessage = message;
        transitionRunning = true;
        typingIndicatorVisible = false;
        // Only update the specific position changed
        int transitionPosition = messages.size() - 1;
        if (transitionPosition >= 0) notifyItemChanged(transitionPosition);
        return true;
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= getItemCount()) {
            return android.support.v7.widget.RecyclerView.NO_ID;
        }
        if (transitionRunning && position == messages.size() - 1) {
            return Long.MIN_VALUE + 2L;
        }
        if (typingIndicatorVisible && position == getItemCount() - 1) {
            return Long.MIN_VALUE + 1L;
        }
        return super.getItemId(position);
    }

    @Override
    public int getItemViewType(int position) {
        if (transitionRunning && position == messages.size() - 1) {
            return VIEW_TYPE_TYPING;
        }
        if (typingIndicatorVisible && position == getItemCount() - 1) {
            return VIEW_TYPE_TYPING;
        }
        return VIEW_TYPE_MESSAGE;
    }

    protected void maybeAnimateSend(ViewHolder holder, Message message, boolean isMine) {
        if (holder == null || holder.bubble == null) {
            return;
        }
        holder.bubble.clearAnimation();
        String key = animationKeyForMessage(message);
        if (key == null || key.length() == 0) {
            return;
        }
        if (isMine) {
            if (!pendingSendAnimations.remove(key)) {
                return;
            }
            if (playedSendAnimations.containsKey(message)) {
                return;
            }
            playedSendAnimations.put(message, Boolean.TRUE);
            if (shouldAnimateOutgoingMedia(message)) {
                AnimationSet outgoing = new AnimationSet(true);
                outgoing.setInterpolator(new DecelerateInterpolator());
                TranslateAnimation slide = new TranslateAnimation(
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.35f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0f);
                slide.setDuration(SEND_MEDIA_ANIM_DURATION_MS);
                AlphaAnimation alpha = new AlphaAnimation(0.25f, 1f);
                alpha.setDuration(SEND_MEDIA_ANIM_DURATION_MS);
                outgoing.addAnimation(slide);
                outgoing.addAnimation(alpha);
                holder.bubble.startAnimation(outgoing);
                return;
            }
            AnimationSet set = new AnimationSet(true);
            set.setInterpolator(new DecelerateInterpolator());
            ScaleAnimation scale = new ScaleAnimation(
                    0.92f, 1f, 0.92f, 1f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 1f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 1f);
            scale.setDuration(SEND_ANIM_DURATION_MS);
            AlphaAnimation alpha = new AlphaAnimation(0.6f, 1f);
            alpha.setDuration(SEND_ANIM_DURATION_MS);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            holder.bubble.startAnimation(set);
            return;
        }
        if (!shouldAnimateIncomingMedia(message)) {
            return;
        }
        if (!pendingIncomingMediaAnimations.remove(key)) {
            return;
        }
        AnimationSet incoming = new AnimationSet(true);
        incoming.setInterpolator(new DecelerateInterpolator());
        TranslateAnimation slide = new TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_SELF, -0.35f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0f);
        slide.setDuration(RECEIVE_MEDIA_ANIM_DURATION_MS);
        AlphaAnimation alpha = new AlphaAnimation(0.25f, 1f);
        alpha.setDuration(RECEIVE_MEDIA_ANIM_DURATION_MS);
        incoming.addAnimation(slide);
        incoming.addAnimation(alpha);
        holder.bubble.startAnimation(incoming);
    }

    private String animationKeyForMessage(Message message) {
        if (message == null) {
            return null;
        }
        if (message.id != null && !message.id.isEmpty()) {
            return "id:" + message.id;
        }
        if (message.created_at > 0) {
            return "ts:" + message.created_at + "_" + (message.from_uid == null ? "" : message.from_uid);
        }
        return "obj:" + System.identityHashCode(message);
    }

    protected boolean shouldAnimateIncomingMedia(Message message) {
        if (message == null) {
            return false;
        }
        String type = message.msg_type == null ? "" : message.msg_type.trim().toLowerCase();
        return "image".equals(type) || "voice".equals(type);
    }

    protected boolean shouldAnimateOutgoingMedia(Message message) {
        if (message == null) {
            return false;
        }
        String type = message.msg_type == null ? "" : message.msg_type.trim().toLowerCase();
        return "image".equals(type)
                || "voice".equals(type)
                || "resource".equals(type)
                || "video".equals(type);
    }

    protected void applyBubbleBackground(ViewHolder holder, int drawableRes) {
        if (holder == null || holder.bubble == null) {
            return;
        }
        if (!ThemeColorUtil.applyPrimaryBubbleBackground(context, holder.bubble, drawableRes)) {
            holder.bubble.setBackgroundResource(drawableRes);
        }
        holder.bubble.setPadding(holder.bubblePaddingLeft, holder.bubblePaddingTop,
                holder.bubblePaddingRight, holder.bubblePaddingBottom);
    }

    protected void clearBubbleBackground(ViewHolder holder) {
        if (holder == null || holder.bubble == null) {
            return;
        }
        holder.bubble.setBackgroundResource(0);
        holder.bubble.setPadding(0, 0, 0, 0);
    }

    protected void bindStatus(ViewHolder holder, int status) {
        if (holder == null) {
            return;
        }
        bindStatusViews(holder.statusText, holder.statusIcon, status);
    }

    protected void bindStatusViews(TextView statusText, ImageView statusIcon, int status) {
        if (statusIcon == null) {
            return;
        }
        // Telegram 风格：服务端确认后显示单对勾，对方已读后显示双对勾。
        if (statusText != null) {
            statusText.setText("");
            statusText.setVisibility(View.GONE);
        }
        statusIcon.setVisibility(View.VISIBLE);
        if (status == Message.STATUS_READ) {
            statusIcon.setImageResource(R.drawable.ic_msg_read);
        } else {
            // SENT 与 DELIVERED 均尚未被对方读到，统一使用单对勾。
            statusIcon.setImageResource(R.drawable.ic_msg_sent);
        }
    }

    protected void bindPendingSide(ViewHolder holder, Message target, boolean isMine) {
        PendingSideUiHelper.bind(holder == null ? null : holder.pendingSide,
                holder == null ? null : holder.pendingProgress,
                holder == null ? null : holder.pendingFailed,
                holder == null ? null : holder.statusRow,
                holder == null ? null : holder.statusRowMedia,
                isMine,
                target != null && target.localPending,
                target != null && target.localFailed,
                target == null ? -1 : target.localProgress);
        // 发送中/失败时不显示图片右下角状态勾
        if (holder != null && holder.imageStatus != null
                && ((target != null && target.localPending) || (target != null && target.localFailed))) {
            holder.imageStatus.setVisibility(View.GONE);
        }
    }

    protected int textColorPrimary() {
        return ContextCompat.getColor(context, R.color.color_text_primary);
    }

    protected int textColorSecondary() {
        return ContextCompat.getColor(context, R.color.color_text_secondary);
    }

    protected int textColorAction() {
        return ThemeColorUtil.getPrimaryColor(context);
    }

    protected void applyMineStyle(ViewHolder holder, String type, Message target, boolean useMediaStatus) {
        holder.messageContainer.setGravity(Gravity.END);
        setImageTailSide(holder, 1);
        if ("red_packet".equals(type)) {
            applyBubbleBackground(holder, R.drawable.bg_red_packet_out);
        } else {
            applyBubbleBackground(holder, R.drawable.bg_msg_me);
        }
        holder.text.setTextColor(0xFFFFFFFF);
        holder.voiceDuration.setTextColor(0xFFFFFFFF);
        if (holder.voiceTranscript != null) {
            // Transcript chip uses a neutral background in day/night; keep text tied to theme primary text color.
            holder.voiceTranscript.setTextColor(textColorPrimary());
        }
        holder.voiceIcon.setColorFilter(0xFFFFFFFF);
        holder.resourceTitle.setTextColor(0xFFFFFFFF);
        holder.resourceSub.setTextColor(0xCCFFFFFF);
        holder.resourceAction.setTextColor(0xFFFFFFFF);
        holder.resourceIcon.setColorFilter(0xFFFFFFFF);
        holder.redPacketTitle.setTextColor(0xFFFFFFFF);
        holder.redPacketDesc.setTextColor(0xCCFFFFFF);
        if (holder.redPacketOpenTip != null) {
            holder.redPacketOpenTip.setTextColor(0x99FFFFFF);
        }
        if (holder.redPacketStatusIcon != null && holder.redPacketStatusIcon.getVisibility() == View.VISIBLE) {
            holder.redPacketStatusIcon.setColorFilter(0x99FFFFFF);
        }
        if (useMediaStatus) {
            if (holder.statusRow != null) {
                holder.statusRow.setVisibility(View.GONE);
            }
            boolean isVoice = "voice".equals(type);
            if (holder.statusRowMedia != null) {
                holder.statusRowMedia.setVisibility(isVoice ? View.VISIBLE : View.GONE);
            }
            if (holder.imageStatus != null) {
                // 图片/视频消息：勾叠加在图片右下角
                holder.imageStatus.setVisibility(isVoice ? View.GONE : View.VISIBLE);
            }
            if (isVoice) {
                bindStatusViews(holder.statusTextMedia, holder.statusIconMedia, target.status);
            } else {
                bindStatusViews(null, holder.imageStatus, target.status);
            }
        } else {
            if (holder.statusRowMedia != null) {
                holder.statusRowMedia.setVisibility(View.GONE);
            }
            if (holder.imageStatus != null) {
                holder.imageStatus.setVisibility(View.GONE);
            }
            if (holder.statusRow != null) {
                // 文本消息的状态已和时间一起内联，避免独立 View 把多行气泡撑宽。
                boolean inlineTextStatus = "text".equals(type);
                holder.statusRow.setVisibility(inlineTextStatus ? View.GONE : View.VISIBLE);
                if (!inlineTextStatus) {
                    bindStatus(holder, target.status);
                }
            }
        }
    }

    private void setImageTailSide(ViewHolder holder, int tailSide) {
        if (holder != null && holder.image instanceof com.im.oldchat.ui.widget.RoundedImageView) {
            // 气泡开关独立控制图片消息：开启=6dp 圆角，关闭=完全方角。
            boolean bubbleRounded = com.im.oldchat.util.SettingsPrefs.isBubbleRoundedEnabled(context);
            com.im.oldchat.ui.widget.RoundedImageView image =
                    (com.im.oldchat.ui.widget.RoundedImageView) holder.image;
            image.setCornerRadiusPx(bubbleRounded
                    ? 6f * context.getResources().getDisplayMetrics().density : 0f);
            image.setTailSide(bubbleRounded ? tailSide : 0);
        }
    }

    protected void applyOtherStyle(ViewHolder holder, String type) {
        holder.messageContainer.setGravity(Gravity.START);
        setImageTailSide(holder, 2);
        if ("red_packet".equals(type)) {
            applyBubbleBackground(holder, R.drawable.bg_red_packet_in);
        } else {
            applyBubbleBackground(holder, R.drawable.bg_msg_other);
        }
        int textColor = textColorPrimary();
        int subColor = textColorSecondary();
        int actionColor = textColorAction();
        holder.text.setTextColor(textColor);
        holder.voiceDuration.setTextColor(textColor);
        if (holder.voiceTranscript != null) {
            holder.voiceTranscript.setTextColor(textColor);
        }
        holder.voiceIcon.setColorFilter(textColor);
        holder.resourceTitle.setTextColor(textColor);
        holder.resourceSub.setTextColor(subColor);
        holder.resourceAction.setTextColor(actionColor);
        holder.resourceIcon.setColorFilter(actionColor);
        holder.redPacketTitle.setTextColor(0xFFFFFFFF);
        holder.redPacketDesc.setTextColor(0xCCFFFFFF);
        if (holder.redPacketOpenTip != null) {
            holder.redPacketOpenTip.setTextColor(0x99FFFFFF);
        }
        if (holder.redPacketStatusIcon != null) {
            holder.redPacketStatusIcon.setVisibility(View.GONE);
        }
        if (holder.statusRow != null) {
            holder.statusRow.setVisibility(View.GONE);
        }
        if (holder.statusRowMedia != null) {
            holder.statusRowMedia.setVisibility(View.GONE);
        }
        if (holder.imageStatus != null) {
            holder.imageStatus.setVisibility(View.GONE);
        }
    }

    protected void dispatchSelectionStateChanged() {
        if (selectionModeListener != null) {
            selectionModeListener.onSelectionModeChanged(selectionMode, selectedMessageKeys.size());
        }
    }

    private void pruneSelectionKeys() {
        if (selectedMessageKeys.isEmpty()) {
            return;
        }
        HashSet<String> valid = new HashSet<String>();
        for (int i = 0; i < messages.size(); i++) {
            valid.add(selectionKey(messages.get(i)));
        }
        for (java.util.Iterator<String> it = selectedMessageKeys.iterator(); it.hasNext(); ) {
            String key = it.next();
            if (!valid.contains(key)) {
                it.remove();
            }
        }
        if (selectedMessageKeys.isEmpty()) {
            selectionMode = false;
        }
        dispatchSelectionStateChanged();
    }

    private String selectionKey(Message message) {
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
