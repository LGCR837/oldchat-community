package com.im.oldchat.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.support.v7.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.Toast;
import com.im.oldchat.ui.widget.DiscoverTileLayout;
import com.im.oldchat.ui.widget.TiltFrameLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天磁贴长按排序与配置弹窗辅助类。
 */
class ChatActionTileDialogHelper {

    public static void showTileOptionsDialog(final Activity activity, final DiscoverTileLayout tileGrid,
                                             final TiltFrameLayout tileView, final ChatActionTileHelper.ActionTileDef def,
                                             final SharedPreferences prefs,
                                             final ChatActionTileHelper.ActionTileDef[] allDefs, final String prefPrefix) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final int currentIndex = tileGrid.indexOfChild(tileView);
        final int totalCount = tileGrid.getChildCount();

        List<String> options = new ArrayList<String>();
        if (currentIndex > 0) {
            options.add("向前移动一位");
            options.add("置顶到首位");
        }
        if (currentIndex < totalCount - 1) {
            options.add("向后移动一位");
        }
        options.add("恢复默认排序");

        final String[] items = options.toArray(new String[0]);

        new AlertDialog.Builder(activity)
                .setTitle("【" + def.title + "】磁贴位置调整")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String selected = items[which];
                        if ("向前移动一位".equals(selected)) {
                            moveTile(activity, tileGrid, tileView, currentIndex - 1, prefs, prefPrefix);
                        } else if ("置顶到首位".equals(selected)) {
                            moveTile(activity, tileGrid, tileView, 0, prefs, prefPrefix);
                        } else if ("向后移动一位".equals(selected)) {
                            moveTile(activity, tileGrid, tileView, currentIndex + 1, prefs, prefPrefix);
                        } else if ("恢复默认排序".equals(selected)) {
                            resetDefaults(activity, tileGrid, prefs, allDefs, prefPrefix);
                        }
                    }
                })
                .show();
    }

    private static void moveTile(Activity activity, DiscoverTileLayout tileGrid,
                                 TiltFrameLayout tileView, int targetIndex,
                                 SharedPreferences prefs, String prefPrefix) {
        if (tileGrid == null || tileView == null || targetIndex < 0 || targetIndex >= tileGrid.getChildCount()) {
            return;
        }
        ViewGroup.LayoutParams lp = tileView.getLayoutParams();
        tileGrid.removeView(tileView);
        tileGrid.addView(tileView, targetIndex, lp);
        saveOrder(tileGrid, prefs, prefPrefix);
        tileGrid.requestLayout();
        Toast.makeText(activity, "磁贴顺序已调整", Toast.LENGTH_SHORT).show();
    }

    private static void resetDefaults(Activity activity, DiscoverTileLayout tileGrid,
                                      SharedPreferences prefs, ChatActionTileHelper.ActionTileDef[] allDefs, String prefPrefix) {
        for (ChatActionTileHelper.ActionTileDef def : allDefs) {
            prefs.edit().remove(prefPrefix + "size_" + def.id).apply();
        }
        prefs.edit().remove(prefPrefix + "order").apply();
        tileGrid.removeAllViews();
        ChatActionTileHelper.setupTileGrid(activity, (android.view.View) tileGrid.getParent(), tileGrid.getId(), allDefs, prefPrefix);
        tileGrid.requestLayout();
        Toast.makeText(activity, "已恢复默认排序", Toast.LENGTH_SHORT).show();
    }

    public static void saveOrder(DiscoverTileLayout tileGrid, SharedPreferences prefs, String prefPrefix) {
        if (tileGrid == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tileGrid.getChildCount(); i++) {
            if (i > 0) sb.append(",");
            sb.append(tileGrid.getChildAt(i).getId());
        }
        prefs.edit().putString(prefPrefix + "order", sb.toString()).apply();
    }

    public static void applySavedOrder(DiscoverTileLayout tileGrid, SharedPreferences prefs, String prefPrefix) {
        if (tileGrid == null) return;
        String saved = prefs.getString(prefPrefix + "order", "");
        if (saved == null || saved.length() == 0) return;
        String[] ids = saved.split(",");
        int insertIndex = 0;
        for (String s : ids) {
            try {
                int id = Integer.parseInt(s.trim());
                android.view.View child = tileGrid.findViewById(id);
                if (child != null && child.getParent() == tileGrid) {
                    int current = tileGrid.indexOfChild(child);
                    if (current != insertIndex) {
                        ViewGroup.LayoutParams lp = child.getLayoutParams();
                        tileGrid.removeView(child);
                        tileGrid.addView(child, Math.min(insertIndex, tileGrid.getChildCount()), lp);
                    }
                    insertIndex++;
                }
            } catch (Throwable ignored) {
            }
        }
        tileGrid.requestLayout();
    }
}
