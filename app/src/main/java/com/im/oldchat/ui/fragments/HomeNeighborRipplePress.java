package com.im.oldchat.ui.fragments;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.im.oldchat.R;
import com.im.oldchat.ui.widget.TiltFrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 主页会话行的邻接 3D 水波。递归收集整个主页内的可见会话行，
 * 按屏幕 Y 坐标排序，所以跨“新消息/聊天/自定义分组”也能联动。
 */
final class HomeNeighborRipplePress {
    private static final int MAX_DISTANCE = 2;

    private HomeNeighborRipplePress() { }

    static void bind(final View row) {
        if (!(row instanceof TiltFrameLayout)) return;
        ((TiltFrameLayout) row).setPressLifecycleListener(
                new TiltFrameLayout.PressLifecycleListener() {
            @Override public void onPressChanged(TiltFrameLayout view, boolean pressed) {
                animateNeighbors(view, pressed);
            }
        });
    }

    private static void animateNeighbors(TiltFrameLayout center, boolean pressed) {
        View root = findHomeRoot(center);
        if (root == null) return;
        List<TiltFrameLayout> rows = new ArrayList<TiltFrameLayout>();
        collectRows(root, rows);
        if (rows.size() < 2) return;
        final int[] location = new int[2];
        Collections.sort(rows, new Comparator<TiltFrameLayout>() {
            @Override public int compare(TiltFrameLayout left, TiltFrameLayout right) {
                left.getLocationOnScreen(location);
                int leftY = location[1];
                right.getLocationOnScreen(location);
                int rightY = location[1];
                return leftY < rightY ? -1 : (leftY == rightY ? 0 : 1);
            }
        });
        int index = rows.indexOf(center);
        if (index < 0) return;
        for (int distance = 1; distance <= MAX_DISTANCE; distance++) {
            animateOne(index - distance >= 0 ? rows.get(index - distance) : null,
                    distance, -1, pressed);
            animateOne(index + distance < rows.size() ? rows.get(index + distance) : null,
                    distance, 1, pressed);
        }
    }

    private static View findHomeRoot(View view) {
        View current = view;
        while (current != null) {
            if (current.getId() == R.id.pastelMainContainer) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static void collectRows(View node, List<TiltFrameLayout> out) {
        if (node instanceof TiltFrameLayout && node.getTag() instanceof PastelChatRowRenderer.RowHolderMarker
                && node.getVisibility() == View.VISIBLE && node.getHeight() > 0) {
            out.add((TiltFrameLayout) node);
            return;
        }
        if (!(node instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) node;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) collectRows(child, out);
        }
    }

    private static void animateOne(TiltFrameLayout tile, int distance,
                                   int direction, boolean pressed) {
        if (tile == null) return;
        float angle = pressed ? direction * (distance == 1 ? 10f : 5.5f) : 0f;
        float sink = pressed ? (distance == 1 ? 3.2f : 1.6f) : 0f;
        tile.animateNeighborTilt(angle, sink,
                pressed ? distance * 22L : distance * 14L);
    }
}
