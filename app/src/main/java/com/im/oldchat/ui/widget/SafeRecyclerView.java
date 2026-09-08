package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

/**
 * 安全的 RecyclerView 封装。
 * 捕获滑动、触摸、绘制、布局排版过程中由于多线程并发更新、
 * 数据增删未同步或动画残留导致的 IndexOutOfBoundsException / Inconsistency detected 等异常，避免闪退。
 */
public class SafeRecyclerView extends RecyclerView {

    private static final String TAG = "SafeRecyclerView";

    public SafeRecyclerView(Context context) {
        super(context);
        init();
    }

    public SafeRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SafeRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        if (getLayoutManager() == null) {
            setLayoutManager(new SafeLinearLayoutManager(getContext()));
        }
        setItemAnimator(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            return super.onTouchEvent(e);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in onTouchEvent, caught safely", ex);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in onTouchEvent", t);
            return false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            return super.dispatchTouchEvent(ev);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in dispatchTouchEvent, caught safely", ex);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in dispatchTouchEvent", t);
            return false;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        try {
            return super.onInterceptTouchEvent(e);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in onInterceptTouchEvent, caught safely", ex);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in onInterceptTouchEvent", t);
            return false;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int top, int r, int b) {
        try {
            super.onLayout(changed, l, top, r, b);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in onLayout, caught safely", ex);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in onLayout", t);
        }
    }

    @Override
    public void draw(Canvas c) {
        try {
            super.draw(c);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in draw, caught safely", ex);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in draw", t);
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        try {
            super.dispatchDraw(c);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in dispatchDraw, caught safely", ex);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in dispatchDraw", t);
        }
    }

    @Override
    public void scrollBy(int x, int y) {
        try {
            super.scrollBy(x, y);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in scrollBy, caught safely", ex);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in scrollBy", t);
        }
    }

    @Override
    public void scrollToPosition(int position) {
        try {
            super.scrollToPosition(position);
        } catch (IndexOutOfBoundsException ex) {
            Log.e(TAG, "Inconsistency detected in scrollToPosition, caught safely", ex);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in scrollToPosition", t);
        }
    }
}
