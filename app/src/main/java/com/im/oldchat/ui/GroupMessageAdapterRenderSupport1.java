package com.im.oldchat.ui;

import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliShareUtil;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.BurnAfterSecondsUtil;
import com.im.oldchat.util.ClipboardUtil;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.RedPacketPayload;
import com.im.oldchat.util.RedPacketStateStore;

import java.util.List;

abstract class GroupMessageAdapterRenderSupport1 extends GroupMessageAdapterRenderSupport0 {
    GroupMessageAdapterRenderSupport1(android.content.Context context, List<GroupMessage> messages, String myUID) {
        super(context, messages, myUID);
    }

    @Override
    protected void bindMessageTypeSection(final ViewHolder holderFinal, final GroupMessage target,
                                          MessagePayload payload, String type, boolean isMine) {
        if ("image".equals(type) || "video".equals(type)) {
            final boolean isVideo = "video".equals(type);
            if (holderFinal.imageContainer != null) {
                holderFinal.imageContainer.setVisibility(View.VISIBLE);
            }
            holderFinal.image.setVisibility(View.VISIBLE);
            // 图片气泡带尾巴：我的消息右上角(1)、对方左上角(2)；气泡形状关闭时去 tail。
            if (holderFinal.image instanceof com.im.oldchat.ui.widget.RoundedImageView) {
                boolean bubbleRounded = com.im.oldchat.util.SettingsPrefs.isBubbleRoundedEnabled(context);
                com.im.oldchat.ui.widget.RoundedImageView image =
                        (com.im.oldchat.ui.widget.RoundedImageView) holderFinal.image;
                image.setCornerRadiusPx(bubbleRounded
                        ? 6f * context.getResources().getDisplayMetrics().density : 0f);
                image.setTailSide(bubbleRounded ? (isMine ? 1 : 2) : 0);
            }
            final String thumb = !TextUtils.isEmpty(target.thumb_url) ? target.thumb_url : target.media_url;
            boolean boundLocalPreview = !isVideo
                    && target.localPreviewUri != null
                    && target.localPreviewUri.length() > 0
                    && (target.localPending || target.localFailed)
                    && PendingImagePreviewBinder.bind(holderFinal.image, target.localPreviewUri);
            if (holderFinal.imageLoading != null) {
                holderFinal.imageLoading.setVisibility(View.GONE);
            }
            if (!boundLocalPreview) {
                ChatInlineImagePrefetcher.bindInlineImage(context, holderFinal.image,
                        holderFinal.imageLoading, thumb, false);
            }
            if (!isVideo && boundLocalPreview) {
                holderFinal.image.setOnClickListener(null);
            } else {
                // Use cached listener or create new one
                if (holderFinal.imageClickListener == null) {
                    holderFinal.imageClickListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ViewHolder h = (ViewHolder) v.getTag(R.id.view_holder_tag);
                            GroupMessage msg = (GroupMessage) v.getTag(R.id.message_tag);
                            if (h == null || msg == null) return;
                            String type = msg.msg_type == null ? "" : msg.msg_type.toLowerCase();
                            boolean isVid = "video".equals(type);
                            if (isVid) {
                                openVideo(msg);
                            } else {
                                // 预览显示缩略图；原图优先 original_url（CDN），旧消息无原图记录时回退 media_url。
                                String previewUrl = !android.text.TextUtils.isEmpty(msg.thumb_url) ? msg.thumb_url : msg.media_url;
                                String originalUrl = !android.text.TextUtils.isEmpty(msg.original_url) ? msg.original_url : msg.media_url;
                                ChatImagePreviewDialog.show(context, previewUrl, originalUrl);
                            }
                        }
                    };
                }
                holderFinal.image.setTag(R.id.view_holder_tag, holderFinal);
                holderFinal.image.setTag(R.id.message_tag, target);
                holderFinal.image.setOnClickListener(holderFinal.imageClickListener);
            }
            if (holderFinal.videoPlay != null) {
                holderFinal.videoPlay.setVisibility(isVideo ? View.VISIBLE : View.GONE);
            }
            if (holderFinal.imageTime != null) {
                holderFinal.imageTime.setText(ChatTimeFormatter.formatMessageFooterTime(target.created_at));
            }
            if (holderFinal.imageTimeBadge != null) {
                holderFinal.imageTimeBadge.setVisibility(View.VISIBLE);
            }
            if (holderFinal.imageStatus != null) {
                if (isMine && !target.localPending && !target.localFailed) {
                    holderFinal.imageStatus.setVisibility(View.VISIBLE);
                    holderFinal.imageStatus.setImageResource(target.read_count > 0 ? R.drawable.read : R.drawable.sent);
                } else {
                    holderFinal.imageStatus.setVisibility(View.GONE);
                }
            }
            return;
        }

        if ("voice".equals(type)) {
            holderFinal.voiceRow.setVisibility(View.VISIBLE);
            int seconds = Math.max(1, target.duration_ms / 1000);
            seconds = Math.min(seconds, MAX_VOICE_SECONDS);
            boolean loadingVoice = voicePlayer.isLoading(target);
            int width = dpToPx(VOICE_MIN_DP + (VOICE_MAX_DP - VOICE_MIN_DP) * seconds / MAX_VOICE_SECONDS);
            if (loadingVoice) {
                width = dpToPx(VOICE_MAX_DP);
            }
            ViewGroup.LayoutParams params = holderFinal.voiceRow.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                if (holderFinal.lastVoiceRowWidth != width) {
                    params.width = width;
                    holderFinal.voiceRow.setLayoutParams(params);
                    holderFinal.lastVoiceRowWidth = width;
                }
            }
            holderFinal.voiceDuration.setText(voicePlayer.getDurationLabel(target, seconds));
            if (holderFinal.voiceLoading != null) {
                holderFinal.voiceLoading.setVisibility(loadingVoice ? View.VISIBLE : View.GONE);
            }
            if (holderFinal.voiceTime != null) {
                holderFinal.voiceTime.setText(ChatTimeFormatter.formatMessageFooterTime(target.created_at));
                holderFinal.voiceTime.setTextColor(isMine ? 0xCCFFFFFF : android.support.v4.content.ContextCompat.getColor(context, R.color.color_text_secondary));
                holderFinal.voiceTime.setVisibility(View.VISIBLE);
            }
            // Use cached listener for voice row
            if (holderFinal.voiceClickListener == null) {
                holderFinal.voiceClickListener = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        GroupMessage msg = (GroupMessage) v.getTag(R.id.message_tag);
                        if (msg != null) {
                            voicePlayer.play(msg);
                        }
                    }
                };
            }
            holderFinal.voiceRow.setTag(R.id.message_tag, target);
            holderFinal.voiceRow.setOnClickListener(holderFinal.voiceClickListener);
            if (voicePlayer.isPlaying(target)) {
                holderFinal.voiceIcon.startAnimation(buildVoiceAnimation());
            } else {
                holderFinal.voiceIcon.clearAnimation();
            }
            bindVoiceTranscript(holderFinal, payload);
            return;
        }

        boolean isMusicMessage = "music".equals(type)
                || ("resource".equals(type) && payload != null && "music".equals(payload.mediaKind));
        if (isMusicMessage) {
            holderFinal.text.setVisibility(View.GONE);
            holderFinal.resourceCard.setVisibility(View.VISIBLE);
            holderFinal.resourceAction.setVisibility(View.VISIBLE);
            final String songUrl = MediaUrlResolver.resolve(target.media_url);
            MusicShareInfo info = getMusicShareInfo(payload.text, songUrl, target.thumb_url);
            holderFinal.resourceTitle.setText(info.title);
            holderFinal.resourceSub.setText(info.sub);
            holderFinal.resourceAction.setText("播放");
            holderFinal.resourceIcon.setImageResource(R.drawable.ic_voice);
            bindMusicPreview(holderFinal, info.coverUrl);
            if (holderFinal.resourceTime != null) {
                holderFinal.resourceTime.setText(ChatTimeFormatter.formatMessageFooterTime(target.created_at));
                holderFinal.resourceTime.setTextColor(isMine ? 0xCCFFFFFF : android.support.v4.content.ContextCompat.getColor(context, R.color.color_text_secondary));
                holderFinal.resourceTime.setVisibility(View.VISIBLE);
            }
            if (holderFinal.resourceFooter != null) {
                holderFinal.resourceFooter.setVisibility(View.VISIBLE);
            }
            if (holderFinal.resourceStatus != null) {
                if (isMine && !target.localPending && !target.localFailed) {
                    holderFinal.resourceStatus.setVisibility(View.VISIBLE);
                    holderFinal.resourceStatus.setImageResource(target.read_count > 0 ? R.drawable.read : R.drawable.sent);
                } else {
                    holderFinal.resourceStatus.setVisibility(View.GONE);
                }
            }
            if (songUrl != null && songUrl.length() > 0) {
                // Use cached listener for music
                if (holderFinal.musicClickListener == null) {
                    holderFinal.musicClickListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ViewHolder h = (ViewHolder) v.getTag(R.id.view_holder_tag);
                            if (h == null) return;
                            openMusicShare(h.musicTitle, h.musicArtist, h.musicUrl,
                                    h.musicCoverUrl, h.musicLyricsUrl, h.musicItemId, h.musicOwnerUid);
                        }
                    };
                }
                holderFinal.musicTitle = info.title;
                holderFinal.musicArtist = info.artist;
                holderFinal.musicUrl = songUrl;
                holderFinal.musicCoverUrl = info.coverUrl;
                holderFinal.musicLyricsUrl = info.lyricsUrl;
                holderFinal.musicItemId = info.itemId;
                holderFinal.musicOwnerUid = info.ownerUid;
                holderFinal.resourceCard.setTag(R.id.view_holder_tag, holderFinal);
                holderFinal.resourceCard.setTag(R.id.message_tag, songUrl);
                holderFinal.bubble.setOnClickListener(holderFinal.musicClickListener);
                holderFinal.resourceCard.setOnClickListener(holderFinal.musicClickListener);
                holderFinal.resourceAction.setOnClickListener(holderFinal.musicClickListener);
            } else {
                holderFinal.resourceSub.setText("歌曲链接不可用");
                holderFinal.resourceAction.setVisibility(View.GONE);
                holderFinal.bubble.setOnClickListener(null);
                holderFinal.resourceCard.setOnClickListener(null);
                holderFinal.resourceAction.setOnClickListener(null);
            }
            return;
        }

        if ("resource".equals(type)) {
            holderFinal.text.setVisibility(View.GONE);
            holderFinal.resourceCard.setVisibility(View.VISIBLE);
            if (holderFinal.resourceTime != null) {
                holderFinal.resourceTime.setText(ChatTimeFormatter.formatMessageFooterTime(target.created_at));
                holderFinal.resourceTime.setTextColor(isMine ? 0xCCFFFFFF : android.support.v4.content.ContextCompat.getColor(context, R.color.color_text_secondary));
                holderFinal.resourceTime.setVisibility(View.VISIBLE);
            }
            if (holderFinal.resourceFooter != null) {
                holderFinal.resourceFooter.setVisibility(View.VISIBLE);
            }
            if (holderFinal.resourceStatus != null) {
                if (isMine && !target.localPending && !target.localFailed) {
                    holderFinal.resourceStatus.setVisibility(View.VISIBLE);
                    holderFinal.resourceStatus.setImageResource(target.read_count > 0 ? R.drawable.read : R.drawable.sent);
                } else {
                    holderFinal.resourceStatus.setVisibility(View.GONE);
                }
            }
            final String sourceUrl = target.media_url;
            final String url = MediaUrlResolver.resolve(sourceUrl);
            if (url != null && url.length() > 0) {
                ResourceInfo info = getResourceInfo(payload.text, url);
                final boolean isBiliShare = info.biliShare;
                final String downloadFileName = info.title;
                holderFinal.resourceTitle.setText(info.title);
                holderFinal.resourceSub.setText(info.sub);
                holderFinal.resourceAction.setVisibility(View.VISIBLE);
                holderFinal.resourceAction.setText(isBiliShare ? "观看" : "下载");
                holderFinal.resourceIcon.setImageResource(isBiliShare ? R.drawable.ic_action_video : R.drawable.ic_document);
                bindResourcePreview(holderFinal, info);
                // Use cached listener for resource
                if (holderFinal.resourceClickListener == null) {
                    holderFinal.resourceClickListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String url = (String) v.getTag(R.id.message_tag);
                            Boolean isBili = (Boolean) v.getTag(R.id.tag_is_bili);
                            if (url == null) return;
                            if (isBili != null && isBili) {
                                openBiliShare(url);
                            } else {
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
                        }
                    };
                }
                holderFinal.bubble.setTag(R.id.message_tag, sourceUrl);
                holderFinal.bubble.setTag(R.id.tag_is_bili, isBiliShare);
                holderFinal.resourceCard.setTag(R.id.message_tag, sourceUrl);
                holderFinal.resourceCard.setTag(R.id.tag_is_bili, isBiliShare);
                holderFinal.resourceAction.setTag(R.id.message_tag, sourceUrl);
                holderFinal.resourceAction.setTag(R.id.tag_is_bili, isBiliShare);
                holderFinal.bubble.setOnClickListener(holderFinal.resourceClickListener);
                holderFinal.resourceCard.setOnClickListener(holderFinal.resourceClickListener);
                holderFinal.resourceAction.setOnClickListener(holderFinal.resourceClickListener);
            } else {
                ResourceInfo info = getResourceInfo(payload.text, "");
                holderFinal.resourceTitle.setText(info.title);
                String statusLine = isMine ? FileUploadUiTextUtil.extractStatusLine(payload.text) : "";
                holderFinal.resourceSub.setText(statusLine.length() > 0 ? statusLine : "链接不可用");
                FileUploadUiTextUtil.bindProgress(holderFinal.fileUploadProgress, target.localUploadState, target.localProgress);
                holderFinal.resourceIcon.setImageResource(R.drawable.ic_document);
                holderFinal.resourceAction.setText(statusLine.length() > 0 ? "上传中" : "下载");
                holderFinal.resourceAction.setVisibility(View.GONE);
                bindResourcePreview(holderFinal, null);
                holderFinal.bubble.setOnClickListener(null);
                holderFinal.resourceCard.setOnClickListener(null);
                holderFinal.resourceAction.setOnClickListener(null);
            }
            return;
        }

        if ("red_packet".equals(type)) {
            holderFinal.redPacketContainer.setVisibility(View.VISIBLE);
            RedPacketPayload redPacket = RedPacketPayload.fromBody(target.body);
            boolean redPacketDone = redPacket.isDoneInPayload()
                    || RedPacketStateStore.isPacketDone(context, redPacket.packetId);
            String title = redPacket.title == null ? "" : redPacket.title;
            if (title.isEmpty()) {
                title = context.getString(R.string.red_packet_title_default);
            }
            holderFinal.redPacketTitle.setText(title);
            String desc;
            if (redPacket.totalAmount > 0 && redPacket.totalCount > 0) {
                String amount = context.getString(R.string.message_red_packet_amount, redPacket.totalAmount);
                String count = context.getString(R.string.message_red_packet_count, redPacket.totalCount);
                desc = amount + " · " + count;
            } else {
                desc = context.getString(R.string.message_red_packet_tag);
            }
            holderFinal.redPacketDesc.setText(desc);
            if (holderFinal.redPacketOpenTip != null) {
                holderFinal.redPacketOpenTip.setText(redPacketDone
                        ? R.string.red_packet_status_done
                        : R.string.red_packet_open_tip);
            }
            if (holderFinal.redPacketStatusIcon != null) {
                if (isMine) {
                    if (target.read_count > 0) {
                        holderFinal.redPacketStatusIcon.setImageResource(R.drawable.ic_msg_read);
                    } else {
                        holderFinal.redPacketStatusIcon.setImageResource(R.drawable.ic_msg_sent);
                    }
                    holderFinal.redPacketStatusIcon.setColorFilter(0x99FFFFFF);
                    holderFinal.redPacketStatusIcon.setVisibility(View.VISIBLE);
                } else {
                    holderFinal.redPacketStatusIcon.setVisibility(View.GONE);
                }
            }
            final String packetId = redPacket.packetId;
            View.OnClickListener open = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openRedPacket(packetId);
                }
            };
            holderFinal.bubble.setOnClickListener(open);
            holderFinal.redPacketContainer.setOnClickListener(open);
            return;
        }

        holderFinal.text.setVisibility(View.VISIBLE);
        holderFinal.text.setMovementMethod(null);
        holderFinal.text.setLinksClickable(false);
        if (BurnAfterSecondsUtil.isEnabled(target.burn_after_seconds)) {
            String ft = ChatTimeFormatter.formatMessageFooterTime(target.created_at);
            BubbleTextLayout.bind(holderFinal.text, context, target, payload.text, payload.mentions, ft,
                    resolveInlineStatusDrawable(target, isMine),
                    isMine && !target.localPending && !target.localFailed ? target.read_count : 0, isMine);
            View.OnClickListener openBurn = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (BurnMessageUiHelper.onGroupBurnMessageClick(context, target, isMine)) {
                        notifyDataSetChanged();
                    }
                }
            };
            holderFinal.bubble.setOnClickListener(openBurn);
            holderFinal.text.setOnClickListener(openBurn);
        } else if (ForwardMessageComposer.hasForward(payload)) {
            String ft = ChatTimeFormatter.formatMessageFooterTime(target.created_at);
            BubbleTextLayout.bindStyled(holderFinal.text, context,
                    ForwardMessageComposer.buildBubbleText(context, payload.forward), ft,
                    resolveInlineStatusDrawable(target, isMine),
                    isMine && !target.localPending && !target.localFailed ? target.read_count : 0, isMine);
            final MessagePayload.ForwardBundle bundle = payload.forward;
            View.OnClickListener openForward = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ForwardMessageDetailDialog.show(context, bundle);
                }
            };
            holderFinal.bubble.setOnClickListener(openForward);
            holderFinal.text.setOnClickListener(openForward);
        } else {
            String ft = ChatTimeFormatter.formatMessageFooterTime(target.created_at);
            BubbleTextLayout.bind(holderFinal.text, context, target, payload.text, payload.mentions, ft,
                    resolveInlineStatusDrawable(target, isMine),
                    isMine && !target.localPending && !target.localFailed ? target.read_count : 0, isMine);
        }
        renderGroupMessageButtons(holderFinal, payload, target);
    }

    /** 渲染群聊消息内联按钮。 */
    private void renderGroupMessageButtons(GroupMessageAdapterRenderSupport0.ViewHolder holder,
                                           MessagePayload payload, GroupMessage msg) {
        if (holder == null || holder.buttonPanel == null || payload == null) {
            return;
        }
        if (payload.buttons == null || payload.buttons.isEmpty()) {
            holder.buttonPanel.setVisibility(View.GONE);
            return;
        }
        final String toId = msg.group_id != null ? msg.group_id : "";
        final String toType = "group";
        MessageButtonPanel.render(holder.buttonPanel, payload, msg.id, toType, toId,
                new MessageButtonPanel.ButtonCallback() {
                    @Override
                    public void onSendPayload(MessagePayload.Button button, String type, String id) {
                        sendButtonPayload(button, type, id);
                    }
                });
        holder.buttonPanel.setVisibility(View.VISIBLE);
    }

    /** 群聊按钮发送预设内容。 */
    private void sendButtonPayload(MessagePayload.Button button, String type, String id) {
        if (button == null || id == null || id.isEmpty()) {
            return;
        }
        if (context instanceof GroupChatActivity) {
            GroupChatActivity activity = (GroupChatActivity) context;
            if (activity.messageSender != null) {
                activity.messageSender.sendButtonPayload(button);
                return;
            }
        }
        MessagePayload out = new MessagePayload();
        out.text = button.data;
        out.replyTid = button.tid;
        String body = out.toJson();
        try {
            String token = context.getSharedPreferences("auth",
                    android.content.Context.MODE_PRIVATE).getString("access_token", "");
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("group_id", id);
            json.put("body", body);
            json.put("msg_type", "text");
            com.im.oldchat.api.HttpUtil.post("/groups/message/send", json, token,
                    new com.im.oldchat.api.HttpUtil.Callback() {
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

    private int resolveInlineStatusDrawable(GroupMessage target, boolean isMine) {
        if (!isMine || target == null || target.localPending || target.localFailed) {
            return 0;
        }
        return target.read_count > 0 ? R.drawable.read : R.drawable.sent;
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
}
