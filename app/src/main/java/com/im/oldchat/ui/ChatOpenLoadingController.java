package com.im.oldchat.ui;

import android.database.DataSetObserver;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.BaseAdapter;

final class ChatOpenLoadingController {
    private static final long MIN_SHOW_MS = 360L;
    private static final long DATA_SETTLE_MS = 180L;
    private static final long MAX_SHOW_MS = 1200L;
    private static final long FADE_OUT_MS = 140L;

    private final View maskView;
    private final boolean animate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RecyclerView.AdapterDataObserver recyclerObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            onDataChanged();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            onDataChanged();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            onDataChanged();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            onDataChanged();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            onDataChanged();
        }
    };
    private final DataSetObserver baseObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            onDataChanged();
        }

        @Override
        public void onInvalidated() {
            onDataChanged();
        }
    };
    private final Runnable forceHideRunnable = new Runnable() {
        @Override
        public void run() {
            hideNow();
        }
    };
    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hideNow();
        }
    };

    private RecyclerView.Adapter recyclerAdapter;
    private BaseAdapter baseAdapter;
    private long shownAtMs;
    private boolean hidden = true;

    ChatOpenLoadingController(View maskView, boolean animate) {
        this.maskView = maskView;
        this.animate = animate;
        if (this.maskView != null) {
            this.maskView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    skipNow();
                }
            });
        }
    }

    void attachRecyclerAdapter(RecyclerView.Adapter adapter) {
        if (recyclerAdapter == adapter) {
            return;
        }
        if (recyclerAdapter != null) {
            recyclerAdapter.unregisterAdapterDataObserver(recyclerObserver);
        }
        recyclerAdapter = adapter;
        if (recyclerAdapter != null) {
            recyclerAdapter.registerAdapterDataObserver(recyclerObserver);
        }
    }

    void attachBaseAdapter(BaseAdapter adapter) {
        if (baseAdapter == adapter) {
            return;
        }
        if (baseAdapter != null) {
            baseAdapter.unregisterDataSetObserver(baseObserver);
        }
        baseAdapter = adapter;
        if (baseAdapter != null) {
            baseAdapter.registerDataSetObserver(baseObserver);
        }
    }

    void show() {
        if (maskView == null) {
            return;
        }
        hidden = false;
        shownAtMs = System.currentTimeMillis();
        maskView.clearAnimation();
        maskView.setVisibility(View.VISIBLE);
        maskView.bringToFront();
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(forceHideRunnable);
        handler.postDelayed(forceHideRunnable, MAX_SHOW_MS);
    }

    void release() {
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(forceHideRunnable);
        if (recyclerAdapter != null) {
            recyclerAdapter.unregisterAdapterDataObserver(recyclerObserver);
            recyclerAdapter = null;
        }
        if (baseAdapter != null) {
            baseAdapter.unregisterDataSetObserver(baseObserver);
            baseAdapter = null;
        }
    }

    private void onDataChanged() {
        if (hidden || maskView == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - shownAtMs;
        long delay = Math.max(MIN_SHOW_MS - elapsed, DATA_SETTLE_MS);
        if (delay < 0L) {
            delay = 0L;
        }
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, delay);
    }

    private void hideNow() {
        if (hidden || maskView == null) {
            release();
            return;
        }
        hidden = true;
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(forceHideRunnable);
        if (!animate) {
            maskView.clearAnimation();
            maskView.setVisibility(View.GONE);
            release();
            return;
        }
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(FADE_OUT_MS);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (maskView != null) {
                    maskView.clearAnimation();
                    maskView.setVisibility(View.GONE);
                }
                release();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        maskView.startAnimation(fadeOut);
    }

    private void skipNow() {
        if (maskView == null) {
            release();
            return;
        }
        hidden = true;
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(forceHideRunnable);
        maskView.clearAnimation();
        maskView.setVisibility(View.GONE);
        release();
    }
}
