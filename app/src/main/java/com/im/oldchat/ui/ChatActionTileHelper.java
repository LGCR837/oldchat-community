package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.support.v4.content.ContextCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.ui.widget.DiscoverTileLayout;
import com.im.oldchat.ui.widget.TiltFrameLayout;
import com.im.oldchat.util.SettingsPrefs;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天底部操作栏 Windows Phone / Metro 风格磁贴体系支持。
 */
public final class ChatActionTileHelper {
    private static final String PREFS_NAME = "chat_action_tools_layout";

    public static class ActionTileDef {
        public final int id;
        public final String title;
        public final int iconRes;
        public final int defaultSize;

        public ActionTileDef(int id, String title, int iconRes, int defaultSize) {
            this.id = id;
            this.title = title;
            this.iconRes = iconRes;
            this.defaultSize = defaultSize;
        }
    }

    private static final ActionTileDef[] TOOLS_DEFS = new ActionTileDef[]{
            new ActionTileDef(R.id.btnActionImage, "图片", R.drawable.ic_action_image, DiscoverTileLayout.SIZE_MEDIUM),
            new ActionTileDef(R.id.btnActionVideo, "视频", R.drawable.ic_action_video, DiscoverTileLayout.SIZE_MEDIUM),
            new ActionTileDef(R.id.btnActionRedPacket, "红包", R.drawable.ic_action_red_packet, DiscoverTileLayout.SIZE_MEDIUM),
            new ActionTileDef(R.id.btnActionBurn, "阅后即焚", R.drawable.ic_action_burn, DiscoverTileLayout.SIZE_MEDIUM)
    };

    private static final ActionTileDef[] FILE_DEFS = new ActionTileDef[]{
            new ActionTileDef(R.id.btnFileLosslessImage, "无损图片", R.drawable.ic_action_image, DiscoverTileLayout.SIZE_MEDIUM),
            new ActionTileDef(R.id.btnFileLosslessVideo, "无损视频", R.drawable.ic_action_video, DiscoverTileLayout.SIZE_MEDIUM),
            new ActionTileDef(R.id.btnFileSendDoc, "发送文件", R.drawable.ic_document, DiscoverTileLayout.SIZE_MEDIUM)
    };

    private ChatActionTileHelper() {
    }

    private static class TileMeta {
        final ActionTileDef def;
        final ImageView icon;
        final TextView label;
        final FrameLayout content;

        TileMeta(ActionTileDef def, ImageView icon, TextView label, FrameLayout content) {
            this.def = def;
            this.icon = icon;
            this.label = label;
            this.content = content;
        }
    }

    public static void init(final Activity activity, final View actionPanelRoot) {
        if (activity == null || actionPanelRoot == null) return;
        setupTileGrid(activity, actionPanelRoot, R.id.chatActionTileGrid, TOOLS_DEFS, "tools_");
        setupTileGrid(activity, actionPanelRoot, R.id.chatFileTileGrid, FILE_DEFS, "file_");
    }

    static void setupTileGrid(final Activity activity, final View actionPanelRoot,
                              int gridResId, final ActionTileDef[] defs, final String prefPrefix) {
        final DiscoverTileLayout tileGrid = (DiscoverTileLayout) actionPanelRoot.findViewById(gridResId);
        if (tileGrid == null) return;

        final SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<ActionTileDef> orderedDefs = getOrderedDefs(prefs, defs, prefPrefix);

        for (int i = 0; i < orderedDefs.size(); i++) {
            final ActionTileDef def = orderedDefs.get(i);
            View existing = tileGrid.findViewById(def.id);
            TiltFrameLayout tileView;
            if (existing instanceof TiltFrameLayout) {
                tileView = (TiltFrameLayout) existing;
            } else {
                tileView = new TiltFrameLayout(activity);
                tileView.setId(def.id);
                DiscoverTileLayout.LayoutParams lp = new DiscoverTileLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tileGrid.addView(tileView, lp);
            }

            int fixedSize = DiscoverTileLayout.SIZE_MEDIUM;
            TileMeta meta = buildTileContent(activity, tileView, def);
            tileView.setTag(meta);
            tileGrid.setTileSize(tileView, fixedSize);
            applyTileVisual(activity, tileView, meta, fixedSize);

            final TiltFrameLayout currentTile = tileView;
            tileView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    ChatActionTileDialogHelper.showTileOptionsDialog(activity, tileGrid, currentTile, def, prefs, defs, prefPrefix);
                    return true;
                }
            });
        }

        ChatActionTileDialogHelper.applySavedOrder(tileGrid, prefs, prefPrefix);
    }

    private static List<ActionTileDef> getOrderedDefs(SharedPreferences prefs, ActionTileDef[] allDefs, String prefPrefix) {
        List<ActionTileDef> list = new ArrayList<ActionTileDef>();
        String orderStr = prefs.getString(prefPrefix + "order", "");
        if (orderStr != null && orderStr.length() > 0) {
            String[] parts = orderStr.split(",");
            for (String p : parts) {
                try {
                    int id = Integer.parseInt(p.trim());
                    ActionTileDef def = findDefById(allDefs, id);
                    if (def != null && !list.contains(def)) list.add(def);
                } catch (Throwable ignored) {}
            }
        }
        for (ActionTileDef def : allDefs) {
            if (!list.contains(def)) list.add(def);
        }
        return list;
    }

    private static ActionTileDef findDefById(ActionTileDef[] allDefs, int id) {
        for (ActionTileDef def : allDefs) {
            if (def.id == id) return def;
        }
        return null;
    }

    private static TileMeta buildTileContent(Activity activity, TiltFrameLayout tile, ActionTileDef def) {
        tile.removeAllViews();
        tile.setPadding(0, 0, 0, 0);

        FrameLayout content = new FrameLayout(activity);
        tile.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(def.iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (def.id == R.id.btnActionBurn) icon.setId(R.id.ivActionBurnIcon);
        content.addView(icon);

        TextView label = new TextView(activity);
        label.setText(def.title);
        label.setSingleLine(true);
        if (def.id == R.id.btnActionBurn) label.setId(R.id.tvActionBurnLabel);
        content.addView(label);

        return new TileMeta(def, icon, label, content);
    }

    private static void applyTileVisual(Activity activity, View tileView, TileMeta meta, int size) {
        if (tileView == null || meta == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        float radius = SettingsPrefs.isRoundedButtonsEnabled(activity) ? 8f * density : 0f;

        boolean isDark = SettingsPrefs.isDarkModeEnabled(activity);
        int bgColor = isDark ? 0xFF1C2026 : 0xFFF3F4F6;
        int strokeColor = isDark ? 0x26FFFFFF : 0x1A000000;
        int textColor = ContextCompat.getColor(activity, R.color.colorTextSecondary);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(radius);
        bg.setStroke((int) Math.max(1, density), strokeColor);
        tileView.setBackgroundDrawable(bg);

        meta.icon.clearColorFilter();
        meta.label.setTextColor(textColor);

        int dp8 = (int) (8 * density);
        int dp6 = (int) (6 * density);
        int dp10 = (int) (10 * density);
        int dp12 = (int) (12 * density);

        FrameLayout.LayoutParams iconParams;
        FrameLayout.LayoutParams labelParams;

        if (size == DiscoverTileLayout.SIZE_SMALL) {
            int iconSize = (int) (24 * density);
            iconParams = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
            labelParams = new FrameLayout.LayoutParams(1, 1);
            meta.label.setVisibility(View.GONE);
        } else if (size == DiscoverTileLayout.SIZE_LARGE) {
            int iconSize = (int) (36 * density);
            iconParams = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER_VERTICAL | Gravity.LEFT);
            iconParams.leftMargin = dp12;
            labelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.BOTTOM);
            labelParams.leftMargin = dp12;
            labelParams.rightMargin = dp12;
            labelParams.bottomMargin = dp10;
            meta.label.setTextSize(14f);
            meta.label.setVisibility(View.VISIBLE);
        } else {
            int iconSize = (int) (32 * density);
            iconParams = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
            labelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.BOTTOM);
            labelParams.leftMargin = dp8;
            labelParams.rightMargin = dp8;
            labelParams.bottomMargin = dp6;
            meta.label.setTextSize(12f);
            meta.label.setVisibility(View.VISIBLE);
        }

        meta.icon.setLayoutParams(iconParams);
        meta.label.setLayoutParams(labelParams);
    }
}
