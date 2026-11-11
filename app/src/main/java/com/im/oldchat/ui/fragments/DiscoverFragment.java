package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.data.MomentNoticeStore;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.ui.DailyCheckInWallActivity;
import com.im.oldchat.ui.ScratchActivity;
import com.im.oldchat.ui.DiscoverMonetPalette;
import com.im.oldchat.ui.DiscoverSettingsActivity;
import com.im.oldchat.ui.EmojiPlazaActivity;
import com.im.oldchat.ui.MinimalNewsActivity;
import com.im.oldchat.ui.MomentsActivity;
import com.im.oldchat.ui.MusicPlazaActivity;
import com.im.oldchat.ui.OldViewActivity;
import com.im.oldchat.ui.PublicCourtActivity;
import com.im.oldchat.ui.ReportProgressActivity;
import com.im.oldchat.ui.ResourceSectionsActivity;
import com.im.oldchat.ui.widget.DiscoverTileLayout;
import com.im.oldchat.ui.widget.TiltFrameLayout;
import com.im.oldchat.util.SettingsPrefs;

import java.util.ArrayList;
import java.util.List;

public class DiscoverFragment extends Fragment {
    private static final int REQUEST_IMPORT_CIP = 6401;
    private View btnDiscoverOldView;
    private View btnDiscoverPublicCourt;
    private View btnDiscoverCheckIn;
    private View btnDiscoverScratch;
    private View momentsDot;
    private ImageView ivDiscoverIconEmoji;
    private ImageView ivDiscoverIconMinimalNews;
    private ImageView ivDiscoverIconOldView;
    private ImageView ivDiscoverIconSettings;
    private View rootView;
    private LinearLayout luaAppsContainer;
    private DiscoverTileLayout tileGrid;
    private LinearLayout classicList;
    private SharedPreferences tilePreferences;
    private boolean classicLayoutApplied;
    private View editingTile;
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discover, container, false);
        if (com.im.oldchat.util.ThemeColorUtil.isMinimalDark(view.getContext())) {
            view.setBackgroundColor(0xFF08090A);
        }
        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rootView = view;
        setupWpTileGrid(view);

        View btnDiscoverMoments = view.findViewById(R.id.btnDiscoverMoments);
        View btnDiscoverEmoji = view.findViewById(R.id.btnDiscoverEmoji);
        View btnDiscoverMusic = view.findViewById(R.id.btnDiscoverMusic);
        View btnDiscoverResources = view.findViewById(R.id.btnDiscoverResources);
        View btnDiscoverMinimalNews = view.findViewById(R.id.btnDiscoverMinimalNews);
        btnDiscoverOldView = view.findViewById(R.id.btnDiscoverOldView);
        btnDiscoverCheckIn = view.findViewById(R.id.btnDiscoverCheckIn);
        btnDiscoverScratch = view.findViewById(R.id.btnDiscoverScratch);
        View btnDiscoverReport = view.findViewById(R.id.btnDiscoverReport);
        btnDiscoverPublicCourt = view.findViewById(R.id.btnDiscoverPublicCourt);
        View btnDiscoverSettings = view.findViewById(R.id.btnDiscoverSettings);
        View btnDiscoverMiniApps = view.findViewById(R.id.btnDiscoverMiniApps);
        View btnDiscoverLua = view.findViewById(R.id.btnDiscoverLua);
        View btnDiscoverCipDeveloper = view.findViewById(R.id.btnDiscoverCipDeveloper);
        View btnImportLuaCip = view.findViewById(R.id.btnImportLuaCip);
        View btnLuaCipDocs = view.findViewById(R.id.btnLuaCipDocs);
        momentsDot = view.findViewById(R.id.viewDiscoverMomentsDot);
        ivDiscoverIconEmoji = (ImageView) view.findViewById(R.id.ivDiscoverIconEmoji);
        ivDiscoverIconMinimalNews = (ImageView) view.findViewById(R.id.ivDiscoverIconMinimalNews);
        ivDiscoverIconOldView = (ImageView) view.findViewById(R.id.ivDiscoverIconOldView);
        ivDiscoverIconSettings = (ImageView) view.findViewById(R.id.ivDiscoverIconSettings);
        luaAppsContainer = (LinearLayout) view.findViewById(R.id.luaAppsContainer);
        renderLuaApps(com.im.oldchat.lua.LuaAppStore.getApps(view.getContext()));

        bindOpen(btnDiscoverMoments, MomentsActivity.class);
        bindOpen(btnDiscoverEmoji, EmojiPlazaActivity.class);
        bindOpen(btnDiscoverMusic, MusicPlazaActivity.class);
        bindOpen(btnDiscoverResources, ResourceSectionsActivity.class);
        bindOpen(btnDiscoverMinimalNews, MinimalNewsActivity.class);
        bindOpen(btnDiscoverOldView, OldViewActivity.class);
        bindOpen(btnDiscoverReport, ReportProgressActivity.class);
        bindOpen(btnDiscoverPublicCourt, PublicCourtActivity.class);
        bindOpen(btnDiscoverSettings, DiscoverSettingsActivity.class);
        bindOpen(btnDiscoverMiniApps, com.im.oldchat.ui.MiniAppsActivity.class);
        bindOpen(btnDiscoverCipDeveloper, com.im.oldchat.ui.CipDeveloperActivity.class);
        if (btnDiscoverLua != null) {
            btnDiscoverLua.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (getActivity() == null) return;
                    // 点击前再次读取，自动修复旧版本遗留的空缓存或缺失脚本。
                    com.im.oldchat.lua.LuaAppStore.getApps(getActivity());
                    Intent intent = new Intent(getActivity(), com.im.oldchat.ui.LuaMiniAppActivity.class);
                    intent.putExtra(com.im.oldchat.ui.LuaMiniAppActivity.EXTRA_APP_ID, "welcome");
                    startActivity(intent);
                }
            });
        }

        if (btnImportLuaCip != null) {
            btnImportLuaCip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { chooseLocalCip(); }
            });
        }
        if (btnLuaCipDocs != null) {
            btnLuaCipDocs.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openLuaDocs(); }
            });
        }

        if (btnDiscoverCheckIn != null) {
            btnDiscoverCheckIn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (consumeTileEditClick()) return;
                    open(DailyCheckInWallActivity.class);
                }
            });
        }

        if (btnDiscoverScratch != null) {
            btnDiscoverScratch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (consumeTileEditClick()) return;
                    open(ScratchActivity.class);
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        syncEntryVisibility();
        ensurePinnedCipAppTiles(rootView);
        applyDiscoverLayoutMode(true);
        reloadTileSizes();
        refreshMomentsBadge();
        if (getActivity() != null) {
            // 本地应用先同步渲染，远程请求不能阻塞或覆盖本地入口。
            renderLuaApps(com.im.oldchat.lua.LuaAppStore.getApps(getActivity()));
            com.im.oldchat.lua.LuaAppSyncManager.sync(getActivity(),
                    new com.im.oldchat.lua.LuaAppSyncManager.Callback() {
                        @Override public void onComplete(java.util.List<com.im.oldchat.lua.LuaMiniApp> apps) {
                            if (isAdded()) renderLuaApps(apps);
                        }
                    });
        }
    }

    private void setupWpTileGrid(View root) {
        if (getActivity() == null || root == null) {
            return;
        }
        tileGrid = (DiscoverTileLayout) root.findViewById(R.id.discoverTileGrid);
        classicList = (LinearLayout) root.findViewById(R.id.discoverClassicList);
        if (tileGrid == null) {
            return;
        }
        tilePreferences = getActivity().getSharedPreferences(
                "discover_tile_layout", Context.MODE_PRIVATE);
        View socialSection = root.findViewById(R.id.tvDiscoverSocialSection);
        View toolsSection = root.findViewById(R.id.tvDiscoverToolsSection);
        if (socialSection != null) socialSection.setVisibility(View.GONE);
        if (toolsSection != null) toolsSection.setVisibility(View.GONE);

        addWpTile(root, R.id.btnDiscoverMoments, R.id.ivDiscoverMomentsIcon,
                "朋友圈", R.drawable.ic_discover_moments, 0xFF0078D7,
                DiscoverTileLayout.SIZE_LARGE, true);
        addWpTile(root, R.id.btnDiscoverEmoji, R.id.ivDiscoverIconEmoji,
                "表情广场", R.drawable.ic_discover_emoji_plaza, 0xFFE81123,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverMusic, R.id.ivDiscoverIconMusic,
                "音乐广场", R.drawable.ic_discover_music, 0xFF881798,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverResources, R.id.ivDiscoverIconResources,
                "资源广场", R.drawable.ic_document, 0xFF107C10,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverMinimalNews, R.id.ivDiscoverIconMinimalNews,
                "极简简讯", R.drawable.ic_minimal_news, 0xFF00B7C3,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverOldView, R.id.ivDiscoverIconOldView,
                "旧视界", R.drawable.ic_discover_old_view, 0xFF5C2D91,
                DiscoverTileLayout.SIZE_SMALL, false);
        addWpTile(root, R.id.btnDiscoverCheckIn, R.id.ivDiscoverIconCheckIn,
                "每日签到", R.drawable.ic_discover_qiandao, 0xFFFF8C00,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverScratch, R.id.ivDiscoverIconScratch,
                "每日刮刮乐", R.drawable.ic_discover_qiandao, 0xFFD4A017,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverReport, R.id.ivDiscoverIconReport,
                "举报进度", R.drawable.ic_discover_report, 0xFFD13438,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverPublicCourt, R.id.ivDiscoverIconPublicCourt,
                "公开法庭", R.drawable.ic_discover_fating, 0xFF4A5459,
                DiscoverTileLayout.SIZE_SMALL, false);
        addWpTile(root, R.id.btnDiscoverMiniApps, R.id.ivMiniAppRowIcon,
                "小程序", R.drawable.ic_mini_app, 0xFF008272,
                DiscoverTileLayout.SIZE_MEDIUM, false);
        addWpTile(root, R.id.btnDiscoverSettings, R.id.ivDiscoverIconSettings,
                "发现设置", R.drawable.ic_setting_appearance, 0xFF68768A,
                DiscoverTileLayout.SIZE_SMALL, false);
        ensurePinnedCipAppTiles(root);
        applySavedTileOrder();
        applyDiscoverLayoutMode(true);
    }

    private int generateCipTileId(String appId) {
        if (appId == null) return 0x70000001;
        return 0x70000000 | (Math.abs(appId.hashCode()) & 0x0FFFFFFF);
    }

    private int generateTileColor(String appId) {
        if (getActivity() != null && SettingsPrefs.isDiscoverRandomSolidColorEnabled(getActivity())) {
            return DiscoverMonetPalette.generateSolidColorForString(appId,
                    com.im.oldchat.util.ThemeColorUtil.isDarkAppearance(getActivity()));
        }
        if (appId == null) return 0xFF008272;
        int hash = Math.abs(appId.hashCode());
        int[] palette = new int[]{
                0xFF008272, 0xFF0078D7, 0xFF107C10, 0xFF881798,
                0xFFD13438, 0xFF00B7C3, 0xFFFF8C00, 0xFF5C2D91
        };
        return palette[hash % palette.length];
    }

    private void ensurePinnedCipAppTiles(View root) {
        if (getActivity() == null || root == null || tileGrid == null) return;
        String raw = tilePreferences == null ? "" : tilePreferences.getString("pinned_cip_app_ids", "");
        if (raw == null || raw.length() == 0) return;
        String[] appIds = raw.split(",");
        java.util.List<com.im.oldchat.lua.LuaMiniApp> allApps = com.im.oldchat.lua.LuaAppStore.getApps(getActivity());
        for (String idStr : appIds) {
            final String appId = idStr.trim();
            if (appId.length() == 0) continue;
            com.im.oldchat.lua.LuaMiniApp foundApp = null;
            if (allApps != null) {
                for (com.im.oldchat.lua.LuaMiniApp a : allApps) {
                    if (a != null && appId.equals(a.id)) {
                        foundApp = a;
                        break;
                    }
                }
            }
            if (foundApp == null) {
                foundApp = new com.im.oldchat.lua.LuaMiniApp();
                foundApp.id = appId;
                foundApp.name = appId;
            }
            addCipAppWpTile(root, foundApp);
        }
    }

    private void addCipAppWpTile(View root, final com.im.oldchat.lua.LuaMiniApp app) {
        if (root == null || app == null || app.id == null || tileGrid == null) return;
        int tileId = generateCipTileId(app.id);

        View existing = root.findViewById(tileId);
        final TiltFrameLayout tileView = (existing instanceof TiltFrameLayout)
                ? (TiltFrameLayout) existing : new TiltFrameLayout(root.getContext());
        tileView.setId(tileId);

        if (tileView.getParent() == null && tileGrid != null) {
            DiscoverTileLayout.LayoutParams params = new DiscoverTileLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tileGrid.addView(tileView, params);
        }

        int tileColor = generateTileColor(app.id);
        int savedColor = tilePreferences == null ? tileColor : tilePreferences.getInt(colorPreferenceKey(tileId), tileColor);

        TileMeta meta = buildCipTileContent(tileView, app, savedColor);
        meta.defaultSize = DiscoverTileLayout.SIZE_MEDIUM;
        tileView.setTag(meta);

        tileView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (consumeTileEditClick()) return;
                Intent intent = new Intent(getActivity(), com.im.oldchat.ui.LuaMiniAppActivity.class);
                intent.putExtra(com.im.oldchat.ui.LuaMiniAppActivity.EXTRA_APP_ID, app.id);
                startActivity(intent);
            }
        });

        tileView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showCipTileOptionsDialog(app, tileView);
                return true;
            }
        });

        int savedSize = tilePreferences == null ? DiscoverTileLayout.SIZE_MEDIUM
                : tilePreferences.getInt(tilePreferenceKey(tileId), DiscoverTileLayout.SIZE_MEDIUM);
        tileGrid.setTileSize(tileView, savedSize);
        applyTileVisual(tileView);
    }

    private TileMeta buildCipTileContent(TiltFrameLayout tile, com.im.oldchat.lua.LuaMiniApp app, int color) {
        tile.removeAllViews();
        tile.setPadding(0, 0, 0, 0);
        FrameLayout content = new FrameLayout(tile.getContext());
        tile.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(tile.getContext());
        boolean hasCustomIcon = false;
        if (app.icon_url != null && app.icon_url.length() > 0) {
            hasCustomIcon = true;
            com.im.oldchat.util.ImageLoader.load(icon, app.icon_url);
        } else {
            icon.setImageResource(R.drawable.ic_mini_app);
            icon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        }
        content.addView(icon);

        TextView label = new TextView(tile.getContext());
        label.setText(app.name != null ? app.name : app.id);
        label.setTextColor(0xFFFFFFFF);
        label.setSingleLine(true);
        content.addView(label);

        TextView resizeHandle = new TextView(tile.getContext());
        resizeHandle.setText("↔");
        resizeHandle.setTextColor(0xFFFFFFFF);
        resizeHandle.setTextSize(16f);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setBackgroundDrawable(tileShape(0xAA000000, 0xFFFFFFFF, dp(1)));
        resizeHandle.setVisibility(View.GONE);
        content.addView(resizeHandle);

        TileMeta meta = new TileMeta(icon, label, resizeHandle, null, color,
                app.name != null ? app.name : app.id, R.drawable.ic_mini_app, DiscoverTileLayout.SIZE_MEDIUM);
        meta.hasCustomIcon = hasCustomIcon;
        meta.isCipApp = true;
        meta.cipApp = app;
        return meta;
    }

    private void showCipTileOptionsDialog(final com.im.oldchat.lua.LuaMiniApp app, final View tileView) {
        if (getActivity() == null || app == null) return;
        String[] items = new String[]{"切换磁贴大小", "编辑排序/位置", "从发现页磁贴移除"};
        new android.support.v7.app.AlertDialog.Builder(getActivity())
                .setTitle(app.name + " (小程序)")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            cycleTileSize(tileView);
                        } else if (which == 1) {
                            openTileEditor(tileView.getId());
                        } else if (which == 2) {
                            unpinCipAppTile(app.id);
                            Toast.makeText(getActivity(), "已从发现页磁贴移除", Toast.LENGTH_SHORT).show();
                            applyDiscoverLayoutMode(true);
                        }
                    }
                })
                .show();
    }

    private void addWpTile(View root, int tileId, int iconId, String title,
                           int iconRes, int color, int defaultSize, boolean moments) {
        final View tileView = root.findViewById(tileId);
        if (!(tileView instanceof TiltFrameLayout) || tileGrid == null) {
            return;
        }
        ViewParent parent = tileView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(tileView);
        }
        DiscoverTileLayout.LayoutParams params = new DiscoverTileLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tileGrid.addView(tileView, params);

        int defaultColor = color;
        if (getActivity() != null && SettingsPrefs.isDiscoverRandomSolidColorEnabled(getActivity())) {
            defaultColor = DiscoverMonetPalette.generateSolidColorForId(tileId,
                    com.im.oldchat.util.ThemeColorUtil.isDarkAppearance(getActivity()));
        }
        int savedColor = tilePreferences == null ? defaultColor
                : tilePreferences.getInt(colorPreferenceKey(tileId), defaultColor);
        TileMeta meta = buildTileContent((TiltFrameLayout) tileView, iconId,
                title, iconRes, savedColor, moments);
        meta.defaultSize = defaultSize;
        tileView.setTag(meta);
        int savedSize = tilePreferences == null ? defaultSize
                : tilePreferences.getInt(tilePreferenceKey(tileId), defaultSize);
        tileGrid.setTileSize(tileView, savedSize);
        applyTileVisual(tileView);

        tileView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                openTileEditor(v.getId());
                return true;
            }
        });
    }

    private TileMeta buildTileContent(TiltFrameLayout tile, int iconId, String title,
                                      int iconRes, int color, boolean moments) {
        tile.removeAllViews();
        tile.setPadding(0, 0, 0, 0);
        FrameLayout content = new FrameLayout(tile.getContext());
        tile.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(tile.getContext());
        icon.setId(iconId);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        content.addView(icon);

        TextView label = new TextView(tile.getContext());
        label.setText(title);
        label.setTextColor(0xFFFFFFFF);
        label.setSingleLine(true);
        content.addView(label);

        View noticeDot = null;
        if (moments) {
            noticeDot = new View(tile.getContext());
            noticeDot.setId(R.id.viewDiscoverMomentsDot);
            noticeDot.setBackgroundResource(R.drawable.bg_badge_dot);
            noticeDot.setVisibility(View.GONE);
            content.addView(noticeDot);
        }

        TextView resizeHandle = new TextView(tile.getContext());
        resizeHandle.setText("↔");
        resizeHandle.setTextColor(0xFFFFFFFF);
        resizeHandle.setTextSize(16f);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setBackgroundDrawable(tileShape(0xAA000000, 0xFFFFFFFF, dp(1)));
        resizeHandle.setVisibility(View.GONE);
        content.addView(resizeHandle);

        return new TileMeta(icon, label, resizeHandle, noticeDot, color,
                title, iconRes, DiscoverTileLayout.SIZE_MEDIUM);
    }

    private void applyTileVisual(View tileView) {
        if (!(tileView.getTag() instanceof TileMeta) || tileGrid == null) {
            return;
        }
        TileMeta meta = (TileMeta) tileView.getTag();
        int size = tileGrid.getTileSize(tileView);
        boolean selected = editingTile == tileView;
        tileView.setBackgroundDrawable(tileShape(meta.color,
                selected ? 0xFFFFFFFF : meta.color, selected ? dp(2) : 0));

        int contentColor = DiscoverMonetPalette.isColorLight(meta.color) ? 0xDE000000 : 0xFFFFFFFF;
        if (!meta.hasCustomIcon) {
            meta.icon.setColorFilter(contentColor, PorterDuff.Mode.SRC_IN);
        } else {
            meta.icon.clearColorFilter();
        }
        meta.label.setTextColor(contentColor);

        FrameLayout.LayoutParams iconParams;
        FrameLayout.LayoutParams labelParams;
        if (size == DiscoverTileLayout.SIZE_SMALL) {
            iconParams = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
            labelParams = new FrameLayout.LayoutParams(1, 1);
            meta.label.setVisibility(View.GONE);
        } else if (size == DiscoverTileLayout.SIZE_LARGE) {
            iconParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER);
            labelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.BOTTOM);
            labelParams.leftMargin = dp(12);
            labelParams.rightMargin = dp(12);
            labelParams.bottomMargin = dp(10);
            meta.label.setTextSize(18f);
            meta.label.setVisibility(View.VISIBLE);
        } else {
            iconParams = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER);
            labelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.BOTTOM);
            labelParams.leftMargin = dp(8);
            labelParams.rightMargin = dp(8);
            labelParams.bottomMargin = dp(6);
            meta.label.setTextSize(13f);
            meta.label.setVisibility(View.VISIBLE);
        }
        meta.icon.setLayoutParams(iconParams);
        meta.label.setLayoutParams(labelParams);

        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.RIGHT | Gravity.BOTTOM);
        meta.resizeHandle.setLayoutParams(handleParams);
        meta.resizeHandle.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (meta.noticeDot != null) {
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                    dp(8), dp(8), Gravity.RIGHT | Gravity.TOP);
            dotParams.topMargin = dp(7);
            dotParams.rightMargin = dp(7);
            meta.noticeDot.setLayoutParams(dotParams);
        }
    }

    private void applyDiscoverLayoutMode(boolean force) {
        if (getActivity() == null || tileGrid == null || classicList == null) return;
        boolean classic = SettingsPrefs.isDiscoverClassicLayoutEnabled(getActivity());
        if (!force && classic == classicLayoutApplied) return;
        View hint = rootView == null ? null : rootView.findViewById(R.id.tvDiscoverTileHint);
        if (hint != null) hint.setVisibility(classic ? View.GONE : View.VISIBLE);
        classicLayoutApplied = classic;
        List<View> entries = collectDiscoverEntries();
        tileGrid.removeAllViews();
        classicList.removeAllViews();
        if (classic) {
            tileGrid.setVisibility(View.GONE);
            classicList.setVisibility(View.VISIBLE);
            for (int i = 0; i < entries.size(); i++) {
                View entry = entries.get(i);
                TileMeta meta = entry.getTag() instanceof TileMeta
                        ? (TileMeta) entry.getTag() : null;
                if (meta == null) continue;
                buildClassicEntry((TiltFrameLayout) entry, meta);
                classicList.addView(entry, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
            }
        } else {
            classicList.setVisibility(View.GONE);
            tileGrid.setVisibility(View.VISIBLE);
            for (int i = 0; i < entries.size(); i++) {
                View entry = entries.get(i);
                TileMeta meta = entry.getTag() instanceof TileMeta
                        ? (TileMeta) entry.getTag() : null;
                if (meta == null) continue;
                buildTileEntry((TiltFrameLayout) entry, meta);
                DiscoverTileLayout.LayoutParams params = new DiscoverTileLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tileGrid.addView(entry, params);
                int savedSize = tilePreferences.getInt(tilePreferenceKey(entry.getId()),
                        meta.defaultSize);
                tileGrid.setTileSize(entry, savedSize);
                int defaultColor = meta.color;
                if (getActivity() != null && SettingsPrefs.isDiscoverRandomSolidColorEnabled(getActivity())) {
                    defaultColor = DiscoverMonetPalette.generateSolidColorForId(entry.getId(),
                            com.im.oldchat.util.ThemeColorUtil.isDarkAppearance(getActivity()));
                }
                meta.color = tilePreferences.getInt(colorPreferenceKey(entry.getId()), defaultColor);
                applyTileVisual(entry);
            }
            applySavedTileOrder();
        }
    }

    private List<View> collectDiscoverEntries() {
        List<View> entries = new ArrayList<View>();
        if (tileGrid != null) {
            while (tileGrid.getChildCount() > 0) {
                View child = tileGrid.getChildAt(0);
                tileGrid.removeViewAt(0);
                entries.add(child);
            }
        }
        if (classicList != null) {
            while (classicList.getChildCount() > 0) {
                View child = classicList.getChildAt(0);
                classicList.removeViewAt(0);
                entries.add(child);
            }
        }
        // 首次切换前顺序来自当前容器；后续按保存顺序重排。
        String saved = tilePreferences == null ? "" : tilePreferences.getString("tile_order", "");
        if (saved != null && saved.length() > 0) {
            final java.util.Map<Integer, Integer> rank = new java.util.HashMap<Integer, Integer>();
            String[] ids = saved.split(",");
            for (int i = 0; i < ids.length; i++) {
                try { rank.put(Integer.valueOf(Integer.parseInt(ids[i])), Integer.valueOf(i)); }
                catch (Throwable ignored) { }
            }
            java.util.Collections.sort(entries, new java.util.Comparator<View>() {
                @Override public int compare(View left, View right) {
                    Integer a = rank.get(Integer.valueOf(left.getId()));
                    Integer b = rank.get(Integer.valueOf(right.getId()));
                    int av = a == null ? Integer.MAX_VALUE : a.intValue();
                    int bv = b == null ? Integer.MAX_VALUE : b.intValue();
                    return av < bv ? -1 : (av == bv ? 0 : 1);
                }
            });
        }
        return entries;
    }

    private void buildClassicEntry(TiltFrameLayout entry, TileMeta meta) {
        entry.removeAllViews();
        entry.setTiltEnabled(false);
        entry.setBackgroundResource(R.drawable.bg_seamless_row);
        LinearLayout row = new LinearLayout(entry.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), 0, dp(12), 0);
        entry.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detachFromParent(meta.icon);
        detachFromParent(meta.label);
        if (!meta.hasCustomIcon) {
            meta.icon.setImageResource(meta.iconRes);
            meta.icon.setColorFilter(getResources().getColor(R.color.color_text_secondary),
                    PorterDuff.Mode.SRC_IN);
        } else {
            meta.icon.clearColorFilter();
        }
        row.addView(meta.icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        meta.label.setText(meta.title);
        meta.label.setTextColor(getResources().getColor(R.color.color_text_primary));
        meta.label.setTextSize(15f);
        meta.label.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = dp(12);
        row.addView(meta.label, labelParams);
        TextView arrow = new TextView(entry.getContext());
        arrow.setText("›");
        arrow.setTextSize(22f);
        arrow.setTextColor(getResources().getColor(R.color.color_text_secondary));
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        entry.setOnLongClickListener(null);
    }

    private void buildTileEntry(TiltFrameLayout entry, final TileMeta meta) {
        entry.removeAllViews();
        entry.setTiltEnabled(true);
        FrameLayout content = new FrameLayout(entry.getContext());
        entry.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detachFromParent(meta.icon);
        detachFromParent(meta.label);
        detachFromParent(meta.resizeHandle);
        if (meta.noticeDot != null) detachFromParent(meta.noticeDot);
        if (!meta.hasCustomIcon) {
            meta.icon.setImageResource(meta.iconRes);
        }
        content.addView(meta.icon);
        meta.label.setText(meta.title);
        content.addView(meta.label);
        if (meta.noticeDot != null) content.addView(meta.noticeDot);
        content.addView(meta.resizeHandle);
        if (meta.isCipApp && meta.cipApp != null) {
            entry.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showCipTileOptionsDialog(meta.cipApp, v);
                    return true;
                }
            });
        } else {
            entry.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    openTileEditor(v.getId());
                    return true;
                }
            });
        }
    }

    private void detachFromParent(View view) {
        if (view == null) return;
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
    }

    private void openTileEditor(int selectedTileId) {
        if (getActivity() == null) return;
        Intent intent = new Intent(getActivity(), com.im.oldchat.ui.DiscoverTileEditorActivity.class);
        intent.putExtra(com.im.oldchat.ui.DiscoverTileEditorActivity.EXTRA_SELECTED_TILE_ID,
                selectedTileId);
        startActivity(intent);
    }

    private void reloadTileSizes() {
        if (tileGrid == null || tilePreferences == null) return;
        applySavedTileOrder();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            View tile = tileGrid.getChildAt(i);
            int current = tileGrid.getTileSize(tile);
            int saved = tilePreferences.getInt(tilePreferenceKey(tile.getId()), current);
            tileGrid.setTileSize(tile, saved);
            if (tile.getTag() instanceof TileMeta) {
                TileMeta meta = (TileMeta) tile.getTag();
                int defaultColor = meta.color;
                if (getActivity() != null && SettingsPrefs.isDiscoverRandomSolidColorEnabled(getActivity())) {
                    defaultColor = DiscoverMonetPalette.generateSolidColorForId(tile.getId(),
                            com.im.oldchat.util.ThemeColorUtil.isDarkAppearance(getActivity()));
                }
                meta.color = tilePreferences.getInt(colorPreferenceKey(tile.getId()), defaultColor);
            }
            applyTileVisual(tile);
        }
    }

    private void enterTileEditMode(View tileView) {
        editingTile = tileView;
        if (tileGrid != null) tileGrid.setEditing(true);
        refreshTileEditVisuals();
        Toast.makeText(getActivity(), "点击右下角按钮调整磁贴大小", Toast.LENGTH_SHORT).show();
    }

    private void exitTileEditMode() {
        if (editingTile == null && (tileGrid == null || !tileGrid.isEditing())) {
            return;
        }
        editingTile = null;
        if (tileGrid != null) tileGrid.setEditing(false);
        refreshTileEditVisuals();
    }

    private boolean consumeTileEditClick() {
        if (tileGrid != null && tileGrid.isEditing()) {
            exitTileEditMode();
            return true;
        }
        return false;
    }

    private void cycleTileSize(View tileView) {
        if (tileGrid == null || tileView == null) {
            return;
        }
        int current = tileGrid.getTileSize(tileView);
        int next = current == DiscoverTileLayout.SIZE_SMALL
                ? DiscoverTileLayout.SIZE_MEDIUM
                : (current == DiscoverTileLayout.SIZE_MEDIUM
                ? DiscoverTileLayout.SIZE_LARGE : DiscoverTileLayout.SIZE_SMALL);
        tileGrid.setTileSize(tileView, next);
        if (tilePreferences != null) {
            tilePreferences.edit().putInt(tilePreferenceKey(tileView.getId()), next).apply();
        }
        applyTileVisual(tileView);
    }

    private void refreshTileEditVisuals() {
        if (tileGrid == null) return;
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            applyTileVisual(tileGrid.getChildAt(i));
        }
    }

    private void applySavedTileOrder() {
        if (tileGrid == null || tilePreferences == null) return;
        String saved = tilePreferences.getString("tile_order", "");
        if (saved == null || saved.length() == 0) return;
        String[] ids = saved.split(",");
        int insertIndex = 0;
        for (int i = 0; i < ids.length; i++) {
            int id;
            try {
                id = Integer.parseInt(ids[i]);
            } catch (Throwable ignored) {
                continue;
            }
            View child = tileGrid.findViewById(id);
            if (child == null || child.getParent() != tileGrid) continue;
            int current = tileGrid.indexOfChild(child);
            if (current != insertIndex) {
                ViewGroup.LayoutParams params = child.getLayoutParams();
                tileGrid.removeView(child);
                tileGrid.addView(child, Math.min(insertIndex, tileGrid.getChildCount()), params);
            }
            insertIndex++;
        }
        tileGrid.requestLayout();
    }

    private String tilePreferenceKey(int tileId) {
        return "size_" + tileId;
    }

    private String colorPreferenceKey(int tileId) {
        return "color_" + tileId;
    }

    private GradientDrawable tileShape(int fill, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        drawable.setCornerRadius(0f);
        return drawable;
    }

    private int dp(int value) {
        if (getResources() == null) return value;
        return Math.max(1, (int) (value * getResources().getDisplayMetrics().density + 0.5f));
    }

    private static final class TileMeta {
        final ImageView icon;
        final TextView label;
        final TextView resizeHandle;
        final View noticeDot;
        final String title;
        final int iconRes;
        boolean hasCustomIcon;
        boolean isCipApp;
        com.im.oldchat.lua.LuaMiniApp cipApp;
        int defaultSize;
        int color;

        TileMeta(ImageView icon, TextView label, TextView resizeHandle,
                 View noticeDot, int color, String title, int iconRes, int defaultSize) {
            this.icon = icon;
            this.label = label;
            this.resizeHandle = resizeHandle;
            this.noticeDot = noticeDot;
            this.color = color;
            this.title = title;
            this.iconRes = iconRes;
            this.defaultSize = defaultSize;
        }
    }

    private void chooseLocalCip() {
        if (getActivity() == null) return;
        Intent intent = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        try { startActivityForResult(intent, REQUEST_IMPORT_CIP); }
        catch (Exception e) {
            intent.setType("*/*");
            try { startActivityForResult(intent, REQUEST_IMPORT_CIP); }
            catch (Exception ignored) { Toast.makeText(getActivity(), "没有可用的文件选择器", Toast.LENGTH_SHORT).show(); }
        }
    }

    private void openLuaDocs() {
        if (getActivity() == null) return;
        String origin = com.im.oldchat.api.HttpUtil.getBaseOrigin();
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(origin + "/lua-cip.md"))); }
        catch (Exception e) { Toast.makeText(getActivity(), "无法打开开发文档", Toast.LENGTH_SHORT).show(); }
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_CIP || resultCode != android.app.Activity.RESULT_OK
                || data == null || data.getData() == null || getActivity() == null) return;
        final Uri uri = data.getData();
        final android.app.Activity activity = getActivity();
        new AsyncTask<Void, Void, com.im.oldchat.lua.LuaMiniApp>() {
            String error;
            @Override protected com.im.oldchat.lua.LuaMiniApp doInBackground(Void... values) {
                try { return com.im.oldchat.lua.LocalCipImporter.importPackage(activity, uri); }
                catch (Exception e) { error = e.getMessage(); return null; }
            }
            @Override protected void onPostExecute(com.im.oldchat.lua.LuaMiniApp app) {
                if (!isAdded()) return;
                if (app == null) Toast.makeText(getActivity(), "导入失败：" + (error == null ? "未知错误" : error), Toast.LENGTH_LONG).show();
                else {
                    Toast.makeText(getActivity(), "已导入：" + app.name, Toast.LENGTH_SHORT).show();
                    renderLuaApps(com.im.oldchat.lua.LuaAppStore.getApps(getActivity()));
                }
            }
        }.execute();
    }

    private void renderLuaApps(java.util.List<com.im.oldchat.lua.LuaMiniApp> apps) {
        if (luaAppsContainer == null) return;
        luaAppsContainer.removeAllViews();
        int dynamicCount = 0;
        if (apps != null) for (int i = 0; i < apps.size(); i++) {
            com.im.oldchat.lua.LuaMiniApp candidate = apps.get(i);
            if (candidate != null && candidate.enabled && !"welcome".equals(candidate.id)) dynamicCount++;
        }
        boolean has = dynamicCount > 0;
        if (!has || getActivity() == null) return;
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        for (int i = 0; i < apps.size(); i++) {
            final com.im.oldchat.lua.LuaMiniApp app = apps.get(i);
            if (app == null || !app.enabled || "welcome".equals(app.id)) continue;
            View row = inflater.inflate(R.layout.item_discover_lua_app, luaAppsContainer, false);
            row.setBackgroundResource(R.drawable.bg_seamless_row);
            if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
                ((ViewGroup) row).getChildAt(0).setBackgroundResource(R.drawable.bg_seamless_row);
            }
            disableTiltRecursively(row);
            TextView name = (TextView) row.findViewById(R.id.tvLuaAppName);
            TextView desc = (TextView) row.findViewById(R.id.tvLuaAppDescription);
            ImageView icon = (ImageView) row.findViewById(R.id.ivLuaAppIcon);
            name.setText(app.name);
            desc.setText(app.description);
            desc.setVisibility(app.description == null || app.description.length() == 0 ? View.GONE : View.VISIBLE);
            if (app.icon_url != null && app.icon_url.length() > 0) com.im.oldchat.util.ImageLoader.load(icon, app.icon_url);
            View.OnClickListener clickListener = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent intent = new Intent(getActivity(), com.im.oldchat.ui.LuaMiniAppActivity.class);
                    intent.putExtra(com.im.oldchat.ui.LuaMiniAppActivity.EXTRA_APP_ID, app.id);
                    startActivity(intent);
                }
            };
            View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showLuaAppOptionsDialog(app);
                    return true;
                }
            };

            row.setOnClickListener(clickListener);
            row.setOnLongClickListener(longClickListener);
            if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
                View inner = ((ViewGroup) row).getChildAt(0);
                inner.setOnClickListener(clickListener);
                inner.setOnLongClickListener(longClickListener);
            }
            luaAppsContainer.addView(row);
        }
    }

    private void showLuaAppOptionsDialog(final com.im.oldchat.lua.LuaMiniApp app) {
        if (getActivity() == null || app == null || app.id == null) return;
        final boolean pinned = isCipAppPinned(app.id);
        final boolean isLocal = app.id.startsWith("local_");

        List<String> options = new ArrayList<String>();
        options.add(pinned ? "从发现页磁贴移除" : "固定到发现页磁贴");
        if (isLocal) {
            options.add("删除本地小程序");
        }

        new android.support.v7.app.AlertDialog.Builder(getActivity())
                .setTitle(app.name)
                .setItems(options.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            if (pinned) {
                                unpinCipAppTile(app.id);
                                Toast.makeText(getActivity(), "已从发现页磁贴移除", Toast.LENGTH_SHORT).show();
                            } else {
                                pinCipAppTile(app.id);
                                Toast.makeText(getActivity(), "已添加到发现页磁贴", Toast.LENGTH_SHORT).show();
                            }
                            applyDiscoverLayoutMode(true);
                        } else if (which == 1 && isLocal) {
                            confirmDeleteLocalApp(app);
                        }
                    }
                })
                .show();
    }

    private boolean isCipAppPinned(String appId) {
        if (tilePreferences == null || appId == null) return false;
        String raw = tilePreferences.getString("pinned_cip_app_ids", "");
        if (raw == null || raw.length() == 0) return false;
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (appId.equals(part.trim())) return true;
        }
        return false;
    }

    private void pinCipAppTile(String appId) {
        if (tilePreferences == null || appId == null || isCipAppPinned(appId)) return;
        String raw = tilePreferences.getString("pinned_cip_app_ids", "");
        String updated = (raw == null || raw.length() == 0) ? appId : (raw + "," + appId);
        tilePreferences.edit().putString("pinned_cip_app_ids", updated).apply();
        int tileId = generateCipTileId(appId);
        String order = tilePreferences.getString("tile_order", "");
        if (order != null && order.length() > 0 && !order.contains(String.valueOf(tileId))) {
            tilePreferences.edit().putString("tile_order", order + "," + tileId).apply();
        }
    }

    private void unpinCipAppTile(String appId) {
        if (tilePreferences == null || appId == null) return;
        String raw = tilePreferences.getString("pinned_cip_app_ids", "");
        if (raw == null || raw.length() == 0) return;
        String[] parts = raw.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() > 0 && !trimmed.equals(appId)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(trimmed);
            }
        }
        tilePreferences.edit().putString("pinned_cip_app_ids", sb.toString()).apply();
        int tileId = generateCipTileId(appId);
        String order = tilePreferences.getString("tile_order", "");
        if (order != null && order.length() > 0) {
            String[] oParts = order.split(",");
            StringBuilder oSb = new StringBuilder();
            for (String p : oParts) {
                if (!p.trim().equals(String.valueOf(tileId)) && p.trim().length() > 0) {
                    if (oSb.length() > 0) oSb.append(",");
                    oSb.append(p.trim());
                }
            }
            tilePreferences.edit().putString("tile_order", oSb.toString()).apply();
        }
        if (tileGrid != null) {
            View view = tileGrid.findViewById(tileId);
            if (view != null) tileGrid.removeView(view);
        }
        if (classicList != null) {
            View view = classicList.findViewById(tileId);
            if (view != null) classicList.removeView(view);
        }
    }

    private void confirmDeleteLocalApp(final com.im.oldchat.lua.LuaMiniApp app) {
        if (getActivity() == null || app == null || app.id == null || !app.id.startsWith("local_")) return;
        new android.support.v7.app.AlertDialog.Builder(getActivity())
                .setTitle("删除本地小程序")
                .setMessage("确定删除“" + app.name + "”及其脚本和资源吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        boolean deleted = com.im.oldchat.lua.LuaAppStore.deleteLocalApp(getActivity(), app.id);
                        Toast.makeText(getActivity(), deleted ? "已删除" : "删除失败", Toast.LENGTH_SHORT).show();
                        renderLuaApps(com.im.oldchat.lua.LuaAppStore.getApps(getActivity()));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void syncEntryVisibility() {
        if (getActivity() == null) {
            return;
        }
        boolean showOldView = SettingsPrefs.isOldViewEntryEnabled(getActivity());
        boolean showPublicCourt = SettingsStore.isPublicCourtEnabled(getActivity());
        if (btnDiscoverOldView != null) {
            btnDiscoverOldView.setVisibility(showOldView ? View.VISIBLE : View.GONE);
        }
        if (btnDiscoverPublicCourt != null) {
            btnDiscoverPublicCourt.setVisibility(showPublicCourt ? View.VISIBLE : View.GONE);
        }
        if (tileGrid != null) {
            tileGrid.requestLayout();
        }
    }

    private void bindOpen(View source, final Class<?> cls) {
        if (source == null) {
            return;
        }
        source.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (consumeTileEditClick()) return;
                open(cls);
            }
        });
    }

    private void refreshMomentsBadge() {
        if (momentsDot == null || getActivity() == null) {
            return;
        }
        momentsDot.setVisibility(MomentNoticeStore.hasNotices(getActivity()) ? View.VISIBLE : View.GONE);
    }

    private void open(Class<?> cls) {
        if (getActivity() == null) {
            return;
        }
        startActivity(new Intent(getActivity(), cls));
    }

    private void applySeamlessRows(View root) {
        if (root == null) {
            return;
        }
        int[] ids = new int[]{
                R.id.btnDiscoverMoments, R.id.btnDiscoverEmoji, R.id.btnDiscoverMusic,
                R.id.btnDiscoverResources, R.id.btnDiscoverMinimalNews, R.id.btnDiscoverOldView, R.id.btnDiscoverCheckIn,
                R.id.btnDiscoverScratch, R.id.btnDiscoverReport, R.id.btnDiscoverPublicCourt, R.id.btnDiscoverMiniApps,
                R.id.btnDiscoverSettings, R.id.btnDiscoverCipDeveloper, R.id.btnImportLuaCip,
                R.id.btnLuaCipDocs
        };
        for (int i = 0; i < ids.length; i++) {
            View row = root.findViewById(ids[i]);
            if (row != null) row.setBackgroundResource(R.drawable.bg_seamless_row);
        }
        disableTiltRecursively(root);
    }

    private void disableTiltRecursively(View view) {
        if (view instanceof com.im.oldchat.ui.widget.TiltFrameLayout) {
            ((com.im.oldchat.ui.widget.TiltFrameLayout) view).setTiltEnabled(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableTiltRecursively(group.getChildAt(i));
            }
        }
    }

    private void tintDiscoverIcons() {
        if (getActivity() == null) {
            return;
        }
        int color = getResources().getColor(R.color.color_text_primary);
        tintIcon(ivDiscoverIconEmoji, color);
        tintIcon(ivDiscoverIconMinimalNews, color);
        tintIcon(ivDiscoverIconOldView, color);
        tintIcon(ivDiscoverIconSettings, color);
        android.widget.ImageView iconMini = rootView == null ? null
                : (android.widget.ImageView) rootView.findViewById(R.id.ivDiscoverIconMiniApp);
        android.widget.ImageView iconMiniRow = rootView == null ? null
                : (android.widget.ImageView) rootView.findViewById(R.id.ivMiniAppRowIcon);
        tintIcon(iconMini, color);
        tintIcon(iconMiniRow, color);
    }

    private void tintIcon(ImageView imageView, int color) {
        if (imageView == null) {
            return;
        }
        imageView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }
}
