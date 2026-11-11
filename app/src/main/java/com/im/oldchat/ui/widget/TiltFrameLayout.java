package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.im.oldchat.util.SettingsPrefs;

public class TiltFrameLayout extends FrameLayout {
    /** 供主页等场景监听真实按下/松开生命周期，不依赖外部 OnTouchListener。 */
    public interface PressLifecycleListener {
        void onPressChanged(TiltFrameLayout view, boolean pressed);
    }

    private static final float MAX_ROTATE_DEG = 12.5f;
    private static final float IDLE_ROTATE_DEG = 4.4f;
    private static final float RESET_SPRING = 0.24f;
    private static final long FRAME_DELAY_MS = 16L;
    private static final float PRESS_SINK_Z = 5.5f;
    private static final float IDLE_SINK_Z = 2.3f;
    private static final float CAMERA_DISTANCE_FACTOR = 40f;
    private static final float IDLE_CORNER_MAG = 0.72f;
    private static final float IDLE_STEP = 0.035f;

    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Runnable springRunnable = new Runnable() {
        @Override
        public void run() {
            boolean keep = stepSpring();
            if (keep) {
                postDelayed(this, FRAME_DELAY_MS);
            }
        }
    };
    private final Runnable morphRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoMorphEnabled || touching || springing || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            stepMorph();
            postDelayed(this, FRAME_DELAY_MS);
        }
    };
    private final Runnable neighborTiltRunnable = new Runnable() {
        @Override public void run() {
            neighborTiltProgress += 0.18f;
            if (neighborTiltProgress > 1f) neighborTiltProgress = 1f;
            float eased = 1f - (1f - neighborTiltProgress) * (1f - neighborTiltProgress);
            neighborRotateX = neighborFromRotateX
                    + (neighborTargetRotateX - neighborFromRotateX) * eased;
            neighborSinkZ = neighborFromSinkZ
                    + (neighborTargetSinkZ - neighborFromSinkZ) * eased;
            invalidate();
            if (neighborTiltProgress < 1f) postDelayed(this, FRAME_DELAY_MS);
        }
    };

    private float rotateX;
    private float rotateY;
    private float sinkZ;
    private float touchNx;
    private float touchNy;
    private float morphPhase;
    // 邻接水波专用的附加 3D 姿态，不覆盖本磁贴自己的触摸倾斜。
    private float neighborRotateX;
    private float neighborRotateY;
    private float neighborSinkZ;
    private float neighborFromRotateX;
    private float neighborTargetRotateX;
    private float neighborFromSinkZ;
    private float neighborTargetSinkZ;
    private float neighborTiltProgress;
    private boolean touching;
    private boolean springing;
    private boolean autoMorphEnabled;
    private boolean tiltRuntimeDisabled;
    private final boolean tiltSupported;
    private PressLifecycleListener pressLifecycleListener;

    public TiltFrameLayout(Context context) { this(context, null); }
    public TiltFrameLayout(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public TiltFrameLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        tiltSupported = true;
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        if (tiltSupported) {
            setupCamera();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (!isTiltActive()) {
            // 即使当前关闭 3D 倾斜，主页仍可收到邻接按压生命周期。
            if (action == MotionEvent.ACTION_DOWN) notifyPressLifecycle(true);
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                notifyPressLifecycle(false);
            return super.dispatchTouchEvent(event);
        }
        try {
            if (action == MotionEvent.ACTION_DOWN) {
                touching = true;
                notifyPressLifecycle(true);
                stopSpring();
                stopMorph();
                updateTiltByTouch(event.getX(), event.getY());
                invalidate();
            } else if (action == MotionEvent.ACTION_MOVE) {
                updateTiltByTouch(event.getX(), event.getY());
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
                notifyPressLifecycle(false);
                startSpring();
            }
        } catch (Throwable ignored) {
            disableTiltRuntime();
        }
        return super.dispatchTouchEvent(event);
    }

    public void setPressLifecycleListener(PressLifecycleListener listener) {
        pressLifecycleListener = listener;
    }

    private void notifyPressLifecycle(boolean pressed) {
        if (pressLifecycleListener == null) return;
        try {
            pressLifecycleListener.onPressChanged(this, pressed);
        } catch (Throwable ignored) {
        }
    }

    /** 页面可显式关闭倾斜、回弹和待机形变，同时保留正常点击。 */
    public void setTiltEnabled(boolean enabled) {
        tiltRuntimeDisabled = !enabled;
        if (!enabled) {
            touching = false;
            springing = false;
            autoMorphEnabled = false;
            stopSpring();
            stopMorph();
            resetPose();
            clearAnimation();
            invalidate();
        }
    }

    public void setAutoMorphEnabled(boolean enabled) {
        if (!tiltSupported) {
            autoMorphEnabled = false;
            return;
        }
        if (autoMorphEnabled == enabled) {
            return;
        }
        autoMorphEnabled = enabled;
        if (enabled) {
            startMorph();
        } else {
            stopMorph();
            if (!touching && !springing) {
                resetPose();
                invalidate();
            }
        }
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
        boolean hasNeighborPose = Math.abs(neighborRotateX) > 0.01f
                || Math.abs(neighborRotateY) > 0.01f || neighborSinkZ > 0.01f;
        if (!isTiltActive() && !hasNeighborPose) {
            super.draw(canvas);
            return;
        }
        int save = canvas.save();
        try {
            applyTileMatrix();
            canvas.concat(matrix);
            super.draw(canvas);
            canvas.restoreToCount(save);
        } catch (Throwable ignored) {
            canvas.restoreToCount(save);
            disableTiltRuntime();
            super.draw(canvas);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isTiltActive()) {
            startMorph();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSpring();
        stopMorph();
        removeCallbacks(neighborTiltRunnable);
        clearNeighborTilt();
        notifyPressLifecycle(false);
    }

    private void applyTileMatrix() {
        float px = resolvePivotX();
        float py = resolvePivotY();
        matrix.reset();
        camera.save();
        // Keep pure 3D perspective feeling without scale squash.
        camera.translate(0f, 0f, -(sinkZ + neighborSinkZ));
        camera.rotateX(rotateX + neighborRotateX);
        camera.rotateY(rotateY + neighborRotateY);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-px, -py);
        matrix.postTranslate(px, py);
    }

    private void updateTiltByTouch(float x, float y) {
        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());
        float nx = clamp((x - w * 0.5f) / (w * 0.5f), -1f, 1f);
        float ny = clamp((y - h * 0.5f) / (h * 0.5f), -1f, 1f);
        applyPose(nx, ny, MAX_ROTATE_DEG, PRESS_SINK_Z);
    }

    private void startSpring() {
        if (springing) {
            return;
        }
        springing = true;
        removeCallbacks(springRunnable);
        post(springRunnable);
    }

    private void stopSpring() {
        springing = false;
        removeCallbacks(springRunnable);
    }

    private boolean stepSpring() {
        rotateX += (0f - rotateX) * RESET_SPRING;
        rotateY += (0f - rotateY) * RESET_SPRING;
        sinkZ += (0f - sinkZ) * RESET_SPRING;
        if (Math.abs(rotateX) < 0.15f && Math.abs(rotateY) < 0.15f && sinkZ < 0.2f) {
            resetPose();
            springing = false;
            invalidate();
            startMorph();
            return false;
        }
        invalidate();
        return true;
    }

    private void setupCamera() {
        float density = getResources().getDisplayMetrics().density;
        try {
            Camera.class.getMethod("setLocation", float.class, float.class, float.class)
                    .invoke(camera, 0f, 0f, -CAMERA_DISTANCE_FACTOR * density);
        } catch (Throwable ignored) {
            // API 9/10 has no Camera.setLocation; keep default camera for compatibility.
        }
    }

    private float resolvePivotX() {
        float totalRotateY = rotateY + neighborRotateY;
        return Math.abs(totalRotateY) < 0.01f ? getWidth() * 0.5f
                : (totalRotateY < 0f ? getWidth() : 0f);
    }

    private float resolvePivotY() {
        if (Math.abs(neighborRotateX) >= 0.01f && Math.abs(rotateX) < 0.01f) {
            // 上方邻居以下缘为轴，下方邻居以上缘为轴，形成朝中心传播的折叠感。
            return neighborRotateX < 0f ? getHeight() : 0f;
        }
        return Math.abs(rotateX) < 0.01f ? getHeight() * 0.5f
                : (touchNy < 0f ? getHeight() : 0f);
    }

    private float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private void startMorph() {
        if (!autoMorphEnabled || touching || springing) {
            return;
        }
        removeCallbacks(morphRunnable);
        post(morphRunnable);
    }

    private void stopMorph() {
        removeCallbacks(morphRunnable);
    }

    private void stepMorph() {
        morphPhase += IDLE_STEP;
        if (morphPhase >= 4f) {
            morphPhase -= 4f;
        }
        float nx;
        float ny;
        if (morphPhase < 1f) {
            nx = lerp(-IDLE_CORNER_MAG, IDLE_CORNER_MAG, morphPhase);
            ny = -IDLE_CORNER_MAG;
        } else if (morphPhase < 2f) {
            nx = IDLE_CORNER_MAG;
            ny = lerp(-IDLE_CORNER_MAG, IDLE_CORNER_MAG, morphPhase - 1f);
        } else if (morphPhase < 3f) {
            nx = lerp(IDLE_CORNER_MAG, -IDLE_CORNER_MAG, morphPhase - 2f);
            ny = IDLE_CORNER_MAG;
        } else {
            nx = -IDLE_CORNER_MAG;
            ny = lerp(IDLE_CORNER_MAG, -IDLE_CORNER_MAG, morphPhase - 3f);
        }
        applyPose(nx, ny, IDLE_ROTATE_DEG, IDLE_SINK_Z);
        invalidate();
    }

    private void applyPose(float nx, float ny, float rotateDeg, float sinkScale) {
        touchNx = clamp(nx, -1f, 1f);
        touchNy = clamp(ny, -1f, 1f);
        rotateY = touchNx * rotateDeg;
        rotateX = -touchNy * rotateDeg;
        float intensity = Math.max(Math.abs(touchNx), Math.abs(touchNy));
        sinkZ = intensity * sinkScale;
    }

    private float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** 设置邻接联动的附加 3D 姿态；由主页水波动画逐帧调用。 */
    public void setNeighborTilt(float rotateX, float rotateY, float sinkZ) {
        neighborRotateX = clamp(rotateX, -14f, 14f);
        neighborRotateY = clamp(rotateY, -14f, 14f);
        neighborSinkZ = clamp(sinkZ, 0f, 8f);
        invalidate();
    }

    /** 内部逐帧插值，确保附加 Camera 姿态实际进入 draw()。 */
    public void animateNeighborTilt(float rotateX, float sinkZ, long delayMs) {
        removeCallbacks(neighborTiltRunnable);
        neighborFromRotateX = neighborRotateX;
        neighborTargetRotateX = clamp(rotateX, -14f, 14f);
        neighborFromSinkZ = neighborSinkZ;
        neighborTargetSinkZ = clamp(sinkZ, 0f, 8f);
        neighborTiltProgress = 0f;
        postDelayed(neighborTiltRunnable, Math.max(0L, delayMs));
    }

    public float getNeighborRotateX() {
        return neighborRotateX;
    }

    public float getNeighborSinkZ() {
        return neighborSinkZ;
    }

    public void clearNeighborTilt() {
        removeCallbacks(neighborTiltRunnable);
        neighborRotateX = 0f;
        neighborRotateY = 0f;
        neighborSinkZ = 0f;
        neighborTargetRotateX = 0f;
        neighborTargetSinkZ = 0f;
        invalidate();
    }

    private void resetPose() {
        rotateX = 0f;
        rotateY = 0f;
        sinkZ = 0f;
        touchNx = 0f;
        touchNy = 0f;
    }

    private boolean isTiltActive() {
        return tiltSupported && !tiltRuntimeDisabled
                && SettingsPrefs.isButton3dEffectEnabled(getContext());
    }

    private void disableTiltRuntime() {
        tiltRuntimeDisabled = true;
        touching = false;
        springing = false;
        autoMorphEnabled = false;
        stopSpring();
        stopMorph();
        resetPose();
        clearAnimation();
        invalidate();
    }
}
