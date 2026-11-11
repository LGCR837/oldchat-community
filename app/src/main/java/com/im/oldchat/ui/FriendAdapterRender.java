package com.im.oldchat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.User;
import com.im.oldchat.util.ImageLoader;

final class FriendAdapterRender {
    private FriendAdapterRender() {
    }

    static View bindSystem(final Context context, View convertView, ViewGroup parent, LayoutInflater inflater,
                           final FriendAdapter.OnFriendClickListener listener) {
        SystemHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof SystemHolder)) {
            convertView = inflater.inflate(R.layout.item_friend, parent, false);
            holder = new SystemHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (SystemHolder) convertView.getTag();
        }
        holder.listener = listener;
        boolean hideAvatar = com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(context);
        holder.applyHideAvatar(hideAvatar);
        hideOnlineDot(holder.onlineDot);
        if (holder.title != null) holder.title.setText("\u7CFB\u7EDF\u901A\u77E5");
        if (holder.subtitle != null) holder.subtitle.setText("\u5B98\u65B9\u516C\u544A\u548C\u901A\u77E5");
        UserTitleBinder.bind(holder.badge, "");
        if (!hideAvatar) {
            holder.avatar.setImageResource(R.mipmap.ic_launcher);
            try {
                holder.avatar.setColorFilter(context.getResources().getColor(R.color.color_text_secondary));
            } catch (Exception ignored) {
            }
        }
        holder.avatar.setOnClickListener(null);
        return convertView;
    }

    static View bindRequest(View convertView, ViewGroup parent, LayoutInflater inflater,
                            final FriendAdapter.FriendRequestItem req, final FriendAdapter.OnFriendClickListener listener) {
        RequestHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof RequestHolder)) {
            convertView = inflater.inflate(R.layout.item_friend_request, parent, false);
            holder = new RequestHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (RequestHolder) convertView.getTag();
        }

        holder.listener = listener;
        holder.requestId = req == null ? "" : safeText(req.id);

        boolean groupInvite = req != null && req.isGroupInvite();
        String name = groupInvite ? safeText(req.groupName)
                : (req != null && safeText(req.fromName).length() > 0 ? req.fromName : (req == null ? "" : req.fromUID));
        holder.title.setText(name == null ? "" : name);
        // 按钮圆角跟随设置开关：开=药丸形，关=直角方形。
        boolean rounded = com.im.oldchat.util.SettingsPrefs.isRoundedButtonsEnabled(convertView.getContext());
        holder.btnAccept.setBackgroundResource(rounded
                ? R.drawable.friend_request_accept_pill : R.drawable.friend_request_accept_square);
        holder.btnReject.setBackgroundResource(rounded
                ? R.drawable.friend_request_reject_pill : R.drawable.friend_request_reject_square);
        UserTitleBinder.bindCompact(holder.badge, groupInvite || req == null ? "" : req.fromTitle);
        if (groupInvite) {
            String inviter = safeText(req.fromName).length() > 0 ? req.fromName : req.fromUID;
            holder.subtitle.setText(inviter + " \u9080\u8bf7\u4f60\u52a0\u5165\u7fa4\u804a");
        } else {
            holder.subtitle.setText(req != null && safeText(req.fromUID).length() > 0 ? "UID: " + req.fromUID : "\u597D\u53CB\u8BF7\u6C42");
        }

        boolean hideAvatar = com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(convertView.getContext());
        holder.applyHideAvatar(hideAvatar);
        if (!hideAvatar) {
            holder.avatar.setColorFilter(null);
            if (req == null || safeText(req.avatarUrl).length() == 0) {
                holder.avatar.setImageResource(groupInvite ? R.drawable.group : R.drawable.ic_avatar_placeholder);
                holder.avatar.setTag(null);
            } else {
                ImageLoader.loadAvatarCacheFirstTryOnce(holder.avatar, req.avatarUrl);
            }
        }
        holder.avatar.setOnClickListener(null);
        convertView.setOnClickListener(null);
        return convertView;
    }

    static View bindGroup(View convertView, ViewGroup parent, LayoutInflater inflater, final Group group,
                          final FriendAdapter.OnFriendClickListener listener) {
        GroupHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof GroupHolder)) {
            convertView = inflater.inflate(R.layout.item_friend, parent, false);
            holder = new GroupHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (GroupHolder) convertView.getTag();
        }

        holder.listener = listener;
        holder.group = group;
        boolean hideAvatar = com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(convertView.getContext());
        holder.applyHideAvatar(hideAvatar);
        hideOnlineDot(holder.onlineDot);
        if (holder.title != null) holder.title.setText(group == null ? "" : safeText(group.name));
        if (holder.subtitle != null) holder.subtitle.setText(group == null ? "" : safeText(group.id));
        UserTitleBinder.bind(holder.badge, "");

        if (!hideAvatar) {
            holder.avatar.setColorFilter(null);
            if (group == null || safeText(group.avatar_url).length() == 0) {
                holder.avatar.setImageResource(R.drawable.group);
                holder.avatar.setTag(null);
            } else {
                ImageLoader.loadAvatarCacheFirstTryOnce(holder.avatar, group.avatar_url);
            }
        }
        holder.avatar.setOnClickListener(null);
        return convertView;
    }

    static View bindFriend(View convertView, ViewGroup parent, LayoutInflater inflater, final User friend,
                           final FriendAdapter.OnFriendClickListener listener) {
        FriendHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof FriendHolder)) {
            convertView = inflater.inflate(R.layout.item_friend, parent, false);
            holder = new FriendHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (FriendHolder) convertView.getTag();
        }

        holder.listener = listener;
        holder.friend = friend;
        boolean hideAvatar = com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(convertView.getContext());
        holder.applyHideAvatar(hideAvatar);
        if (holder.title != null) holder.title.setText(FriendNameResolver.resolve(friend));
        if (holder.subtitle != null) holder.subtitle.setText(buildFriendSubtitle(friend));
        UserTitleBinder.bindCompact(holder.badge, friend == null ? "" : friend.user_title);

        if (!hideAvatar) {
            holder.avatar.setColorFilter(null);
            if (friend == null || safeText(friend.avatar_url).length() == 0) {
                holder.avatar.setImageResource(R.drawable.ic_avatar_placeholder);
                holder.avatar.setTag(null);
            } else {
                ImageLoader.loadAvatarCacheFirstTryOnce(holder.avatar, friend.avatar_url);
            }
            if (holder.onlineDot != null) {
                if (friend != null && friend.uid != null && friend.uid.length() > 0) {
                    com.im.oldchat.util.UserPresenceCache.Presence p = com.im.oldchat.util.UserPresenceCache.getPresence(friend.uid);
                    holder.onlineDot.setVisibility(View.VISIBLE);
                    holder.onlineDot.setBackgroundResource(com.im.oldchat.util.PresenceUiHelper.resolveDotDrawable(p.isOnline, p.presenceStatus));
                } else {
                    holder.onlineDot.setVisibility(View.GONE);
                }
            }
        }
        return convertView;
    }

    private static TextView resolveTitle(View root) {
        TextView title = (TextView) root.findViewById(R.id.tvName);
        if (title == null) title = (TextView) root.findViewById(R.id.tvTitle);
        return title;
    }

    private static void hideOnlineDot(View onlineDot) {
        if (onlineDot == null) {
            return;
        }
        onlineDot.setVisibility(View.GONE);
    }

    private static TextView resolveSubtitle(View root) {
        TextView subtitle = (TextView) root.findViewById(R.id.tvSignature);
        if (subtitle == null) subtitle = (TextView) root.findViewById(R.id.tvSubtitle);
        return subtitle;
    }

    private static class BaseHolder {
        final TextView title, subtitle, badge;
        final ImageView avatar;
        final View onlineDot, avatarContainer;

        BaseHolder(View root) {
            title = resolveTitle(root);
            subtitle = resolveSubtitle(root);
            badge = (TextView) root.findViewById(R.id.tvTitleBadge);
            avatar = (ImageView) root.findViewById(R.id.ivAvatar);
            onlineDot = root.findViewById(R.id.vOnlineDot);
            avatarContainer = root.findViewById(R.id.flAvatarContainer);
        }

        void applyHideAvatar(boolean hideAvatar) {
            if (avatarContainer != null) {
                avatarContainer.setVisibility(hideAvatar ? View.GONE : View.VISIBLE);
            } else if (avatar != null) {
                avatar.setVisibility(hideAvatar ? View.GONE : View.VISIBLE);
            }
            if (hideAvatar && onlineDot != null) onlineDot.setVisibility(View.GONE);
        }
    }

    private static class SystemHolder extends BaseHolder implements View.OnClickListener {
        FriendAdapter.OnFriendClickListener listener;
        SystemHolder(View root) {
            super(root);
            root.setOnClickListener(this);
        }
        @Override public void onClick(View v) {
            if (listener != null) listener.onSystemNotificationClick();
        }
    }

    private static class RequestHolder extends BaseHolder {
        final View btnAccept, btnReject;
        FriendAdapter.OnFriendClickListener listener;
        String requestId = "";

        RequestHolder(View root) {
            super(root);
            btnAccept = root.findViewById(R.id.btnAccept);
            btnReject = root.findViewById(R.id.btnReject);
            btnAccept.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (listener != null && requestId != null && requestId.length() > 0) {
                        listener.onRequestAccept(requestId);
                    }
                }
            });
            btnReject.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (listener != null && requestId != null && requestId.length() > 0) {
                        listener.onRequestReject(requestId);
                    }
                }
            });
        }
    }

    private static class GroupHolder extends BaseHolder implements View.OnClickListener {
        FriendAdapter.OnFriendClickListener listener;
        Group group;

        GroupHolder(View root) {
            super(root);
            root.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            if (listener != null && group != null) listener.onGroupClick(group);
        }
    }

    private static class FriendHolder extends BaseHolder implements View.OnClickListener {
        FriendAdapter.OnFriendClickListener listener;
        User friend;
        FriendHolder(View root) {
            super(root);
            root.setOnClickListener(this);
            avatar.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (listener != null && friend != null) listener.onAvatarClick(friend);
                }
            });
        }
        @Override public void onClick(View v) {
            if (listener != null && friend != null) listener.onFriendClick(friend);
        }
    }

    private static String buildFriendSubtitle(User friend) {
        if (friend == null) return "";
        String uid = safeText(friend.uid);
        String remark = safeText(friend.remark_name);
        String orig = safeText(friend.display_name);
        if (orig.length() == 0) orig = safeText(friend.username);
        if (orig.length() == 0) orig = uid;
        if (remark.length() > 0 && orig.length() > 0 && !remark.equals(orig)) {
            return uid.length() > 0 ? "\u539F\u540D\uFF1A" + orig + " \u00B7 UID: " + uid : "\u539F\u540D\uFF1A" + orig;
        }
        return uid.length() > 0 ? "UID: " + uid : "";
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
