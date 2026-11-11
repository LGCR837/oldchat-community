package com.im.oldchat.ui.widget;

import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewPager;
import android.view.View;

/**
 * Vertical cover/pull transition:
 * - Incoming page covers from bottom to top.
 * - Current page is pulled upward slightly.
 */
public class MetroPageTransformer implements ViewPager.PageTransformer {
    private static final float OUTGOING_PULL_FACTOR = 0.30f;
    private static final float OUTGOING_FADE = 0.30f;
    private static final float OUTGOING_MIN_SCALE = 0.97f;
    private static final float INCOMING_START_ALPHA = 0.84f;
    private static final float EDGE_EPS = 0.999f;

    @Override
    public void transformPage(View page, float position) {
        if (page == null) {
            return;
        }
        float width = Math.max(1f, page.getWidth());
        float height = Math.max(1f, page.getHeight());
        float clampedPos = clamp(position, -1f, 1f);
        float progress = Math.abs(clampedPos);

        if (progress >= EDGE_EPS) {
            page.setVisibility(View.INVISIBLE);
            ViewCompat.setAlpha(page, 0f);
            ViewCompat.setScaleX(page, 1f);
            ViewCompat.setScaleY(page, 1f);
            ViewCompat.setTranslationY(page, 0f);
            return;
        }

        page.setVisibility(View.VISIBLE);
        ViewCompat.setScaleX(page, 1f);
        ViewCompat.setScaleY(page, 1f);

        // Remove default horizontal slide; this animation is purely vertical.
        float baseX = -clampedPos * width;
        ViewCompat.setTranslationX(page, baseX);

        if (clampedPos > 0f) {
            // Incoming page: start below and cover upward.
            ViewCompat.setTranslationY(page, height * clampedPos);
            ViewCompat.setAlpha(page, INCOMING_START_ALPHA + (1f - INCOMING_START_ALPHA) * (1f - clampedPos));
            return;
        }

        // Outgoing/current page: pull upward with slight fade and tiny shrink.
        float outProgress = -clampedPos; // 0..1
        ViewCompat.setTranslationY(page, -height * OUTGOING_PULL_FACTOR * outProgress);
        ViewCompat.setAlpha(page, 1f - OUTGOING_FADE * outProgress);
        float outScale = 1f - (1f - OUTGOING_MIN_SCALE) * outProgress;
        ViewCompat.setScaleX(page, outScale);
        ViewCompat.setScaleY(page, outScale);
    }

    private float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
