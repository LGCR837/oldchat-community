package com.im.oldchat.ui.fragments;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.ui.widget.RoundedImageView;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

final class PastelFoldedTileRenderer {
    private static final int COLUMNS = 6;
    private static final String EMPTY_TAG = "pastel_empty_folded";

    private PastelFoldedTileRenderer() {
    }

    static void render(View root, LinearLayout parent, List<RecentItem> items, Typeface roundedBold,
                       PastelHomeUiController.Callbacks callbacks) {
        if (items == null || items.isEmpty()) {
            addEmptyHint(root, parent);
            return;
        }
        int gap = dp(root, 4);
        int contentWidth = resolveContentWidth(root, parent);
        int tileSize = (contentWidth - gap * (COLUMNS - 1)) / COLUMNS;
        if (tileSize < dp(root, 34)) {
            tileSize = dp(root, 34);
        }
        int rowCount = (items.size() + COLUMNS - 1) / COLUMNS;
        int index = 0;
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            LinearLayout row = obtainRow(parent, rowIndex, gap);
            for (int col = 0; col < COLUMNS; col++) {
                View tile;
                if (index < items.size()) {
                    tile = obtainTile(root, row, col);
                    bindTile(root, tile, items.get(index), roundedBold, callbacks);
                    index++;
                } else {
                    tile = obtainPlaceholder(row, col);
                }
                LinearLayout.LayoutParams childLp = new LinearLayout.LayoutParams(0, tileSize, 1f);
                if (col > 0) {
                    childLp.leftMargin = gap;
                }
                tile.setLayoutParams(childLp);
            }
        }
        trimRows(parent, rowCount);
    }

    private static void bindTile(View root, View tile, final RecentItem item, Typeface roundedBold,
                                 final PastelHomeUiController.Callbacks callbacks) {
        TileHolder holder = (TileHolder) tile.getTag();
        if (holder == null) {
            holder = new TileHolder(tile);
            tile.setTag(holder);
        }
        boolean mentionUnread = item.isGroup && item.mentionUnread;
        boolean highlighted = mentionUnread || item.unreadCount > 0;

        if (roundedBold != null && holder.tvTitle != null) {
            holder.tvTitle.setTypeface(roundedBold);
        }
        holder.tvTitle.setText(item.title == null ? "" : item.title);
        if (mentionUnread) {
            holder.tvSubtitle.setVisibility(View.VISIBLE);
            holder.tvSubtitle.setText("@我");
            holder.tvSubtitle.setTextColor(color(root, R.color.colorDanger));
        } else if (item.unreadCount > 0) {
            holder.tvSubtitle.setVisibility(View.VISIBLE);
            holder.tvSubtitle.setText("新消息");
            holder.tvSubtitle.setTextColor(color(root, R.color.color_on_primary));
        } else {
            holder.tvSubtitle.setVisibility(View.GONE);
        }
        holder.tvTitle.setTextColor(highlighted
                ? color(root, R.color.color_on_primary)
                : color(root, R.color.color_text_primary));

        if (item.unreadCount > 0) {
            holder.tvUnread.setVisibility(View.VISIBLE);
            holder.tvUnread.setText(item.unreadCount >= 50 ? "..." : String.valueOf(item.unreadCount));
        } else {
            holder.tvUnread.setVisibility(View.GONE);
        }

        if (item.isSystemNotification) {
            holder.avatar.setImageResource(R.mipmap.ic_launcher);
            holder.onlineDot.setVisibility(View.GONE);
        } else if (item.isGroup) {
            ImageLoader.loadAvatar(holder.avatar, item.avatarUrl);
            holder.onlineDot.setVisibility(View.GONE);
        } else {
            ImageLoader.loadAvatar(holder.avatar, item.avatarUrl);
            holder.onlineDot.setVisibility(View.VISIBLE);
            holder.onlineDot.setBackgroundResource(com.im.oldchat.util.PresenceUiHelper.resolveDotDrawable(item.isOnline, item.presenceStatus));
        }
        final View avatarView = holder.avatar;
        applyTileBackground(root, holder, highlighted);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) {
                    callbacks.onRecentClick(item, avatarView);
                }
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (callbacks != null) {
                    callbacks.onRecentLongClick(v, item);
                }
                return true;
            }
        });
    }

    private static void applyTileBackground(View root, TileHolder holder, boolean highlighted) {
        boolean minimalDark = ThemeColorUtil.isMinimalDark(root.getContext());
        int fill = minimalDark
                ? (highlighted ? 0xFF24272A : 0xFF151719)
                : (highlighted ? ThemeColorUtil.getPrimaryColor(root.getContext())
                : color(root, R.color.color_surface));
        int stroke = minimalDark
                ? 0xFF303337
                : (highlighted ? ThemeColorUtil.getPrimaryDarkColor(root.getContext())
                : color(root, R.color.color_surface_stroke));
        holder.background.setColor(fill);
        holder.background.setStroke(dp(root, 1), stroke);
    }

    private static LinearLayout obtainRow(LinearLayout parent, int rowIndex, int gap) {
        View child = rowIndex < parent.getChildCount() ? parent.getChildAt(rowIndex) : null;
        if (!(child instanceof LinearLayout) || EMPTY_TAG.equals(child.getTag())) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = gap;
            row.setLayoutParams(rowLp);
            if (child == null) {
                parent.addView(row);
            } else {
                parent.removeViewAt(rowIndex);
                parent.addView(row, rowIndex);
            }
            return row;
        }
        LinearLayout row = (LinearLayout) child;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.bottomMargin = gap;
        row.setLayoutParams(lp);
        return row;
    }

    private static View obtainTile(View root, LinearLayout row, int index) {
        View child = index < row.getChildCount() ? row.getChildAt(index) : null;
        if (!(child != null && child.getTag() instanceof TileHolder)) {
            View tile = View.inflate(row.getContext(), R.layout.item_pastel_folded_tile, null);
            TileHolder holder = new TileHolder(tile);
            tile.setTag(holder);
            if (child == null) {
                row.addView(tile);
            } else {
                row.removeViewAt(index);
                row.addView(tile, index);
            }
            return tile;
        }
        return child;
    }

    private static View obtainPlaceholder(LinearLayout row, int index) {
        View child = index < row.getChildCount() ? row.getChildAt(index) : null;
        if (child != null && EMPTY_TAG.equals(child.getTag())) {
            child.setOnClickListener(null);
            child.setOnLongClickListener(null);
            return child;
        }
        View placeholder = new View(row.getContext());
        placeholder.setTag(EMPTY_TAG);
        if (child == null) {
            row.addView(placeholder);
        } else {
            row.removeViewAt(index);
            row.addView(placeholder, index);
        }
        return placeholder;
    }

    private static void trimRows(LinearLayout parent, int keepCount) {
        while (parent.getChildCount() > keepCount) {
            parent.removeViewAt(parent.getChildCount() - 1);
        }
    }

    private static int resolveContentWidth(View root, View parent) {
        int width = parent.getWidth();
        if (width > 0 && parent instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) parent;
            width = width - ll.getPaddingLeft() - ll.getPaddingRight();
        }
        if (width <= 0) {
            width = root.getResources().getDisplayMetrics().widthPixels - dp(root, 52);
        }
        if (width <= 0) {
            width = dp(root, 280);
        }
        return width;
    }

    private static void addEmptyHint(View root, LinearLayout parent) {
        if (parent.getChildCount() == 1) {
            View child = parent.getChildAt(0);
            if (child instanceof TextView && EMPTY_TAG.equals(child.getTag())) {
                ((TextView) child).setText("暂无折叠会话");
                return;
            }
        }
        parent.removeAllViews();
        TextView tv = new TextView(root.getContext());
        tv.setTag(EMPTY_TAG);
        tv.setText("暂无折叠会话");
        tv.setTextSize(12f);
        tv.setTextColor(root.getResources().getColor(R.color.home_empty_text));
        int pad = dp(root, 6);
        tv.setPadding(pad, pad, pad, dp(root, 8));
        parent.addView(tv);
    }

    private static int dp(View root, int value) {
        return (int) (root.getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private static int color(View root, int resId) {
        return root.getResources().getColor(resId);
    }

    private static final class TileHolder {
        final View card;
        final RoundedImageView avatar;
        final TextView tvTitle;
        final TextView tvSubtitle;
        final TextView tvUnread;
        final View onlineDot;
        final GradientDrawable background;

        TileHolder(View tile) {
            card = tile.findViewById(R.id.foldedTileRoot);
            avatar = (RoundedImageView) tile.findViewById(R.id.ivFoldedTileAvatar);
            tvTitle = (TextView) tile.findViewById(R.id.tvFoldedTileTitle);
            tvSubtitle = (TextView) tile.findViewById(R.id.tvFoldedTileSubtitle);
            tvUnread = (TextView) tile.findViewById(R.id.tvFoldedTileUnread);
            onlineDot = tile.findViewById(R.id.vFoldedTileOnlineDot);
            background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(0f);
            card.setBackgroundDrawable(background);
        }
    }
}
