package com.im.oldchat.ui;

import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.ArrayList;
import java.util.List;

abstract class GroupMessageAdapterSupport1 extends GroupMessageAdapterSupport2 {
    protected static final long SEND_ANIM_DURATION_MS = 120L;
    protected static final long SEND_MEDIA_ANIM_DURATION_MS = 180L;
    protected static final long RECEIVE_MEDIA_ANIM_DURATION_MS = 170L;
    GroupMessageAdapterSupport1(android.content.Context context, List<GroupMessage> messages, String myUID) {
        super(context, messages, myUID);
    }

    protected void maybeAnimateSend(ViewHolder holder, GroupMessage message, boolean isMine) {
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

    protected String animationKeyForMessage(GroupMessage message) {
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

    protected boolean shouldAnimateIncomingMedia(GroupMessage message) {
        if (message == null) {
            return false;
        }
        String type = message.msg_type == null ? "" : message.msg_type.trim().toLowerCase();
        return "image".equals(type) || "voice".equals(type);
    }

    protected boolean shouldAnimateOutgoingMedia(GroupMessage message) {
        if (message == null) {
            return false;
        }
        String type = message.msg_type == null ? "" : message.msg_type.trim().toLowerCase();
        return "image".equals(type)
                || "voice".equals(type)
                || "resource".equals(type)
                || "video".equals(type);
    }

    protected boolean isTypingPosition(int position) {
        if (getDisplayTypingUids().isEmpty()) {
            return false;
        }
        if (transitionRunning && position == messages.size() - 1) {
            return false;
        }
        return position == getCount() - 1;
    }

    protected void bindTyping(TypingViewHolder holder, GroupMessage message) {
        if (holder == null) {
            return;
        }
        ArrayList<String> uids = new ArrayList<String>();
        if (message != null) {
            String identity = message.sender_ncuid != null && !message.sender_ncuid.isEmpty()
                    ? message.sender_ncuid : message.from_uid;
            if (identity != null && !identity.isEmpty()) {
                uids.add(identity);
            }
        } else {
            uids.addAll(getDisplayTypingUids());
        }
        if (uids.isEmpty()) {
            holder.reset();
            holder.clearAvatars();
            return;
        }
        bindTypingAvatars(holder, uids);
        if (message == null) {
            holder.showDots();
            holder.start();
            return;
        }
        String preview = ChatMessageUtil.previewForType(message.msg_type, message.body, message.burn_after_seconds);
        String key = message.id != null && message.id.length() > 0
                ? message.id
                : String.valueOf(message.created_at);
        holder.showTransition(preview, key);
        scheduleFinishTransition(holder);
    }

    protected ArrayList<String> getDisplayTypingUids() {
        ArrayList<String> list = new ArrayList<String>();
        for (String uid : typingUids) {
            if (uid == null || uid.isEmpty()) {
                continue;
            }
            if (transitionRunning && uid.equals(transitionUid)) {
                continue;
            }
            list.add(uid);
            if (list.size() > 3) {
                list.remove(0);
            }
        }
        return list;
    }

    protected void bindTypingAvatars(TypingViewHolder holder, ArrayList<String> uids) {
        if (holder == null) {
            return;
        }
        if (holder.avatarStack != null) {
            holder.avatarStack.setVisibility(uids.isEmpty() ? View.GONE : View.VISIBLE);
        }
        ImageView[] targets = new ImageView[]{holder.avatar, holder.avatar2, holder.avatar3};
        for (int i = 0; i < targets.length; i++) {
            ImageView target = targets[i];
            if (target == null) {
                continue;
            }
            if (i < uids.size()) {
                target.setVisibility(View.VISIBLE);
                GroupTypingAvatarLoader.load(context, avatarMap, target, uids.get(i));
            } else {
                target.setVisibility(View.GONE);
                target.setTag(null);
            }
        }
    }

    protected void scheduleFinishTransition(TypingViewHolder holder) {
        if (holder == null) {
            return;
        }
        if (holder.root != null) {
            holder.root.removeCallbacks(holder.finishRunnable);
        }
        holder.finishRunnable = new Runnable() {
            @Override
            public void run() {
                finishTypingTransition();
            }
        };
        if (holder.root != null) {
            holder.root.postDelayed(holder.finishRunnable, TYPING_TRANSITION_MS);
        }
    }

    protected void finishTypingTransition() {
        if (!transitionRunning) {
            return;
        }
        transitionRunning = false;
        transitionMessage = null;
        transitionUid = null;
        notifyDataSetChanged();
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

    protected void bindPendingSide(ViewHolder holder, GroupMessage target, boolean isMine) {
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
}
