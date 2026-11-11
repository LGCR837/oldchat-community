package com.im.oldchat.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Scroller;

import java.lang.ref.WeakReference;

/**
 * 极简轻量级侧滑返回布局。
 * 兼容 minSdk 9 (Android 2.3+)，支持左边缘手势拦截、视差渐变阴影与物理动量回弹。
 */
public class SwipeBackLayout extends FrameLayout {
    private static final int DEFAULT_EDGE_SIZE_DP = 28;
    private static final int SHADOW_WIDTH_DP = 16;
    private static final int MIN_FLING_VELOCITY = 1000;

    private WeakReference<Activity> mActivityRef;
    private View mContentView;
    private Scroller mScroller;
    private VelocityTracker mVelocityTracker;
    private GradientDrawable mShadowDrawable;

    private int mEdgeSize;
    private int mShadowWidth;
    private int mTouchSlop;
    private boolean mEnableSwipe = true;
    private boolean mIsEdgeSwipe = false;
    private boolean mIsDragging = false;
    private float mDownX;
    private float mDownY;
    private int mLastTouchX;

    public SwipeBackLayout(Context context) {
        this(context, null);
    }

    public SwipeBackLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeBackLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mEdgeSize = (int) (DEFAULT_EDGE_SIZE_DP * density + 0.5f);
        mShadowWidth = (int) (SHADOW_WIDTH_DP * density + 0.5f);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mScroller = new Scroller(context);
        mShadowDrawable = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0x40000000, 0x00000000});
        setWillNotDraw(false);
    }

    public static SwipeBackLayout attach(Activity activity) {
        if (activity == null) {
            return null;
        }
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        ViewGroup decorChild = (ViewGroup) decor.getChildAt(0);
        if (decorChild instanceof SwipeBackLayout) {
            return (SwipeBackLayout) decorChild;
        }
        decor.removeView(decorChild);
        SwipeBackLayout swipeBackLayout = new SwipeBackLayout(activity);
        swipeBackLayout.mActivityRef = new WeakReference<Activity>(activity);
        swipeBackLayout.addView(decorChild, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decor.addView(swipeBackLayout, 0);
        swipeBackLayout.mContentView = decorChild;
        return swipeBackLayout;
    }

    public void setEnableSwipe(boolean enable) {
        mEnableSwipe = enable;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mEnableSwipe || mContentView == null) {
            return super.onInterceptTouchEvent(ev);
        }

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                mLastTouchX = (int) mDownX;
                mIsEdgeSwipe = mDownX <= mEdgeSize;
                mIsDragging = false;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!mIsEdgeSwipe) {
                    break;
                }
                float dx = ev.getRawX() - mDownX;
                float dy = Math.abs(ev.getRawY() - mDownY);
                if (dx > mTouchSlop && dx > dy * 1.2f) {
                    mIsDragging = true;
                    hideKeyboard();
                    initVelocityTracker();
                    mVelocityTracker.addMovement(ev);
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsEdgeSwipe = false;
                mIsDragging = false;
                recycleVelocityTracker();
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mEnableSwipe || mContentView == null) {
            return super.onTouchEvent(ev);
        }

        initVelocityTracker();
        mVelocityTracker.addMovement(ev);

        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                if (mIsDragging) {
                    int deltaX = (int) (ev.getRawX() - mLastTouchX);
                    mLastTouchX = (int) ev.getRawX();
                    int currentTranslation = (int) ViewCompat.getTranslationX(mContentView) + deltaX;
                    if (currentTranslation < 0) {
                        currentTranslation = 0;
                    }
                    ViewCompat.setTranslationX(mContentView, currentTranslation);
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mIsDragging) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    float xVelocity = mVelocityTracker.getXVelocity();
                    int currentTranslation = (int) ViewCompat.getTranslationX(mContentView);
                    int width = getWidth();

                    if (currentTranslation > width / 3 || xVelocity > MIN_FLING_VELOCITY) {
                        smoothScrollTo(width, true);
                    } else {
                        smoothScrollTo(0, false);
                    }
                    mIsDragging = false;
                    mIsEdgeSwipe = false;
                    recycleVelocityTracker();
                    return true;
                }
                mIsDragging = false;
                mIsEdgeSwipe = false;
                recycleVelocityTracker();
                break;
        }
        return super.onTouchEvent(ev);
    }

    private void smoothScrollTo(int targetX, boolean finishActivity) {
        int startX = (int) ViewCompat.getTranslationX(mContentView);
        int dx = targetX - startX;
        int duration = Math.min(250, Math.max(120, Math.abs(dx) * 250 / Math.max(1, getWidth())));
        mScroller.startScroll(startX, 0, dx, 0, duration);
        postInvalidate();

        if (finishActivity) {
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    Activity activity = mActivityRef != null ? mActivityRef.get() : null;
                    if (activity != null && !activity.isFinishing()) {
                        activity.finish();
                        activity.overridePendingTransition(0, 0);
                    }
                }
            }, duration);
        }
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            int currentX = mScroller.getCurrX();
            ViewCompat.setTranslationX(mContentView, currentX);
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mContentView != null && mShadowDrawable != null) {
            int childLeft = (int) ViewCompat.getTranslationX(mContentView);
            if (childLeft > 0) {
                mShadowDrawable.setBounds(childLeft - mShadowWidth, 0, childLeft, getHeight());
                mShadowDrawable.draw(canvas);
            }
        }
    }

    private void hideKeyboard() {
        Activity activity = mActivityRef != null ? mActivityRef.get() : null;
        if (activity != null) {
            View focus = activity.getCurrentFocus();
            if (focus != null) {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                }
            }
        }
    }

    private void initVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }
}
