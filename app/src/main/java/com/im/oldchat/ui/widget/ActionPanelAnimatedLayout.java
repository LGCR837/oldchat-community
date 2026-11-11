package com.im.oldchat.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;
import android.widget.LinearLayout;

/**
 * 聊天底栏功能面板（+号展开面板）动画容器。
 * 具备高度防塌陷与残缺自愈机制，彻底避免动画被打断或测量异常时面板只露出一小截导致功能点不到的问题。
 * 严格遵循 Java 7 语法与 Android 2.3+ (minSdk 9) 规范。
 */
public class ActionPanelAnimatedLayout extends LinearLayout {
    private static final long ENTER_ANIM_MS = 220L;
    private static final long EXIT_ANIM_MS = 180L;
    private static final int DEFAULT_MIN_HEIGHT_DP = 160;

    private boolean attached;
    private boolean internalChange;
    private int measuredContentHeight = 0;

    public ActionPanelAnimatedLayout(Context context) {
        super(context);
    }

    public ActionPanelAnimatedLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ActionPanelAnimatedLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);
    }

    private int getMinPanelHeight() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int baseMin = (int) (DEFAULT_MIN_HEIGHT_DP * dm.density + 0.5f);
        // 如果屏幕可用总高度极矮（如小屏或横屏），保底高度不超过屏幕高度的 45%
        if (dm.heightPixels > 0) {
            int maxCap = dm.heightPixels * 45 / 100;
            if (maxCap > 0 && baseMin > maxCap) {
                return maxCap;
            }
        }
        return baseMin;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        ensureWrapContentHeight();
    }

    @Override
    protected void onDetachedFromWindow() {
        clearAnimation();
        attached = false;
        ensureWrapContentHeight();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int h = b - t;
        int minH = getMinPanelHeight();
        // 只有当高度真正大于等于面板最小保底高度且处于 WRAP_CONTENT 状态时，才允许更新高度缓存
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (h >= minH && (lp == null || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT)) {
            measuredContentHeight = h;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        if (internalChange) {
            super.setVisibility(visibility);
            return;
        }
        int current = getVisibility();
        if (!attached) {
            clearAnimation();
            ensureWrapContentHeight();
            super.setVisibility(visibility);
            return;
        }
        if (visibility == current) {
            clearAnimation();
            ensureWrapContentHeight();
            super.setVisibility(visibility);
            return;
        }
        if (visibility == VISIBLE) {
            clearAnimation();
            ensureWrapContentHeight();
            animateExpand();
            return;
        }
        if (current == VISIBLE) {
            clearAnimation();
            animateCollapse(visibility);
            return;
        }
        clearAnimation();
        ensureWrapContentHeight();
        super.setVisibility(visibility);
    }

    private void ensureWrapContentHeight() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            setLayoutParams(lp);
        }
    }

    private void applyVisibilityInternal(int visibility) {
        internalChange = true;
        try {
            super.setVisibility(visibility);
        } finally {
            internalChange = false;
        }
    }

    private int measureTargetHeight() {
        int minH = getMinPanelHeight();
        if (measuredContentHeight >= minH) {
            return measuredContentHeight;
        }
        int width = getWidth();
        if (width <= 0 && getParent() instanceof View) {
            width = ((View) getParent()).getWidth();
        }
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        int widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        measure(widthSpec, heightSpec);
        int measured = getMeasuredHeight();
        int finalH = Math.max(measured, minH);
        measuredContentHeight = finalH;
        return finalH;
    }

    private void animateExpand() {
        final int targetHeight = measureTargetHeight();
        final ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            setLayoutParams(lp);
        }
        applyVisibilityInternal(VISIBLE);

        Animation anim = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                if (lp != null) {
                    if (interpolatedTime >= 1.0f) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    } else {
                        lp.height = Math.max(0, (int) (targetHeight * interpolatedTime));
                    }
                    setLayoutParams(lp);
                }
            }

            @Override
            public boolean willChangeBounds() {
                return true;
            }
        };
        anim.setDuration(ENTER_ANIM_MS);
        anim.setInterpolator(new DecelerateInterpolator(2.0f));
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                clearAnimation();
                ensureWrapContentHeight();
            }
        });
        startAnimation(anim);
    }

    private void animateCollapse(final int targetVisibility) {
        int minH = getMinPanelHeight();
        final int initialHeight = Math.max(getHeight() > 0 ? getHeight() : measureTargetHeight(), minH);
        final ViewGroup.LayoutParams lp = getLayoutParams();

        Animation anim = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                if (lp != null) {
                    if (interpolatedTime >= 1.0f) {
                        lp.height = 0;
                    } else {
                        lp.height = Math.max(0, (int) (initialHeight * (1.0f - interpolatedTime)));
                    }
                    setLayoutParams(lp);
                }
            }

            @Override
            public boolean willChangeBounds() {
                return true;
            }
        };
        anim.setDuration(EXIT_ANIM_MS);
        anim.setInterpolator(new AccelerateInterpolator(1.8f));
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                clearAnimation();
                applyVisibilityInternal(targetVisibility);
                ensureWrapContentHeight();
            }
        });
        startAnimation(anim);
    }
}
