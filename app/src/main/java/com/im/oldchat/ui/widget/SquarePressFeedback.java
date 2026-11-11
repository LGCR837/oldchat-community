package com.im.oldchat.ui.widget;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.im.oldchat.R;
import com.im.oldchat.util.SettingsPrefs;

public final class SquarePressFeedback {
    private static final long REAPPLY_MIN_INTERVAL_MS = 820L;
    private static final long REAPPLY_DEBOUNCE_MS = 180L;
    private static final int MAX_SCAN_NODES = 320;
    private static Method getListenerInfoMethod;
    private static Field onTouchListenerField;
    private static boolean listenerInfoResolved;

    private SquarePressFeedback() {
    }

    public static void install(final View root) {
        if (root == null) {
            return;
        }
        if (!SettingsPrefs.isButton3dEffectEnabled(root.getContext())) {
            return;
        }
        apply(root);
        Object installed = root.getTag(R.id.tag_square_press_installed);
        if (Boolean.TRUE.equals(installed)) {
            return;
        }
        root.setTag(R.id.tag_square_press_installed, Boolean.TRUE);
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            return;
        }
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                scheduleApply(root);
            }
        });
    }

    public static void apply(View root) {
        if (root == null) {
            return;
        }
        if (!SettingsPrefs.isButton3dEffectEnabled(root.getContext())) {
            return;
        }
        applyRecursive(root, 0);
        root.setTag(R.id.tag_square_press_last_apply_ms, Long.valueOf(SystemClock.uptimeMillis()));
    }

    private static void scheduleApply(final View root) {
        if (root == null) {
            return;
        }
        Object pending = root.getTag(R.id.tag_square_press_pending);
        if (Boolean.TRUE.equals(pending)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long lastApply = readLastApplyMs(root);
        long delay = REAPPLY_DEBOUNCE_MS;
        if (lastApply > 0L && now - lastApply < REAPPLY_MIN_INTERVAL_MS) {
            delay = REAPPLY_MIN_INTERVAL_MS - (now - lastApply);
        }
        root.setTag(R.id.tag_square_press_pending, Boolean.TRUE);
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                root.setTag(R.id.tag_square_press_pending, Boolean.FALSE);
                if (root.getWindowToken() == null) {
                    return;
                }
                apply(root);
            }
        }, Math.max(0L, delay));
    }

    private static long readLastApplyMs(View root) {
        Object tag = root.getTag(R.id.tag_square_press_last_apply_ms);
        if (tag instanceof Long) {
            return ((Long) tag).longValue();
        }
        return 0L;
    }

    private static int applyRecursive(View view, int visited) {
        if (visited >= MAX_SCAN_NODES) {
            return visited;
        }
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return visited;
        }
        visited++;
        bindIfNeeded(view);
        if (!(view instanceof ViewGroup)) {
            return visited;
        }
        if (isSkipSubtreeContainer(view)) {
            return visited;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            visited = applyRecursive(group.getChildAt(i), visited);
            if (visited >= MAX_SCAN_NODES) {
                break;
            }
        }
        return visited;
    }

    private static void bindIfNeeded(View view) {
        if (view == null || !view.isClickable()) {
            return;
        }
        if (view instanceof TiltFrameLayout) {
            return;
        }
        if (view.getId() == R.id.btnNewMessage
                || view.getId() == R.id.btnJumpToUnread) {
            return;
        }
        if (isInChatMessageList(view) || isInSwipeListContainer(view)) {
            return;
        }
        Object boundTag = view.getTag(R.id.tag_square_press_bound);
        if (Boolean.TRUE.equals(boundTag)) {
            return;
        }
        View.OnTouchListener existingTouch = resolveCurrentTouchListener(view);
        if (existingTouch instanceof SquareTiltTouchListener) {
            view.setTag(R.id.tag_square_press_bound, Boolean.TRUE);
            return;
        }
        view.setTag(R.id.tag_square_press_bound, Boolean.TRUE);
        view.setOnTouchListener(new SquareTiltTouchListener(view, existingTouch));
    }

    private static View.OnTouchListener resolveCurrentTouchListener(View view) {
        if (view == null) {
            return null;
        }
        try {
            ensureListenerInfoReflection();
            if (getListenerInfoMethod == null || onTouchListenerField == null) {
                return null;
            }
            Object listenerInfo = getListenerInfoMethod.invoke(view);
            if (listenerInfo == null) {
                return null;
            }
            Object listener = onTouchListenerField.get(listenerInfo);
            if (listener instanceof View.OnTouchListener) {
                return (View.OnTouchListener) listener;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureListenerInfoReflection() {
        if (listenerInfoResolved) {
            return;
        }
        listenerInfoResolved = true;
        try {
            getListenerInfoMethod = View.class.getDeclaredMethod("getListenerInfo");
            getListenerInfoMethod.setAccessible(true);
            Class<?> listenerInfoCls = Class.forName("android.view.View$ListenerInfo");
            onTouchListenerField = listenerInfoCls.getDeclaredField("mOnTouchListener");
            onTouchListenerField.setAccessible(true);
        } catch (Throwable ignored) {
            getListenerInfoMethod = null;
            onTouchListenerField = null;
        }
    }

    private static boolean isInChatMessageList(View view) {
        View current = view;
        while (current != null) {
            if (current.getId() == R.id.lvMessages) {
                return true;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            current = (View) parent;
        }
        return false;
    }

    private static boolean isInSwipeListContainer(View view) {
        View current = view;
        while (current != null) {
            if (isSkipSubtreeContainer(current)) {
                return true;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            current = (View) parent;
        }
        return false;
    }

    private static boolean isSkipSubtreeContainer(View view) {
        if (view instanceof AbsListView) {
            return true;
        }
        String cls = view.getClass().getName();
        return "android.support.v7.widget.RecyclerView".equals(cls)
                || "androidx.recyclerview.widget.RecyclerView".equals(cls);
    }
}
