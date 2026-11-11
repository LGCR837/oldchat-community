package com.im.oldchat.ui;

import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.RedPacketPayload;
import com.im.oldchat.util.RedPacketStateStore;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

abstract class GroupMessageAdapterRenderSupport2 extends GroupMessageAdapterRenderSupport1 {
    GroupMessageAdapterRenderSupport2(android.content.Context context, List<GroupMessage> messages, String myUID) {
        super(context, messages, myUID);
    }

    @Override
    protected void bindSenderSection(final ViewHolder holderFinal, final GroupMessage target, String type,
                                     boolean compactWithPrevious, boolean compactWithNext, boolean isMine) {
        boolean hideAvatar = com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(context);
        if (isMine) {
            holderFinal.messageContainer.setGravity(Gravity.END);
            holderFinal.avatar.setVisibility(View.GONE);
            if (holderFinal.avatarRight != null) {
                holderFinal.avatarRight.setVisibility(hideAvatar ? View.GONE : (compactWithPrevious ? View.INVISIBLE : View.VISIBLE));
            }
            boolean nameOutside = com.im.oldchat.util.SettingsPrefs.isSenderNameOutsideBubble(context);
            LinearLayout targetSenderRow = nameOutside ? holderFinal.senderRowOutside : holderFinal.senderRow;
            LinearLayout otherSenderRow = nameOutside ? holderFinal.senderRow : holderFinal.senderRowOutside;
            TextView targetSenderName = nameOutside ? holderFinal.senderNameOutside : holderFinal.senderName;
            TextView targetSenderBadge = nameOutside ? holderFinal.senderBadgeOutside : holderFinal.senderBadge;
            TextView targetSenderTitle = nameOutside ? holderFinal.senderTitleOutside : holderFinal.senderTitle;

            if (otherSenderRow != null) {
                otherSenderRow.setVisibility(View.GONE);
            }
            int senderGravity = nameOutside ? Gravity.END : Gravity.START;
            if (targetSenderRow != null) {
                if (compactWithPrevious) {
                    targetSenderRow.setVisibility(View.GONE);
                } else {
                    targetSenderRow.setVisibility(View.VISIBLE);
                    applyLinearLayoutGravity(targetSenderRow, senderGravity | Gravity.CENTER_VERTICAL,
                            LinearLayout.LayoutParams.WRAP_CONTENT, holderFinal, true);
                }
            }
            if (holderFinal.contentContainer != null) {
                applyLinearLayoutGravity(holderFinal.contentContainer, Gravity.END,
                        LinearLayout.LayoutParams.WRAP_CONTENT, holderFinal, false);
            }
            applySenderNameGravity(targetSenderName, senderGravity, holderFinal);
            final String selfUid = MyUidStore.getCurrentUid(context);
            String profileUid = (selfUid != null && selfUid.length() > 0) ? selfUid : target.from_uid;
            ResolvedMemberMeta selfMeta = resolveMemberMetaForMessage(target, profileUid);
            final String profileUidFinal = selfMeta.canonicalUid == null || selfMeta.canonicalUid.length() == 0
                    ? profileUid : selfMeta.canonicalUid;
            String myName = selfMeta.name;
            if (targetSenderName != null) {
                targetSenderName.setText(myName == null ? "" : myName);
                targetSenderName.setTextColor(com.im.oldchat.util.UserNameColorUtil.getUserNameColor(context, profileUidFinal));
                targetSenderName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            if (targetSenderBadge != null) {
                if (compactWithPrevious) {
                    targetSenderBadge.setVisibility(View.GONE);
                } else {
                    bindRoleBadge(targetSenderBadge, selfMeta.role);
                }
            }
            if (compactWithPrevious) {
                UserTitleBinder.bindChatBubbleTitle(targetSenderTitle, "");
            } else {
                UserTitleBinder.bindChatBubbleTitle(targetSenderTitle, selfMeta.title);
            }
            if (holderFinal.avatarRight != null) {
                if (hideAvatar) {
                    holderFinal.avatarRight.setVisibility(View.GONE);
                    holderFinal.avatarRight.setOnClickListener(null);
                } else if (compactWithPrevious) {
                    holderFinal.avatarRight.setOnClickListener(null);
                } else {
                    String myAvatar = resolveAvatarUrlForMessage(target, profileUidFinal);
                    if (myAvatar == null || myAvatar.isEmpty()) {
                        holderFinal.avatarRight.setImageResource(R.drawable.ic_avatar_placeholder);
                        holderFinal.avatarRight.setTag(null);
                    } else {
                        Object currentTag = holderFinal.avatarRight.getTag();
                        if (currentTag == null || !myAvatar.equals(currentTag)) {
                            holderFinal.avatarRight.setTag(myAvatar);
                            ImageLoader.loadAvatarCacheFirstTryOnce(holderFinal.avatarRight, myAvatar);
                        }
                    }
                    holderFinal.avatarRight.setOnClickListener(new View.OnClickListener() {

                        public void onClick(View v) {
                            String openUid = normalizeProfileUid(profileUidFinal);
                            if (openUid.length() == 0) {
                                return;
                            }
                            Intent intent = new Intent(context, UserSpaceActivity.class);
                            intent.putExtra("ncuid", openUid);
                            context.startActivity(intent);
                        }
                    });
                }
            }
            boolean isRedPacketDone = false;
            if ("red_packet".equals(type)) {
                RedPacketPayload packet = RedPacketPayload.fromBody(target.body);
                isRedPacketDone = packet.isDoneInPayload()
                        || RedPacketStateStore.isPacketDone(context, packet.packetId);
                applyBubbleBackground(holderFinal,
                        isRedPacketDone ? R.drawable.bg_red_packet_out_dim : R.drawable.bg_red_packet_out);
            } else {
                applyBubbleBackground(holderFinal, R.drawable.bg_msg_me);
            }
            holderFinal.text.setTextColor(0xFFFFFFFF);
            holderFinal.voiceDuration.setTextColor(0xFFFFFFFF);
            if (holderFinal.voiceTranscript != null) {
                holderFinal.voiceTranscript.setTextColor(
                        ContextCompat.getColor(context, R.color.color_text_primary));
            }
            holderFinal.voiceIcon.setColorFilter(0xFFFFFFFF);
            holderFinal.resourceTitle.setTextColor(0xFFFFFFFF);
            holderFinal.resourceSub.setTextColor(0xCCFFFFFF);
            holderFinal.resourceAction.setTextColor(0xFFFFFFFF);
            holderFinal.resourceIcon.setColorFilter(0xFFFFFFFF);
            holderFinal.redPacketTitle.setTextColor(isRedPacketDone ? 0xEAFFFFFF : 0xFFFFFFFF);
            holderFinal.redPacketDesc.setTextColor(isRedPacketDone ? 0xBFFFFFFF : 0xCCFFFFFF);
            if (holderFinal.redPacketOpenTip != null) {
                holderFinal.redPacketOpenTip.setTextColor(isRedPacketDone ? 0xA9FFFFFF : 0x99FFFFFF);
            }
            if (holderFinal.redPacketStatusIcon != null && holderFinal.redPacketStatusIcon.getVisibility() == View.VISIBLE) {
                holderFinal.redPacketStatusIcon.setColorFilter(isRedPacketDone ? 0x88FFFFFF : 0x99FFFFFF);
            }
            boolean useMediaStatus = "image".equals(type) || "video".equals(type) || "voice".equals(type);
            boolean isImageType = "image".equals(type) || "video".equals(type);
            if ("text".equals(type)) {
                // 文本状态已内联在时间右侧；独立状态行会把多行气泡撑宽。
                if (holderFinal.statusRow != null) {
                    holderFinal.statusRow.setVisibility(View.GONE);
                }
                if (holderFinal.statusRowMedia != null) {
                    holderFinal.statusRowMedia.setVisibility(View.GONE);
                }
                if (holderFinal.imageStatus != null) {
                    holderFinal.imageStatus.setVisibility(View.GONE);
                }
            } else {
                bindReadStatus(holderFinal, target.read_count, useMediaStatus, isImageType);
            }
            bindPendingSide(holderFinal, target, true);
            return;
        }

        holderFinal.messageContainer.setGravity(Gravity.START);
        holderFinal.avatar.setVisibility(hideAvatar ? View.GONE : (compactWithPrevious ? View.INVISIBLE : View.VISIBLE));
        if (holderFinal.avatarRight != null) {
            holderFinal.avatarRight.setVisibility(View.GONE);
        }
        boolean nameOutside = com.im.oldchat.util.SettingsPrefs.isSenderNameOutsideBubble(context);
        LinearLayout targetSenderRow = nameOutside ? holderFinal.senderRowOutside : holderFinal.senderRow;
        LinearLayout otherSenderRow = nameOutside ? holderFinal.senderRow : holderFinal.senderRowOutside;
        TextView targetSenderName = nameOutside ? holderFinal.senderNameOutside : holderFinal.senderName;
        TextView targetSenderBadge = nameOutside ? holderFinal.senderBadgeOutside : holderFinal.senderBadge;
        TextView targetSenderTitle = nameOutside ? holderFinal.senderTitleOutside : holderFinal.senderTitle;

        if (otherSenderRow != null) {
            otherSenderRow.setVisibility(View.GONE);
        }
        ResolvedMemberMeta senderMeta = resolveMemberMetaForMessage(target, target.from_uid);
        final String senderUid = senderMeta.canonicalUid == null || senderMeta.canonicalUid.length() == 0
                ? (target.from_uid == null ? "" : target.from_uid) : senderMeta.canonicalUid;
        String name = senderMeta.name;
        int role = senderMeta.role;
        if (targetSenderName != null) {
            targetSenderName.setText(name == null ? "" : name);
            targetSenderName.setTextColor(com.im.oldchat.util.UserNameColorUtil.getUserNameColor(context, senderUid));
            targetSenderName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        if (targetSenderRow != null) {
            if (compactWithPrevious) {
                targetSenderRow.setVisibility(View.GONE);
            } else {
                targetSenderRow.setVisibility(View.VISIBLE);
                applyLinearLayoutGravity(targetSenderRow, Gravity.START | Gravity.CENTER_VERTICAL,
                        LinearLayout.LayoutParams.WRAP_CONTENT, holderFinal, true);
            }
        }
        if (holderFinal.contentContainer != null) {
            applyLinearLayoutGravity(holderFinal.contentContainer, Gravity.START,
                    LinearLayout.LayoutParams.WRAP_CONTENT, holderFinal, false);
        }
        applySenderNameGravity(targetSenderName, Gravity.START, holderFinal);
        if (compactWithPrevious) {
            if (targetSenderBadge != null) {
                targetSenderBadge.setVisibility(View.GONE);
            }
            UserTitleBinder.bindChatBubbleTitle(targetSenderTitle, "");
        } else {
            bindRoleBadge(targetSenderBadge, role);
            UserTitleBinder.bindChatBubbleTitle(targetSenderTitle, senderMeta.title);
        }
        if (hideAvatar) {
            holderFinal.avatar.setVisibility(View.GONE);
            holderFinal.avatar.setOnClickListener(null);
        } else if (compactWithPrevious) {
            holderFinal.avatar.setOnClickListener(null);
        } else {
            String avatarUrl = resolveAvatarUrlForMessage(target, senderUid);
            if (avatarUrl == null || avatarUrl.isEmpty()) {
                holderFinal.avatar.setImageResource(R.drawable.ic_avatar_placeholder);
                holderFinal.avatar.setTag(null);
            } else {
                Object currentTag = holderFinal.avatar.getTag();
                if (currentTag == null || !avatarUrl.equals(currentTag)) {
                    holderFinal.avatar.setTag(avatarUrl);
                    ImageLoader.loadAvatarCacheFirstTryOnce(holderFinal.avatar, avatarUrl);
                }
            }
            holderFinal.avatar.setOnClickListener(new View.OnClickListener() {

                public void onClick(View v) {
                    String openUid = normalizeProfileUid(senderUid);
                    if (openUid.length() == 0) {
                        return;
                    }
                    // 点击对方头像：强制刷新该成员缓存（网络查最新资料写 SQLite）。
                    forceRefreshMemberCache(openUid, target);
                    Intent intent = new Intent(context, UserSpaceActivity.class);
                    intent.putExtra("ncuid", openUid);
                    context.startActivity(intent);
                }
            });
        }
        boolean isRedPacketDone = false;
        if ("red_packet".equals(type)) {
            RedPacketPayload packet = RedPacketPayload.fromBody(target.body);
            isRedPacketDone = packet.isDoneInPayload()
                    || RedPacketStateStore.isPacketDone(context, packet.packetId);
            applyBubbleBackground(holderFinal,
                    isRedPacketDone ? R.drawable.bg_red_packet_in_dim : R.drawable.bg_red_packet_in);
        } else {
            applyBubbleBackground(holderFinal, R.drawable.bg_msg_other);
        }
        int textColor = ContextCompat.getColor(context, R.color.color_text_primary);
        int subColor = ContextCompat.getColor(context, R.color.color_text_secondary);
        int actionColor = ThemeColorUtil.getPrimaryColor(context);
        holderFinal.text.setTextColor(textColor);
        holderFinal.voiceDuration.setTextColor(textColor);
        if (holderFinal.voiceTranscript != null) {
            holderFinal.voiceTranscript.setTextColor(textColor);
        }
        holderFinal.voiceIcon.setColorFilter(textColor);
        holderFinal.resourceTitle.setTextColor(textColor);
        holderFinal.resourceSub.setTextColor(subColor);
        holderFinal.resourceAction.setTextColor(actionColor);
        holderFinal.resourceIcon.setColorFilter(actionColor);
        holderFinal.redPacketTitle.setTextColor(isRedPacketDone ? 0xEAFFFFFF : 0xFFFFFFFF);
        holderFinal.redPacketDesc.setTextColor(isRedPacketDone ? 0xBFFFFFFF : 0xCCFFFFFF);
        if (holderFinal.redPacketOpenTip != null) {
            holderFinal.redPacketOpenTip.setTextColor(isRedPacketDone ? 0xA9FFFFFF : 0x99FFFFFF);
        }
        if (holderFinal.redPacketStatusIcon != null) {
            holderFinal.redPacketStatusIcon.setVisibility(View.GONE);
        }
        if (holderFinal.statusRow != null) {
            holderFinal.statusRow.setVisibility(View.GONE);
        }
        if (holderFinal.statusRowMedia != null) {
            holderFinal.statusRowMedia.setVisibility(View.GONE);
        }
        if (holderFinal.imageStatus != null) {
            holderFinal.imageStatus.setVisibility(View.GONE);
        }
        bindPendingSide(holderFinal, target, false);
    }

    private void applyLinearLayoutGravity(LinearLayout layout, int gravity, int width,
                                          ViewHolder holder, boolean senderRow) {
        if (layout == null || holder == null) {
            return;
        }
        if (senderRow) {
            if (holder.lastSenderRowGravity != gravity) {
                layout.setGravity(gravity);
                holder.lastSenderRowGravity = gravity;
            }
        } else {
            if (holder.lastContentGravity != gravity) {
                layout.setGravity(gravity);
                holder.lastContentGravity = gravity;
            }
        }
        ViewGroup.LayoutParams params = layout.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
            if (senderRow) {
                if (holder.lastSenderRowWidth != width || holder.lastSenderRowLayoutGravity != gravity) {
                    lp.width = width;
                    lp.gravity = gravity;
                    layout.setLayoutParams(lp);
                    holder.lastSenderRowWidth = width;
                    holder.lastSenderRowLayoutGravity = gravity;
                }
            } else {
                if (holder.lastContentLayoutGravity != gravity) {
                    lp.gravity = gravity;
                    layout.setLayoutParams(lp);
                    holder.lastContentLayoutGravity = gravity;
                }
            }
        }
    }

    private void applySenderNameGravity(android.widget.TextView textView, int gravity, ViewHolder holder) {
        if (textView == null || holder == null) {
            return;
        }
        if (holder.lastSenderNameGravity != gravity) {
            textView.setGravity(gravity);
            holder.lastSenderNameGravity = gravity;
        }
    }

    /** 点击头像强制刷新该成员缓存：网络查最新资料写入 SQLite，再刷新本群适配器。 */
    private void forceRefreshMemberCache(String ncuid, GroupMessage message) {
        if (ncuid == null || ncuid.length() == 0) {
            return;
        }
        final String groupId = message == null ? null : message.group_id;
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        final String token;
        try {
            token = context.getSharedPreferences("auth",
                    android.content.Context.MODE_PRIVATE).getString("access_token", "");
        } catch (Throwable ignored) {
            return;
        }
        if (token == null || token.length() == 0) {
            return;
        }
        java.util.ArrayList<String> one = new java.util.ArrayList<String>();
        one.add(ncuid);
        final String finalNcuid = ncuid;
        GroupMemberLookup.lookup(context, groupId, token, one, new GroupMemberLookup.Callback() {
            @Override
            public void onResult(List<GroupMember> fetched) {
                if (fetched == null || fetched.isEmpty()) {
                    return;
                }
                GroupMember fresh = null;
                for (int i = 0; i < fetched.size(); i++) {
                    if (fetched.get(i) != null && finalNcuid.equals(fetched.get(i).ncuid)) {
                        fresh = fetched.get(i);
                        break;
                    }
                }
                if (fresh == null) return;
                // 更新活动映射并刷新消息列表。
                updateMemberMetaFor(fresh);
                notifyDataSetChangedCompat();
            }

            @Override
            public void onError() {
            }
        });
    }

    private void updateMemberMetaFor(GroupMember fresh) {
        if (fresh == null) return;
        String key = fresh.ncuid;
        String name = com.im.oldchat.models.FriendNameResolver.resolve("",
                fresh.display_name, fresh.username, fresh.uid);
        if (name.length() > 0 && nameMap != null) nameMap.put(key, name);
        if (fresh.avatar_url != null && fresh.avatar_url.length() > 0 && avatarMap != null) {
            avatarMap.put(key, fresh.avatar_url);
        }
        if (titleMap != null) titleMap.put(key,
                fresh.user_title == null ? "" : fresh.user_title);
        if (roleMap != null) roleMap.put(key, Integer.valueOf(fresh.role));
    }

    private void notifyDataSetChangedCompat() {
        try {
            notifyDataSetChanged();
        } catch (Throwable ignored) {
        }
    }

}
