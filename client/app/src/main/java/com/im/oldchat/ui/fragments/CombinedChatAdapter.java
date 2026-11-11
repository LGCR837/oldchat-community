package com.im.oldchat.ui.fragments;

import android.support.v7.util.DiffUtil;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.ui.ChatTimeFormatter;
import com.im.oldchat.util.ImageLoader;
import java.util.ArrayList;
import java.util.List;

class CombinedChatAdapter extends RecyclerView.Adapter<CombinedChatAdapter.ViewHolder> {
    interface AvatarTracker {
        String buildKey(RecentItem item);
        boolean isLoaded(String key);
        void markLoading(String key);
        void markLoaded(String key);
    }

    interface Listener {
        void onItemClick(RecentItem item);
        void onAvatarClick(RecentItem item);
        void onItemLongClick(View anchor, RecentItem item);
    }

    private static final int FOLDED_CHILD_LEFT_INDENT_DP = 18;
    private static final int AVATAR_SIZE_NORMAL_DP = 46;
    private static final int AVATAR_SIZE_FOLDED_CHILD_DP = 40;

    private final List<RecentItem> items = new ArrayList<>();
    private final AvatarTracker avatarTracker;
    private final Listener listener;

    CombinedChatAdapter(AvatarTracker avatarTracker, Listener listener) {
        this.avatarTracker = avatarTracker;
        this.listener = listener;
    }

    public void updateItems(List<RecentItem> data) {
        // 使用DiffUtil来智能更新，避免不必要的刷新
        List<RecentItem> newItems = new ArrayList<>();
        if (data != null && !data.isEmpty()) {
            newItems.addAll(data);
        }

        // 如果列表为空，直接清空
        if (newItems.isEmpty() && items.isEmpty()) {
            return;
        }

        // 简单的差异检测：只在数据真正变化时才更新
        if (newItems.size() == items.size()) {
            boolean changed = false;
            for (int i = 0; i < newItems.size(); i++) {
                RecentItem newItem = newItems.get(i);
                RecentItem oldItem = items.get(i);
                if (!isSameItem(newItem, oldItem)) {
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                // 数据没变化，不刷新UI
                return;
            }
        }

        final List<RecentItem> oldItems = new ArrayList<RecentItem>(items);
        final List<RecentItem> targetItems = newItems;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldItems.size(); }
            @Override public int getNewListSize() { return targetItems.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                RecentItem oldItem = oldItems.get(oldPosition);
                RecentItem newItem = targetItems.get(newPosition);
                if (oldItem == null || newItem == null || oldItem.id == null || newItem.id == null) {
                    return oldItem == newItem;
                }
                return oldItem.id.equals(newItem.id)
                        && oldItem.isGroup == newItem.isGroup
                        && oldItem.isChannel == newItem.isChannel
                        && oldItem.isSystemNotification == newItem.isSystemNotification
                        && oldItem.isFoldedFolder == newItem.isFoldedFolder;
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return isSameItem(oldItems.get(oldPosition), targetItems.get(newPosition));
            }
        }, false);
        items.clear();
        items.addAll(targetItems);
        diff.dispatchUpdatesTo(this);
    }

    private boolean isSameItem(RecentItem a, RecentItem b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.id == null || b.id == null) {
            return false;
        }
        // 比较关键字段
        return a.id.equals(b.id) &&
               a.isGroup == b.isGroup &&
               a.isChannel == b.isChannel &&
               a.isSystemNotification == b.isSystemNotification &&
               a.isFoldedFolder == b.isFoldedFolder &&
               a.folded == b.folded &&
                a.unreadCount == b.unreadCount &&
                a.mentionUnread == b.mentionUnread &&
                a.pinned == b.pinned &&
                a.timestamp == b.timestamp &&
                a.isOnline == b.isOnline &&
                equals(a.title, b.title) &&
                equals(a.subtitle, b.subtitle) &&
                equals(a.draftText, b.draftText) &&
                equals(a.avatarUrl, b.avatarUrl);
    }

    private boolean equals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_chat, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        super.onViewRecycled(holder);
        // 清除头像加载，防止头像串位
        if (holder.avatar != null) {
            holder.avatar.setImageDrawable(null);
            holder.avatar.setTag(null);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final RecentItem item = items.get(position);
        bindTime(holder.time, item.timestamp);
        bindPinned(holder.pinned, item.pinned);
        boolean isExpandedFoldChild = item.folded && !item.isFoldedFolder && !item.isSystemNotification;
        applyExpandedFoldVisual(holder, isExpandedFoldChild);

        // 先清除头像，防止复用时显示旧头像
        holder.avatar.setTag(null);

        boolean hideAvatar = com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(holder.itemView.getContext());
        if (holder.avatarContainer != null) {
            holder.avatarContainer.setVisibility(hideAvatar ? View.GONE : View.VISIBLE);
        }

        if (item.isFoldedFolder) {
            holder.text1.setText(item.title == null ? "折叠的聊天" : item.title);
            holder.text2.setText(item.subtitle == null ? "" : item.subtitle);
            if (holder.titleBadge != null) {
                String foldedTag = item.userTitle == null ? "" : item.userTitle.trim();
                if (foldedTag.length() == 0) {
                    com.im.oldchat.ui.UserTitleBinder.bind(holder.titleBadge, "");
                } else {
                    holder.titleBadge.setText(foldedTag);
                    if ("已展开".equals(foldedTag)) {
                        holder.titleBadge.setBackgroundResource(R.drawable.bg_chip_warning);
                        holder.titleBadge.setTextColor(holder.itemView.getResources().getColor(R.color.color_text_primary));
                    } else {
                        holder.titleBadge.setBackgroundResource(R.drawable.bg_chip_pending);
                        holder.titleBadge.setTextColor(holder.itemView.getResources().getColor(R.color.color_on_primary));
                    }
                    holder.titleBadge.setVisibility(View.VISIBLE);
                }
            }
            if (!hideAvatar) {
                holder.avatar.setImageResource(android.R.drawable.ic_menu_agenda);
            }
            if (holder.onlineDot != null) {
                holder.onlineDot.setVisibility(View.GONE);
            }
            holder.avatar.setOnClickListener(null);
            bindUnreadCount(holder.unreadCount, item.unreadCount);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onItemClick(item);
                    }
                }
            });
            holder.itemView.setOnLongClickListener(null);
            return;
        }

        // 系统通知项
        if (item.isSystemNotification) {
            holder.text1.setText(item.title == null ? "" : item.title);
            holder.text2.setText("官方公告和通知");
            if (holder.titleBadge != null) {
                com.im.oldchat.ui.UserTitleBinder.bind(holder.titleBadge, "");
            }
            if (!hideAvatar) {
                holder.avatar.setImageResource(R.drawable.ic_notification);
            }
            if (holder.onlineDot != null) {
                holder.onlineDot.setVisibility(View.GONE);
            }
            holder.avatar.setOnClickListener(null);
            bindUnreadCount(holder.unreadCount, item.unreadCount);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onItemClick(item);
                    }
                }
            });
            holder.itemView.setOnLongClickListener(null);
            return;
        }

        // 原有的群组和私聊逻辑
        String title = item.title == null ? "" : item.title;
        if (item.isGroup && item.groupMemberCount > 0) {
            title = title + "(" + item.groupMemberCount + ")";
        }
        holder.text1.setText(title);
        if (holder.titleBadge != null) {
            if (item.isGroup || item.isSystemNotification) {
                com.im.oldchat.ui.UserTitleBinder.bind(holder.titleBadge, "");
            } else {
                com.im.oldchat.ui.UserTitleBinder.bindCompact(holder.titleBadge, item.userTitle);
            }
        }
        String draft = item.draftText;
        if (draft != null && draft.trim().length() > 0) {
            String prefix = "[草稿]";
            String text = draft;
            if (text == null) {
                text = "";
            }
            if (text.length() > 0) {
                text = prefix + " " + text;
            } else {
                text = prefix;
            }
            SpannableString span = new SpannableString(text);
            span.setSpan(new ForegroundColorSpan(0xFFE53935), 0, prefix.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.text2.setText(span);
        } else if (item.isGroup && (item.subtitle == null || item.subtitle.isEmpty())) {
            holder.text2.setText("群聊");
        } else if (item.isGroup && item.mentionUnread) {
            String suffix = item.subtitle == null ? "" : item.subtitle;
            String prefix = "[有人@我]";
            String text = suffix.length() == 0 ? prefix : (prefix + " " + suffix);
            SpannableString span = new SpannableString(text);
            span.setSpan(new ForegroundColorSpan(0xFFE53935), 0, prefix.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.text2.setText(span);
        } else {
            holder.text2.setText(item.subtitle == null ? "" : item.subtitle);
        }
        
        // 加载头像 - 使用tag防止串位
        if (!hideAvatar) {
            if ((item.isGroup || item.isChannel) && (item.avatarUrl == null || item.avatarUrl.isEmpty())) {
                holder.avatar.setImageResource(R.drawable.group);
            } else if (item.avatarUrl == null || item.avatarUrl.isEmpty()) {
                holder.avatar.setImageResource(R.drawable.ic_avatar_placeholder);
            } else {
                // 设置tag用于防止头像串位
                holder.avatar.setTag(item.avatarUrl);
                if (avatarTracker != null) {
                    final String key = avatarTracker.buildKey(item);
                    if (avatarTracker.isLoaded(key)) {
                        ImageLoader.loadAvatar(holder.avatar, item.avatarUrl);
                    } else {
                        avatarTracker.markLoading(key);
                        final String avatarUrl = item.avatarUrl;
                        ImageLoader.loadAvatar(holder.avatar, item.avatarUrl, new com.im.oldchat.util.ImageLoader.ImageLoadListener() {
                            @Override
                            public void onComplete(String url) {
                                avatarTracker.markLoaded(key);
                            }
                        });
                    }
                } else {
                    ImageLoader.loadAvatar(holder.avatar, item.avatarUrl);
                }
            }
        }
        
        if (holder.onlineDot != null) {
            if (!hideAvatar && !item.isGroup && !item.isSystemNotification && !item.isFoldedFolder) {
                holder.onlineDot.setVisibility(View.VISIBLE);
                holder.onlineDot.setBackgroundResource(item.isOnline ? R.drawable.bg_online_dot : R.drawable.bg_offline_dot);
            } else {
                holder.onlineDot.setVisibility(View.GONE);
            }
        }
        
        holder.avatar.setOnClickListener(null);
        if (!item.isGroup && item.id != null && !item.id.isEmpty() && listener != null) {
            holder.avatar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onAvatarClick(item);
                }
            });
        }
        bindUnreadCount(holder.unreadCount, item.unreadCount);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onItemClick(item);
                }
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener == null) {
                    return false;
                }
                listener.onItemLongClick(v, item);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
    private void applyExpandedFoldVisual(ViewHolder holder, boolean isExpandedFoldChild) {
        if (holder == null) {
            return;
        }

        float titleSize = isExpandedFoldChild ? 14f : 15f;
        float subtitleSize = isExpandedFoldChild ? 11f : 12f;
        float timeSize = isExpandedFoldChild ? 10f : 11f;
        float badgeSize = isExpandedFoldChild ? 10f : 11f;
        float pinnedSize = isExpandedFoldChild ? 9f : 10f;

        holder.text1.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSize);
        holder.text2.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize);
        holder.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSize);
        holder.unreadCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, badgeSize);
        holder.pinned.setTextSize(TypedValue.COMPLEX_UNIT_SP, pinnedSize);

        updateAvatarSize(holder.avatar, isExpandedFoldChild ? AVATAR_SIZE_FOLDED_CHILD_DP : AVATAR_SIZE_NORMAL_DP);
        updateCardIndent(holder.card, isExpandedFoldChild ? FOLDED_CHILD_LEFT_INDENT_DP : 0);

        if (holder.card != null) {
            holder.card.setMinimumHeight(dp(holder.card, isExpandedFoldChild ? 66 : 72));
        }
    }

    private void updateAvatarSize(ImageView avatar, int sizeDp) {
        if (avatar == null) {
            return;
        }
        ViewGroup.LayoutParams params = avatar.getLayoutParams();
        if (params == null) {
            return;
        }
        int sizePx = dp(avatar, sizeDp);
        if (params.width == sizePx && params.height == sizePx) {
            return;
        }
        params.width = sizePx;
        params.height = sizePx;
        avatar.setLayoutParams(params);
    }

    private void updateCardIndent(View card, int leftDp) {
        if (card == null) {
            return;
        }
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
        int leftPx = dp(card, leftDp);
        if (marginParams.leftMargin == leftPx) {
            return;
        }
        marginParams.leftMargin = leftPx;
        card.setLayoutParams(marginParams);
    }

    private int dp(View view, int value) {
        if (view == null) {
            return value;
        }
        float density = view.getResources().getDisplayMetrics().density;
        return (int) (density * value + 0.5f);
    }

    private void bindTime(TextView timeView, long timestamp) {
        if (timeView == null) {
            return;
        }
        String text = ChatTimeFormatter.formatTime(timestamp);
        if (text == null || text.length() == 0) {
            timeView.setVisibility(View.GONE);
            return;
        }
        timeView.setText(text);
        timeView.setVisibility(View.VISIBLE);
    }

    private void bindUnreadCount(TextView unreadView, int unreadCount) {
        if (unreadView == null) {
            return;
        }
        if (unreadCount <= 0) {
            unreadView.setVisibility(View.GONE);
            return;
        }
        String label = unreadCount >= 50 ? "..." : String.valueOf(unreadCount);
        unreadView.setText(label);
        unreadView.setVisibility(View.VISIBLE);
    }

    private void bindPinned(TextView pinnedView, boolean pinned) {
        if (pinnedView == null) {
            return;
        }
        pinnedView.setVisibility(pinned ? View.VISIBLE : View.GONE);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1, text2;
        TextView titleBadge;
        TextView pinned;
        TextView time;
        ImageView avatar;
        View avatarContainer;
        TextView unreadCount;
        View card;
        View onlineDot;

        ViewHolder(View v) {
            super(v);
            text1 = v.findViewById(R.id.tvTitle);
            text2 = v.findViewById(R.id.tvSubtitle);
            titleBadge = v.findViewById(R.id.tvTitleBadge);
            pinned = v.findViewById(R.id.tvPinned);
            time = v.findViewById(R.id.tvTime);
            avatar = v.findViewById(R.id.ivAvatar);
            avatarContainer = v.findViewById(R.id.flRecentAvatar);
            unreadCount = v.findViewById(R.id.tvUnreadCount);
            card = v.findViewById(R.id.recentChatCard);
            onlineDot = v.findViewById(R.id.vRecentOnlineDot);
        }
    }
}
