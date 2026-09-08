package com.im.oldchat.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.support.v7.widget.AppCompatImageView;
import android.util.AttributeSet;
import com.im.oldchat.R;

/**
 * 圆角/圆形图片，可选“气泡尾巴”：靠近头像一侧外凸一个三角形尖角。
 * tailSide: 0=无尾巴；1=右上角(我的消息，头像在右)；2=左上角(对方消息，头像在左)。
 */
public class RoundedImageView extends AppCompatImageView {
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();
    private float radiusPx;
    private boolean circleCrop;
    private int tailSide;

    public RoundedImageView(Context context) {
        super(context);
        init(context, null);
    }

    public RoundedImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public RoundedImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        float defaultDp = 6f;
        float d = context.getResources().getDisplayMetrics().density;
        radiusPx = defaultDp * d;

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RoundedImageView);
            try {
                radiusPx = a.getDimension(R.styleable.RoundedImageView_cornerRadius, radiusPx);
                tailSide = a.getInt(R.styleable.RoundedImageView_tailSide, 0);
            } finally {
                a.recycle();
            }
        }

        if (Build.VERSION.SDK_INT >= 11 && Build.VERSION.SDK_INT < 18) {
            // clipPath is unreliable with HW acceleration on older APIs.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rect.set(0, 0, w, h);
        updatePath();
    }

    public void setCornerRadiusPx(float radiusPx) {
        if (radiusPx < 0) radiusPx = 0;
        this.radiusPx = radiusPx;
        updatePath();
        invalidate();
    }

    /** 头像专用圆形遮罩，由 ImageLoader 的头像加载入口自动启用。 */
    public void setCircleCrop(boolean circleCrop) {
        if (this.circleCrop == circleCrop) {
            return;
        }
        this.circleCrop = circleCrop;
        updatePath();
        invalidate();
    }

    /** 设置头像侧直角：1=右上角直角(我的消息，头像在右)，2=左上角直角(对方消息，头像在左)，0=全圆角。 */
    public void setTailSide(int tailSide) {
        if (this.tailSide == tailSide) {
            return;
        }
        this.tailSide = tailSide;
        updatePath();
        invalidate();
    }

    private void updatePath() {
        clipPath.reset();
        if (circleCrop) {
            float radius = Math.min(rect.width(), rect.height()) / 2f;
            clipPath.addCircle(rect.centerX(), rect.centerY(), radius, Path.Direction.CW);
            return;
        }
        // 与文字气泡一致：靠近头像侧直角(0dp)，其余三角圆润。
        float tl = radiusPx, tr = radiusPx, br = radiusPx, bl = radiusPx;
        if (tailSide == 1) {
            tr = 0f; // 右上角直角
        } else if (tailSide == 2) {
            tl = 0f; // 左上角直角
        }
        buildRoundRectPath(clipPath, rect, tl, tr, br, bl);
    }

    /** 兼容 API 9：手动构造四角圆角可不同的矩形路径（quadTo 近似圆角）。 */
    private static void buildRoundRectPath(Path path, RectF r,
                                           float tl, float tr, float br, float bl) {
        float left = r.left, top = r.top, right = r.right, bottom = r.bottom;
        path.moveTo(left + tl, top);
        if (tr > 0f) {
            path.lineTo(right - tr, top);
            path.quadTo(right, top, right, top + tr);
        } else {
            path.lineTo(right, top);
        }
        if (br > 0f) {
            path.lineTo(right, bottom - br);
            path.quadTo(right, bottom, right - br, bottom);
        } else {
            path.lineTo(right, bottom);
        }
        if (bl > 0f) {
            path.lineTo(left + bl, bottom);
            path.quadTo(left, bottom, left, bottom - bl);
        } else {
            path.lineTo(left, bottom);
        }
        if (tl > 0f) {
            path.lineTo(left, top + tl);
            path.quadTo(left, top, left + tl, top);
        } else {
            path.lineTo(left, top);
        }
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!circleCrop && radiusPx <= 0f && tailSide == 0) {
            super.onDraw(canvas);
            return;
        }
        int save = canvas.save();
        canvas.clipPath(clipPath);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }
}
