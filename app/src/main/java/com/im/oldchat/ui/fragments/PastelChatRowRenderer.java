package com.im.oldchat.ui.fragments;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.ui.ChatTimeFormatter;
import com.im.oldchat.ui.widget.TiltFrameLayout;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

final class PastelChatRowRenderer {
    /** 仅用于从主页复杂分组层级中识别真正的会话行。 */
    interface RowHolderMarker { }

    private static final String EMPTY_TAG = "pastel_empty_chat";

    private PastelChatRowRenderer() {
    }

    static void render(View root, LinearLayout parent, List<RecentItem> data, int rowLayoutRes,
                       Typeface roundedBold, boolean autoMorph, PastelHomeUiController.Callbacks callbacks) {
        if (data == null || data.isEmpty()) {
            renderEmpty(root, parent, "暂无会话");
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(root.getContext());
        for (int i = 0; i < data.size(); i++) {
            View row = obtainRow(inflater, parent, i, rowLayoutRes, autoMorph);
            bindRow(root, row, data.get(i), roundedBold, callbacks);
        }
        trimChildren(parent, data.size());
    }

    private static View obtainRow(LayoutInflater inflater, LinearLayout parent, int index,
                                  int rowLayoutRes, boolean autoMorph) {
        View child = index < parent.getChildCount() ? parent.getChildAt(index) : null;
        if (!(child != null && child.getTag() instanceof RowHolder)) {
            View row = inflater.inflate(rowLayoutRes, parent, false);
            if (row instanceof TiltFrameLayout) {
                ((TiltFrameLayout) row).setAutoMorphEnabled(autoMorph);
            }
            row.setTag(new RowHolder(row));
            if (child == null) {
                parent.addView(row);
            } else {
                parent.removeViewAt(index);
                parent.addView(row, index);
            }
            return row;
        }
        if (child instanceof TiltFrameLayout) {
            ((TiltFrameLayout) child).setAutoMorphEnabled(autoMorph);
        }
        return child;
    }

    private static void bindRow(View root, View row, final RecentItem item, Typeface roundedBold,
                                final PastelHomeUiController.Callbacks callbacks) {
        RowHolder holder = (RowHolder) row.getTag();
        if (holder == null) {
            holder = new RowHolder(row);
            row.setTag(holder);
        }
        if (roundedBold != null) {
            holder.tvTitle.setTypeface(roundedBold);
        }
        holder.tvTitle.setText(item.title == null ? "" : item.title);
        boolean minimalDark = ThemeColorUtil.isMinimalDark(root.getContext());
        if (holder.classicSurface != null) {
            if (minimalDark) {
                holder.classicSurface.setBackgroundResource(R.drawable.bg_minimal_dark_row);
            } else if (item.folded) {
                holder.classicSurface.setBackgroundResource(R.drawable.bg_classic_home_folded);
            } else if (item.pinned) {
                holder.classicSurface.setBackgroundResource(R.drawable.bg_classic_home_pinned);
            } else {
                holder.classicSurface.setBackgroundResource(R.drawable.bg_friend_row_native);
            }
        } else if (minimalDark) {
            row.setBackgroundResource(R.drawable.bg_minimal_dark_row);
        }
        boolean mentionUnread = item.isGroup && item.mentionUnread;
        holder.tvAt.setVisibility(mentionUnread ? View.VISIBLE : View.GONE);
        String subtitle = item.subtitle == null ? "" : item.subtitle;
        holder.tvSub.setText((mentionUnread ? "\u0040\u6211 " : "") + subtitle);
        holder.tvSub.setTextColor(root.getResources().getColor(
                mentionUnread ? R.color.home_row_subtitle_mention : R.color.home_row_subtitle_text));
        String time = ChatTimeFormatter.formatTime(item.timestamp);
        holder.tvTime.setText(time == null || time.length() == 0 ? "--:--" : time);
        if (item.unreadCount > 0) {
            holder.tvUnread.setVisibility(View.VISIBLE);
            holder.tvUnread.setText(item.unreadCount >= 50 ? "..." : String.valueOf(item.unreadCount));
        } else {
            holder.tvUnread.setVisibility(View.GONE);
        }
        if (item.isSystemNotification) {
            holder.tvTag.setVisibility(View.VISIBLE);
            holder.tvTag.setText("系统");
            holder.avatar.setImageResource(R.mipmap.ic_launcher);
            holder.onlineDot.setVisibility(View.GONE);
        } else if (item.isGroup) {
            holder.tvTag.setVisibility(View.VISIBLE);
            holder.tvTag.setText("群聊");
            ImageLoader.loadAvatar(holder.avatar, item.avatarUrl);
            holder.onlineDot.setVisibility(View.GONE);
        } else {
            holder.tvTag.setVisibility(View.GONE);
            ImageLoader.loadAvatar(holder.avatar, item.avatarUrl);
            holder.onlineDot.setVisibility(View.VISIBLE);
            holder.onlineDot.setBackgroundResource(com.im.oldchat.util.PresenceUiHelper.resolveDotDrawable(item.isOnline, item.presenceStatus));
        }
        int width = dp(root, Math.min(90, 42 + Math.max(0, item.unreadCount) * 4));
        ViewGroup.LayoutParams lp = holder.preview.getLayoutParams();
        if (lp != null && lp.width != width) {
            lp.width = width;
            holder.preview.setLayoutParams(lp);
        }
        final View avatarView = holder.avatar;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) {
                    callbacks.onRecentClick(item, avatarView);
                }
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (callbacks != null) {
                    callbacks.onRecentLongClick(v, item);
                }
                return true;
            }
        });
        HomeNeighborRipplePress.bind(row);
    }

    private static void renderEmpty(View root, LinearLayout parent, String text) {
        if (parent.getChildCount() == 1) {
            View child = parent.getChildAt(0);
            if (child instanceof TextView && EMPTY_TAG.equals(child.getTag())) {
                ((TextView) child).setText(text);
                return;
            }
        }
        parent.removeAllViews();
        TextView tv = new TextView(root.getContext());
        tv.setTag(EMPTY_TAG);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(root.getResources().getColor(R.color.home_empty_text));
        int pad = dp(root, 6);
        tv.setPadding(pad, pad, pad, dp(root, 8));
        parent.addView(tv);
    }

    private static void trimChildren(LinearLayout parent, int keepCount) {
        while (parent.getChildCount() > keepCount) {
            parent.removeViewAt(parent.getChildCount() - 1);
        }
    }

    private static int dp(View root, int value) {
        return (int) (root.getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private static final class RowHolder implements RowHolderMarker {
        final TextView tvTitle;
        final TextView tvAt;
        final TextView tvTag;
        final TextView tvSub;
        final TextView tvTime;
        final TextView tvUnread;
        final View preview;
        final ImageView avatar;
        final View onlineDot;
        final View classicSurface;

        RowHolder(View row) {
            tvTitle = (TextView) row.findViewById(R.id.tvPastelTitle);
            tvAt = (TextView) row.findViewById(R.id.tvPastelAt);
            tvTag = (TextView) row.findViewById(R.id.tvPastelTag);
            tvSub = (TextView) row.findViewById(R.id.tvPastelSubtitle);
            tvTime = (TextView) row.findViewById(R.id.tvPastelTime);
            tvUnread = (TextView) row.findViewById(R.id.tvPastelUnread);
            preview = row.findViewById(R.id.vPastelPreviewBar);
            avatar = (ImageView) row.findViewById(R.id.ivPastelAvatar);
            onlineDot = row.findViewById(R.id.vPastelOnlineDot);
            classicSurface = row.findViewById(R.id.classicRowSurface);
        }
    }
}
