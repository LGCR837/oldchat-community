package com.im.oldchat.util;

import android.support.v4.view.ViewCompat;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;

public class TypingAnimator {
    private static final long DOT_DURATION_MS = 520L;
    private static final long DOT_DELAY_MS = 170L;
    private static final float DOT_MIN_ALPHA = 0.35f;

    private boolean isRunning = false;
    private View dot1;
    private View dot2;
    private View dot3;

    public void start(View d1, View d2, View d3) {
        if (d1 == null || d2 == null || d3 == null) {
            return;
        }
        if (isRunning) {
            stop();
        }
        isRunning = true;
        dot1 = d1;
        dot2 = d2;
        dot3 = d3;

        ViewCompat.setAlpha(dot1, 1f);
        ViewCompat.setAlpha(dot2, 1f);
        ViewCompat.setAlpha(dot3, 1f);

        Animation anim1 = createDotAnimation(0L);
        Animation anim2 = createDotAnimation(DOT_DELAY_MS);
        Animation anim3 = createDotAnimation(DOT_DELAY_MS * 2L);
        d1.startAnimation(anim1);
        d2.startAnimation(anim2);
        d3.startAnimation(anim3);
    }

    public void stop() {
        isRunning = false;
        if (dot1 != null) {
            dot1.clearAnimation();
            ViewCompat.setAlpha(dot1, 1f);
        }
        if (dot2 != null) {
            dot2.clearAnimation();
            ViewCompat.setAlpha(dot2, 1f);
        }
        if (dot3 != null) {
            dot3.clearAnimation();
            ViewCompat.setAlpha(dot3, 1f);
        }
        dot1 = null;
        dot2 = null;
        dot3 = null;
    }

    private Animation createDotAnimation(long startDelay) {
        AlphaAnimation animator = new AlphaAnimation(1f, DOT_MIN_ALPHA);
        animator.setDuration(DOT_DURATION_MS);
        animator.setStartOffset(startDelay);
        animator.setRepeatCount(Animation.INFINITE);
        animator.setRepeatMode(Animation.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.setFillAfter(false);
        return animator;
    }
}
