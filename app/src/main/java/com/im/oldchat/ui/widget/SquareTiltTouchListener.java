package com.im.oldchat.ui.widget;

import android.graphics.Camera;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;

import com.im.oldchat.util.SettingsPrefs;

final class SquareTiltTouchListener implements View.OnTouchListener {
    private static final float MAX_ROTATE_DEG = 12.5f;
    private static final float PRESS_SINK_Z = 5.5f;
    private static final float CAMERA_DISTANCE_FACTOR = 40f;
    private static final long DOWN_DURATION_MS = 90L;
    private static final long MOVE_DURATION_MS = 36L;
    private static final long UP_DURATION_MS = 120L;

    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final View.OnTouchListener delegate;

    private float rotateX;
    private float rotateY;
    private float sinkZ;
    private float touchNx;
    private float touchNy;
    private boolean pressed;

    SquareTiltTouchListener(View anchor, View.OnTouchListener delegate) {
        this.delegate = delegate;
        setupCamera(anchor);
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (view == null || event == null || !view.isEnabled()) {
            return false;
        }
        if (!SettingsPrefs.isButton3dEffectEnabled(view.getContext())) {
            if (delegate != null) {
                return delegate.onTouch(view, event);
            }
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pressed = true;
            float fromRotateX = rotateX;
            float fromRotateY = rotateY;
            float fromSinkZ = sinkZ;
            float fromNx = touchNx;
            float fromNy = touchNy;
            updatePoseByTouch(view, event.getX(), event.getY());
            playTilt(view, fromRotateX, fromRotateY, fromSinkZ, fromNx, fromNy,
                    rotateX, rotateY, sinkZ, touchNx, touchNy, DOWN_DURATION_MS, true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!pressed) {
                return false;
            }
            float fromRotateX = rotateX;
            float fromRotateY = rotateY;
            float fromSinkZ = sinkZ;
            float fromNx = touchNx;
            float fromNy = touchNy;
            updatePoseByTouch(view, event.getX(), event.getY());
            playTilt(view, fromRotateX, fromRotateY, fromSinkZ, fromNx, fromNy,
                    rotateX, rotateY, sinkZ, touchNx, touchNy, MOVE_DURATION_MS, true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!pressed) {
                return false;
            }
            pressed = false;
            float fromRotateX = rotateX;
            float fromRotateY = rotateY;
            float fromSinkZ = sinkZ;
            float fromNx = touchNx;
            float fromNy = touchNy;
            rotateX = 0f;
            rotateY = 0f;
            sinkZ = 0f;
            touchNx = 0f;
            touchNy = 0f;
            playTilt(view, fromRotateX, fromRotateY, fromSinkZ, fromNx, fromNy,
                    0f, 0f, 0f, 0f, 0f, UP_DURATION_MS, false);
        }
        if (delegate != null) {
            return delegate.onTouch(view, event);
        }
        return false;
    }

    private void updatePoseByTouch(View view, float x, float y) {
        float w = Math.max(1f, view.getWidth());
        float h = Math.max(1f, view.getHeight());
        float nx = clamp((x - w * 0.5f) / (w * 0.5f), -1f, 1f);
        float ny = clamp((y - h * 0.5f) / (h * 0.5f), -1f, 1f);
        touchNx = nx;
        touchNy = ny;
        rotateY = nx * MAX_ROTATE_DEG;
        rotateX = -ny * MAX_ROTATE_DEG;
        sinkZ = Math.max(Math.abs(nx), Math.abs(ny)) * PRESS_SINK_Z;
    }

    private void playTilt(View view,
                          float fromRotateX, float fromRotateY, float fromSinkZ, float fromNx, float fromNy,
                          float toRotateX, float toRotateY, float toSinkZ, float toNx, float toNy,
                          long durationMs, boolean downOrMove) {
        PressTiltAnimation anim = new PressTiltAnimation(view, camera, matrix,
                fromRotateX, fromRotateY, fromSinkZ, fromNx, fromNy,
                toRotateX, toRotateY, toSinkZ, toNx, toNy);
        anim.setDuration(Math.max(0L, durationMs));
        anim.setFillAfter(true);
        if (downOrMove) {
            anim.setInterpolator(new DecelerateInterpolator());
        } else {
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
        }
        view.clearAnimation();
        view.startAnimation(anim);
    }

    private void setupCamera(View anchor) {
        if (anchor == null) {
            return;
        }
        float density = anchor.getResources().getDisplayMetrics().density;
        try {
            Camera.class.getMethod("setLocation", float.class, float.class, float.class)
                    .invoke(camera, 0f, 0f, -CAMERA_DISTANCE_FACTOR * density);
        } catch (Throwable ignored) {
            // API 9/10 fallback.
        }
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

    private static final class PressTiltAnimation extends Animation {
        private final View target;
        private final Camera camera;
        private final Matrix reusableMatrix;
        private final float fromRotateX;
        private final float fromRotateY;
        private final float fromSinkZ;
        private final float fromNx;
        private final float fromNy;
        private final float toRotateX;
        private final float toRotateY;
        private final float toSinkZ;
        private final float toNx;
        private final float toNy;

        PressTiltAnimation(View target, Camera camera, Matrix reusableMatrix,
                           float fromRotateX, float fromRotateY, float fromSinkZ, float fromNx, float fromNy,
                           float toRotateX, float toRotateY, float toSinkZ, float toNx, float toNy) {
            this.target = target;
            this.camera = camera;
            this.reusableMatrix = reusableMatrix;
            this.fromRotateX = fromRotateX;
            this.fromRotateY = fromRotateY;
            this.fromSinkZ = fromSinkZ;
            this.fromNx = fromNx;
            this.fromNy = fromNy;
            this.toRotateX = toRotateX;
            this.toRotateY = toRotateY;
            this.toSinkZ = toSinkZ;
            this.toNx = toNx;
            this.toNy = toNy;
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation transformation) {
            if (target == null || camera == null || transformation == null) {
                return;
            }
            float t = clamp01(interpolatedTime);
            float curRotateX = lerp(fromRotateX, toRotateX, t);
            float curRotateY = lerp(fromRotateY, toRotateY, t);
            float curSinkZ = lerp(fromSinkZ, toSinkZ, t);
            float curNx = lerp(fromNx, toNx, t);
            float curNy = lerp(fromNy, toNy, t);

            float width = Math.max(1f, target.getWidth());
            float height = Math.max(1f, target.getHeight());
            float pivotX = Math.abs(curRotateY) < 0.01f ? width * 0.5f : (curNx < 0f ? width : 0f);
            float pivotY = Math.abs(curRotateX) < 0.01f ? height * 0.5f : (curNy < 0f ? height : 0f);

            reusableMatrix.reset();
            camera.save();
            camera.translate(0f, 0f, -curSinkZ);
            camera.rotateX(curRotateX);
            camera.rotateY(curRotateY);
            camera.getMatrix(reusableMatrix);
            camera.restore();

            reusableMatrix.preTranslate(-pivotX, -pivotY);
            reusableMatrix.postTranslate(pivotX, pivotY);
            transformation.getMatrix().set(reusableMatrix);
        }

        private float lerp(float from, float to, float t) {
            return from + (to - from) * t;
        }

        private float clamp01(float value) {
            if (value < 0f) {
                return 0f;
            }
            if (value > 1f) {
                return 1f;
            }
            return value;
        }
    }
}
