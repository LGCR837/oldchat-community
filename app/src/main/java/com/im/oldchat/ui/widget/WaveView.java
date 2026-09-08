package com.im.oldchat.ui.widget;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import com.im.oldchat.R;
public class WaveView extends View {
    private static final int FRAME_DELAY_MS = 16;
    private static final int POINT_COUNT = 15;
    private static final float ACTIVE_GATE = 0.018f;
    private final Paint backWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frontWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path backWavePath = new Path();
    private final Path frontWavePath = new Path();
    private final Handler frameHandler = new Handler();
    private final float[] targetBins = new float[POINT_COUNT];
    private final float[] displayBins = new float[POINT_COUNT];
    private final float[] tempXPoints = new float[POINT_COUNT];
    private final float[] tempYPoints = new float[POINT_COUNT];
    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!animating) {
                return;
            }
            advanceOffset();
            invalidate();
            frameHandler.postDelayed(this, FRAME_DELAY_MS);
        }
    };
    private float waveLengthPx;
    private float backAmplitudePx;
    private float frontAmplitudePx;
    private float backSpeedPx;
    private float frontSpeedPx;
    private float backOffsetPx;
    private float frontOffsetPx;
    private float targetLevelRatio;
    private float displayLevelRatio;
    private float motionRatio;
    private boolean attached;
    private boolean animating;
    public WaveView(Context context) {
        super(context);
        init(context, null);
    }
    public WaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }
    public WaveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }
    private void init(Context context, AttributeSet attrs) {
        backWavePaint.setStyle(Paint.Style.FILL);
        frontWavePaint.setStyle(Paint.Style.FILL);
        int defaultBackColor = Color.parseColor("#F43F5E");
        int defaultFrontColor = Color.WHITE;
        waveLengthPx = dp(168f);
        backAmplitudePx = dp(14f);
        frontAmplitudePx = dp(10f);
        backSpeedPx = dp(0.85f);
        frontSpeedPx = dp(1.15f);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.WaveView);
            try {
                defaultBackColor = a.getColor(R.styleable.WaveView_waveBackColor, defaultBackColor);
                defaultFrontColor = a.getColor(R.styleable.WaveView_waveFrontColor, defaultFrontColor);
                waveLengthPx = a.getDimension(R.styleable.WaveView_waveLength, waveLengthPx);
                backAmplitudePx = a.getDimension(R.styleable.WaveView_waveBackAmplitude, backAmplitudePx);
                frontAmplitudePx = a.getDimension(R.styleable.WaveView_waveFrontAmplitude, frontAmplitudePx);
                backSpeedPx = a.getDimension(R.styleable.WaveView_waveBackSpeed, backSpeedPx);
                frontSpeedPx = a.getDimension(R.styleable.WaveView_waveFrontSpeed, frontSpeedPx);
            } finally {
                a.recycle();
            }
        }
        backWavePaint.setColor(defaultBackColor);
        frontWavePaint.setColor(defaultFrontColor);
    }
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        syncAnimationState();
    }
    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stop();
        super.onDetachedFromWindow();
    }
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncAnimationState();
    }
    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncAnimationState();
    }
    private void syncAnimationState() {
        boolean shouldAnimate = attached && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE;
        if (shouldAnimate) {
            start();
        } else {
            stop();
        }
    }
    public void start() {
        if (animating) {
            return;
        }
        animating = true;
        frameHandler.removeCallbacks(frameRunnable);
        frameHandler.post(frameRunnable);
    }
    public void stop() {
        animating = false;
        frameHandler.removeCallbacks(frameRunnable);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        updateDisplayState();
        float backBaseY = height * 0.56f;
        float frontBaseY = height * 0.71f;
        float backAmp = backAmplitudePx * (0.62f + motionRatio * 1.35f);
        float frontAmp = frontAmplitudePx * (0.56f + motionRatio * 1.12f);
        buildWavePath(backWavePath, width, height, backBaseY, backAmp, backOffsetPx, 0.22f, 1.0f);
        buildWavePath(frontWavePath, width, height, frontBaseY, frontAmp, frontOffsetPx, 0.0f, 0.88f);
        canvas.drawPath(backWavePath, backWavePaint);
        canvas.drawPath(frontWavePath, frontWavePaint);
    }
    private void updateDisplayState() {
        float total = 0f;
        float max = 0f;
        for (int i = 0; i < POINT_COUNT; i++) {
            float value = displayBins[i] * 0.64f + targetBins[i] * 0.36f;
            if (value < ACTIVE_GATE) {
                value = 0f;
            }
            displayBins[i] = value;
            total += value;
            if (value > max) {
                max = value;
            }
        }
        displayLevelRatio = displayLevelRatio * 0.68f + targetLevelRatio * 0.32f;
        if (displayLevelRatio < ACTIVE_GATE) {
            displayLevelRatio = 0f;
        }
        float avg = total / POINT_COUNT;
        motionRatio = Math.max(max, Math.max(avg, displayLevelRatio));
        if (motionRatio < ACTIVE_GATE) {
            motionRatio = 0f;
        }
    }
    private void buildWavePath(Path path, int width, int height,
                               float baseY, float amplitude, float offset,
                               float phaseShift, float layerScale) {
        float yBase = clamp(baseY, 0f, (float) height);
        float segment = width / (float) (POINT_COUNT - 1);
        for (int i = 0; i < POINT_COUNT; i++) {
            float pointValue = sampleBin(i, width, offset, phaseShift) * layerScale;
            tempXPoints[i] = i * segment;
            tempYPoints[i] = clamp(yBase - amplitude * pointValue, 0f, (float) height);
        }
        path.reset();
        path.moveTo(0f, height);
        path.lineTo(tempXPoints[0], tempYPoints[0]);
        float prevX = tempXPoints[0];
        float prevY = tempYPoints[0];
        for (int i = 1; i < POINT_COUNT; i++) {
            float nextX = tempXPoints[i];
            float nextY = tempYPoints[i];
            path.quadTo(prevX, prevY, (prevX + nextX) * 0.5f, (prevY + nextY) * 0.5f);
            prevX = nextX;
            prevY = nextY;
        }
        path.quadTo(prevX, prevY, tempXPoints[POINT_COUNT - 1], tempYPoints[POINT_COUNT - 1]);
        path.lineTo(width, height);
        path.close();
    }
    private float sampleBin(int pointIndex, int width, float offsetPx, float phaseShift) {
        float renderWidth = waveLengthPx > 0f ? waveLengthPx : width;
        if (renderWidth <= 0f) {
            renderWidth = 1f;
        }
        float phase = (offsetPx / renderWidth) * (POINT_COUNT - 1) + phaseShift * (POINT_COUNT - 1);
        float samplePos = pointIndex + phase;
        while (samplePos < 0f) {
            samplePos += POINT_COUNT;
        }
        while (samplePos >= POINT_COUNT) {
            samplePos -= POINT_COUNT;
        }
        int left = (int) samplePos;
        int right = left + 1;
        if (right >= POINT_COUNT) {
            right = 0;
        }
        float t = samplePos - left;
        return displayBins[left] * (1f - t) + displayBins[right] * t;
    }
    private void advanceOffset() {
        if (waveLengthPx <= 0f || motionRatio <= 0f) {
            return;
        }
        float speedBoost = 0.24f + motionRatio * 1.06f;
        backOffsetPx += backSpeedPx * speedBoost;
        frontOffsetPx += frontSpeedPx * speedBoost;
        while (backOffsetPx >= waveLengthPx) {
            backOffsetPx -= waveLengthPx;
        }
        while (frontOffsetPx >= waveLengthPx) {
            frontOffsetPx -= waveLengthPx;
        }
    }
    public void setMusicBins(int[] bins) {
        if (bins == null || bins.length == 0) {
            for (int i = 0; i < POINT_COUNT; i++) {
                targetBins[i] = 0f;
            }
            targetLevelRatio = 0f;
            if (!animating) {
                invalidate();
            }
            return;
        }
        float total = 0f;
        int sourceLast = bins.length - 1;
        for (int i = 0; i < POINT_COUNT; i++) {
            float sourcePos = (i * sourceLast) / (float) (POINT_COUNT - 1);
            int left = (int) sourcePos;
            int right = left + 1;
            if (right > sourceLast) {
                right = sourceLast;
            }
            float value = bins[left] * (1f - (sourcePos - left)) + bins[right] * (sourcePos - left);
            value = clamp(value, 0f, 100f);
            if (value < 2f) {
                value = 0f;
            }
            targetBins[i] = value / 100f;
            total += targetBins[i];
        }
        targetLevelRatio = total / POINT_COUNT;
        if (!animating) {
            invalidate();
        }
    }
    public void setMusicLevel(int level) {
        if (level < 0) {
            level = 0;
        } else if (level > 100) {
            level = 100;
        }
        if (level < 2) {
            level = 0;
        }
        targetLevelRatio = level / 100f;
        for (int i = 0; i < POINT_COUNT; i++) {
            targetBins[i] = targetLevelRatio;
        }
        if (!animating) {
            invalidate();
        }
    }
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
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
