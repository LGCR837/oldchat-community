package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.ui.widget.DiscoverTileLayout;
import com.im.oldchat.ui.widget.TiltFrameLayout;
import com.im.oldchat.util.SettingsPrefs;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 独立的发现页磁贴编辑器，兼容 Android 2.3+。 */
public class DiscoverTileEditorActivity extends BaseActivity {
    public static final String EXTRA_SELECTED_TILE_ID = "selected_tile_id";

    private static final String PREFS_NAME = "discover_tile_layout";
    private static final int REQUEST_PICK_PALETTE_IMAGE = 7301;

    private static final TileDefinition[] TILES = new TileDefinition[]{
            new TileDefinition(R.id.btnDiscoverMoments, "朋友圈",
                    R.drawable.ic_discover_moments, 0xFF0078D7, DiscoverTileLayout.SIZE_LARGE),
            new TileDefinition(R.id.btnDiscoverEmoji, "表情广场",
                    R.drawable.ic_discover_emoji_plaza, 0xFFE81123, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverMusic, "音乐广场",
                    R.drawable.ic_discover_music, 0xFF881798, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverResources, "资源广场",
                    R.drawable.ic_document, 0xFF107C10, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverMinimalNews, "极简简讯",
                    R.drawable.ic_minimal_news, 0xFF00B7C3, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverOldView, "旧视界",
                    R.drawable.ic_discover_old_view, 0xFF5C2D91, DiscoverTileLayout.SIZE_SMALL),
            new TileDefinition(R.id.btnDiscoverCheckIn, "每日签到",
                    R.drawable.ic_discover_qiandao, 0xFFFF8C00, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverScratch, "每日刮刮乐",
                    R.drawable.ic_discover_qiandao, 0xFFD4A017, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverReport, "举报进度",
                    R.drawable.ic_discover_report, 0xFFD13438, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverPublicCourt, "公开法庭",
                    R.drawable.ic_discover_fating, 0xFF4A5459, DiscoverTileLayout.SIZE_SMALL),
            new TileDefinition(R.id.btnDiscoverMiniApps, "小程序",
                    R.drawable.ic_mini_app, 0xFF008272, DiscoverTileLayout.SIZE_MEDIUM),
            new TileDefinition(R.id.btnDiscoverSettings, "发现设置",
                    R.drawable.ic_setting_appearance, 0xFF68768A, DiscoverTileLayout.SIZE_SMALL)
    };

    private DiscoverTileLayout tileGrid;
    private SharedPreferences preferences;
    private View selectedTile;
    private View draggedTile;
    private View dropTargetTile;
    private boolean destroyed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_discover_tile_editor);

        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        tileGrid = findViewByIdCompat(R.id.discoverEditorTileGrid);
        View back = findViewByIdCompat(R.id.btnDiscoverEditorBack);
        View done = findViewByIdCompat(R.id.btnDiscoverEditorDone);
        View.OnClickListener finishListener = new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        };
        if (back != null) back.setOnClickListener(finishListener);
        if (done != null) done.setOnClickListener(finishListener);
        View pickPalette = findViewByIdCompat(R.id.btnDiscoverPickPaletteImage);
        View randomSolid = findViewByIdCompat(R.id.btnDiscoverRandomSolidColor);
        View resetPalette = findViewByIdCompat(R.id.btnDiscoverResetPalette);
        if (pickPalette != null) {
            pickPalette.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    choosePaletteImage();
                }
            });
        }
        if (randomSolid != null) {
            randomSolid.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    applyRandomSolidColors();
                }
            });
        }
        if (resetPalette != null) {
            resetPalette.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    resetPaletteColors();
                }
            });
        }

        int requestedId = getIntent() == null ? 0
                : getIntent().getIntExtra(EXTRA_SELECTED_TILE_ID, 0);
        if (tileGrid != null) {
            for (int i = 0; i < TILES.length; i++) {
                TileDefinition definition = TILES[i];
                if (!shouldShow(definition.tileId)) continue;
                View tile = addTile(definition);
                if (definition.tileId == requestedId) selectedTile = tile;
            }
            loadPinnedCipAppTiles(requestedId);
            applySavedTileOrder();
            saveTileOrder();
            if (selectedTile == null && tileGrid.getChildCount() > 0) {
                selectedTile = tileGrid.getChildAt(0);
            }
            refreshSelection();
            tileGrid.setEditing(true);
        }
    }

    private void loadPinnedCipAppTiles(int requestedId) {
        if (tileGrid == null) return;
        String raw = preferences == null ? "" : preferences.getString("pinned_cip_app_ids", "");
        if (raw == null || raw.length() == 0) return;
        String[] appIds = raw.split(",");
        java.util.List<com.im.oldchat.lua.LuaMiniApp> allApps = com.im.oldchat.lua.LuaAppStore.getApps(this);
        for (String idStr : appIds) {
            String appId = idStr.trim();
            if (appId.length() == 0) continue;
            String name = appId;
            String iconUrl = null;
            if (allApps != null) {
                for (com.im.oldchat.lua.LuaMiniApp a : allApps) {
                    if (a != null && appId.equals(a.id)) {
                        if (a.name != null) name = a.name;
                        iconUrl = a.icon_url;
                        break;
                    }
                }
            }
            int tileId = 0x70000000 | (Math.abs(appId.hashCode()) & 0x0FFFFFFF);
            int color = generateTileColor(appId);
            TileDefinition def = new TileDefinition(tileId, name, R.drawable.ic_mini_app, color, DiscoverTileLayout.SIZE_MEDIUM);
            View tile = addTile(def);
            if (tile.getTag() instanceof EditorTileMeta) {
                EditorTileMeta meta = (EditorTileMeta) tile.getTag();
                if (iconUrl != null && iconUrl.length() > 0) {
                    meta.hasCustomIcon = true;
                    com.im.oldchat.util.ImageLoader.load(meta.icon, iconUrl);
                }
            }
            if (tileId == requestedId) selectedTile = tile;
        }
    }

    private int generateTileColor(String appId) {
        if (SettingsPrefs.isDiscoverRandomSolidColorEnabled(this)) {
            return DiscoverMonetPalette.generateSolidColorForString(appId,
                    com.im.oldchat.util.ThemeColorUtil.isDarkAppearance(this));
        }
        if (appId == null) return 0xFF008272;
        int hash = Math.abs(appId.hashCode());
        int[] palette = new int[]{
                0xFF008272, 0xFF0078D7, 0xFF107C10, 0xFF881798,
                0xFFD13438, 0xFF00B7C3, 0xFFFF8C00, 0xFF5C2D91
        };
        return palette[hash % palette.length];
    }

    private boolean shouldShow(int tileId) {
        if (tileId == R.id.btnDiscoverOldView) {
            return SettingsPrefs.isOldViewEntryEnabled(this);
        }
        if (tileId == R.id.btnDiscoverPublicCourt) {
            return SettingsStore.isPublicCourtEnabled(this);
        }
        return true;
    }

    private View addTile(final TileDefinition definition) {
        final TiltFrameLayout tile = new TiltFrameLayout(this);
        tile.setId(definition.tileId);
        DiscoverTileLayout.LayoutParams params = new DiscoverTileLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tileGrid.addView(tile, params);

        EditorTileMeta meta = buildTileContent(tile, definition);
        meta.color = preferences.getInt(colorPreferenceKey(definition.tileId), definition.color);
        tile.setTag(meta);
        int size = preferences.getInt(preferenceKey(definition.tileId), definition.defaultSize);
        tileGrid.setTileSize(tile, size);
        applyTileVisual(tile);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selectTile(v);
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                selectTile(v);
                return true;
            }
        });
        meta.resizeHandle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cycleSize(tile);
            }
        });
        meta.moveHandle.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                return handleMoveTouch(tile, v, event);
            }
        });
        return tile;
    }

    private EditorTileMeta buildTileContent(TiltFrameLayout tile, TileDefinition definition) {
        FrameLayout content = new FrameLayout(this);
        tile.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(definition.iconRes);
        icon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        content.addView(icon);

        TextView label = new TextView(this);
        label.setText(definition.title);
        label.setTextColor(0xFFFFFFFF);
        label.setSingleLine(true);
        content.addView(label);

        TextView moveHandle = new TextView(this);
        moveHandle.setText("移");
        moveHandle.setTextColor(0xFFFFFFFF);
        moveHandle.setTextSize(14f);
        moveHandle.setGravity(Gravity.CENTER);
        moveHandle.setBackgroundDrawable(shape(0xAA000000, 0xFFFFFFFF, dp(1)));
        content.addView(moveHandle);

        TextView resizeHandle = new TextView(this);
        resizeHandle.setText("↔");
        resizeHandle.setTextColor(0xFFFFFFFF);
        resizeHandle.setTextSize(16f);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setBackgroundDrawable(shape(0xAA000000, 0xFFFFFFFF, dp(1)));
        content.addView(resizeHandle);
        return new EditorTileMeta(icon, label, moveHandle, resizeHandle, definition.color);
    }

    private void selectTile(View tile) {
        selectedTile = tile;
        refreshSelection();
    }

    private void cycleSize(View tile) {
        if (tileGrid == null || tile == null) return;
        int current = tileGrid.getTileSize(tile);
        int next = current == DiscoverTileLayout.SIZE_SMALL
                ? DiscoverTileLayout.SIZE_MEDIUM
                : (current == DiscoverTileLayout.SIZE_MEDIUM
                ? DiscoverTileLayout.SIZE_LARGE : DiscoverTileLayout.SIZE_SMALL);
        tileGrid.setTileSize(tile, next);
        preferences.edit().putInt(preferenceKey(tile.getId()), next).apply();
        applyTileVisual(tile);
    }

    private boolean handleMoveTouch(View tile, View handle, MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            selectedTile = tile;
            draggedTile = tile;
            dropTargetTile = null;
            handle.getParent().requestDisallowInterceptTouchEvent(true);
            refreshSelection();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            View target = findTileAt(event.getRawX(), event.getRawY());
            if (target == draggedTile) target = null;
            if (target != dropTargetTile) {
                dropTargetTile = target;
                refreshSelection();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handle.getParent().requestDisallowInterceptTouchEvent(false);
            View source = draggedTile;
            View target = action == MotionEvent.ACTION_UP ? dropTargetTile : null;
            draggedTile = null;
            dropTargetTile = null;
            if (source != null && target != null) {
                swapTiles(source, target);
                saveTileOrder();
            }
            refreshSelection();
            return true;
        }
        return true;
    }

    private View findTileAt(float rawX, float rawY) {
        if (tileGrid == null) return null;
        Rect rect = new Rect();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            View child = tileGrid.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.getGlobalVisibleRect(rect)
                    && rect.contains((int) rawX, (int) rawY)) {
                return child;
            }
        }
        return null;
    }

    /** 交换两块磁贴的业务顺序，容器随后按新顺序自动重新排版。 */
    private void swapTiles(View first, View second) {
        if (tileGrid == null) return;
        int firstIndex = tileGrid.indexOfChild(first);
        int secondIndex = tileGrid.indexOfChild(second);
        if (firstIndex < 0 || secondIndex < 0 || firstIndex == secondIndex) return;
        List<View> children = new ArrayList<View>();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            children.add(tileGrid.getChildAt(i));
        }
        Collections.swap(children, firstIndex, secondIndex);
        tileGrid.removeAllViews();
        for (int i = 0; i < children.size(); i++) {
            View child = children.get(i);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            tileGrid.addView(child, params);
        }
        tileGrid.requestLayout();
    }

    private void saveTileOrder() {
        if (tileGrid == null) return;
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            if (i > 0) order.append(',');
            order.append(tileGrid.getChildAt(i).getId());
        }
        preferences.edit().putString("tile_order", order.toString()).apply();
    }

    private void applySavedTileOrder() {
        if (tileGrid == null) return;
        String saved = preferences.getString("tile_order", "");
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

    private void refreshSelection() {
        if (tileGrid == null) return;
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            applyTileVisual(tileGrid.getChildAt(i));
        }
    }

    private void choosePaletteImage() {
        Intent intent = new Intent(Build.VERSION.SDK_INT >= 19
                ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_PALETTE_IMAGE);
        } catch (Throwable error) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_PALETTE_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        Toast.makeText(getApplicationContext(),
                "正在从图片提取颜色…", Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, int[]>() {
            @Override protected int[] doInBackground(Void... params) {
                Bitmap bitmap = null;
                try {
                    bitmap = decodeSampledBitmap(uri, 256);
                    return bitmap == null ? null
                            : DiscoverMonetPalette.extract(bitmap, TILES.length);
                } catch (Throwable ignored) {
                    return null;
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
            }

            @Override protected void onPostExecute(int[] colors) {
                if (destroyed || isFinishing()) return;
                if (colors == null || colors.length == 0) {
                    Toast.makeText(getApplicationContext(),
                            "无法从这张图片提取颜色", Toast.LENGTH_SHORT).show();
                    return;
                }
                applyPalette(colors);
                Toast.makeText(getApplicationContext(),
                        "已应用图片配色", Toast.LENGTH_SHORT).show();
            }
        }.execute();
    }

    private Bitmap decodeSampledBitmap(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream first = null;
        try {
            first = getContentResolver().openInputStream(uri);
            if (first == null) return null;
            BitmapFactory.decodeStream(first, null, bounds);
        } finally {
            if (first != null) try { first.close(); } catch (Throwable ignored) { }
        }
        int sample = 1;
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream second = null;
        try {
            second = getContentResolver().openInputStream(uri);
            return second == null ? null : BitmapFactory.decodeStream(second, null, options);
        } finally {
            if (second != null) try { second.close(); } catch (Throwable ignored) { }
        }
    }

    private void applyPalette(int[] colors) {
        if (tileGrid == null || colors == null || colors.length == 0) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            View tile = tileGrid.getChildAt(i);
            if (tile == null || !(tile.getTag() instanceof EditorTileMeta)) continue;
            EditorTileMeta meta = (EditorTileMeta) tile.getTag();
            int color = colors[i % colors.length];
            meta.color = color;
            editor.putInt(colorPreferenceKey(tile.getId()), color);
            applyTileVisual(tile);
        }
        editor.apply();
    }

    private void applyRandomSolidColors() {
        if (tileGrid == null) return;
        boolean isDark = com.im.oldchat.util.ThemeColorUtil.isDarkAppearance(this);
        SharedPreferences.Editor editor = preferences.edit();
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            View tile = tileGrid.getChildAt(i);
            if (tile == null || !(tile.getTag() instanceof EditorTileMeta)) continue;
            EditorTileMeta meta = (EditorTileMeta) tile.getTag();
            int color = DiscoverMonetPalette.generateRandomSolidColor(isDark, rng);
            meta.color = color;
            editor.putInt(colorPreferenceKey(tile.getId()), color);
            applyTileVisual(tile);
        }
        editor.apply();
        Toast.makeText(getApplicationContext(),
                "已应用随机纯色 (R+G+B=" + (isDark ? "300" : "600") + ")",
                Toast.LENGTH_SHORT).show();
    }

    private void resetPaletteColors() {
        if (tileGrid == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            View tile = tileGrid.getChildAt(i);
            if (tile == null || !(tile.getTag() instanceof EditorTileMeta)) continue;
            EditorTileMeta meta = (EditorTileMeta) tile.getTag();
            String title = meta.label != null ? meta.label.getText().toString() : "";
            int defaultColor = getDefaultColorForTile(tile.getId(), title);
            meta.color = defaultColor;
            editor.putInt(colorPreferenceKey(tile.getId()), defaultColor);
            applyTileVisual(tile);
        }
        editor.apply();
        Toast.makeText(getApplicationContext(),
                "已恢复默认磁贴颜色", Toast.LENGTH_SHORT).show();
    }

    private int getDefaultColorForTile(int tileId, String appIdOrTitle) {
        for (int i = 0; i < TILES.length; i++) {
            if (TILES[i].tileId == tileId) return TILES[i].color;
        }
        return generateTileColor(appIdOrTitle);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void applyTileVisual(View tile) {
        if (!(tile.getTag() instanceof EditorTileMeta) || tileGrid == null) return;
        EditorTileMeta meta = (EditorTileMeta) tile.getTag();
        int size = tileGrid.getTileSize(tile);
        boolean selected = tile == selectedTile;
        boolean dropTarget = tile == dropTargetTile && tile != draggedTile;
        tile.setBackgroundDrawable(shape(meta.color,
                dropTarget ? 0xFFFFD54F : (selected ? 0xFFFFFFFF : meta.color),
                dropTarget || selected ? dp(2) : 0));

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
            labelParams = labelLayoutParams(dp(12), dp(10));
            meta.label.setTextSize(18f);
            meta.label.setVisibility(View.VISIBLE);
        } else {
            iconParams = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER);
            labelParams = labelLayoutParams(dp(8), dp(6));
            meta.label.setTextSize(13f);
            meta.label.setVisibility(View.VISIBLE);
        }
        meta.icon.setLayoutParams(iconParams);
        meta.label.setLayoutParams(labelParams);
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.RIGHT | Gravity.BOTTOM);
        meta.resizeHandle.setLayoutParams(handleParams);
        meta.resizeHandle.setVisibility(selected ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams moveParams = new FrameLayout.LayoutParams(
                dp(32), dp(32), Gravity.RIGHT | Gravity.TOP);
        meta.moveHandle.setLayoutParams(moveParams);
        // 移动入口始终显示，避免未选中状态或老系统字形缺失导致用户找不到。
        meta.moveHandle.setVisibility(View.VISIBLE);
    }

    private FrameLayout.LayoutParams labelLayoutParams(int horizontal, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.BOTTOM);
        params.leftMargin = horizontal;
        params.rightMargin = horizontal;
        params.bottomMargin = bottom;
        return params;
    }

    private String preferenceKey(int tileId) {
        return "size_" + tileId;
    }

    private String colorPreferenceKey(int tileId) {
        return "color_" + tileId;
    }

    private GradientDrawable shape(int fill, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        drawable.setCornerRadius(0f);
        return drawable;
    }

    private int dp(int value) {
        return Math.max(1, (int) (value
                * getResources().getDisplayMetrics().density + 0.5f));
    }

    private static final class TileDefinition {
        final int tileId;
        final String title;
        final int iconRes;
        final int color;
        final int defaultSize;

        TileDefinition(int tileId, String title, int iconRes, int color, int defaultSize) {
            this.tileId = tileId;
            this.title = title;
            this.iconRes = iconRes;
            this.color = color;
            this.defaultSize = defaultSize;
        }
    }

    private static final class EditorTileMeta {
        final ImageView icon;
        final TextView label;
        final TextView moveHandle;
        final TextView resizeHandle;
        boolean hasCustomIcon;
        int color;

        EditorTileMeta(ImageView icon, TextView label, TextView moveHandle,
                       TextView resizeHandle, int color) {
            this.icon = icon;
            this.label = label;
            this.moveHandle = moveHandle;
            this.resizeHandle = resizeHandle;
            this.color = color;
        }
    }
}
