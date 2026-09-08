package com.im.oldchat.ui;

import android.graphics.drawable.Drawable;
import android.view.View;

import com.im.oldchat.R;

final class MessageViewResetHelper {
    private MessageViewResetHelper() {
    }

    static void prepareForContentBind(MessageAdapterSupport1.ViewHolder holder) {
        if (holder == null) {
            return;
        }
        setVisibility(holder.text, View.GONE);
        setVisibility(holder.imageContainer, View.GONE);
        setVisibility(holder.videoPlay, View.GONE);
        setVisibility(holder.image, View.GONE);
        clearImage(holder.image);
        setVisibility(holder.voiceRow, View.GONE);
        holder.voiceRow.setOnClickListener(null);
        if (holder.voiceTranscript != null) {
            setVisibility(holder.voiceTranscript, View.GONE);
            if (holder.voiceTranscript.length() > 0) {
                holder.voiceTranscript.setText("");
            }
        }
        setVisibility(holder.voiceLoading, View.GONE);
        setVisibility(holder.imageLoading, View.GONE);
        holder.image.setOnClickListener(null);
        holder.bubble.setOnClickListener(null);
        holder.text.setOnClickListener(null);
        holder.itemView.setOnLongClickListener(null);
        holder.bubble.setOnLongClickListener(null);
        holder.text.setOnLongClickListener(null);
        holder.image.setOnLongClickListener(null);
        holder.voiceRow.setOnLongClickListener(null);
        if (holder.voiceTranscript != null) {
            holder.voiceTranscript.setOnLongClickListener(null);
        }
        setVisibility(holder.resourceCard, View.GONE);
        if (holder.fileUploadProgress != null) {
            holder.fileUploadProgress.setVisibility(View.GONE);
            holder.fileUploadProgress.setIndeterminate(false);
            holder.fileUploadProgress.setProgress(0);
        }
        holder.resourceCard.setOnClickListener(null);
        holder.resourceAction.setOnClickListener(null);
        holder.resourceCard.setOnLongClickListener(null);
        holder.resourceAction.setOnLongClickListener(null);
        setVisibility(holder.resourceCoverContainer, View.GONE);
        if (holder.resourceCover != null) {
            holder.resourceCover.setTag(null);
            holder.resourceCover.setImageDrawable(null);
        }
        setVisibility(holder.redPacketContainer, View.GONE);
        holder.redPacketContainer.setOnClickListener(null);
        holder.redPacketContainer.setOnLongClickListener(null);
        if (holder.redPacketStatusIcon != null) {
            setVisibility(holder.redPacketStatusIcon, View.GONE);
            holder.redPacketStatusIcon.setImageResource(R.drawable.ic_msg_sent);
            holder.redPacketStatusIcon.setColorFilter(0x99FFFFFF);
        }
        setVisibility(holder.quoteContainer, View.GONE);
        setVisibility(holder.messageFooterTime, View.GONE);
        setVisibility(holder.imageTimeBadge, View.GONE);
        setVisibility(holder.resourceFooter, View.GONE);
        setVisibility(holder.voiceTime, View.GONE);
        holder.quoteContainer.setOnLongClickListener(null);
        if (holder.quoteImage != null) {
            setVisibility(holder.quoteImage, View.GONE);
            holder.quoteImage.setTag(null);
            holder.quoteImage.setImageDrawable(null);
        }
        holder.voiceIcon.clearAnimation();
        ForwardBubbleStyleUtil.reset(holder.text);
    }

    private static void clearImage(android.widget.ImageView imageView) {
        if (imageView == null) {
            return;
        }
        imageView.clearAnimation();
        if (imageView.getTag() != null) {
            imageView.setTag(null);
        }
        Drawable drawable = imageView.getDrawable();
        if (drawable != null) {
            imageView.setImageDrawable(null);
        }
    }

    private static void setVisibility(View view, int visibility) {
        if (view != null && view.getVisibility() != visibility) {
            view.setVisibility(visibility);
        }
    }
}
