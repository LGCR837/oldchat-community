package com.im.oldchat.ui;

import android.view.MotionEvent;
import android.view.View;
import android.support.v4.view.ViewCompat;
import android.view.ViewConfiguration;

final class ChatSwipeQuoteTouchListener implements View.OnTouchListener {
    interface Callback {
        void onSwipeProgress(float progress);
        void onSwipeRelease();
        void onSwipeQuote();
    }

    private final float minDistancePx;
    private final float maxVerticalPx;
    private final float maxTranslatePx;
    private final float tapSlopPx;
    private final View targetView;
    private final Callback callback;
    private float downX;
    private float downY;
    private boolean tracking;
    private boolean swiping;
    private boolean longPressTriggered;
    private View pressedView;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            View view = pressedView;
            if (!tracking || swiping || view == null) {
                return;
            }
            longPressTriggered = true;
            tracking = false;
            resetAnimatedState();
            notifyRelease();
            view.performLongClick();
        }
    };

    ChatSwipeQuoteTouchListener(float density, View targetView, Callback callback) {
        this.minDistancePx = density * 72f;
        this.maxVerticalPx = density * 48f;
        this.maxTranslatePx = density * 72f;
        this.tapSlopPx = density * 8f;
        this.targetView = targetView;
        this.callback = callback;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                tracking = true;
                swiping = false;
                longPressTriggered = false;
                pressedView = v;
                applyProgress(0f);
                if (v != null) {
                    v.removeCallbacks(longPressRunnable);
                    v.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) {
                    return false;
                }
                float rawDx = event.getRawX() - downX;
                float rawDy = event.getRawY() - downY;
                if (Math.abs(rawDx) > tapSlopPx || Math.abs(rawDy) > tapSlopPx) {
                    cancelLongPress(v);
                }
                if (Math.abs(rawDy) > maxVerticalPx) {
                    tracking = false;
                    resetAnimatedState();
                    notifyRelease();
                    return false;
                }
                float dx = Math.max(0f, rawDx);
                if (dx > 0f) {
                    swiping = true;
                    if (v != null && v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                applyProgress(Math.min(1f, dx / minDistancePx));
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress(v);
                tracking = false;
                swiping = false;
                longPressTriggered = false;
                pressedView = null;
                resetAnimatedState();
                notifyRelease();
                return true;
            case MotionEvent.ACTION_UP:
                cancelLongPress(v);
                if (longPressTriggered) {
                    swiping = false;
                    longPressTriggered = false;
                    pressedView = null;
                    return true;
                }
                if (!tracking) {
                    return false;
                }
                tracking = false;
                float releaseDx = Math.max(0f, event.getRawX() - downX);
                float dy = Math.abs(event.getRawY() - downY);
                boolean handledSwipe = releaseDx >= minDistancePx && dy <= maxVerticalPx && callback != null;
                resetAnimatedState();
                notifyRelease();
                if (handledSwipe) {
                    callback.onSwipeQuote();
                    return true;
                }
                if (!swiping && v != null) {
                    v.performClick();
                }
                swiping = false;
                pressedView = null;
                return true;
            default:
                return false;
        }
    }

    private void applyProgress(float progress) {
        if (targetView != null) {
            ViewCompat.setTranslationX(targetView, maxTranslatePx * progress);
            ViewCompat.setAlpha(targetView, 1f - (0.08f * progress));
        }
        if (callback != null) {
            callback.onSwipeProgress(progress);
        }
    }

    private void resetAnimatedState() {
        if (targetView != null) {
            ViewCompat.animate(targetView).translationX(0f).alpha(1f).setDuration(140L).start();
        }
    }

    private void notifyRelease() {
        if (callback != null) {
            callback.onSwipeRelease();
        }
    }

    private void cancelLongPress(View view) {
        View target = view != null ? view : pressedView;
        if (target != null) {
            target.removeCallbacks(longPressRunnable);
        }
    }
}
