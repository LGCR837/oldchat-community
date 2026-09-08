package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.AppCompatImageView;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

public class ZoomImageView extends AppCompatImageView {
    private static final float MAX_SCALE_MULTIPLIER = 4.0f;

    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float baseScale = 1.0f;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private boolean matrixInitialized = false;

    public ZoomImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float current = getScale();
                if (current > baseScale * 1.45f) {
                    centerImage();
                } else {
                    float target = Math.min(baseScale * 2.5f, baseScale * MAX_SCALE_MULTIPLIER);
                    float factor = target / Math.max(0.0001f, current);
                    matrix.postScale(factor, factor, e.getX(), e.getY());
                    constrainTranslation();
                    setImageMatrix(matrix);
                }
                return true;
            }
        });
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        matrixInitialized = false;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!matrixInitialized && getDrawable() != null) {
            centerImage();
            matrixInitialized = true;
        }
    }

    private void centerImage() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }

        matrix.reset();
        float scaleX = (float) viewWidth / drawableWidth;
        float scaleY = (float) viewHeight / drawableHeight;
        // 首次打开必须完整适配屏幕；原实现强制最小 1.0，会把大图按原尺寸裁切。
        baseScale = Math.min(scaleX, scaleY);
        if (baseScale <= 0f) {
            baseScale = 1.0f;
        }
        float scaledWidth = drawableWidth * baseScale;
        float scaledHeight = drawableHeight * baseScale;
        float dx = (viewWidth - scaledWidth) / 2f;
        float dy = (viewHeight - scaledHeight) / 2f;
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector != null) {
            gestureDetector.onTouchEvent(event);
        }
        if (scaleDetector != null) {
            scaleDetector.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (!dragging) {
                        dragging = Math.hypot(dx, dy) > 4;
                    }
                    if (dragging && getScale() > baseScale * 1.001f) {
                        matrix.postTranslate(dx, dy);
                        constrainTranslation();
                        setImageMatrix(matrix);
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                constrainTranslation();
                setImageMatrix(matrix);
                break;
        }
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float currentScale = getScale();
            float minScale = baseScale;
            float maxScale = baseScale * MAX_SCALE_MULTIPLIER;
            float targetScale = currentScale * scaleFactor;
            if (targetScale < minScale) {
                scaleFactor = minScale / Math.max(0.0001f, currentScale);
            } else if (targetScale > maxScale) {
                scaleFactor = maxScale / Math.max(0.0001f, currentScale);
            }
            matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            constrainTranslation();
            setImageMatrix(matrix);
            return true;
        }
    }

    private void constrainTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(bounds);
        float dx = 0f;
        float dy = 0f;
        if (bounds.width() <= getWidth()) {
            dx = getWidth() / 2f - bounds.centerX();
        } else if (bounds.left > 0f) {
            dx = -bounds.left;
        } else if (bounds.right < getWidth()) {
            dx = getWidth() - bounds.right;
        }
        if (bounds.height() <= getHeight()) {
            dy = getHeight() / 2f - bounds.centerY();
        } else if (bounds.top > 0f) {
            dy = -bounds.top;
        } else if (bounds.bottom < getHeight()) {
            dy = getHeight() - bounds.bottom;
        }
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy);
        }
    }

    private float getScale() {
        matrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }
}
