package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class CoverAmbientMotionView extends View {

    private static final int BLOB_COUNT = 4;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader mBackgroundShader;
    private boolean mBackgroundDirty = true;
    private final Blob[] mBlobs = new Blob[BLOB_COUNT];
    private final int[] mColors = new int[]{
            Color.parseColor("#FF5B6E"),
            Color.parseColor("#4D8BFF"),
            Color.parseColor("#FFC857")
    };
    // 暗色模式专用暗色系 blob（随封面取色后按明度压暗，或直接用暗色系）
    private final int[] mDarkColors = new int[]{
            Color.parseColor("#8A3A4A"),
            Color.parseColor("#33507A"),
            Color.parseColor("#7A5C2A")
    };
    private boolean mDarkMode = false;

    private boolean mRunning;

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            invalidate();
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                postOnAnimation(this);
            } else {
                postDelayed(this, 16L);
            }
        }
    };

    public CoverAmbientMotionView(Context context) {
        this(context, null);
    }

    public CoverAmbientMotionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setDither(true);
        initBlobs();
    }

    private void initBlobs() {
        for (int i = 0; i < BLOB_COUNT; i++) {
            Blob blob = new Blob();
            blob.sizeFactor = 0.16f + (i * 0.028f);
            blob.heightFactor = 0.62f + ((i % 2) * 0.14f);
            blob.centerXFactor = 0.18f + (i * 0.21f);
            blob.centerYFactor = 0.22f + ((i % 3) * 0.18f);
            blob.orbitX = 0.08f + (i * 0.018f);
            blob.orbitY = 0.09f + ((i % 2) * 0.024f);
            blob.speed = 0.22f + (i * 0.045f);
            blob.rotationSpeed = 13f + (i * 3.8f);
            blob.phase = i * 1.37f;
            blob.colorIndex = i % mColors.length;
            mBlobs[i] = blob;
        }
    }

    public void setAccentColors(int[] colors) {
        if (colors == null || colors.length == 0) {
            return;
        }
        for (int i = 0; i < mColors.length; i++) {
            mColors[i] = colors[i % colors.length];
            mDarkColors[i] = buildDarkTone(mColors[i]);
        }
        mBackgroundDirty = true;
        invalidate();
    }

    /** 设置暗色模式：使用暗色系 blob 颜色，适配深色播放页背景。 */
    public void setDarkMode(boolean dark) {
        if (mDarkMode == dark) {
            return;
        }
        mDarkMode = dark;
        mBackgroundDirty = true;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mRunning = true;
        removeCallbacks(mTicker);
        mTicker.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mRunning = false;
        removeCallbacks(mTicker);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        drawMonetBackground(canvas, width, height);
        float shortSide = Math.min(width, height);
        float elapsed = SystemClock.uptimeMillis() / 1000f;
        for (int i = 0; i < mBlobs.length; i++) {
            Blob blob = mBlobs[i];
            if (blob == null) {
                continue;
            }
            float local = elapsed * blob.speed;
            float centerX = (width * blob.centerXFactor)
                    + ((float) Math.sin(local + blob.phase) * width * blob.orbitX);
            float centerY = (height * blob.centerYFactor)
                    + ((float) Math.cos((local * 0.82f) + blob.phase) * height * blob.orbitY);
            float pulse = 0.92f + (((float) Math.sin((local * 1.35f) + blob.phase)) * 0.08f);
            float blobWidth = shortSide * (0.28f + blob.sizeFactor) * pulse;
            float blobHeight = blobWidth * blob.heightFactor;
            float rotation = (float) Math.sin((local * 0.45f) + blob.phase) * blob.rotationSpeed;
            int alpha = 56 + (i * 12) + (int) (((float) Math.sin((local * 1.1f) + blob.phase)) * 8f);
            int base = mDarkMode ? mDarkColors[blob.colorIndex % mDarkColors.length]
                    : mColors[blob.colorIndex % mColors.length];
            int color = withAlpha(base, alpha);
            float radius = blobWidth * 0.52f;

            canvas.save();
            canvas.rotate(rotation, centerX, centerY);
            canvas.translate(centerX, centerY);
            canvas.scale(1f, blobHeight / blobWidth);
            mPaint.setShader(new RadialGradient(
                    0f,
                    0f,
                    radius,
                    new int[]{withAlpha(color, 120), withAlpha(color, 68), withAlpha(color, 0)},
                    new float[]{0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(0f, 0f, radius, mPaint);
            mPaint.setShader(null);
            canvas.restore();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBackgroundDirty = true;
    }

    /** 封面色生成全屏混合渐变，不再是白底上的几个色块。 */
    private void drawMonetBackground(Canvas canvas, float width, float height) {
        if (mBackgroundDirty || mBackgroundShader == null) {
            int[] source = mDarkMode ? mDarkColors : mColors;
            int top = mDarkMode ? source[0] : mixWithWhite(source[0], 0.62f);
            int middle = mDarkMode ? source[1] : mixWithWhite(source[1], 0.70f);
            int bottom = mDarkMode ? source[2] : mixWithWhite(source[2], 0.76f);
            mBackgroundShader = new LinearGradient(0f, 0f, width, height,
                    new int[]{top, middle, bottom}, new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
            mBackgroundPaint.setShader(mBackgroundShader);
            mBackgroundDirty = false;
        }
        canvas.drawRect(0f, 0f, width, height, mBackgroundPaint);
    }

    private int buildDarkTone(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0.20f, Math.min(0.70f, hsv[1] * 0.78f));
        hsv[2] = Math.max(0.12f, Math.min(0.28f, hsv[2] * 0.36f));
        return Color.HSVToColor(hsv);
    }

    private int mixWithWhite(int color, float whiteRatio) {
        float keep = 1f - whiteRatio;
        int red = Math.round((Color.red(color) * keep) + (255f * whiteRatio));
        int green = Math.round((Color.green(color) * keep) + (255f * whiteRatio));
        int blue = Math.round((Color.blue(color) * keep) + (255f * whiteRatio));
        return Color.rgb(red, green, blue);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static class Blob {
        float sizeFactor;
        float heightFactor;
        float centerXFactor;
        float centerYFactor;
        float orbitX;
        float orbitY;
        float speed;
        float rotationSpeed;
        float phase;
        int colorIndex;
    }
}
