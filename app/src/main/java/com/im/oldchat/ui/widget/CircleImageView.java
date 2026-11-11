package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.support.v7.widget.AppCompatImageView;

/**
 * 圆形头像控件。
 * 兼容 Android 2.3+ (minSdk 9)，具备绘制与空图防崩溃保护。
 */
public class CircleImageView extends AppCompatImageView {

    private final Paint mPaint;
    private final Matrix mMatrix;

    public CircleImageView(Context context) {
        super(context);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mMatrix = new Matrix();
    }

    public CircleImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mMatrix = new Matrix();
    }

    public CircleImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mMatrix = new Matrix();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            super.onDraw(canvas);
            return;
        }

        Bitmap bitmap = null;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            try {
                int w = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : getWidth();
                int h = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : getHeight();
                if (w > 0 && h > 0) {
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bitmap);
                    drawable.setBounds(0, 0, w, h);
                    drawable.draw(c);
                }
            } catch (Throwable ignored) {
            }
        }

        if (bitmap == null || bitmap.isRecycled()) {
            super.onDraw(canvas);
            return;
        }

        int width = getWidth();
        int height = getHeight();
        int size = Math.min(width, height);
        if (size <= 0) {
            return;
        }
        float radius = size / 2f;
        float centerX = width / 2f;
        float centerY = height / 2f;

        try {
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(
                    (float) size / bitmap.getWidth(),
                    (float) size / bitmap.getHeight()
            );
            mMatrix.setScale(scale, scale);
            float dx = (size - bitmap.getWidth() * scale) / 2f;
            float dy = (size - bitmap.getHeight() * scale) / 2f;
            mMatrix.postTranslate(dx, dy);
            shader.setLocalMatrix(mMatrix);
            mPaint.setShader(shader);

            canvas.drawCircle(centerX, centerY, radius, mPaint);
        } catch (Throwable ignored) {
            super.onDraw(canvas);
        }
    }
}
