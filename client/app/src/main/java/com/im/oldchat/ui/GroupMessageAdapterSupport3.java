package com.im.oldchat.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.TypingAnimator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

abstract class GroupMessageAdapterSupport3 extends android.widget.BaseAdapter {
    protected static final int PAYLOAD_CACHE_LIMIT = 400;
    protected static final int VIEW_TYPE_MESSAGE = 0;
    protected static final int VIEW_TYPE_TYPING = 1;
    protected static final int MAX_VOICE_SECONDS = 60;
    protected static final int VOICE_MIN_DP = 80;
    protected static final int VOICE_MAX_DP = 200;
    protected static final float BASE_MESSAGE_TEXT_SP = 16f;
    protected static final float BASE_SENDER_NAME_SP = 13f;
    protected static final float BASE_QUOTE_SENDER_SP = 12f;
    protected static final float BASE_QUOTE_CONTENT_SP = 13f;
    protected static final float BASE_VOICE_DURATION_SP = 14f;
    protected static final float BASE_VOICE_TRANSCRIPT_SP = 13f;
    protected static final float BASE_RESOURCE_TITLE_SP = 15f;
    protected static final float BASE_RESOURCE_SUB_SP = 12f;
    protected static final float BASE_RESOURCE_ACTION_SP = 12f;
    protected static final long TYPING_TRANSITION_MS = 200;
    protected static final int MESSAGE_TEXT_MAX_DP = 260;
    protected static final int MESSAGE_TEXT_MIN_DP = 150;
    protected static final int QUOTE_TEXT_MAX_DP = 240;
    protected static final int QUOTE_TEXT_MIN_DP = 140;
    protected static final int OUTGOING_AVATAR_SLOT_DP = 42;
    protected static final int BUBBLE_HORIZONTAL_PADDING_DP = 20;
    protected static final int ROW_HORIZONTAL_PADDING_DP = 8;
    protected static final int READ_STATUS_RESERVED_DP = 36;
    protected static final int WIDTH_SAFETY_MARGIN_DP = 6;

    protected final Context context;
    protected final List<GroupMessage> messages;
    protected String myUID;
    protected int myRole = 0;
    protected Map<String, String> nameMap = new HashMap<String, String>();
    protected Map<String, String> avatarMap = new HashMap<String, String>();
    protected Map<String, String> titleMap = new HashMap<String, String>();
    protected Map<String, Integer> roleMap = new HashMap<String, Integer>();
    protected final Map<String, CachedPayload> payloadCache = new HashMap<String, CachedPayload>();
    protected final GroupMessageVoicePlayer voicePlayer;
    protected GroupMessageAdapter.GroupMessageActionListener actionListener;
    protected float fontScale = 1.0f;
    protected android.widget.ListView attachedListView;
    protected GroupMessageRecyclerView attachedRecyclerView;
    protected int attachedRecyclerHeaderCount;
    protected final LinkedHashSet<String> typingUids = new LinkedHashSet<String>();
    protected GroupMessage transitionMessage;
    protected boolean transitionRunning = false;
    protected String transitionUid;
    protected final HashSet<String> pendingSendAnimations = new HashSet<String>();
    protected final java.util.WeakHashMap<GroupMessage, Boolean> playedSendAnimations =
            new java.util.WeakHashMap<GroupMessage, Boolean>();
    protected final HashSet<String> pendingIncomingMediaAnimations = new HashSet<String>();
    protected GroupMessageAdapter.QuoteClickListener quoteClickListener;
    protected int cachedMessageTextMaxWidth = -1;
    protected int cachedQuoteTextMaxWidth = -1;
    protected int cachedWidthScreenPx = -1;

    GroupMessageAdapterSupport3(Context context, List<GroupMessage> messages, String myUID) {
        this.context = context;
        this.messages = messages;
        this.myUID = myUID;
        this.voicePlayer = new GroupMessageVoicePlayer(context, new GroupMessageVoicePlayer.PlaybackListener() {
            @Override
            public void onPlaybackStateChanged() {
                notifyDataSetChanged();
            }
        });
    }

    /**
     * 数据变更后必须同步通知，不能延迟：getCount/getItem/getItemId 实时读取
     * messages/typingUids，若通知滞后于数据变更，RecyclerView 在下一帧布局时
     * 会以新 itemCount 校验旧 ViewHolder，抛出
     * "Inconsistency detected. Invalid view holder adapter position"。
     */
    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
    }

    public void notifyDataSetChangedNow() {
        super.notifyDataSetChanged();
    }

    public void attachListView(android.widget.ListView listView) {
        this.attachedListView = listView;
        this.attachedRecyclerView = null;
        this.attachedRecyclerHeaderCount = 0;
    }

    public void attachRecyclerView(GroupMessageRecyclerView recyclerView, int headerCount) {
        this.attachedRecyclerView = recyclerView;
        this.attachedRecyclerHeaderCount = Math.max(0, headerCount);
        this.attachedListView = null;
    }

    protected void notifyRecyclerItemInserted(int position) {
        if (attachedRecyclerView != null) {
            attachedRecyclerView.notifyLegacyItemInserted(position);
            return;
        }
        notifyDataSetChanged();
    }

    protected void notifyRecyclerItemRangeInserted(int positionStart, int itemCount) {
        if (itemCount <= 0) return;
        if (attachedRecyclerView != null) {
            attachedRecyclerView.notifyLegacyItemRangeInserted(positionStart, itemCount);
            return;
        }
        notifyDataSetChanged();
    }

    protected void notifyRecyclerItemChanged(int position) {
        if (attachedRecyclerView != null) {
            attachedRecyclerView.notifyLegacyItemChanged(position);
            return;
        }
        notifyDataSetChanged();
    }

    protected void notifyRecyclerItemRemoved(int position) {
        if (attachedRecyclerView != null) {
            attachedRecyclerView.notifyLegacyItemRemoved(position);
            return;
        }
        notifyDataSetChanged();
    }

    protected void refreshVisibleRows() {
        if (attachedRecyclerView != null) {
            int first = attachedRecyclerView.getFirstVisiblePosition() - attachedRecyclerHeaderCount;
            int last = attachedRecyclerView.getLastVisiblePosition() - attachedRecyclerHeaderCount;
            if (last >= 0) {
                attachedRecyclerView.notifyLegacyVisibleRangeChanged(Math.max(0, first), last);
            }
            return;
        }
        notifyDataSetChanged();
    }

    protected static class ViewHolder {
        final LinearLayout senderRow;
        final TextView senderName;
        final TextView senderBadge;
        final TextView senderTitle;
        final LinearLayout senderRowOutside;
        final TextView senderNameOutside;
        final TextView senderBadgeOutside;
        final TextView senderTitleOutside;
        final ImageView avatar;
        final ImageView avatarRight;
        final LinearLayout contentContainer;
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
        final LinearLayout redPacketContainer;
        final ImageView redPacketIcon;
        final TextView redPacketTitle;
        final TextView redPacketDesc;
        final TextView redPacketOpenTip;
        final ImageView redPacketStatusIcon;
        final LinearLayout quoteContainer;
        final View quoteLine;
        final TextView quoteSender;
        final TextView quoteContent;
        final ImageView quoteImage;
        final View imageContainer;
        final ImageView image;
        final View imageLoading;
        final ImageView videoPlay;
        final LinearLayout voiceRow;
        final ImageView voiceIcon;
        final TextView voiceDuration;
        final TextView voiceTranscript;
        final ProgressBar voiceLoading;
        final LinearLayout statusRow;
        final ImageView statusIcon;
        final TextView readCount;
        final LinearLayout statusRowMedia;
        final ImageView statusIconMedia;
        final TextView readCountMedia;
        final ImageView imageStatus;
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
        final int messageContainerPaddingLeft;
        final int messageContainerPaddingTop;
        final int messageContainerPaddingRight;
        final int messageContainerPaddingBottom;
        final int bubblePaddingLeft;
        final int bubblePaddingTop;
        final int bubblePaddingRight;
        final int bubblePaddingBottom;
        float lastAppliedFontScale = Float.NaN;
        int lastAppliedMessageMaxWidth = -1;
        int lastAppliedQuoteMaxWidth = -1;
        int lastVoiceRowWidth = -1;
        int lastSenderRowGravity = Integer.MIN_VALUE;
        int lastSenderRowWidth = Integer.MIN_VALUE;
        int lastSenderRowLayoutGravity = Integer.MIN_VALUE;
        int lastContentGravity = Integer.MIN_VALUE;
        int lastContentLayoutGravity = Integer.MIN_VALUE;
        int lastSenderNameGravity = Integer.MIN_VALUE;

        // Cached listeners to avoid creating new instances on every bind
        View.OnClickListener imageClickListener;
        View.OnClickListener voiceClickListener;
        View.OnClickListener musicClickListener;
        View.OnClickListener resourceClickListener;
        View.OnClickListener redPacketClickListener;
        View.OnClickListener burnClickListener;
        View.OnClickListener forwardClickListener;
        String musicTitle;
        String musicArtist;
        String musicUrl;
        String musicCoverUrl;
        String musicLyricsUrl;
        String musicItemId;
        String musicOwnerUid;

        ViewHolder(View view) {
            messageContainer = view.findViewById(R.id.message_container);
            senderRow = view.findViewById(R.id.sender_row);
            senderName = view.findViewById(R.id.tvSenderName);
            senderBadge = view.findViewById(R.id.tvSenderBadge);
            senderTitle = view.findViewById(R.id.tvSenderTitle);
            senderRowOutside = view.findViewById(R.id.sender_row_outside);
            senderNameOutside = view.findViewById(R.id.tvSenderNameOutside);
            senderBadgeOutside = view.findViewById(R.id.tvSenderBadgeOutside);
            senderTitleOutside = view.findViewById(R.id.tvSenderTitleOutside);
            avatar = view.findViewById(R.id.ivAvatar);
            avatarRight = view.findViewById(R.id.ivAvatarRight);
            contentContainer = view.findViewById(R.id.content_container);
            text = view.findViewById(R.id.tvMessage);
            bubble = view.findViewById(R.id.message_bubble);
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
            redPacketContainer = view.findViewById(R.id.red_packet_container);
            redPacketIcon = view.findViewById(R.id.ivRedPacketIcon);
            redPacketTitle = view.findViewById(R.id.tvRedPacketTitle);
            redPacketDesc = view.findViewById(R.id.tvRedPacketDesc);
            redPacketOpenTip = view.findViewById(R.id.tvRedPacketOpenTip);
            redPacketStatusIcon = view.findViewById(R.id.ivRedPacketStatus);
            quoteContainer = view.findViewById(R.id.quote_container);
            quoteLine = view.findViewById(R.id.vQuoteLine);
            quoteSender = view.findViewById(R.id.tvQuoteSender);
            quoteContent = view.findViewById(R.id.tvQuoteContent);
            quoteImage = view.findViewById(R.id.ivQuoteImage);
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
            statusRow = view.findViewById(R.id.message_status_row);
            statusIcon = view.findViewById(R.id.ivMessageStatus);
            readCount = view.findViewById(R.id.tvReadCount);
            statusRowMedia = view.findViewById(R.id.message_status_media_row);
            statusIconMedia = view.findViewById(R.id.ivMessageStatusMedia);
            readCountMedia = view.findViewById(R.id.tvReadCountMedia);
            imageStatus = view.findViewById(R.id.ivImageStatus);
            messageTime = view.findViewById(R.id.tvMessageTime);
            messageFooterTime = view.findViewById(R.id.tvMessageFooterTime);
            pendingSide = view.findViewById(R.id.message_pending_side);
            pendingProgress = view.findViewById(R.id.pbMessagePending);
            pendingFailed = view.findViewById(R.id.tvMessagePendingFailed);
            messageContainerPaddingLeft = messageContainer != null ? messageContainer.getPaddingLeft() : 0;
            messageContainerPaddingTop = messageContainer != null ? messageContainer.getPaddingTop() : 0;
            messageContainerPaddingRight = messageContainer != null ? messageContainer.getPaddingRight() : 0;
            messageContainerPaddingBottom = messageContainer != null ? messageContainer.getPaddingBottom() : 0;
            bubblePaddingLeft = bubble != null ? bubble.getPaddingLeft() : 0;
            bubblePaddingTop = bubble != null ? bubble.getPaddingTop() : 0;
            bubblePaddingRight = bubble != null ? bubble.getPaddingRight() : 0;
            bubblePaddingBottom = bubble != null ? bubble.getPaddingBottom() : 0;
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                if (bubble != null) bubble.setOutlineProvider(null);
                if (messageContainer != null) messageContainer.setOutlineProvider(null);
                if (quoteContainer != null) quoteContainer.setOutlineProvider(null);
                if (resourceCard != null) resourceCard.setOutlineProvider(null);
                if (redPacketContainer != null) redPacketContainer.setOutlineProvider(null);
                if (contentContainer != null) contentContainer.setOutlineProvider(null);
            }
        }
    }

    protected static class TypingViewHolder {
        final View root;
        final View avatarStack;
        final ImageView avatar;
        final ImageView avatar2;
        final ImageView avatar3;
        final View dotsContainer;
        final TextView textView;
        final View dot1;
        final View dot2;
        final View dot3;
        final TypingAnimator animator;
        String transitionKey;
        Runnable finishRunnable;

        TypingViewHolder(View view) {
            root = view;
            avatarStack = view.findViewById(R.id.typingAvatarStack);
            avatar = view.findViewById(R.id.ivTypingAvatar);
            avatar2 = view.findViewById(R.id.ivTypingAvatar2);
            avatar3 = view.findViewById(R.id.ivTypingAvatar3);
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
                if (root != null) {
                    root.removeCallbacks(finishRunnable);
                }
                finishRunnable = null;
            }
        }

        void clearAvatars() {
            if (avatarStack != null) {
                avatarStack.setVisibility(View.GONE);
            }
            if (avatar != null) {
                avatar.setVisibility(View.GONE);
                avatar.setTag(null);
            }
            if (avatar2 != null) {
                avatar2.setVisibility(View.GONE);
                avatar2.setTag(null);
            }
            if (avatar3 != null) {
                avatar3.setVisibility(View.GONE);
                avatar3.setTag(null);
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
                    dotsContainer.measure(
                            android.view.View.MeasureSpec.UNSPECIFIED,
                            android.view.View.MeasureSpec.UNSPECIFIED);
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

    protected static class CachedPayload {
        String body;
        MessagePayload payload;
    }

    protected int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

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
        if (holder.senderName != null) {
            holder.senderName.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SENDER_NAME_SP * scale);
        }
        if (holder.senderNameOutside != null) {
            holder.senderNameOutside.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SENDER_NAME_SP * scale);
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

    protected void applyMessageWidthLimits(ViewHolder holder) {
        if (holder == null) {
            return;
        }
        ensureWidthCache();
        int messageMax = cachedMessageTextMaxWidth;
        if (holder.text != null && holder.lastAppliedMessageMaxWidth != messageMax) {
            holder.text.setMaxWidth(messageMax);
            holder.lastAppliedMessageMaxWidth = messageMax;
        }
        if (holder.quoteContent != null && holder.lastAppliedQuoteMaxWidth != cachedQuoteTextMaxWidth) {
            int quoteMax = cachedQuoteTextMaxWidth;
            holder.quoteContent.setMaxWidth(quoteMax);
            holder.lastAppliedQuoteMaxWidth = quoteMax;
        }
    }

    private void ensureWidthCache() {
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        if (screenWidth == cachedWidthScreenPx
                && cachedMessageTextMaxWidth > 0 && cachedQuoteTextMaxWidth > 0) {
            return;
        }
        int reserved = dpToPx(ROW_HORIZONTAL_PADDING_DP)
                + dpToPx(OUTGOING_AVATAR_SLOT_DP)
                + dpToPx(BUBBLE_HORIZONTAL_PADDING_DP)
                + dpToPx(READ_STATUS_RESERVED_DP)
                + dpToPx(WIDTH_SAFETY_MARGIN_DP);
        int dynamic = screenWidth - reserved;
        int max = dpToPx(MESSAGE_TEXT_MAX_DP);
        int min = dpToPx(MESSAGE_TEXT_MIN_DP);
        int messageMax;
        if (dynamic < min) {
            messageMax = min;
        } else if (dynamic > max) {
            messageMax = max;
        } else {
            messageMax = dynamic;
        }
        int quoteMax = Math.min(dpToPx(QUOTE_TEXT_MAX_DP), messageMax + dpToPx(16));
        quoteMax = Math.max(dpToPx(QUOTE_TEXT_MIN_DP), quoteMax);
        cachedWidthScreenPx = screenWidth;
        cachedMessageTextMaxWidth = messageMax;
        cachedQuoteTextMaxWidth = quoteMax;
    }
}
