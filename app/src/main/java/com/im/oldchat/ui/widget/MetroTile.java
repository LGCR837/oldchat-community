package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MetroTile extends FrameLayout {
    private static final float MAX_ROTATE_DEG = 7.5f;
    private static final float RESET_SPRING = 0.24f;
    private static final long FRAME_DELAY_MS = 16L;

    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final ViewBadge badgeView;
    private final Runnable springRunnable = new Runnable() {
        @Override
        public void run() {
            boolean keep = stepSpring();
            if (keep) {
                postDelayed(this, FRAME_DELAY_MS);
            }
        }
    };

    private float rotateX;
    private float rotateY;
    private boolean springing;
    private long lastTouchDownAt;

    public MetroTile(Context context) {
        this(context, null);
    }

    public MetroTile(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackgroundColor(Color.parseColor("#00ADEF"));

        iconView = new ImageView(context);
        LayoutParams iconLp = new LayoutParams(dp(30), dp(30));
        iconLp.gravity = android.view.Gravity.CENTER;
        iconView.setLayoutParams(iconLp);
        addView(iconView);

        badgeView = new ViewBadge(context);
        LayoutParams badgeLp = new LayoutParams(dp(8), dp(8));
        badgeLp.gravity = android.view.Gravity.RIGHT | android.view.Gravity.TOP;
        badgeLp.topMargin = dp(2);
        badgeLp.rightMargin = dp(2);
        badgeView.setLayoutParams(badgeLp);
        badgeView.setVisibility(GONE);
        addView(badgeView);

        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LayoutParams textLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        textLp.gravity = android.view.Gravity.LEFT | android.view.Gravity.BOTTOM;
        textBox.setLayoutParams(textLp);
        addView(textBox);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(1);
        textBox.addView(titleView);

        subtitleView = new TextView(context);
        subtitleView.setTextColor(Color.parseColor("#D9FFFFFF"));
        subtitleView.setTextSize(12f);
        subtitleView.setMaxLines(2);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setVisibility(GONE);
        textBox.addView(subtitleView);
    }

    public void setTileColor(int color) {
        setBackgroundColor(color);
    }

    public void setIconResource(int resId) {
        iconView.setImageResource(resId);
        iconView.setColorFilter(Color.WHITE);
    }

    public void setTitle(CharSequence text) {
        titleView.setText(text == null ? "" : text);
    }

    public void setSubtitle(CharSequence text) {
        if (text == null || text.length() == 0) {
            subtitleView.setText("");
            subtitleView.setVisibility(GONE);
            return;
        }
        subtitleView.setText(text);
        subtitleView.setVisibility(VISIBLE);
    }

    public void setBadgeVisible(boolean visible) {
        badgeView.setVisibility(visible ? VISIBLE : GONE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchDownAt = SystemClock.uptimeMillis();
            stopSpring();
            updateTiltByTouch(event.getX(), event.getY());
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            updateTiltByTouch(event.getX(), event.getY());
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean perform = isInside(event.getX(), event.getY());
            startSpring();
            if (perform) {
                long dt = SystemClock.uptimeMillis() - lastTouchDownAt;
                if (dt < 800L) {
                    performClick();
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            startSpring();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
        int save = canvas.save();
        applyTileMatrix();
        canvas.concat(matrix);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void applyTileMatrix() {
        float px = getWidth() * 0.5f;
        float py = getHeight() * 0.5f;
        matrix.reset();
        camera.save();
        camera.rotateX(rotateX);
        camera.rotateY(rotateY);
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
        rotateY = nx * MAX_ROTATE_DEG;
        rotateX = -ny * MAX_ROTATE_DEG;
    }

    private boolean isInside(float x, float y) {
        return x >= 0f && y >= 0f && x <= getWidth() && y <= getHeight();
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
        if (Math.abs(rotateX) < 0.15f && Math.abs(rotateY) < 0.15f) {
            rotateX = 0f;
            rotateY = 0f;
            springing = false;
            invalidate();
            return false;
        }
        invalidate();
        return true;
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

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private static class ViewBadge extends android.view.View {
        public ViewBadge(Context context) {
            super(context);
            setBackgroundColor(Color.parseColor("#E81123"));
        }
    }
}
