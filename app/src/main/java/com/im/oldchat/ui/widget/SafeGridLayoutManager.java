package com.im.oldchat.ui.widget;

import android.content.Context;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;

/**
 * 包装 GridLayoutManager，捕获由于列表数据并发修改或动画未完成时引起的
 * "IndexOutOfBoundsException: Inconsistency detected" 异常。
 */
public class SafeGridLayoutManager extends GridLayoutManager {

    private static final String TAG = "SafeGridLayoutManager";

    public SafeGridLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SafeGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    public SafeGridLayoutManager(Context context, int spanCount, int orientation, boolean reverseLayout) {
        super(context, spanCount, orientation, reverseLayout);
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
}
