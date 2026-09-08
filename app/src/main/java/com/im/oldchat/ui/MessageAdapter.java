package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.ClipboardUtil;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.RedPacketPayload;
import com.im.oldchat.util.RedPacketStateStore;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

public class MessageAdapter extends MessageAdapterSupport0 {
    private String friendUid;

    public void setFriendUid(String uid) {
        this.friendUid = uid;
    }

    public interface MessageActionListener {
        void onQuote(Message message);
        void onCopy(String text);
        void onRecall(Message message);
        void onReEdit(Message message);
        void onVoiceTranscribe(Message message);
        void onForward(Message message);
        void onEnterMultiSelect(Message message);
    }

    public interface SelectionModeListener {
        void onSelectionModeChanged(boolean enabled, int count);
    }

    public interface QuoteClickListener {
        void onQuoteClick(MessagePayload.Quote quote);
    }

    public MessageAdapter(Context context, List<Message> messages, String myUID) {
        super(context, messages, myUID);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_TYPING) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_typing_indicator, parent, false);
            return new TypingViewHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == VIEW_TYPE_TYPING) {
            bindTyping((TypingViewHolder) holder, transitionRunning ? transitionMessage : null);
            ((TypingViewHolder) holder).itemView.setBackgroundColor(0x00000000);
            return;
        }
        Message msg = messages.get(position);
        bindMessage((ViewHolder) holder, msg, position);
    }

    @Override
    public void onViewRecycled(RecyclerView.ViewHolder holder) {
        if (holder instanceof TypingViewHolder) {
            ((TypingViewHolder) holder).reset();
        } else if (holder instanceof ViewHolder) {
            ViewHolder messageHolder = (ViewHolder) holder;
            messageHolder.image.clearAnimation();
            messageHolder.image.setTag(null);
            messageHolder.image.setImageDrawable(null);
            if (messageHolder.imageLoading != null) {
                messageHolder.imageLoading.setVisibility(View.GONE);
            }
            if (messageHolder.videoPlay != null) {
                messageHolder.videoPlay.setVisibility(View.GONE);
            }
            if (messageHolder.imageStatus != null) {
                messageHolder.imageStatus.setVisibility(View.GONE);
            }
        }
        super.onViewRecycled(holder);
    }

    private void bindMessage(final ViewHolder holder, final Message msg, int position) {        applyFontScale(holder);
        final boolean selectionMode = isSelectionMode();
        final boolean isMine = MyUidStore.isMyUid(context, msg.from_uid, myUID);
        BurnMessageUiHelper.prepareDirectMessage(context, msg, isMine);
        final MessagePayload payload = getPayload(msg);
        String type = msg.msg_type == null ? "text" : msg.msg_type.toLowerCase();
        final boolean isBurnLockedMessage = msg.burn_after_seconds > 0 && !"recall".equals(type);
        final boolean isForwardMessage = "text".equals(type) && ForwardMessageComposer.hasForward(payload);
        final boolean isImageMessage = "image".equals(type);
        final boolean isEmojiMessage = isImageMessage && payload != null && "emoji".equals(payload.mediaKind);
        final boolean isMusicMessage = "music".equals(type)
                || ("resource".equals(type) && payload != null && "music".equals(payload.mediaKind));
        final boolean useMediaStatus = isMine && ("image".equals(type) || "video".equals(type) || "voice".equals(type));
        boolean redPacketDone = false;
        holder.messageContainer.setVisibility(View.VISIBLE);
        bindJumpHighlight(holder.itemView, msg.id);
        if (holder.messageTime != null) {
            holder.messageTime.setOnClickListener(null);
            holder.messageTime.setClickable(false);
            holder.messageTime.setTextColor(ContextCompat.getColor(context, R.color.color_text_secondary));
        }

        if ("recall".equals(type)) {
            holder.messageContainer.setVisibility(View.GONE);
            String recallText = isMine ? context.getString(R.string.message_recalled_self)
                    : context.getString(R.string.message_recalled_other);
            String footerTime = ChatTimeFormatter.formatMessageFooterTime(msg.created_at);
            if (canReEditRecalled(msg, isMine) && holder.messageTime != null) {
                SpannableStringBuilder builder = new SpannableStringBuilder();
                builder.append(recallText).append("  ");
                int actionStart = builder.length();
                builder.append("重新编辑");
                builder.setSpan(new ForegroundColorSpan(ThemeColorUtil.getPrimaryColor(context)),
                        actionStart, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (footerTime != null && footerTime.length() > 0) {
                    builder.append("\n").append(footerTime);
                }
                holder.messageTime.setText(builder);
                holder.messageTime.setClickable(true);
                holder.messageTime.setOnClickListener(new View.OnClickListener() {
                    
                    public void onClick(View v) {
                        if (actionListener != null) {
                            actionListener.onReEdit(msg);
                        }
                    }
                });
            } else {
                holder.messageTime.setText(footerTime == null || footerTime.length() == 0
                        ? recallText : recallText + "\n" + footerTime);
            }
            holder.messageTime.setVisibility(View.VISIBLE);
            return;
        }

        bindTimeLabel(holder, msg, position);
        MessageViewResetHelper.prepareForContentBind(holder);
        // 文本消息时间由 BubbleTimeTextView 内部渲染，媒体与卡片消息由各自组件底部独立渲染。

        if ("image".equals(type) || "video".equals(type)) {
            final boolean isVideo = "video".equals(type);
            holder.image.setVisibility(View.VISIBLE);
            final String thumb = !TextUtils.isEmpty(msg.thumb_url) ? msg.thumb_url : msg.media_url;
            boolean boundLocalPreview = !isVideo
                    && msg.localPreviewUri != null
                    && msg.localPreviewUri.length() > 0
                    && (msg.localPending || msg.localFailed)
                    && PendingImagePreviewBinder.bind(holder.image, msg.localPreviewUri);

            if (holder.imageContainer != null) {
                holder.imageContainer.setVisibility(View.VISIBLE);
            }
            if (holder.imageLoading != null) {
                holder.imageLoading.setVisibility(View.GONE);
            }
            if (holder.videoPlay != null) {
                holder.videoPlay.setVisibility(isVideo ? View.VISIBLE : View.GONE);
            }
            if (holder.imageTime != null) {
                holder.imageTime.setText(ChatTimeFormatter.formatMessageFooterTime(msg.created_at));
            }
            if (holder.imageTimeBadge != null) {
                holder.imageTimeBadge.setVisibility(View.VISIBLE);
            }
            if (holder.imageStatus != null) {
                if (isMine && !msg.localPending && !msg.localFailed) {
                    holder.imageStatus.setVisibility(View.VISIBLE);
                    holder.imageStatus.setImageResource("read".equals(msg.status) ? R.drawable.read : R.drawable.sent);
                } else {
                    holder.imageStatus.setVisibility(View.GONE);
                }
            }

            if (!boundLocalPreview) {
                ChatInlineImagePrefetcher.bindInlineImage(context, holder.image,
                        holder.imageLoading, thumb, false);
            }

            if (!isVideo && boundLocalPreview) {
                holder.image.setOnClickListener(null);
            } else {
                holder.image.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (isVideo) {
                            openVideo(msg);
                        } else {
                            // 预览显示缩略图；原图优先 original_url（CDN），旧消息无原图记录时回退 media_url。
                            String previewUrl = !TextUtils.isEmpty(msg.thumb_url) ? msg.thumb_url : msg.media_url;
                            String originalUrl = !TextUtils.isEmpty(msg.original_url) ? msg.original_url : msg.media_url;
                            ChatImagePreviewDialog.show(context, previewUrl, originalUrl);
                        }
                    }
                });
            }
        } else if ("voice".equals(type)) {
            holder.voiceRow.setVisibility(View.VISIBLE);
            int seconds = Math.max(1, msg.duration_ms / 1000);
            seconds = Math.min(seconds, MAX_VOICE_SECONDS);
            boolean loadingVoice = voicePlayer.isLoading(msg);
            int width = dpToPx(VOICE_MIN_DP + (VOICE_MAX_DP - VOICE_MIN_DP) * seconds / MAX_VOICE_SECONDS);
            if (loadingVoice) {
                width = dpToPx(VOICE_MAX_DP);
            }
            ViewGroup.LayoutParams params = holder.voiceRow.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                if (holder.lastVoiceRowWidth != width) {
                    params.width = width;
                    holder.voiceRow.setLayoutParams(params);
                    holder.lastVoiceRowWidth = width;
                }
            }
            holder.voiceDuration.setText(voicePlayer.getDurationLabel(msg, seconds));
            if (holder.voiceLoading != null) {
                holder.voiceLoading.setVisibility(loadingVoice ? View.VISIBLE : View.GONE);
            }
            if (holder.voiceTime != null) {
                holder.voiceTime.setText(ChatTimeFormatter.formatMessageFooterTime(msg.created_at));
                holder.voiceTime.setTextColor(isMine ? 0xCCFFFFFF : ContextCompat.getColor(context, R.color.color_text_secondary));
                holder.voiceTime.setVisibility(View.VISIBLE);
            }
            holder.voiceRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    voicePlayer.play(msg);
                }
            });
            if (voicePlayer.isPlaying(msg)) {
                holder.voiceIcon.startAnimation(buildVoiceAnimation());
            } else {
                holder.voiceIcon.clearAnimation();
            }
            bindVoiceTranscript(holder, payload);
        } else if (isMusicMessage) {
            holder.text.setVisibility(View.GONE);
            holder.resourceCard.setVisibility(View.VISIBLE);
            holder.resourceAction.setVisibility(View.VISIBLE);
            final String songUrl = MediaUrlResolver.resolve(msg.media_url);
            MusicShareInfo info = getMusicShareInfo(payload.text, songUrl, msg.thumb_url);
            holder.resourceTitle.setText(info.title);
            holder.resourceSub.setText(info.sub);
            holder.resourceAction.setText("播放");
            holder.resourceIcon.setImageResource(R.drawable.ic_voice);
            bindMusicPreview(holder, info.coverUrl);
            if (holder.resourceTime != null) {
                holder.resourceTime.setText(ChatTimeFormatter.formatMessageFooterTime(msg.created_at));
                holder.resourceTime.setTextColor(isMine ? 0xCCFFFFFF : ContextCompat.getColor(context, R.color.color_text_secondary));
                holder.resourceTime.setVisibility(View.VISIBLE);
            }
            if (holder.resourceFooter != null) {
                holder.resourceFooter.setVisibility(View.VISIBLE);
            }
            if (holder.resourceStatus != null) {
                if (isMine && !msg.localPending && !msg.localFailed) {
                    holder.resourceStatus.setVisibility(View.VISIBLE);
                    holder.resourceStatus.setImageResource("read".equals(msg.status) ? R.drawable.read : R.drawable.sent);
                } else {
                    holder.resourceStatus.setVisibility(View.GONE);
                }
            }
            if (songUrl != null && songUrl.length() > 0) {
                final String songTitle = info.title;
                final String songArtist = info.artist;
                final String coverUrl = info.coverUrl;
                final String lyricsUrl = info.lyricsUrl;
                final String itemId = info.itemId;
                final String ownerUid = info.ownerUid;
                View.OnClickListener open = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openMusicShare(songTitle, songArtist, songUrl, coverUrl, lyricsUrl, itemId, ownerUid);
                    }
                };
                holder.bubble.setOnClickListener(open);
                holder.resourceCard.setOnClickListener(open);
                holder.resourceAction.setOnClickListener(open);
            } else {
                holder.resourceSub.setText("歌曲链接不可用");
                holder.resourceAction.setVisibility(View.GONE);
                holder.bubble.setOnClickListener(null);
                holder.resourceCard.setOnClickListener(null);
                holder.resourceAction.setOnClickListener(null);
            }
        } else if ("resource".equals(type)) {
            holder.text.setVisibility(View.GONE);
            holder.resourceCard.setVisibility(View.VISIBLE);
            holder.resourceAction.setVisibility(View.VISIBLE);
            if (holder.resourceTime != null) {
                holder.resourceTime.setText(ChatTimeFormatter.formatMessageFooterTime(msg.created_at));
                holder.resourceTime.setTextColor(isMine ? 0xCCFFFFFF : ContextCompat.getColor(context, R.color.color_text_secondary));
                holder.resourceTime.setVisibility(View.VISIBLE);
            }
            if (holder.resourceFooter != null) {
                holder.resourceFooter.setVisibility(View.VISIBLE);
            }
            if (holder.resourceStatus != null) {
                if (isMine && !msg.localPending && !msg.localFailed) {
                    holder.resourceStatus.setVisibility(View.VISIBLE);
                    holder.resourceStatus.setImageResource("read".equals(msg.status) ? R.drawable.read : R.drawable.sent);
                } else {
                    holder.resourceStatus.setVisibility(View.GONE);
                }
            }
            final String sourceUrl = msg.media_url;
            final String url = MediaUrlResolver.resolve(sourceUrl);
            if (url != null && url.length() > 0) {
                ResourceInfo info = getResourceInfo(payload.text, url);
                final boolean isBiliShare = info.biliShare;
                final String downloadFileName = info.title;
                holder.resourceTitle.setText(info.title);
                holder.resourceSub.setText(info.sub);
                holder.resourceAction.setText(isBiliShare ? "观看" : "下载");
                holder.resourceIcon.setImageResource(isBiliShare ? R.drawable.ic_action_video : R.drawable.ic_document);
                bindResourcePreview(holder, info);
                View.OnClickListener open = isBiliShare ? new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openBiliShare(url);
                    }
                } : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(context)
                                .setTitle("下载资源")
                                .setMessage(url)
                                .setPositiveButton("保存到下载", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        com.im.oldchat.service.FileDownloadService.enqueue(context, url,
                                                downloadFileName, "application/octet-stream");
                                        Toast.makeText(context, "已加入后台下载", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNeutralButton("取消", null)
                                .show();
                    }
                };
                holder.bubble.setOnClickListener(open);
                holder.resourceCard.setOnClickListener(open);
                holder.resourceAction.setOnClickListener(open);
            } else {
                ResourceInfo info = getResourceInfo(payload.text, "");
                holder.resourceTitle.setText(info.title);
                String statusLine = isMine ? FileUploadUiTextUtil.extractStatusLine(payload.text) : "";
                holder.resourceSub.setText(statusLine.length() > 0 ? statusLine : "链接不可用");
                FileUploadUiTextUtil.bindProgress(holder.fileUploadProgress, msg.localUploadState, msg.localProgress);
                holder.resourceIcon.setImageResource(R.drawable.ic_document);
                holder.resourceAction.setText(statusLine.length() > 0 ? "上传中" : "下载");
                holder.resourceAction.setVisibility(View.GONE);
                bindResourcePreview(holder, null);
                holder.bubble.setOnClickListener(null);
                holder.resourceCard.setOnClickListener(null);
                holder.resourceAction.setOnClickListener(null);
            }
        } else if ("red_packet".equals(type)) {
            holder.redPacketContainer.setVisibility(View.VISIBLE);
            RedPacketPayload redPacket = RedPacketPayload.fromBody(msg.body);
            redPacketDone = redPacket.isDoneInPayload()
                    || RedPacketStateStore.isPacketDone(context, redPacket.packetId);
            String title = redPacket.title == null ? "" : redPacket.title;
            if (title.isEmpty()) {
                title = context.getString(R.string.red_packet_title_default);
            }
            holder.redPacketTitle.setText(title);
            String desc;
            if (redPacket.totalAmount > 0 && redPacket.totalCount > 0) {
                String amount = context.getString(R.string.message_red_packet_amount, redPacket.totalAmount);
                String count = context.getString(R.string.message_red_packet_count, redPacket.totalCount);
                desc = amount + " · " + count;
            } else {
                desc = context.getString(R.string.message_red_packet_tag);
            }
            holder.redPacketDesc.setText(desc);
            if (holder.redPacketOpenTip != null) {
                holder.redPacketOpenTip.setText(redPacketDone
                        ? R.string.red_packet_status_done
                        : R.string.red_packet_open_tip);
            }
            if (holder.redPacketStatusIcon != null) {
                if (isMine) {
                    if (msg.status == Message.STATUS_READ) {
                        holder.redPacketStatusIcon.setImageResource(R.drawable.ic_msg_read);
                    } else {
                        holder.redPacketStatusIcon.setImageResource(R.drawable.ic_msg_sent);
                    }
                    holder.redPacketStatusIcon.setColorFilter(0x99FFFFFF);
                    holder.redPacketStatusIcon.setVisibility(View.VISIBLE);
                } else {
                    holder.redPacketStatusIcon.setVisibility(View.GONE);
                }
            }
            final String packetId = redPacket.packetId;
            View.OnClickListener open = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openRedPacket(packetId);
                }
            };
            holder.bubble.setOnClickListener(open);
            holder.redPacketContainer.setOnClickListener(open);
        } else {
        holder.text.setVisibility(View.VISIBLE);
        holder.text.setMovementMethod(null);
        holder.text.setLinksClickable(false);
        if (isBurnLockedMessage) {
            String ft = ChatTimeFormatter.formatMessageFooterTime(msg.created_at);
            BubbleTextLayout.bind(holder.text, context, msg, payload.text, payload.mentions, ft,
                    resolveInlineStatusDrawable(msg, isMine), 0, isMine);
            final int currentPosition = position;
            View.OnClickListener openBurn = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (BurnMessageUiHelper.onDirectBurnMessageClick(context, msg, isMine)) {
                        notifyItemChanged(currentPosition);
                    }
                }
            };
            holder.bubble.setOnClickListener(openBurn);
            holder.text.setOnClickListener(openBurn);
            } else if (isForwardMessage) {
                String ft = ChatTimeFormatter.formatMessageFooterTime(msg.created_at);
                BubbleTextLayout.bindStyled(holder.text, context,
                        ForwardMessageComposer.buildBubbleText(context, payload.forward), ft,
                        resolveInlineStatusDrawable(msg, isMine), 0, isMine);
                final MessagePayload.ForwardBundle bundle = payload.forward;
                View.OnClickListener openForward = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ForwardMessageDetailDialog.show(context, bundle);
                    }
                };
                holder.bubble.setOnClickListener(openForward);
                holder.text.setOnClickListener(openForward);
            } else {
                String ft = ChatTimeFormatter.formatMessageFooterTime(msg.created_at);
                BubbleTextLayout.bind(holder.text, context, msg, payload.text, payload.mentions, ft,
                        resolveInlineStatusDrawable(msg, isMine), 0, isMine);
                renderMessageButtons(holder, payload, msg, isMine);
            }
        }

        if (isMine) {
            applyMineStyle(holder, type, msg, useMediaStatus);
        } else {
            applyOtherStyle(holder, type);
            holder.messageContainer.setGravity(Gravity.START);
        }
        bindPendingSide(holder, msg, isMine);
        if (isBurnLockedMessage) {
            applyBubbleBackground(holder, isMine ? R.drawable.bg_msg_burn_me : R.drawable.bg_msg_burn_other);
        }
        if (isMusicMessage) {
            applyBubbleBackground(holder, isMine ? R.drawable.bg_msg_music_me : R.drawable.bg_msg_music_other);
        }
        if ("red_packet".equals(type) && redPacketDone) {
            applyRedPacketDoneStyle(holder, isMine);
        }
        if (isImageMessage) {
            clearBubbleBackground(holder);
        }
        if (isForwardMessage) {
            ForwardBubbleStyleUtil.applyCard(holder.text, context);
        }

        bindQuote(holder, payload.quote, isMine);
        if (selectionMode) {
            MessageActionBinder.clear(holder.bubble, holder.text, holder.image, holder.voiceRow, holder.voiceTranscript);
            bindSelectionActions(holder, msg);
        } else {
            clearSelectionActions(holder);
            MessageActionBinder.bind(context,
                    holder.bubble,
                    holder.text,
                    holder.image,
                    holder.voiceRow,
                    holder.voiceTranscript,
                    msg,
                    payload,
                    isMine,
                    actionListener,
                    new MessageActionBinder.RecallChecker() {
                        @Override
                        public boolean canRecall(Message target) {
                            return canRecallMessage(target);
                        }
                    });
        }
        applySelectionRowState(holder.itemView, msg);
        ChatSwipeQuoteBinder.bindDirect(context, selectionMode, holder, msg, payload, actionListener);
        maybeAnimateSend(holder, msg, isMine);
    }

    private int resolveInlineStatusDrawable(Message msg, boolean isMine) {
        if (!isMine || msg == null || msg.localPending || msg.localFailed) {
            return 0;
        }
        return msg.status == Message.STATUS_READ ? R.drawable.ic_msg_read : R.drawable.ic_msg_sent;
    }

    private boolean canReEditRecalled(Message msg, boolean isMine) {
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

    private void applyRedPacketDoneStyle(ViewHolder holder, boolean isMine) {
        applyBubbleBackground(holder, isMine ? R.drawable.bg_red_packet_out_dim : R.drawable.bg_red_packet_in_dim);
        holder.redPacketTitle.setTextColor(0xEAFFFFFF);
        holder.redPacketDesc.setTextColor(0xBFFFFFFF);
        if (holder.redPacketOpenTip != null) {
            holder.redPacketOpenTip.setTextColor(0xA9FFFFFF);
        }
        if (holder.redPacketStatusIcon != null && holder.redPacketStatusIcon.getVisibility() == View.VISIBLE) {
            holder.redPacketStatusIcon.setColorFilter(0x88FFFFFF);
        }
    }

    private void bindVoiceTranscript(ViewHolder holder, MessagePayload payload) {
        if (holder == null || holder.voiceTranscript == null) {
            return;
        }
        String transcript = payload == null ? "" : payload.voiceText;
        if (transcript != null) {
            transcript = transcript.trim();
        }
        if (transcript == null || transcript.length() == 0) {
            holder.voiceTranscript.setVisibility(View.GONE);
            holder.voiceTranscript.setText("");
            return;
        }
        holder.voiceTranscript.setText(transcript);
        holder.voiceTranscript.setVisibility(View.VISIBLE);
    }

    private void bindSelectionActions(final ViewHolder holder, final Message message) {
        if (holder == null || message == null) {
            return;
        }
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
        bindToggle(holder.itemView, click, longClick);
        bindToggle(holder.bubble, click, longClick);
        bindToggle(holder.text, click, longClick);
        bindToggle(holder.image, click, longClick);
        bindToggle(holder.voiceRow, click, longClick);
        bindToggle(holder.voiceTranscript, click, longClick);
        bindToggle(holder.resourceCard, click, longClick);
        bindToggle(holder.resourceAction, click, longClick);
        bindToggle(holder.redPacketContainer, click, longClick);
        bindToggle(holder.quoteContainer, click, longClick);
    }

    private void clearSelectionActions(ViewHolder holder) {
        if (holder == null) {
            return;
        }
        clearToggle(holder.itemView);
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

    private void applySelectionRowState(View rowView, Message message) {
        if (rowView == null || message == null) {
            return;
        }
        if (isSelectionMode() && isSelected(message)) {
            rowView.setBackgroundColor(SELECTION_HIGHLIGHT_COLOR);
            return;
        }
        if (!shouldHighlightMessage(message.id)) {
            rowView.setBackgroundColor(0x00000000);
        }
    }

    /** 渲染消息内联按钮（机器人消息）。 */
    private void renderMessageButtons(MessageAdapterSupport1.ViewHolder holder,
                                      MessagePayload payload, Message msg, boolean isMine) {
        if (holder == null || holder.buttonPanel == null || payload == null) {
            return;
        }
        if (payload.buttons == null || payload.buttons.isEmpty()) {
            holder.buttonPanel.setVisibility(View.GONE);
            return;
        }
        // 私聊按钮发送目标：直接使用 friendUid（ChatActivity 已设置）
        final String toType = "direct";
        final String targetId = friendUid != null ? friendUid : "";
        MessageButtonPanel.render(holder.buttonPanel, payload, msg.id, toType, targetId,
                new MessageButtonPanel.ButtonCallback() {
                    @Override
                    public void onSendPayload(MessagePayload.Button button, String type, String id) {
                        sendButtonPayload(button, type, id);
                    }
                });
        holder.buttonPanel.setVisibility(View.VISIBLE);
    }

    /** 点击按钮发送预设内容到当前会话。 */
    private void sendButtonPayload(MessagePayload.Button button, String type, String id) {
        if (button == null) {
            return;
        }
        if (context instanceof ChatActivity) {
            ChatActivity activity = (ChatActivity) context;
            if (activity.messageSender != null) {
                activity.messageSender.sendButtonPayload(button);
                return;
            }
        }
        String toId = type != null && type.equals("group") ? id : (friendUid != null ? friendUid : id);
        if (toId == null || toId.isEmpty()) {
            return;
        }
        MessagePayload out = new MessagePayload();
        out.text = button.data;
        out.replyTid = button.tid;
        String body = out.toJson();
        if ("send_image".equals(button.action)) {
            out.text = "[图片] " + (button.data == null ? "" : button.data);
            body = out.toJson();
        }
        sendPresetText(type, toId, body);
    }

    private void sendPresetText(String type, String toId, String body) {
        try {
            String token = context.getSharedPreferences("auth",
                    android.content.Context.MODE_PRIVATE).getString("access_token", "");
            org.json.JSONObject json = new org.json.JSONObject();
            if (type != null && type.equals("group")) {
                json.put("group_id", toId);
            } else {
                json.put("to_uid", toId);
            }
            json.put("body", body);
            json.put("msg_type", "text");
            com.im.oldchat.api.HttpUtil.post(type != null && type.equals("group")
                            ? "/groups/message/send" : "/direct/send",
                    json, token, new com.im.oldchat.api.HttpUtil.Callback() {
                        @Override
                        public void onSuccess(String response) {
                        }

                        @Override
                        public void onError(int code, String error) {
                        }
                    });
        } catch (Throwable ignored) {
        }
    }
}
