package com.im.oldchat.ui.widget;

import android.content.Context;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;

/**
 * 包装 LinearLayoutManager，捕获由于列表数据并发修改或动画未完成时引起的
 * "IndexOutOfBoundsException: Inconsistency detected" 异常，避免应用崩溃。
 */
public class SafeLinearLayoutManager extends LinearLayoutManager {

    private static final String TAG = "SafeLinearLayoutManager";

    public SafeLinearLayoutManager(Context context) {
        super(context);
    }

    public SafeLinearLayoutManager(Context context, int orientation, boolean reverseLayout) {
        super(context, orientation, reverseLayout);
    }

    public SafeLinearLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        try {
            super.onLayoutChildren(recycler, state);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "Inconsistency detected in onLayoutChildren, caught safely", e);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in onLayoutChildren", t);
        }
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
        try {
            return super.scrollVerticallyBy(dy, recycler, state);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "Inconsistency detected in scrollVerticallyBy, caught safely", e);
            return 0;
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in scrollVerticallyBy", t);
            return 0;
        }
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        try {
            return super.scrollHorizontallyBy(dx, recycler, state);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "Inconsistency detected in scrollHorizontallyBy, caught safely", e);
            return 0;
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in scrollHorizontallyBy", t);
            return 0;
        }
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    @Override
    public void scrollToPositionWithOffset(int position, int offset) {
        try {
            super.scrollToPositionWithOffset(position, offset);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "Inconsistency detected in scrollToPositionWithOffset, caught safely", e);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in scrollToPositionWithOffset", t);
        }
    }

    @Override
    public void scrollToPosition(int position) {
        try {
            super.scrollToPosition(position);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "Inconsistency detected in scrollToPosition, caught safely", e);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in scrollToPosition", t);
        }
    }
}
