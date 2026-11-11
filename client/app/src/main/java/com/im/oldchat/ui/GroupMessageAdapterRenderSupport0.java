package com.im.oldchat.ui;

import android.support.v4.content.ContextCompat;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

abstract class GroupMessageAdapterRenderSupport0 extends GroupMessageAdapterSupport0 {
    GroupMessageAdapterRenderSupport0(android.content.Context context, List<GroupMessage> messages, String myUID) {
        super(context, messages, myUID);
    }

    protected boolean canCompactBetween(GroupMessage previous, GroupMessage current) {
        if (previous == null || current == null) {
            return false;
        }
        String currentUid = current.from_uid == null ? "" : current.from_uid.trim();
        String previousUid = previous.from_uid == null ? "" : previous.from_uid.trim();
        if (currentUid.length() == 0 || previousUid.length() == 0 || !currentUid.equals(previousUid)) {
            return false;
        }
        String currentType = current.msg_type == null ? "text" : current.msg_type.trim().toLowerCase();
        String previousType = previous.msg_type == null ? "text" : previous.msg_type.trim().toLowerCase();
        return !"recall".equals(currentType) && !"recall".equals(previousType);
    }

    protected boolean shouldCompactWithPrevious(int position, GroupMessage current) {
        if (current == null || position <= 0 || position >= messages.size()) {
            return false;
        }
        if (shouldShowTime(current, position)) {
            return false;
        }
        GroupMessage previous = messages.get(position - 1);
        return canCompactBetween(previous, current);
    }

    protected boolean shouldCompactWithNext(int position, GroupMessage current) {
        if (current == null || position < 0 || position >= messages.size() - 1) {
            return false;
        }
        GroupMessage next = messages.get(position + 1);
        if (shouldShowTime(next, position + 1)) {
            return false;
        }
        return canCompactBetween(current, next);
    }

    protected void applyCompactRowStyle(ViewHolder holder, boolean compactWithPrevious, boolean compactWithNext) {
        if (holder == null || holder.messageContainer == null) {
            return;
        }
        int compactPadding = dpToPx(1);
        int normalPadding = dpToPx(2);
        int topPadding = compactWithPrevious ? compactPadding : normalPadding;
        int bottomPadding = compactWithNext ? compactPadding : normalPadding;
        holder.messageContainer.setPadding(
                holder.messageContainerPaddingLeft,
                topPadding,
                holder.messageContainerPaddingRight,
                bottomPadding);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        long _gvs = android.os.SystemClock.uptimeMillis();
        int viewType = getItemViewType(position);
        if (viewType == VIEW_TYPE_TYPING) {
            TypingViewHolder typingHolder;
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_typing_indicator, parent, false);
                typingHolder = new TypingViewHolder(convertView);
                convertView.setTag(typingHolder);
            } else {
                typingHolder = (TypingViewHolder) convertView.getTag();
            }
            boolean isTransitionRow = transitionRunning && position == messages.size() - 1;
            bindTyping(typingHolder, isTransitionRow ? transitionMessage : null);
            convertView.setBackgroundColor(0x00000000);
            return convertView;
        }

        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_group_message, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        if (position < 0 || position >= messages.size()) {
            holder.messageContainer.setVisibility(View.GONE);
            if (holder.messageTime != null) {
                holder.messageTime.setText("");
                holder.messageTime.setVisibility(View.GONE);
            }
            return convertView;
        }

        GroupMessage msg = messages.get(position);
        final GroupMessage target = msg;

        applyFontScale(holder);
        applyMessageWidthLimits(holder);
        final ViewHolder holderFinal = holder;
        bindJumpHighlight(convertView, target.id);
        final boolean isMine = com.im.oldchat.util.NcuidIdentity.isMine(
                context, target.sender_ncuid, target.from_uid, myUID);
        BurnMessageUiHelper.prepareGroupMessage(context, target, isMine);
        String type = target.msg_type == null ? "text" : target.msg_type.toLowerCase();
        MessagePayload payload = getPayload(target);
        final boolean isForwardMessage = "text".equals(type) && ForwardMessageComposer.hasForward(payload);
        final boolean isImageMessage = "image".equals(type);
        final boolean isBurnLockedMessage = BurnAfterSecondsUtil.isEnabled(target.burn_after_seconds)
                && !"recall".equals(type);
        final boolean isMusicMessage = "music".equals(type)
                || ("resource".equals(type) && payload != null && "music".equals(payload.mediaKind));
        holderFinal.messageContainer.setVisibility(View.VISIBLE);
        if (holderFinal.messageTime != null) {
            holderFinal.messageTime.setOnClickListener(null);
            holderFinal.messageTime.setClickable(false);
            holderFinal.messageTime.setTextColor(ContextCompat.getColor(context, R.color.color_text_secondary));
        }

        if ("recall".equals(type)) {
            holderFinal.messageContainer.setVisibility(View.GONE);
            String name = resolveName(target.from_uid);
            if (name == null || name.isEmpty()) {
                name = "成员";
            }
            String recallText = context.getString(R.string.message_recalled_member, name);
            String footerTime = ChatTimeFormatter.formatMessageFooterTime(target.created_at);
            if (canReEditRecalled(target, isMine) && holderFinal.messageTime != null) {
                SpannableStringBuilder builder = new SpannableStringBuilder();
                builder.append(recallText).append("  ");
                int actionStart = builder.length();
                builder.append("重新编辑");
                builder.setSpan(new ForegroundColorSpan(ThemeColorUtil.getPrimaryColor(context)),
                        actionStart, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (footerTime != null && footerTime.length() > 0) {
                    builder.append("\n").append(footerTime);
                }
                holderFinal.messageTime.setText(builder);
                holderFinal.messageTime.setClickable(true);
                holderFinal.messageTime.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (actionListener != null) {
                            actionListener.onReEdit(target);
                        }
                    }
                });
            } else {
                holderFinal.messageTime.setText(footerTime == null || footerTime.length() == 0
                        ? recallText : recallText + "\n" + footerTime);
            }
            holderFinal.messageTime.setVisibility(View.VISIBLE);
            return convertView;
        }

        bindTimeLabel(holder, msg, position);
        resetMessageHolderForBind(holder);
        // 文本消息时间由 BubbleTimeTextView 内部渲染，媒体与卡片消息由各自组件底部独立渲染。
        ForwardBubbleStyleUtil.reset(holderFinal.text);

        final boolean compactWithPrevious = shouldCompactWithPrevious(position, target);
        final boolean compactWithNext = shouldCompactWithNext(position, target);
        applyCompactRowStyle(holderFinal, compactWithPrevious, compactWithNext);

        bindMessageTypeSection(holderFinal, target, payload, type, isMine);
        bindSenderSection(holderFinal, target, type, compactWithPrevious, compactWithNext, isMine);

        if (isBurnLockedMessage) {
            applyBubbleBackground(holderFinal, isMine ? R.drawable.bg_msg_burn_me : R.drawable.bg_msg_burn_other);
        }
        if (isMusicMessage) {
            applyBubbleBackground(holderFinal, isMine ? R.drawable.bg_msg_music_me : R.drawable.bg_msg_music_other);
        }
        if (isImageMessage) {
            clearBubbleBackground(holderFinal);
        }
        if (isForwardMessage) {
            ForwardBubbleStyleUtil.applyCard(holderFinal.text, context);
        }
        bindQuote(holderFinal, payload.quote, isMine);
        bindItemActions(convertView, holderFinal, target, payload);
        long _gve = android.os.SystemClock.uptimeMillis();
        long _gvc = _gve - _gvs;
        if (_gvc > 4) {
            android.util.Log.w("GroupChatPerf", "getView pos=" + position + " cost=" + _gvc + "ms type=" + (target.msg_type == null ? "text" : target.msg_type));
        }
        return convertView;
    }

    protected boolean canReEditRecalled(GroupMessage msg, boolean isMine) {
        if (!isMine || msg == null) {
            return false;
        }
        String text = msg.recall_edit_text == null ? "" : msg.recall_edit_text.trim();
        if (text.length() == 0) {
            return false;
        }
        String type = msg.recall_edit_type == null ? "text" : msg.recall_edit_type.toLowerCase();
        return "text".equals(type);
    }

    protected String normalizeProfileUid(String rawUid) {
        if (rawUid == null) {
            return "";
        }
        String uid = rawUid.trim();
        while (uid.startsWith("@")) {
            uid = uid.substring(1).trim();
        }
        return uid;
    }

    protected abstract void bindMessageTypeSection(ViewHolder holder, GroupMessage target,
                                                   MessagePayload payload, String type, boolean isMine);

    protected abstract void bindSenderSection(ViewHolder holder, GroupMessage target, String type,
                                              boolean compactWithPrevious, boolean compactWithNext,
                                              boolean isMine);
}
