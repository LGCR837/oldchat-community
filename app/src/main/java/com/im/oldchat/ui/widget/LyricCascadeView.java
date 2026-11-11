package com.im.oldchat.ui.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.StaticLayout;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.os.Build;

import com.im.oldchat.models.LyricLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LyricCascadeView extends View {

    public interface OnLyricSeekListener {
        void onSeekTo(long timeMs);
    }

    public interface OnEmptyClickListener {
        void onEmptyClick();
    }

    // 颜色默认值：暗色模式（白色系）
    private static final int ACTIVE_COLOR_DEFAULT = Color.WHITE;
    private static final int NORMAL_COLOR_DEFAULT = Color.parseColor("#B3FFFFFF");
    private static final int FADE_COLOR_DEFAULT = Color.parseColor("#5CFFFFFF");
    // 两色模式：暗色系歌词（配合浅色/两色动态背景）
    private static final int ACTIVE_COLOR_TWO_TONE = Color.parseColor("#3A3733");
    private static final int NORMAL_COLOR_TWO_TONE = Color.parseColor("#8C837A");
    private static final int FADE_COLOR_TWO_TONE = Color.parseColor("#B5ABA0");

    private int mActiveColor = ACTIVE_COLOR_DEFAULT;
    private int mNormalColor = NORMAL_COLOR_DEFAULT;
    private int mFadeColor = FADE_COLOR_DEFAULT;

    // 内存优化：限制最大歌词行数和动画数量
    private static final int MAX_LYRIC_LINES = 200;  // 最大显示200行歌词
    private static final int MAX_ANIMATORS = 50;     // 最大同时运行的动画数
    private static final long INTRO_STEP_DELAY = 52L;
    private static final long INTRO_LINE_DURATION = 360L;
    private static final long EXIT_STEP_DELAY = 34L;
    private static final long EXIT_LINE_DURATION = 280L;
    private static final int ANIM_VISIBLE_RANGE = 5;

    private final TextPaint mTextPaint;
    private final TextPaint mHighlightPaint;
    private final TextPaint mHintPaint;
    private final float mDensity;
    private final float mActiveTextSize;
    private final float mNormalTextSize;
    private final float mSidePadding;
    private final float mLineHeight;

    private final List<LyricLine> mLyrics = new ArrayList<LyricLine>();
    private final List<LineState> mStates = new ArrayList<LineState>();
    // API<11 无 android.animation.ValueAnimator，用 Object 承载避免类加载崩溃（动画在 API<11 直接跳过）
    private final List<Object> mAnimators = new ArrayList<Object>();

    private String mHintText = "暂无歌词";
    private int mCurrentIndex = -1;
    private int mPlaybackIndex = -1;
    private int[] mActiveLineSet = new int[0];
    private boolean mHasLyrics = false;
    private boolean mIntroPlayed = false;
    private boolean mIsIntroAnimating = false;
    private boolean mEnableMultilineLayout = true;
    private boolean mIsUserDragging = false;
    private int mAnimationSession = 0;
    private boolean mPreviewEnabled = false;
    private boolean mBounceEnabled = true;
    private boolean mPlaybackRunning = false;
    private boolean mHasTimedLyrics = false;
    private boolean mFrameLoopPosted = false;
    private long mCurrentTimeMs = 0L;
    private long mAnchorLyricTimeMs = 0L;
    private long mAnchorRealtimeMs = 0L;
    private long mLastFrameRealtimeMs = 0L;
    private float mLastTouchY = 0f;
    private float mTouchDownY = 0f;
    private float mPreviewOffsetY = 0f;
    private OnLyricSeekListener mSeekListener;
    private OnEmptyClickListener mEmptyClickListener;
    private VelocityTracker mVelocityTracker;
    private Runnable mPendingExitEndRunnable;

    private static final float MIN_FLING_VELOCITY = 600f;
    private static final float MAX_FLING_OFFSET_LINES = 6f;

    private final Runnable mPreviewResetRunnable = new Runnable() {
        @Override
        public void run() {
            settleFromPreview();
        }
    };

    private final Runnable mFrameTicker = new Runnable() {
        @Override
        public void run() {
            mFrameLoopPosted = false;
            if (!mHasLyrics) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            float deltaSeconds;
            if (mLastFrameRealtimeMs <= 0L) {
                deltaSeconds = 0.016f;
            } else {
                deltaSeconds = Math.max(0.001f, Math.min(0.025f, (now - mLastFrameRealtimeMs) / 1000f));
            }
            mLastFrameRealtimeMs = now;
            if (mPlaybackRunning) {
                updateCurrentTimeInternal(getAnimatedTimeMs(), false);
            }
            boolean changed = updateLineTransforms(deltaSeconds);
            if (changed) {
                postInvalidateOnAnimationCompat();
            }
            ensureFrameLoop();
        }
    };

    public LyricCascadeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (Build.VERSION.SDK_INT >= 11) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        mDensity = getResources().getDisplayMetrics().density;

        // 歌词字号：固定基准 18sp（OldChat 无独立字号设置项）
        int lyricSize = 18;
        float sizeRatio = lyricSize / 18f; // 以18sp为基准计算比例

        mActiveTextSize = sp(lyricSize);
        mNormalTextSize = sp(lyricSize * 0.78f);
        mSidePadding = dp(24f);
        mLineHeight = dp(72f * sizeRatio);

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        mTextPaint.setTextSize(mNormalTextSize);
        mTextPaint.setColor(mNormalColor);

        mHighlightPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mHighlightPaint.setTextAlign(Paint.Align.LEFT);
        mHighlightPaint.setTextSize(mNormalTextSize);
        mHighlightPaint.setColor(mActiveColor);
        mHighlightPaint.setFakeBoldText(true);
        mHighlightPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mHighlightPaint.setStrokeWidth(dp(0.55f));

        mHintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mHintPaint.setColor(mNormalColor);
        mHintPaint.setTextAlign(Paint.Align.CENTER);
        mHintPaint.setTextSize(sp(16f * sizeRatio));
    }

    /** 设置歌词颜色模式：0=暗色（白色系，默认），1=两色（暗色系）。 */
    public void setLyricColorMode(int mode) {
        int active = ACTIVE_COLOR_DEFAULT;
        int normal = NORMAL_COLOR_DEFAULT;
        int fade = FADE_COLOR_DEFAULT;
        if (mode == 1) {
            active = ACTIVE_COLOR_TWO_TONE;
            normal = NORMAL_COLOR_TWO_TONE;
            fade = FADE_COLOR_TWO_TONE;
        }
        if (mActiveColor != active || mNormalColor != normal || mFadeColor != fade) {
            mActiveColor = active;
            mNormalColor = normal;
            mFadeColor = fade;
            mTextPaint.setColor(mNormalColor);
            mHighlightPaint.setColor(mActiveColor);
            mHintPaint.setColor(mNormalColor);
            invalidate();
        }
    }

    /** 设置歌词动效弹性开关（true=弹簧缩放/位移动效，false=平滑线性移动）。 */
    public void setBounceEnabled(boolean enabled) {
        if (mBounceEnabled == enabled) {
            return;
        }
        mBounceEnabled = enabled;
        if (!enabled) {
            // 关闭弹性：立即把弹簧状态跳到目标值，避免残影
            for (int i = 0; i < mStates.size(); i++) {
                LineState state = mStates.get(i);
                if (state.posYSpring != null) {
                    state.posYSpring.setPosition(state.currentY);
                }
                if (state.scaleSpring != null) {
                    state.scaleSpring.setPosition(state.currentScale);
                }
            }
            invalidate();
        }
    }

    public boolean isBounceEnabled() {
        return mBounceEnabled;
    }

    public void showMessage(String text) {
        cancelAllAnimators();
        mHintText = TextUtils.isEmpty(text) ? "暂无歌词" : text;
        mHasLyrics = false;
        mCurrentIndex = -1;
        mPlaybackIndex = -1;
        mIntroPlayed = false;
        mIsIntroAnimating = false;
        mEnableMultilineLayout = true;
        mIsUserDragging = false;
        mPlaybackRunning = false;
        mHasTimedLyrics = false;
        mFrameLoopPosted = false;
        mPreviewOffsetY = 0f;
        mCurrentTimeMs = 0L;
        mAnchorLyricTimeMs = 0L;
        mAnchorRealtimeMs = 0L;
        mLastFrameRealtimeMs = 0L;
        removeCallbacks(mFrameTicker);
        releaseVelocityTracker();
        mLyrics.clear();
        mStates.clear();
        clearLockedWraps();
        invalidate();
    }

    public void setLyrics(List<LyricLine> lyrics) {
        boolean wasPlaybackRunning = mPlaybackRunning;
        cancelAllAnimators();
        mLyrics.clear();
        mStates.clear();
        clearLockedWraps();
        mCurrentIndex = -1;
        mPlaybackIndex = -1;
        mIntroPlayed = false;
        mIsIntroAnimating = false;
        mEnableMultilineLayout = true;
        mIsUserDragging = false;
        mPlaybackRunning = wasPlaybackRunning;
        mHasTimedLyrics = false;
        mFrameLoopPosted = false;
        mPreviewOffsetY = 0f;
        mCurrentTimeMs = 0L;
        mAnchorLyricTimeMs = 0L;
        mAnchorRealtimeMs = 0L;
        mLastFrameRealtimeMs = 0L;
        removeCallbacks(mFrameTicker);
        releaseVelocityTracker();

        if (lyrics == null || lyrics.isEmpty()) {
            showMessage("暂无歌词");
            return;
        }

        // 限制最大歌词行数，防止极端情况内存溢出
        int limit = Math.min(lyrics.size(), MAX_LYRIC_LINES);
        for (int i = 0; i < limit; i++) {
            LyricLine line = lyrics.get(i);
            if (line != null && !TextUtils.isEmpty(line.getText())) {
                mLyrics.add(line);
                if (line.hasTimedWords()) {
                    mHasTimedLyrics = true;
                }
            }
        }
        if (mLyrics.isEmpty()) {
            showMessage("暂无歌词");
            return;
        }

        mHasLyrics = true;
        float topY = getViewportTopY();
        float windowStart = getWindowStartFloat(0f);
        for (int i = 0; i < mLyrics.size(); i++) {
            LineState state = new LineState();
            state.line = mLyrics.get(i);
            state.text = state.line.getText();
            state.index = i;
            float baseY = topY + ((i - windowStart) * mLineHeight);
            state.currentY = baseY;
            state.targetY = baseY;
            state.currentX = mSidePadding;
            state.targetX = mSidePadding;
            state.currentAlpha = 0f;
            state.targetAlpha = alphaForDistance(Math.abs(i - 0));
            state.currentScale = 1f;
            state.targetScale = 1f;
            state.currentHighlightAlpha = 0f;
            state.targetHighlightAlpha = 0f;
            state.posYSpring = new SpringValue(baseY, 0.9f, 15f, 90f);
            state.scaleSpring = new SpringValue(1f, 2f, 25f, 100f);
            state.alphaLinear = new LinearValue(0f, 0.22f);
            state.highlightLinear = new LinearValue(0f, 0.18f);
            mStates.add(state);
        }
        if (mPlaybackRunning && mHasTimedLyrics) {
            mAnchorRealtimeMs = SystemClock.elapsedRealtime();
            ensureFrameLoop();
        }
        requestLayout();
        invalidate();
    }

    /**
     * 重置歌词位置到当前播放位置（用于滑动切换后恢复显示）
     */
    public void resetToCurrentPosition() {
        if (!mHasLyrics || mStates.isEmpty()) {
            return;
        }
        cancelAllAnimators();
        clearLockedWraps();
        mHandler.removeCallbacks(mPreviewResetRunnable);
        mIsUserDragging = false;
        mPreviewOffsetY = 0f;
        releaseVelocityTracker();
        if (mPlaybackIndex >= 0) {
            mCurrentIndex = mPlaybackIndex;
        }
        applyStaticLayout(mCurrentIndex >= 0 ? mCurrentIndex : 0, true);
    }

    public void playIntroAnimation() {
        playIntroAnimation(false);
    }

    public void setOnLyricSeekListener(OnLyricSeekListener listener) {
        mSeekListener = listener;
    }

    public void setOnEmptyClickListener(OnEmptyClickListener listener) {
        mEmptyClickListener = listener;
    }

    public void setPreviewEnabled(boolean enabled) {
        if (mPreviewEnabled == enabled) {
            return;
        }
        mPreviewEnabled = enabled;
        if (!enabled) {
            cancelPreviewInteraction();
        }
    }

    public void playIntroAnimation(boolean force) {
        if (!mHasLyrics || mStates.isEmpty()) {
            return;
        }
        if (mIntroPlayed && !force) {
            return;
        }
        cancelAllAnimators();
        cancelPendingExitCallback();
        mAnimationSession++;
        final int session = mAnimationSession;
        mIntroPlayed = true;
        mIsIntroAnimating = true;
        // 入场动画期间也保持最终排版，避免翻译/换行在动画结束后再跳变一帧。
        mEnableMultilineLayout = true;

        final float width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        final int anchorIndex = mCurrentIndex >= 0 ? mCurrentIndex : 0;
        final int visibleStart = Math.max(0, anchorIndex - ANIM_VISIBLE_RANGE);
        final int visibleEnd = Math.min(mStates.size() - 1, anchorIndex + ANIM_VISIBLE_RANGE);
        final long stepDelay = INTRO_STEP_DELAY;
        final long duration = INTRO_LINE_DURATION;
        final long maxDelay = Math.max(0, visibleEnd - visibleStart) * stepDelay;
        final float topY = getViewportTopY();
        final float windowStart = getWindowStartFloat(anchorIndex);
        ensureStateLayoutMetrics();
        lockWrapsForIntro();

        boolean continueFromCurrentState = force && hasVisibleLyricState();

        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            int distance = Math.abs(i - anchorIndex);
            float targetY = resolveLineCenterY(i, windowStart, topY);
            float targetScale = distance == 0 ? 1.2f : (distance == 1 ? 1.08f : 1f);
            float targetAlpha = alphaForDistance(distance);
            float introOffsetY = distance == 0 ? 0f : (i < anchorIndex ? -dp(4f) : dp(4f));
            float startX = width + dp(48f) + ((i - visibleStart) * dp(10f));

            state.targetY = targetY;
            state.targetX = mSidePadding;
            state.targetAlpha = targetAlpha;
            state.targetScale = targetScale;

            if (!continueFromCurrentState) {
                state.currentY = targetY + introOffsetY;
                state.currentX = startX;
                state.currentAlpha = 0f;
                state.currentScale = distance == 0 ? 1.08f : 1f;
            }
        }

        final ArrayList<Float> startXList = new ArrayList<Float>();
        final ArrayList<Float> startYList = new ArrayList<Float>();
        final ArrayList<Float> startAlphaList = new ArrayList<Float>();
        final ArrayList<Float> startScaleList = new ArrayList<Float>();
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            startXList.add(Float.valueOf(state.currentX));
            startYList.add(Float.valueOf(state.currentY));
            startAlphaList.add(Float.valueOf(state.currentAlpha));
            startScaleList.add(Float.valueOf(state.currentScale));
        }

        if (Build.VERSION.SDK_INT >= 11) {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration + maxDelay);
            animator.setInterpolator(null);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float globalPlayTime = ((Float) animation.getAnimatedValue()).floatValue()
                        * (duration + maxDelay);
                for (int i = 0; i < mStates.size(); i++) {
                    LineState state = mStates.get(i);
                    int clampedIndex = Math.max(visibleStart, Math.min(i, visibleEnd));
                    long lineDelay = Math.max(0, clampedIndex - visibleStart) * stepDelay;
                    float local = clamp01((globalPlayTime - lineDelay) / (float) duration);
                    float eased = easeInOutCubic(local);
                    state.currentX = lerp(startXList.get(i).floatValue(), state.targetX, eased);
                    state.currentY = lerp(startYList.get(i).floatValue(), state.targetY, eased);
                    state.currentAlpha = lerp(startAlphaList.get(i).floatValue(), state.targetAlpha, eased);
                    state.currentScale = lerp(startScaleList.get(i).floatValue(), state.targetScale, eased);
                }
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (session != mAnimationSession) {
                    return;
                }
                mIsIntroAnimating = false;
                for (int i = 0; i < mStates.size(); i++) {
                    LineState state = mStates.get(i);
                    state.currentX = state.targetX;
                    state.currentY = state.targetY;
                    state.currentAlpha = state.targetAlpha;
                    state.currentScale = state.targetScale;
                    if (state.posYSpring != null) {
                        state.posYSpring.setPosition(state.currentY);
                    }
                    if (state.scaleSpring != null) {
                        state.scaleSpring.setPosition(state.currentScale);
                    }
                    if (state.alphaLinear != null) {
                        state.alphaLinear.setPosition(state.currentAlpha);
                    }
                    if (state.highlightLinear != null) {
                        state.highlightLinear.setPosition(state.currentHighlightAlpha);
                    }
                }
                clearLockedWraps();
                if (mPlaybackIndex >= 0 && mPlaybackIndex != anchorIndex) {
                    animateToIndex(mPlaybackIndex);
                    return;
                }
                invalidate();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (session != mAnimationSession) {
                    return;
                }
                mIsIntroAnimating = false;
                clearLockedWraps();
            }
            });
            mAnimators.add(animator);
            animator.start();
        } else {
            // API<11 无 ValueAnimator：直接跳到最终状态
            mIsIntroAnimating = false;
            for (int i = 0; i < mStates.size(); i++) {
                LineState state = mStates.get(i);
                state.currentX = state.targetX;
                state.currentY = state.targetY;
                state.currentAlpha = state.targetAlpha;
                state.currentScale = state.targetScale;
            }
            clearLockedWraps();
            invalidate();
        }
    }

    /**
     * 出场动画：入场动画的反向效果
     * 歌词从左向右滑出屏幕，同时透明度从1到0
     */
    public void playExitAnimation(final OnLyricAnimationEndListener listener) {
        if (!mHasLyrics || mStates.isEmpty()) {
            if (listener != null) {
                listener.onAnimationEnd();
            }
            return;
        }
        mCurrentTimeMs = getAnimatedTimeMs();
        removeCallbacks(mFrameTicker);
        mFrameLoopPosted = false;
        cancelAllAnimators();
        cancelPendingExitCallback();
        mAnimationSession++;
        // 出场动画沿用当前多行排版，避免原文/翻译在滑出时重新挤成一行。
        mEnableMultilineLayout = true;
        freezeCurrentLayoutState();
        lockWrapsForExit();

        final float width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        final float endX = width + dp(80f);
        int anchorIndex = mCurrentIndex >= 0 ? mCurrentIndex : 0;
        int visibleStart = Math.max(0, anchorIndex - ANIM_VISIBLE_RANGE);
        int visibleEnd = Math.min(mStates.size() - 1, anchorIndex + ANIM_VISIBLE_RANGE);
        long maxDelay = 0L;

        for (int i = 0; i < mStates.size(); i++) {
            final LineState state = mStates.get(i);
            final float startX = state.currentX;
            final float startAlpha = state.currentAlpha;
            int clampedIndex = Math.max(visibleStart, Math.min(i, visibleEnd));
            long delay = Math.abs(clampedIndex - anchorIndex) * EXIT_STEP_DELAY;
            if (delay > maxDelay) {
                maxDelay = delay;
            }

            if (Build.VERSION.SDK_INT >= 11) {
                ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(EXIT_LINE_DURATION);
                animator.setStartDelay(delay);
                animator.setInterpolator(null);

                animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = ((Float) animation.getAnimatedValue()).floatValue();
                    float eased = easeInOutCubic(t);
                    state.currentX = lerp(startX, endX, eased);
                    state.currentAlpha = lerp(startAlpha, 0f, eased);
                    invalidate();
                }
            });

            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    state.currentX = endX;
                    state.currentAlpha = 0f;
                    invalidate();
                }
            });

                mAnimators.add(animator);
                animator.start();
            } else {
                state.currentX = endX;
                state.currentAlpha = 0f;
            }
        }

        // 通知动画结束
        if (Build.VERSION.SDK_INT < 11) {
            invalidate();
            if (listener != null) {
                listener.onAnimationEnd();
            }
            return;
        }
        if (listener != null) {
            long totalDuration = EXIT_LINE_DURATION + maxDelay + 20L;
            mPendingExitEndRunnable = new Runnable() {
                @Override
                public void run() {
                    mPendingExitEndRunnable = null;
                    listener.onAnimationEnd();
                }
            };
            mHandler.postDelayed(mPendingExitEndRunnable, totalDuration);
        }
    }

    private void cancelPendingExitCallback() {
        if (mPendingExitEndRunnable != null) {
            mHandler.removeCallbacks(mPendingExitEndRunnable);
            mPendingExitEndRunnable = null;
        }
    }

    private void freezeCurrentLayoutState() {
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state == null) {
                continue;
            }
            state.targetX = state.currentX;
            state.targetY = state.currentY;
            state.targetAlpha = state.currentAlpha;
            state.targetScale = state.currentScale;
            state.targetHighlightAlpha = state.currentHighlightAlpha;
            if (state.posYSpring != null) {
                state.posYSpring.setPosition(state.currentY);
            }
            if (state.scaleSpring != null) {
                state.scaleSpring.setPosition(state.currentScale);
            }
            if (state.alphaLinear != null) {
                state.alphaLinear.setPosition(state.currentAlpha);
            }
            if (state.highlightLinear != null) {
                state.highlightLinear.setPosition(state.currentHighlightAlpha);
            }
        }
    }

    private void lockWrapsForIntro() {
        float width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state == null) {
                continue;
            }
            float targetScale = state.targetScale <= 0f ? 1f : state.targetScale;
            state.lockedWrapWidth = Math.max(dp(80f), (width - state.targetX - mSidePadding) / targetScale);
        }
    }

    private void lockWrapsForExit() {
        float width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state == null) {
                continue;
            }
            float currentScale = state.currentScale <= 0f ? 1f : state.currentScale;
            state.lockedWrapWidth = Math.max(dp(80f), (width - state.currentX - mSidePadding) / currentScale);
        }
    }

    private void clearLockedWraps() {
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state != null) {
                state.lockedWrapWidth = 0f;
            }
        }
    }

    private boolean hasVisibleLyricState() {
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state.currentAlpha > 0.01f) {
                return true;
            }
        }
        return false;
    }

    public long getIntroAnimationTotalDuration() {
        if (!mHasLyrics || mStates.isEmpty()) {
            return INTRO_LINE_DURATION;
        }
        int anchorIndex = mCurrentIndex >= 0 ? mCurrentIndex : 0;
        int visibleStart = Math.max(0, anchorIndex - ANIM_VISIBLE_RANGE);
        int visibleEnd = Math.min(mStates.size() - 1, anchorIndex + ANIM_VISIBLE_RANGE);
        return INTRO_LINE_DURATION + Math.max(0, visibleEnd - visibleStart) * INTRO_STEP_DELAY;
    }

    public long getExitAnimationTotalDuration() {
        if (!mHasLyrics || mStates.isEmpty()) {
            return EXIT_LINE_DURATION;
        }
        int anchorIndex = mCurrentIndex >= 0 ? mCurrentIndex : 0;
        int visibleStart = Math.max(0, anchorIndex - ANIM_VISIBLE_RANGE);
        int visibleEnd = Math.min(mStates.size() - 1, anchorIndex + ANIM_VISIBLE_RANGE);
        long maxDelay = Math.max(anchorIndex - visibleStart, visibleEnd - anchorIndex) * EXIT_STEP_DELAY;
        return EXIT_LINE_DURATION + maxDelay + 20L;
    }

    private android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public void setCurrentTime(long timeMs) {
        if (!mHasLyrics || mLyrics.isEmpty()) {
            return;
        }
        mAnchorLyricTimeMs = timeMs;
        mAnchorRealtimeMs = SystemClock.elapsedRealtime();
        updateCurrentTimeInternal(timeMs, true);
        if (mPlaybackRunning) {
            ensureFrameLoop();
        }
    }

    public void setPlaybackRunning(boolean running) {
        if (mPlaybackRunning == running) {
            if (running) {
                ensureFrameLoop();
            }
            return;
        }
        if (!running) {
            mCurrentTimeMs = getAnimatedTimeMs();
            removeCallbacks(mFrameTicker);
            mFrameLoopPosted = false;
            mLastFrameRealtimeMs = 0L;
        } else {
            mPlaybackRunning = true;
            mAnchorLyricTimeMs = mCurrentTimeMs;
            mAnchorRealtimeMs = SystemClock.elapsedRealtime();
            mLastFrameRealtimeMs = mAnchorRealtimeMs;
            ensureFrameLoop();
            return;
        }
        mPlaybackRunning = running;
    }

    private void updateCurrentTimeInternal(long timeMs, boolean allowInvalidate) {
        mCurrentTimeMs = timeMs;
        int[] active = findActiveLines(timeMs);
        // 同刻多行（主歌词+和声）时，焦点取组内第一行（主歌词），避免和声行顶替焦点
        int index = active.length > 0 ? active[0] : findCurrentLine(timeMs);
        if (index < 0) {
            index = 0;
        }
        mPlaybackIndex = index;
        mActiveLineSet = active;
        if (!mIntroPlayed) {
            // 首次入场动画开始前，只记录当前高亮行，避免先直显一帧再播放动画
            mCurrentIndex = index;
            return;
        }
        if (mIsIntroAnimating) {
            return;
        }
        if (mIsUserDragging) {
            return;
        }
        if (index != mCurrentIndex) {
            animateToIndex(index);
            return;
        }
        if (mHasTimedLyrics || active.length > 0) {
            if (allowInvalidate) {
                invalidate();
            } else {
                postInvalidateOnAnimationCompat();
            }
        }
    }

    private long getAnimatedTimeMs() {
        if (!mPlaybackRunning) {
            return mCurrentTimeMs;
        }
        long elapsed = SystemClock.elapsedRealtime() - mAnchorRealtimeMs;
        return mAnchorLyricTimeMs + Math.max(0L, elapsed);
    }

    private void ensureFrameLoop() {
        boolean needsPlaybackFrames = mPlaybackRunning && mHasLyrics;
        boolean needsAnimationFrames = hasActiveLineAnimations();
        if (mFrameLoopPosted || !mHasLyrics || (!needsPlaybackFrames && !needsAnimationFrames)) {
            return;
        }
        mFrameLoopPosted = true;
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            postOnAnimation(mFrameTicker);
        } else {
            postDelayed(mFrameTicker, 16L);
        }
    }

    private boolean updateLineTransforms(float deltaSeconds) {
        boolean changed = false;
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state.posYSpring == null || state.scaleSpring == null
                    || state.alphaLinear == null || state.highlightLinear == null) {
                continue;
            }
            if (mBounceEnabled) {
                state.posYSpring.update(deltaSeconds);
                state.scaleSpring.update(deltaSeconds);
            }
            state.alphaLinear.update(deltaSeconds);
            state.highlightLinear.update(deltaSeconds);

            float newY;
            float newScale;
            if (mBounceEnabled) {
                newY = state.posYSpring.getCurrentPosition();
                newScale = state.scaleSpring.getCurrentPosition();
            } else {
                // 关闭弹性：目标值本身由 applyStaticLayout 线性推进，
                // 这里让弹簧直接跟随目标，表现为平滑移动无回弹。
                state.posYSpring.setPosition(state.targetY);
                state.scaleSpring.setPosition(state.targetScale);
                newY = state.targetY;
                newScale = state.targetScale;
            }
            float newAlpha = state.alphaLinear.getCurrentPosition();
            float newHighlight = state.highlightLinear.getCurrentPosition();

            if (Math.abs(newY - state.currentY) > 0.01f
                    || Math.abs(newScale - state.currentScale) > 0.001f
                    || Math.abs(newAlpha - state.currentAlpha) > 0.001f
                    || Math.abs(newHighlight - state.currentHighlightAlpha) > 0.001f) {
                changed = true;
            }
            state.currentY = newY;
            state.currentScale = newScale;
            state.currentAlpha = newAlpha;
            state.currentHighlightAlpha = newHighlight;
        }
        return changed;
    }

    private void ensureStateLayoutMetrics() {
        if (mStates.isEmpty()) {
            return;
        }
        float availableWidth = getContentWidth();
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state == null) {
                continue;
            }
            if (Math.abs(state.cachedMeasureWidth - availableWidth) < 1f && state.layoutHeight > 0f) {
                continue;
            }
            state.cachedMeasureWidth = availableWidth;
            state.layoutHeight = Math.max(mLineHeight + dp(14f), measureLineLayoutHeight(state, availableWidth) + dp(22f));
        }
    }

    private float getContentWidth() {
        float width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(80f), width - (mSidePadding * 2f));
    }

    private float measureLineLayoutHeight(LineState state, float availableWidth) {
        if (state == null || state.text == null) {
            return mLineHeight;
        }
        if (state.line != null && state.line.hasTimedWords()) {
            return measureTimedLineHeight(state.text, availableWidth);
        }
        if (mEnableMultilineLayout) {
            StaticLayout layout = new StaticLayout(
                    state.text,
                    mTextPaint,
                    Math.max(1, (int) availableWidth),
                    Layout.Alignment.ALIGN_NORMAL,
                    1.08f,
                    dp(2f),
                    true);
            return layout.getHeight();
        }
        Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
        return metrics.descent - metrics.ascent;
    }

    private float measureTimedLineHeight(String text, float availableWidth) {
        int lineBreak = text.indexOf('\n');
        String translationText = lineBreak >= 0 ? text.substring(lineBreak + 1).trim() : "";
        Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
        float primaryHeight = metrics.descent - metrics.ascent;
        int primaryLineCount = countWrappedTimedLines(text, availableWidth);
        float lineGap = dp(4f);
        float totalHeight = (primaryHeight * primaryLineCount) + (Math.max(0, primaryLineCount - 1) * lineGap);
        if (translationText.length() > 0) {
            totalHeight += primaryHeight + dp(6f);
        }
        return Math.max(mLineHeight, totalHeight);
    }

    private int countWrappedTimedLines(String text, float availableWidth) {
        if (availableWidth <= 0f) {
            return 1;
        }
        int count = 1;
        float cursorX = 0f;
        String primaryText = text;
        int lineBreak = text.indexOf('\n');
        if (lineBreak >= 0) {
            primaryText = text.substring(0, lineBreak);
        }
        String[] segments = primaryText.split(" ");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.length() == 0) {
                continue;
            }
            String renderedWord = i == segments.length - 1 ? segment : segment + " ";
            float wordWidth = mTextPaint.measureText(renderedWord);
            if (cursorX > 0f && cursorX + wordWidth > availableWidth) {
                count++;
                cursorX = 0f;
            }
            cursorX += wordWidth;
        }
        return Math.max(1, count);
    }

    private float resolveLineCenterY(int lineIndex, float windowStart, float topY) {
        ensureStateLayoutMetrics();
        int windowStartIndex = (int) Math.floor(windowStart);
        float windowFraction = windowStart - windowStartIndex;
        float cursorTop = topY;
        if (windowStartIndex < 0) {
            cursorTop += (-windowStartIndex) * mLineHeight;
            windowFraction = 0f;
            windowStartIndex = 0;
        } else if (windowFraction > 0f && windowStartIndex < mStates.size()) {
            cursorTop -= getLineSlotHeight(windowStartIndex) * windowFraction;
        }
        if (lineIndex >= windowStartIndex) {
            for (int i = windowStartIndex; i < lineIndex; i++) {
                cursorTop += getLineSlotHeight(i);
            }
        } else {
            for (int i = windowStartIndex - 1; i >= lineIndex; i--) {
                cursorTop -= getLineSlotHeight(i);
            }
        }
        return cursorTop + (getLineSlotHeight(lineIndex) * 0.5f);
    }

    private float getLineSlotHeight(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= mStates.size()) {
            return mLineHeight;
        }
        LineState state = mStates.get(lineIndex);
        if (state == null || state.layoutHeight <= 0f) {
            return mLineHeight;
        }
        float slot = state.layoutHeight;
        // 活动行（焦点行 + 组内同时行）绘制时会放大（scale 1.08~1.2），
        // 槽位按 1.2 预留余量，避免放大后的文字溢出压到相邻行（"顶掉"）。
        if (isActiveLine(lineIndex) || lineIndex == mCurrentIndex) {
            slot *= 1.2f;
        }
        return Math.max(mLineHeight, slot);
    }

    private boolean hasActiveLineAnimations() {
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            if (state.posYSpring != null && !state.posYSpring.arrived()) {
                return true;
            }
            if (state.scaleSpring != null && !state.scaleSpring.arrived()) {
                return true;
            }
            if (state.alphaLinear != null && !state.alphaLinear.arrived()) {
                return true;
            }
            if (state.highlightLinear != null && !state.highlightLinear.arrived()) {
                return true;
            }
        }
        return false;
    }

    private void applyStaticLayout(int focusIndex, boolean allowInvalidate) {
        if (!mHasLyrics || mStates.isEmpty()) {
            return;
        }
        if (focusIndex < 0) {
            focusIndex = 0;
        } else if (focusIndex >= mStates.size()) {
            focusIndex = mStates.size() - 1;
        }
        float topY = getViewportTopY();
        float windowStart = getWindowStartFloat(focusIndex);
        mCurrentIndex = focusIndex;
        ensureStateLayoutMetrics();

        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            int distance = Math.abs(i - focusIndex);
            boolean timedActive = isActiveLine(i);
            float targetY = resolveLineCenterY(i, windowStart, topY);
            float targetScale = scaleForDistance(distance);
            float targetAlpha = alphaForDistance(distance);
            if (timedActive && distance == 1) {
                targetScale = Math.max(targetScale, 1.1f);
                targetAlpha = Math.max(targetAlpha, 0.9f);
            }

            state.currentY = targetY;
            state.targetY = targetY;
            state.currentX = mSidePadding;
            state.targetX = mSidePadding;
            state.currentScale = targetScale;
            state.targetScale = targetScale;
            state.currentAlpha = targetAlpha;
            state.targetAlpha = targetAlpha;
            state.currentHighlightAlpha = resolveHighlightAlpha(state.line, timedActive, distance);
            state.targetHighlightAlpha = state.currentHighlightAlpha;
            state.posYSpring.setPosition(targetY);
            state.scaleSpring.setPosition(targetScale);
            state.alphaLinear.setPosition(targetAlpha);
            state.highlightLinear.setPosition(state.currentHighlightAlpha);
        }

        if (allowInvalidate) {
            invalidate();
        } else {
            postInvalidateOnAnimationCompat();        }
    }

    private void postInvalidateOnAnimationCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            postInvalidateOnAnimation();
        } else {
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mPreviewEnabled || !mHasLyrics || mStates.isEmpty() || !mIntroPlayed) {
            if (!mHasLyrics && event.getAction() == MotionEvent.ACTION_UP && mEmptyClickListener != null) {
                mEmptyClickListener.onEmptyClick();
                performClick();
                return true;
            }
            return super.onTouchEvent(event);
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                cancelAllAnimators();
                mHandler.removeCallbacks(mPreviewResetRunnable);
                ensureVelocityTracker();
                mVelocityTracker.clear();
                mVelocityTracker.addMovement(event);
                mIsUserDragging = true;
                mLastTouchY = event.getY();
                mTouchDownY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(event);
                }
                float deltaY = event.getY() - mLastTouchY;
                mLastTouchY = event.getY();
                mPreviewOffsetY = clampPreviewOffset(mPreviewOffsetY + deltaY);
                applyPreviewLayout();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float totalDeltaY = event.getY() - mTouchDownY;
                float yVelocity = 0f;
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(event);
                    mVelocityTracker.computeCurrentVelocity(1000);
                    yVelocity = mVelocityTracker.getYVelocity();
                }
                mLastTouchY = 0f;
                mTouchDownY = 0f;
                mHandler.removeCallbacks(mPreviewResetRunnable);
                if (Math.abs(totalDeltaY) < dp(10f)) {
                    releaseVelocityTracker();
                    performPreviewSeek(findNearestLineIndex(event.getY()));
                } else {
                    startPreviewFling(yVelocity);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int findCurrentLine(long timeMs) {
        int left = 0;
        int right = mLyrics.size() - 1;
        while (left <= right) {
            int mid = (left + right) / 2;
            if (mLyrics.get(mid).getTime() <= timeMs) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return right;
    }

    /**
     * 查找当前时刻所有"活动"歌词行（同时播放/重叠）：
     * 1) TTML：用 [begin, end] 区间包含判断，重叠段落同时活动；
     * 2) LRC：时间戳接近（≤250ms）的行已由 LyricParser 标记同一 overlapGroup，
     *    组内行共享活动区间，同时高亮。
     * 返回按索引升序的数组；空数组表示无活动行（间奏空白段）。
     */
    private int[] findActiveLines(long timeMs) {
        ArrayList<Integer> active = new ArrayList<Integer>();
        if (mLyrics == null || mLyrics.isEmpty()) {
            return new int[0];
        }
        // 基准行 = 最后一个 begin<=time 的行
        int base = findCurrentLine(timeMs);
        if (base < 0 || base >= mLyrics.size()) {
            return new int[0];
        }
        LyricLine baseLine = mLyrics.get(base);
        // 1) 基准行本身：TTML 行若已过 end 则不活动（间奏空白）
        if (baseLine.isActiveAt(timeMs)) {
            active.add(base);
        } else {
            return new int[0];
        }
        // 2) LRC 重叠组（合唱/双声部/主唱+和声）：同组行同时高亮
        int group = baseLine.getOverlapGroup();
        if (group > 0) {
            for (int i = 0; i < mLyrics.size(); i++) {
                if (i == base || active.contains(Integer.valueOf(i))) {
                    continue;
                }
                LyricLine line = mLyrics.get(i);
                if (line != null && line.getOverlapGroup() == group && line.isActiveAt(timeMs)) {
                    active.add(i);
                }
            }
        }
        // 3) TTML 区间重叠：附近有显式 end 区间且与基准行区间相交的行
        int start = Math.max(0, base - 3);
        int end = Math.min(mLyrics.size() - 1, base + 3);
        for (int i = start; i <= end; i++) {
            if (i == base || active.contains(Integer.valueOf(i))) {
                continue;
            }
            LyricLine line = mLyrics.get(i);
            if (line == null || line.getTime() > timeMs) {
                continue;
            }
            // 仅对有显式 end 区间的 TTML 行做区间重叠判定（LRC 行 endTime==time 恒活动，靠组判定）
            if (line.getEndTime() > line.getTime() && line.isActiveAt(timeMs)
                    && baseLine.overlaps(line, 0L)) {
                active.add(i);
            }
        }
        Collections.sort(active);
        int[] result = new int[active.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = active.get(i).intValue();
        }
        return result;
    }

    private void animateToIndex(int newIndex) {
        mCurrentIndex = newIndex;
        if (mStates.isEmpty()) {
            return;
        }
        float topY = getViewportTopY();
        float windowStart = getWindowStartFloat(newIndex);
        ensureStateLayoutMetrics();
        int visibleCapacity = getVisibleLineCapacity();
        int visibleStartIndex = Math.max(0, (int) Math.floor(windowStart));
        int windowEndIndex = Math.min(mStates.size() - 1, (int) Math.floor(windowStart) + visibleCapacity - 1);
        float delaySeconds = 0f;
        float baseDelaySeconds = 0.05f;
        
        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            int distance = Math.abs(i - newIndex);
            boolean timedActive = isActiveLine(i);
            
            float targetY = resolveLineCenterY(i, windowStart, topY);
            float targetScale = distance == 0 ? 1.2f : (distance == 1 ? 1.08f : 1f);
            float targetAlpha = alphaForDistance(distance);
            if (timedActive && distance == 1) {
                targetScale = Math.max(targetScale, 1.1f);
                targetAlpha = Math.max(targetAlpha, 0.9f);
            }
            
            final float startY = state.currentY;
            final float startScale = state.currentScale;
            final float startAlpha = state.currentAlpha;
            final float startHighlightAlpha = state.currentHighlightAlpha;
            
            state.targetY = targetY;
            state.targetScale = targetScale;
            state.targetAlpha = targetAlpha;
            state.targetHighlightAlpha = resolveHighlightAlpha(state.line, timedActive, distance);

            float stateDelay = 0f;
            if (i >= visibleStartIndex && i <= windowEndIndex) {
                stateDelay = delaySeconds;
            }

            if (Math.abs(targetY - startY) < 1f) {
                state.posYSpring.setPosition(targetY);
            } else {
                configureLineSpring(state, Math.abs(targetY - startY));
                state.posYSpring.setTargetPosition(targetY, stateDelay);
            }
            state.scaleSpring.setTargetPosition(targetScale, 0f);
            state.alphaLinear.setTargetPosition(targetAlpha, stateDelay);
            state.highlightLinear.setTargetPosition(state.targetHighlightAlpha, 0f);

            if (i >= visibleStartIndex && i <= windowEndIndex && targetY >= topY) {
                delaySeconds += baseDelaySeconds;
                if (i >= newIndex) {
                    baseDelaySeconds /= 1.05f;
                }
            }
        }
        mLastFrameRealtimeMs = SystemClock.elapsedRealtime();
        ensureFrameLoop();
        invalidate();
    }

    private void configureLineSpring(LineState state, float moveDistance) {
        if (state == null || state.posYSpring == null) {
            return;
        }
        if (moveDistance <= mLineHeight * 0.55f) {
            state.posYSpring.setParams(0.8f, 9.2f, 132f);
        } else if (moveDistance <= mLineHeight * 1.1f) {
            state.posYSpring.setParams(0.84f, 10.8f, 118f);
        } else {
            state.posYSpring.setParams(0.88f, 12.8f, 104f);
        }
    }

    private void applyPreviewLayout() {
        float topY = getViewportTopY();
        float previewCenter = mCurrentIndex - (mPreviewOffsetY / mLineHeight);
        float windowStart = getWindowStartFloat(previewCenter);
        ensureStateLayoutMetrics();

        for (int i = 0; i < mStates.size(); i++) {
            LineState state = mStates.get(i);
            float distance = Math.abs(i - previewCenter);
            boolean timedActive = isActiveLine(i);
            state.currentY = resolveLineCenterY(i, windowStart, topY);
            state.targetY = state.currentY;
            state.currentX = mSidePadding;
            state.targetX = mSidePadding;
            state.currentScale = scaleForDistance(distance);
            state.targetScale = state.currentScale;
            state.currentAlpha = alphaForDistance(distance);
            if (timedActive && distance <= 1.2f) {
                state.currentScale = Math.max(state.currentScale, 1.08f);
                state.targetScale = state.currentScale;
                state.currentAlpha = Math.max(state.currentAlpha, 0.88f);
            }
            state.targetAlpha = state.currentAlpha;
            state.currentHighlightAlpha = resolveHighlightAlpha(state.line, timedActive, distance <= 1.2f ? 1 : (int) Math.ceil(distance));
            state.targetHighlightAlpha = state.currentHighlightAlpha;
            if (state.posYSpring != null) {
                state.posYSpring.setPosition(state.currentY);
            }
            if (state.scaleSpring != null) {
                state.scaleSpring.setPosition(state.currentScale);
            }
            if (state.alphaLinear != null) {
                state.alphaLinear.setPosition(state.currentAlpha);
            }
            if (state.highlightLinear != null) {
                state.highlightLinear.setPosition(state.currentHighlightAlpha);
            }
        }
        invalidate();
    }

    private float resolveHighlightAlpha(LyricLine line, boolean timedActive, int distance) {
        if (line == null) {
            return 0f;
        }
        // 活动行（含重叠组内其他行）整体高亮；逐字行额外做卡拉OK
        if (timedActive) {
            return line.hasTimedWords() ? 1f : 0.85f;
        }
        if (distance == 1 && mCurrentTimeMs > line.getEndTime()) {
            long fadeWindow = 520L;
            float fade = 1f - clamp01((mCurrentTimeMs - line.getEndTime()) / (float) fadeWindow);
            return Math.max(0f, fade);
        }
        return 0f;
    }

    private void settleFromPreview() {
        if (!mIsUserDragging && Math.abs(mPreviewOffsetY) < 0.5f) {
            return;
        }
        mIsUserDragging = false;
        mPreviewOffsetY = 0f;
        releaseVelocityTracker();
        if (mPlaybackIndex >= 0) {
            animateToIndex(mPlaybackIndex);
        } else if (mCurrentIndex >= 0) {
            animateToIndex(mCurrentIndex);
        }
    }

    public void cancelPreviewInteraction() {
        mHandler.removeCallbacks(mPreviewResetRunnable);
        cancelAllAnimators();
        mIsUserDragging = false;
        mPreviewOffsetY = 0f;
        mLastTouchY = 0f;
        mTouchDownY = 0f;
        releaseVelocityTracker();
        if (mHasLyrics && !mStates.isEmpty()) {
            if (mPlaybackIndex >= 0) {
                mCurrentIndex = mPlaybackIndex;
            }
            resetToCurrentPosition();
        }
    }

    private void startPreviewFling(float yVelocity) {
        releaseVelocityTracker();
        if (Math.abs(yVelocity) < MIN_FLING_VELOCITY) {
            mHandler.postDelayed(mPreviewResetRunnable, 900L);
            return;
        }

        final float startOffset = mPreviewOffsetY;
        float extraOffset = (yVelocity / 2400f) * mLineHeight;
        float maxExtraOffset = MAX_FLING_OFFSET_LINES * mLineHeight;
        if (extraOffset > maxExtraOffset) {
            extraOffset = maxExtraOffset;
        } else if (extraOffset < -maxExtraOffset) {
            extraOffset = -maxExtraOffset;
        }
        final float endOffset = clampPreviewOffset(startOffset + extraOffset);

        if (Math.abs(endOffset - startOffset) < 1f) {
            mHandler.postDelayed(mPreviewResetRunnable, 900L);
            return;
        }

        if (Build.VERSION.SDK_INT >= 11) {
            ValueAnimator animator = ValueAnimator.ofFloat(startOffset, endOffset);
            animator.setDuration(420L);
            animator.setInterpolator(null);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = ((Float) animation.getAnimatedValue()).floatValue();
                float t = clamp01((value - startOffset) / (endOffset - startOffset));
                float eased = 1f - (float) Math.pow(1f - t, 3);
                mPreviewOffsetY = lerp(startOffset, endOffset, eased);
                applyPreviewLayout();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mHandler.postDelayed(mPreviewResetRunnable, 900L);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mHandler.postDelayed(mPreviewResetRunnable, 900L);
            }
        });
            mAnimators.add(animator);
            animator.start();
        } else {
            mPreviewOffsetY = endOffset;
            applyPreviewLayout();
            mHandler.postDelayed(mPreviewResetRunnable, 900L);
        }
    }

    private void performPreviewSeek(int index) {
        mIsUserDragging = false;
        mPreviewOffsetY = 0f;
        if (index < 0 || index >= mLyrics.size()) {
            settleFromPreview();
            return;
        }
        mPlaybackIndex = index;
        if (mSeekListener != null) {
            mSeekListener.onSeekTo(mLyrics.get(index).getTime());
        }
        animateToIndex(index);
    }

    private int findNearestLineIndex(float touchY) {
        if (mStates.isEmpty()) {
            return -1;
        }
        int nearestIndex = -1;
        float nearestDistance = Float.MAX_VALUE;
        for (int i = 0; i < mStates.size(); i++) {
            float distance = Math.abs(mStates.get(i).currentY - touchY);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private float clampPreviewOffset(float offsetY) {
        if (mStates.isEmpty()) {
            return 0f;
        }
        float minOffset = (mCurrentIndex - (mStates.size() - 1)) * mLineHeight;
        float maxOffset = mCurrentIndex * mLineHeight;
        if (offsetY < minOffset) return minOffset;
        if (offsetY > maxOffset) return maxOffset;
        return offsetY;
    }

    private float getViewportTopY() {
        // 从顶部下方一小段距离开始，留出状态栏/标题栏空间
        return dp(16f);
    }

    private int getVisibleLineCapacity() {
        float height = getHeight() > 0 ? getHeight() : dp(600f);
        float availableHeight = height - getViewportTopY() - dp(80f);
        return Math.max(3, (int) Math.floor(availableHeight / mLineHeight));
    }

    private int getPinnedRowIndex() {
        // 当前播放行落在屏幕中部附近，同时保留顶部若干行先启动的级联空间
        int visibleCapacity = getVisibleLineCapacity();
        if (visibleCapacity <= 3) {
            return 1;
        }
        if (visibleCapacity <= 5) {
            return 2;
        }
        return Math.max(2, visibleCapacity / 2);
    }

    private float getWindowStartFloat(float focusIndex) {
        // 返回浮点数的窗口起始位置，实现连续滚动
        float pinned = getPinnedRowIndex();
        return clampWindowStart(focusIndex - pinned);
    }

    private float clampWindowStart(float windowStart) {
        int minStart = -getPinnedRowIndex();
        int maxStart = Math.max(0, mStates.size() - getVisibleLineCapacity());
        if (windowStart < minStart) {
            return minStart;
        }
        if (windowStart > maxStart) {
            return maxStart;
        }
        return windowStart;
    }

    private float easeInOutCubic(float t) {
        if (t < 0.5f) {
            return 4 * t * t * t;
        } else {
            float f = -2 * t + 2;
            return 1 - (f * f * f) / 2;
        }
    }

    private float easeInOutSine(float t) {
        return (float) (-(Math.cos(Math.PI * t) - 1f) / 2f);
    }

    private float springEaseOut(float t) {
        float eased = easeInOutSine(t);
        float wave = (float) Math.sin(Math.PI * t);
        return clamp01(eased + (wave * (1f - t) * 0.06f));
    }

    private float lerp(float start, float end, float fraction) {
        return start + ((end - start) * fraction);
    }

    private float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private void ensureVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    private void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private float alphaForDistance(int distance) {
        if (distance == 0) return 1f;
        if (distance == 1) return 0.72f;
        if (distance == 2) return 0.45f;
        if (distance == 3) return 0.25f;
        return 0.12f;
    }

    private float alphaForDistance(float distance) {
        if (distance <= 0f) return 1f;
        if (distance <= 1f) return lerp(1f, 0.72f, distance);
        if (distance <= 2f) return lerp(0.72f, 0.45f, distance - 1f);
        if (distance <= 3f) return lerp(0.45f, 0.25f, distance - 2f);
        if (distance <= 4f) return lerp(0.25f, 0.12f, distance - 3f);
        return 0.12f;
    }

    private float scaleForDistance(float distance) {
        if (distance <= 0f) return 1.2f;
        if (distance <= 1f) return lerp(1.2f, 1.08f, distance);
        if (distance <= 2f) return lerp(1.08f, 1f, distance - 1f);
        return 1f;
    }

    private void cancelAllAnimators() {
        // 限制最大动画数量，防止极端情况内存溢出
        if (mAnimators.size() > MAX_ANIMATORS) {
            // 如果动画过多，直接清除旧动画引用（它们会继续运行但不再被追踪）
            mAnimators.subList(0, mAnimators.size() - MAX_ANIMATORS / 2).clear();
        }
        for (int i = 0; i < mAnimators.size(); i++) {
            Object animator = mAnimators.get(i);
            if (animator != null && Build.VERSION.SDK_INT >= 11) {
                try {
                    ((android.animation.ValueAnimator) animator).cancel();
                } catch (Exception ignored) {
                }
            }
        }
        mAnimators.clear();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAllAnimators();
        removeCallbacks(mFrameTicker);
        releaseVelocityTracker();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (height == 0) {
            height = (int) dp(360f);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mHasLyrics || mStates.isEmpty()) {
            Paint.FontMetrics hintMetrics = mHintPaint.getFontMetrics();
            float hintY = getHeight() / 2f - (hintMetrics.ascent + hintMetrics.descent) / 2f;
            drawMultilineText(canvas, mHintText, getWidth() / 2f, hintY, mHintPaint, getWidth() - dp(32f), true);
            return;
        }

        for (int i = mStates.size() - 1; i >= 0; i--) {
            LineState state = mStates.get(i);
            if (state.currentAlpha <= 0.01f || !isStateVisibleForDraw(state)) {
                continue;
            }
            
            float scale = state.currentScale;
            boolean timedActive = isTimedLineActive(state.line);
            int color = (i == mCurrentIndex || timedActive) ? mActiveColor : ((Math.abs(i - mCurrentIndex) == 1) ? mNormalColor : mFadeColor);
            mTextPaint.setColor(color);
            mTextPaint.setAlpha((int) (255 * state.currentAlpha));
            
            // 使用 Canvas 缩放代替 setTextSize，避免字体纹理重建
            canvas.save();
            canvas.translate(state.currentX, state.currentY);
            canvas.scale(scale, scale);
            float drawWidth = resolveDrawWidth(state, scale);

            if (state.line != null && state.line.hasTimedWords()) {
                drawTimedLineText(canvas, state, 0f, 0f, mTextPaint,
                        drawWidth);
            } else if (mEnableMultilineLayout) {
                drawMultilineText(canvas, state.text, 0f, 0f, mTextPaint,
                        drawWidth, false);
            } else {
                drawSingleLineText(canvas, state.text, 0f, 0f, mTextPaint,
                        drawWidth);
            }
            
            canvas.restore();
        }
    }

    private boolean isStateVisibleForDraw(LineState state) {
        if (state == null) {
            return false;
        }
        float estimatedHalfHeight = Math.max(getLineSlotHeight(state.index) * 0.5f, (getLineSlotHeight(state.index) * state.currentScale) * 0.55f);
        float top = state.currentY - estimatedHalfHeight;
        float bottom = state.currentY + estimatedHalfHeight;
        return bottom >= -mLineHeight && top <= getHeight() + mLineHeight;
    }

    private float resolveDrawWidth(LineState state, float scale) {
        if (state != null && state.lockedWrapWidth > 0f) {
            return state.lockedWrapWidth;
        }
        return (getWidth() - state.currentX - mSidePadding) / scale;
    }

    private boolean isTimedLineActive(LyricLine line) {
        if (line == null || !line.hasTimedWords()) {
            return false;
        }
        return mCurrentTimeMs >= line.getTime() && mCurrentTimeMs <= Math.max(line.getEndTime(), line.getTime());
    }

    /** 第 index 行是否在当前时刻活动（含重叠组：组内任一行活动即活动）。 */
    private boolean isActiveLine(int index) {
        if (index < 0 || index >= mLyrics.size()) {
            return false;
        }
        for (int i = 0; i < mActiveLineSet.length; i++) {
            if (mActiveLineSet[i] == index) {
                return true;
            }
        }
        return false;
    }

    private void drawSingleLineText(Canvas canvas, String text, float x, float centerY, TextPaint paint, float width) {
        if (text == null) {
            return;
        }
        CharSequence displayText = TextUtils.ellipsize(text.replace('\n', ' '), paint, Math.max(1f, width), TextUtils.TruncateAt.END);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(displayText.toString(), x, baseline, paint);
    }

    private void drawMultilineText(Canvas canvas, String text, float x, float centerY, TextPaint paint, float width, boolean centered) {
        if (text == null) {
            return;
        }
        int availableWidth = Math.max(1, (int) width);
        StaticLayout layout = new StaticLayout(
                text,
                paint,
                availableWidth,
                centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL,
                1.08f,
                dp(2f),
                true);
        canvas.save();
        float translateX = centered ? x - (availableWidth / 2f) : x;
        float translateY = centerY - (layout.getHeight() / 2f);
        canvas.translate(translateX, translateY);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawTimedLineText(Canvas canvas, LineState state, float x, float centerY, TextPaint paint, float width) {
        LyricLine line = state != null ? state.line : null;
        if (line == null || line.getText() == null) {
            return;
        }
        String text = line.getText();
        int lineBreak = text.indexOf('\n');
        String primaryText = lineBreak >= 0 ? text.substring(0, lineBreak) : text;
        String translationText = lineBreak >= 0 ? text.substring(lineBreak + 1).trim() : "";

        Paint.FontMetrics metrics = paint.getFontMetrics();
        float primaryHeight = metrics.descent - metrics.ascent;
        float primaryLineGap = dp(4f);
        int primaryLineCount = countWrappedTimedLines(primaryText, width);
        float translationGap = dp(6f);
        float totalHeight = (primaryHeight * primaryLineCount) + (Math.max(0, primaryLineCount - 1) * primaryLineGap);
        if (translationText.length() > 0) {
            totalHeight += primaryHeight + translationGap;
        }
        float primaryTop = centerY - (totalHeight / 2f);
        float primaryBaseline = primaryTop - metrics.ascent;
        drawTimedPrimaryLine(canvas, state, x, primaryBaseline, paint, width, primaryHeight + primaryLineGap);

        if (translationText.length() > 0) {
            int previousAlpha = paint.getAlpha();
            paint.setColor(mNormalColor);
            paint.setAlpha((int) (previousAlpha * 0.88f));
            float translationBaseline = primaryBaseline
                    + ((primaryLineCount - 1) * (primaryHeight + primaryLineGap))
                    + primaryHeight + translationGap;
            canvas.drawText(translationText, x, translationBaseline, paint);
            paint.setAlpha(previousAlpha);
        }
    }

    private void drawProgressLineText(Canvas canvas, LineState state, float x, float centerY,
                                      TextPaint paint, float width, boolean multiline) {
        if (state == null || state.line == null || state.text == null) {
            return;
        }
        float progress = resolveLinePlaybackProgress(state.line, state.index);
        int alpha = paint.getAlpha();
        mHighlightPaint.setAlpha(alpha);
        mHighlightPaint.setStrokeWidth(dp(0.55f) + (dp(0.30f) * easeOutQuart(progress)));
        if (multiline) {
            drawProgressMultilineText(canvas, state.text, x, centerY, paint, width, progress);
        } else {
            drawProgressSingleLineText(canvas, state.text, x, centerY, paint, width, progress);
        }
        mHighlightPaint.setStrokeWidth(dp(0.55f));
    }

    private void drawProgressSingleLineText(Canvas canvas, String text, float x, float centerY,
                                            TextPaint paint, float width, float progress) {
        CharSequence displayText = TextUtils.ellipsize(text.replace('\n', ' '), paint,
                Math.max(1f, width), TextUtils.TruncateAt.END);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
        String rendered = displayText.toString();
        float renderedWidth = paint.measureText(rendered);
        canvas.drawText(rendered, x, baseline, paint);
        if (progress > 0f && renderedWidth > 0f) {
            canvas.save();
            canvas.clipRect(x, baseline + metrics.ascent - dp(4f),
                    x + (renderedWidth * progress), baseline + metrics.descent + dp(4f));
            canvas.drawText(rendered, x, baseline, mHighlightPaint);
            canvas.restore();
        }
    }

    private void drawProgressMultilineText(Canvas canvas, String text, float x, float centerY,
                                           TextPaint paint, float width, float progress) {
        int availableWidth = Math.max(1, (int) width);
        StaticLayout layout = new StaticLayout(
                text,
                paint,
                availableWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.08f,
                dp(2f),
                true);
        float translateY = centerY - (layout.getHeight() / 2f);
        canvas.save();
        canvas.translate(x, translateY);
        layout.draw(canvas);
        if (progress > 0f) {
            StaticLayout highlightLayout = new StaticLayout(
                    text,
                    mHighlightPaint,
                    availableWidth,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.08f,
                    dp(2f),
                    true);
            canvas.save();
            canvas.clipRect(0f, 0f, availableWidth * progress, layout.getHeight());
            highlightLayout.draw(canvas);
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawTimedPrimaryLine(Canvas canvas, LineState state,
                                      float startX, float baseline, TextPaint paint,
                                      float availableWidth, float lineAdvance) {
        LyricLine line = state != null ? state.line : null;
        List<LyricLine.TimedWord> timedWords = line.getTimedWords();
        if (timedWords == null || timedWords.isEmpty()) {
            canvas.drawText(line != null ? line.getText() : "", startX, baseline, paint);
            return;
        }
        int alpha = paint.getAlpha();
        Paint.FontMetrics metrics = paint.getFontMetrics();

        mTextPaint.setAlpha(alpha);
        float trailingHighlight = state != null ? clamp01(state.currentHighlightAlpha) : 0f;
        mHighlightPaint.setAlpha((int) (alpha * Math.max(trailingHighlight, 0f)));
        float baseStrokeWidth = dp(0.55f);

        float cursorX = startX;
        float currentBaseline = baseline;
        for (int i = 0; i < timedWords.size(); i++) {
            LyricLine.TimedWord word = timedWords.get(i);
            String wordText = word.getText();
            if (wordText == null || wordText.length() == 0) {
                continue;
            }
            String renderedWord = i == timedWords.size() - 1 ? wordText : wordText + " ";
            float wordWidth = paint.measureText(renderedWord);

            if (cursorX > startX && cursorX - startX + wordWidth > availableWidth) {
                cursorX = startX;
                currentBaseline += lineAdvance;
            }

            float wordEmphasis = resolveWordEmphasisProgress(state, line, word, i, timedWords.size());
            float wordOffsetY = resolveWordFloatOffsetY(wordEmphasis)
                    + resolveWordDriftOffsetY(state, line, word, i, timedWords.size(), wordEmphasis);
            float wordOffsetX = resolveWordFloatOffsetX(wordEmphasis, i, timedWords.size())
                    + resolveWordDriftOffsetX(state, line, word, i, timedWords.size(), wordEmphasis);
            float drawX = cursorX + wordOffsetX;
            float drawBaseline = currentBaseline + wordOffsetY;

            canvas.drawText(renderedWord, drawX, drawBaseline, mTextPaint);

            mHighlightPaint.setStrokeWidth(baseStrokeWidth + (dp(0.42f) * wordEmphasis));

            if (mCurrentTimeMs >= word.getEndTime()) {
                canvas.drawText(renderedWord, drawX, drawBaseline, mHighlightPaint);
            } else if (mCurrentTimeMs >= word.getStartTime()) {
                long duration = Math.max(1L, word.getEndTime() - word.getStartTime());
                float progress = clamp01((mCurrentTimeMs - word.getStartTime()) / (float) duration);
                float fillWidth = wordWidth * progress;
                if (fillWidth > 0f) {
                    canvas.save();
                    canvas.clipRect(drawX, drawBaseline + metrics.ascent - dp(4f),
                            drawX + fillWidth, drawBaseline + metrics.descent + dp(4f));
                    canvas.drawText(renderedWord, drawX, drawBaseline, mHighlightPaint);
                    canvas.restore();
                }
            }

            mHighlightPaint.setStrokeWidth(baseStrokeWidth);

            cursorX += wordWidth;
        }
    }

    private float resolveWordEmphasisProgress(LineState state, LyricLine line,
                                              LyricLine.TimedWord word, int wordIndex, int totalWords) {
        if (line == null || word == null) {
            return 0f;
        }
        long duration = Math.max(140L, word.getEndTime() - word.getStartTime());
        long leadIn = Math.min(200L, (long) (duration * 0.32f));
        long windowStart = word.getStartTime() - leadIn;
        long lineFadeEnd = Math.max(line.getEndTime(), word.getEndTime()) + 520L;
        if (mCurrentTimeMs <= windowStart || mCurrentTimeMs >= lineFadeEnd) {
            return 0f;
        }
        float envelope;
        if (mCurrentTimeMs < word.getEndTime()) {
            float rise = clamp01((mCurrentTimeMs - windowStart) / (float) Math.max(1L, word.getEndTime() - windowStart));
            envelope = easeOutQuart(rise);
        } else {
            envelope = resolveWordSustainProgress(state, line, word);
        }
        if (totalWords > 1) {
            envelope *= (0.92f + (0.08f * (1f - (wordIndex / (float) totalWords))));
        }
        return clamp01(envelope);
    }

    private float resolveWordFloatOffsetY(float emphasis) {
        return -sp(1.35f) * emphasis;
    }

    private float resolveWordFloatOffsetX(float emphasis, int wordIndex, int totalWords) {
        float direction = ((wordIndex % 2) == 0) ? -1f : 1f;
        float balance = totalWords <= 1 ? 0f : ((wordIndex / (float) (totalWords - 1)) - 0.5f);
        return dp(0.9f) * emphasis * (direction * 0.22f + balance * 0.28f);
    }

    private float resolveWordDriftOffsetY(LineState state, LyricLine line,
                                          LyricLine.TimedWord word, int wordIndex,
                                          int totalWords, float emphasis) {
        if (line == null || word == null || emphasis <= 0.01f || mCurrentTimeMs < word.getEndTime()) {
            return 0f;
        }
        float sustain = resolveWordSustainProgress(state, line, word);
        if (sustain <= 0f) {
            return 0f;
        }
        float phase = ((mCurrentTimeMs - word.getEndTime()) / 560f) + (wordIndex * 0.26f);
        float wave = (float) Math.sin(phase);
        return -dp(0.8f) * sustain * (0.4f + (0.6f * emphasis)) * wave;
    }

    private float resolveWordDriftOffsetX(LineState state, LyricLine line,
                                          LyricLine.TimedWord word, int wordIndex,
                                          int totalWords, float emphasis) {
        if (line == null || word == null || emphasis <= 0.01f || mCurrentTimeMs < word.getEndTime()) {
            return 0f;
        }
        float sustain = resolveWordSustainProgress(state, line, word);
        if (sustain <= 0f) {
            return 0f;
        }
        float balance = totalWords <= 1 ? 0f : ((wordIndex / (float) (totalWords - 1)) - 0.5f);
        float phase = ((mCurrentTimeMs - word.getEndTime()) / 760f) + (wordIndex * 0.18f);
        float wave = (float) Math.cos(phase);
        return dp(0.55f) * sustain * (0.5f + Math.abs(balance)) * wave;
    }

    private float resolveWordSustainProgress(LineState state, LyricLine line, LyricLine.TimedWord word) {
        if (line == null || word == null) {
            return 0f;
        }
        if (isTimedLineActive(line)) {
            return 1f;
        }
        float trailing = state != null ? clamp01(state.currentHighlightAlpha) : 0f;
        long releaseWindow = 680L;
        float release = 1f - clamp01((mCurrentTimeMs - word.getEndTime()) / (float) releaseWindow);
        return Math.max(trailing, easeOutQuart(release));
    }

    private float resolveLinePlaybackProgress(LyricLine line, int lineIndex) {
        if (line == null) {
            return 0f;
        }
        long startTime = line.getTime();
        long endTime = line.getEndTime();
        if (lineIndex >= 0 && lineIndex + 1 < mLyrics.size()) {
            endTime = Math.max(endTime, mLyrics.get(lineIndex + 1).getTime());
        }
        if (endTime <= startTime) {
            endTime = startTime + 1200L;
        }
        return clamp01((mCurrentTimeMs - startTime) / (float) (endTime - startTime));
    }

    private float easeOutQuart(float t) {
        float inv = 1f - clamp01(t);
        return 1f - (inv * inv * inv * inv);
    }

    private float dp(float value) {
        return value * mDensity;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private static class LineState {
        LyricLine line;
        String text;
        int index;
        float currentX;
        float targetX;
        float currentY;
        float targetY;
        float currentAlpha;
        float targetAlpha;
        float currentScale;
        float targetScale;
        float currentHighlightAlpha;
        float targetHighlightAlpha;
        float layoutHeight;
        float cachedMeasureWidth;
        float lockedWrapWidth;
        SpringValue posYSpring;
        SpringValue scaleSpring;
        LinearValue alphaLinear;
        LinearValue highlightLinear;
    }

    private static class LinearValue {
        private float mCurrent;
        private float mTarget;
        private float mStart;
        private float mDurationSeconds;
        private float mElapsedSeconds;
        private float mQueuedDelaySeconds;
        private Float mQueuedTarget;

        LinearValue(float value, float durationSeconds) {
            mCurrent = value;
            mTarget = value;
            mStart = value;
            mDurationSeconds = durationSeconds;
            mElapsedSeconds = durationSeconds;
        }

        void setPosition(float value) {
            mCurrent = value;
            mTarget = value;
            mStart = value;
            mElapsedSeconds = mDurationSeconds;
            mQueuedTarget = null;
            mQueuedDelaySeconds = 0f;
        }

        void setTargetPosition(float value, float delaySeconds) {
            if (delaySeconds > 0f) {
                mQueuedTarget = Float.valueOf(value);
                mQueuedDelaySeconds = delaySeconds;
                return;
            }
            mQueuedTarget = null;
            mQueuedDelaySeconds = 0f;
            mStart = mCurrent;
            mTarget = value;
            mElapsedSeconds = 0f;
        }

        void update(float deltaSeconds) {
            if (mQueuedTarget != null) {
                mQueuedDelaySeconds -= deltaSeconds;
                if (mQueuedDelaySeconds <= 0f) {
                    setTargetPosition(mQueuedTarget.floatValue(), 0f);
                }
            }
            if (arrived()) {
                mCurrent = mTarget;
                return;
            }
            mElapsedSeconds += deltaSeconds;
            float progress = mDurationSeconds <= 0f ? 1f : Math.min(1f, mElapsedSeconds / mDurationSeconds);
            float eased = (float) (-(Math.cos(Math.PI * progress) - 1f) / 2f);
            mCurrent = mStart + ((mTarget - mStart) * eased);
        }

        boolean arrived() {
            return mQueuedTarget == null && Math.abs(mCurrent - mTarget) < 0.001f && mElapsedSeconds >= mDurationSeconds;
        }

        float getCurrentPosition() {
            return mCurrent;
        }
    }

    private static class SpringValue {
        private float mCurrent;
        private float mTarget;
        private float mVelocity;
        private float mQueuedDelaySeconds;
        private Float mQueuedTarget;
        private float mMass;
        private float mDamping;
        private float mStiffness;

        SpringValue(float value, float mass, float damping, float stiffness) {
            mCurrent = value;
            mTarget = value;
            mMass = mass;
            mDamping = damping;
            mStiffness = stiffness;
        }

        void setPosition(float value) {
            mCurrent = value;
            mTarget = value;
            mVelocity = 0f;
            mQueuedTarget = null;
            mQueuedDelaySeconds = 0f;
        }

        void setTargetPosition(float value, float delaySeconds) {
            if (delaySeconds > 0f) {
                mQueuedTarget = Float.valueOf(value);
                mQueuedDelaySeconds = delaySeconds;
                return;
            }
            mQueuedTarget = null;
            mQueuedDelaySeconds = 0f;
            mTarget = value;
        }

        void setParams(float mass, float damping, float stiffness) {
            mMass = mass;
            mDamping = damping;
            mStiffness = stiffness;
        }

        void update(float deltaSeconds) {
            if (mQueuedTarget != null) {
                mQueuedDelaySeconds -= deltaSeconds;
                if (mQueuedDelaySeconds <= 0f) {
                    setTargetPosition(mQueuedTarget.floatValue(), 0f);
                }
            }
            float displacement = mCurrent - mTarget;
            float acceleration = ((-mStiffness * displacement) - (mDamping * mVelocity)) / mMass;
            mVelocity += acceleration * deltaSeconds;
            mCurrent += mVelocity * deltaSeconds;
            if (arrived()) {
                mCurrent = mTarget;
                mVelocity = 0f;
            }
        }

        boolean arrived() {
            return mQueuedTarget == null && Math.abs(mCurrent - mTarget) < 0.01f && Math.abs(mVelocity) < 0.01f;
        }

        float getCurrentPosition() {
            return mCurrent;
        }
    }
}
