package com.im.oldchat.ui;

import android.content.Context;
import android.support.v4.util.LruCache;
import android.support.v7.widget.RecyclerView;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.TypingAnimator;

abstract class MessageAdapterSupport1 extends MessageAdapterSupport2 {
    protected static final int PAYLOAD_CACHE_LIMIT = 400;
    protected static final float BASE_MESSAGE_TEXT_SP = 16f;
    protected static final float BASE_QUOTE_SENDER_SP = 12f;
    protected static final float BASE_QUOTE_CONTENT_SP = 13f;
    protected static final float BASE_VOICE_DURATION_SP = 14f;
    protected static final float BASE_VOICE_TRANSCRIPT_SP = 13f;
    protected static final float BASE_RESOURCE_TITLE_SP = 15f;
    protected static final float BASE_RESOURCE_SUB_SP = 12f;
    protected static final float BASE_RESOURCE_ACTION_SP = 12f;
    protected static final long TYPING_TRANSITION_MS = 200;

    protected final LruCache<String, CachedPayload> payloadCache = new LruCache<String, CachedPayload>(PAYLOAD_CACHE_LIMIT);
    protected boolean typingIndicatorVisible = false;
    protected Message transitionMessage;
    protected boolean transitionRunning = false;

    protected void applyFontScale(ViewHolder holder) {
        if (holder == null) {
            return;
        }
        float scale = fontScale;
        if (holder.lastAppliedFontScale == scale) {
            return;
        }
        holder.lastAppliedFontScale = scale;
        if (holder.text != null) {
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_MESSAGE_TEXT_SP * scale);
        }
        if (holder.quoteSender != null) {
            holder.quoteSender.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_QUOTE_SENDER_SP * scale);
        }
        if (holder.quoteContent != null) {
            holder.quoteContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_QUOTE_CONTENT_SP * scale);
        }
        if (holder.voiceDuration != null) {
            holder.voiceDuration.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_VOICE_DURATION_SP * scale);
        }
        if (holder.voiceTranscript != null) {
            holder.voiceTranscript.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_VOICE_TRANSCRIPT_SP * scale);
        }
        if (holder.resourceTitle != null) {
            holder.resourceTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_RESOURCE_TITLE_SP * scale);
        }
        if (holder.resourceSub != null) {
            holder.resourceSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_RESOURCE_SUB_SP * scale);
        }
        if (holder.resourceAction != null) {
            holder.resourceAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_RESOURCE_ACTION_SP * scale);
        }
    }

    protected MessagePayload getPayload(Message msg) {
        if (msg == null) {
            return new MessagePayload();
        }
        String key = msg.id != null && msg.id.length() > 0 ? msg.id : msg.body;
        if (key != null) {
            CachedPayload cached = payloadCache.get(key);
            if (cached != null && safeEquals(cached.body, msg.body) && cached.payload != null) {
                return cached.payload;
            }
        }
        MessagePayload payload = MessagePayload.fromBody(msg.body);
        cachePayload(key, msg.body, payload);
        return payload;
    }

    private void cachePayload(String key, String body, MessagePayload payload) {
        if (key == null) {
            return;
        }
        CachedPayload entry = new CachedPayload();
        entry.body = body;
        entry.payload = payload;
        payloadCache.put(key, entry);
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static class CachedPayload {
        String body;
        MessagePayload payload;
    }

    protected void bindTyping(TypingViewHolder holder, Message message) {
        if (holder == null) {
            return;
        }
        if (holder.avatarStack != null) {
            holder.avatarStack.setVisibility(View.GONE);
        } else if (holder.avatar != null) {
            holder.avatar.setVisibility(View.GONE);
        }
        if (message == null) {
            holder.showDots();
            holder.start();
            return;
        }
        String preview = ChatMessageUtil.previewForMessage(message);
        String key = message.id != null && message.id.length() > 0 ? message.id : String.valueOf(message.created_at);
        holder.showTransition(preview, key);
        scheduleFinishTransition(holder);
    }

    private void scheduleFinishTransition(TypingViewHolder holder) {
        if (holder == null) {
            return;
        }
        holder.itemView.removeCallbacks(holder.finishRunnable);
        holder.finishRunnable = new Runnable() {
            @Override
            public void run() {
                finishTypingTransition();
            }
        };
        holder.itemView.postDelayed(holder.finishRunnable, TYPING_TRANSITION_MS);
    }

    private void finishTypingTransition() {
        if (!transitionRunning) {
            return;
        }
        transitionRunning = false;
        transitionMessage = null;
        notifyDataSetChanged();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final LinearLayout bubble;
        final LinearLayout buttonPanel;
        final LinearLayout resourceCard;
        final ImageView resourceIcon;
        final TextView resourceTitle;
        final TextView resourceSub;
        final TextView resourceAction;
        final ProgressBar fileUploadProgress;
        final View resourceCoverContainer;
        final ImageView resourceCover;
        final ImageView resourcePlay;
        final LinearLayout quoteContainer;
        final View quoteLine;
        final TextView quoteSender;
        final TextView quoteContent;
        final ImageView quoteImage;
        final LinearLayout statusRow;
        final TextView statusText;
        final ImageView statusIcon;
        final LinearLayout statusRowMedia;
        final TextView statusTextMedia;
        final ImageView statusIconMedia;
        final ImageView imageStatus;
        final LinearLayout redPacketContainer;
        final ImageView redPacketIcon;
        final TextView redPacketTitle;
        final TextView redPacketDesc;
        final TextView redPacketOpenTip;
        final ImageView redPacketStatusIcon;
        final View imageContainer;
        final ImageView image;
        final View imageLoading;
        final ImageView videoPlay;
        final LinearLayout voiceRow;
        final ImageView voiceIcon;
        final TextView voiceDuration;
        final TextView voiceTranscript;
        final ProgressBar voiceLoading;
        final View imageTimeBadge;
        final TextView imageTime;
        final View resourceFooter;
        final TextView resourceTime;
        final ImageView resourceStatus;
        final TextView voiceTime;
        final TextView messageTime;
        final TextView messageFooterTime;
        final LinearLayout messageContainer;
        final View pendingSide;
        final ProgressBar pendingProgress;
        final TextView pendingFailed;
        final int bubblePaddingLeft;
        final int bubblePaddingTop;
        final int bubblePaddingRight;
        final int bubblePaddingBottom;
        float lastAppliedFontScale = Float.NaN;
        int lastVoiceRowWidth = -1;

        ViewHolder(View view) {
            super(view);
            messageContainer = view.findViewById(R.id.message_container);
            bubble = view.findViewById(R.id.message_bubble);
            text = view.findViewById(R.id.tvMessage);
            buttonPanel = view.findViewById(R.id.message_button_panel);
            resourceCard = view.findViewById(R.id.resource_card);
            resourceIcon = view.findViewById(R.id.ivResourceIcon);
            resourceTitle = view.findViewById(R.id.tvResourceTitle);
            resourceSub = view.findViewById(R.id.tvResourceSub);
            resourceAction = view.findViewById(R.id.tvResourceAction);
            fileUploadProgress = view.findViewById(R.id.pbFileUpload);
            resourceCoverContainer = view.findViewById(R.id.flResourceCover);
            resourceCover = view.findViewById(R.id.ivResourceCover);
            resourcePlay = view.findViewById(R.id.ivResourcePlay);
            resourceFooter = view.findViewById(R.id.llResourceFooter);
            resourceTime = view.findViewById(R.id.tvResourceTime);
            resourceStatus = view.findViewById(R.id.ivResourceStatus);
            quoteContainer = view.findViewById(R.id.quote_container);
            quoteLine = view.findViewById(R.id.vQuoteLine);
            quoteSender = view.findViewById(R.id.tvQuoteSender);
            quoteContent = view.findViewById(R.id.tvQuoteContent);
            quoteImage = view.findViewById(R.id.ivQuoteImage);
            statusRow = view.findViewById(R.id.message_status_row);
            statusText = view.findViewById(R.id.tvMessageStatus);
            statusIcon = view.findViewById(R.id.ivMessageStatus);
            statusRowMedia = view.findViewById(R.id.message_status_media_row);
            statusTextMedia = view.findViewById(R.id.tvMessageStatusMedia);
            statusIconMedia = view.findViewById(R.id.ivMessageStatusMedia);
            imageStatus = view.findViewById(R.id.ivImageStatus);
            redPacketContainer = view.findViewById(R.id.red_packet_container);
            redPacketIcon = view.findViewById(R.id.ivRedPacketIcon);
            redPacketTitle = view.findViewById(R.id.tvRedPacketTitle);
            redPacketDesc = view.findViewById(R.id.tvRedPacketDesc);
            redPacketOpenTip = view.findViewById(R.id.tvRedPacketOpenTip);
            redPacketStatusIcon = view.findViewById(R.id.ivRedPacketStatus);
            imageContainer = view.findViewById(R.id.image_container);
            image = view.findViewById(R.id.ivMessageImage);
            imageLoading = view.findViewById(R.id.pbImageLoading);
            videoPlay = view.findViewById(R.id.ivVideoPlay);
            imageTimeBadge = view.findViewById(R.id.llImageTimeBadge);
            imageTime = view.findViewById(R.id.tvImageTime);
            voiceRow = view.findViewById(R.id.voice_row);
            voiceIcon = view.findViewById(R.id.ivVoiceIcon);
            voiceDuration = view.findViewById(R.id.tvVoiceDuration);
            voiceTranscript = view.findViewById(R.id.tvVoiceTranscript);
            voiceLoading = view.findViewById(R.id.pbVoiceLoading);
            voiceTime = view.findViewById(R.id.tvVoiceTime);
            messageTime = view.findViewById(R.id.tvMessageTime);
            messageFooterTime = view.findViewById(R.id.tvMessageFooterTime);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                if (bubble != null) bubble.setOutlineProvider(null);
                if (messageContainer != null) messageContainer.setOutlineProvider(null);
                if (quoteContainer != null) quoteContainer.setOutlineProvider(null);
                if (resourceCard != null) resourceCard.setOutlineProvider(null);
                if (redPacketContainer != null) redPacketContainer.setOutlineProvider(null);
            }
            pendingSide = view.findViewById(R.id.message_pending_side);
            pendingProgress = view.findViewById(R.id.pbMessagePending);
            pendingFailed = view.findViewById(R.id.tvMessagePendingFailed);
            bubblePaddingLeft = bubble != null ? bubble.getPaddingLeft() : 0;
            bubblePaddingTop = bubble != null ? bubble.getPaddingTop() : 0;
            bubblePaddingRight = bubble != null ? bubble.getPaddingRight() : 0;
            bubblePaddingBottom = bubble != null ? bubble.getPaddingBottom() : 0;
        }
    }

    public static class TypingViewHolder extends RecyclerView.ViewHolder {
        final View avatarStack;
        final View avatar;
        final View dotsContainer;
        final TextView textView;
        final View dot1;
        final View dot2;
        final View dot3;
        final TypingAnimator animator;
        String transitionKey;
        Runnable finishRunnable;

        TypingViewHolder(View view) {
            super(view);
            avatarStack = view.findViewById(R.id.typingAvatarStack);
            avatar = view.findViewById(R.id.ivTypingAvatar);
            dotsContainer = view.findViewById(R.id.typingDots);
            textView = view.findViewById(R.id.tvTypingText);
            dot1 = view.findViewById(R.id.typingDot1);
            dot2 = view.findViewById(R.id.typingDot2);
            dot3 = view.findViewById(R.id.typingDot3);
            animator = new TypingAnimator();
        }

        void start() {
            if (animator != null) {
                animator.start(dot1, dot2, dot3);
            }
        }

        void stop() {
            if (animator != null) {
                animator.stop();
            }
        }

        void reset() {
            stop();
            if (dotsContainer != null) {
                dotsContainer.clearAnimation();
                dotsContainer.setVisibility(View.VISIBLE);
            }
            if (textView != null) {
                textView.clearAnimation();
                textView.setVisibility(View.GONE);
            }
            transitionKey = null;
            if (finishRunnable != null) {
                itemView.removeCallbacks(finishRunnable);
                finishRunnable = null;
            }
        }

        void showDots() {
            reset();
        }

        void showTransition(String text, String key) {
            stop();
            if (dotsContainer == null || textView == null) {
                return;
            }
            String safeKey = key == null ? "" : key;
            if (!safeKey.equals(transitionKey)) {
                transitionKey = safeKey;
                dotsContainer.setVisibility(View.VISIBLE);
                textView.setVisibility(View.VISIBLE);
                textView.setText(text == null ? "" : text);
                android.view.animation.AlphaAnimation dotsFade = new android.view.animation.AlphaAnimation(1f, 0f);
                dotsFade.setDuration(TYPING_TRANSITION_MS);
                dotsFade.setFillAfter(true);
                android.view.animation.AlphaAnimation textFade = new android.view.animation.AlphaAnimation(0f, 1f);
                textFade.setDuration(TYPING_TRANSITION_MS);
                textFade.setFillAfter(true);
                dotsContainer.startAnimation(dotsFade);
                int dotsWidth = dotsContainer.getWidth();
                if (dotsWidth <= 0) {
                    dotsContainer.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    dotsWidth = dotsContainer.getMeasuredWidth();
                }
                android.view.animation.AnimationSet textSet = new android.view.animation.AnimationSet(false);
                textSet.setFillAfter(true);
                textSet.addAnimation(textFade);
                if (dotsWidth > 0) {
                    android.view.animation.TranslateAnimation slide =
                            new android.view.animation.TranslateAnimation(0f, -dotsWidth, 0f, 0f);
                    slide.setDuration(TYPING_TRANSITION_MS);
                    slide.setFillAfter(true);
                    textSet.addAnimation(slide);
                }
                textView.startAnimation(textSet);
            } else {
                dotsContainer.setVisibility(View.INVISIBLE);
                textView.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public int getItemCount() {
        if (transitionRunning) {
            return messages.size();
        }
        return messages.size() + (typingIndicatorVisible ? 1 : 0);
    }
}
