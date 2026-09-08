package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import com.im.oldchat.util.SettingsPrefs;

/**
 * Telegram X 风格消息时间尾部 TextView（兼容 API 9 / Java 7）。
 *
 * 原理参考 Telegram X TGMessageText:
 * - 默认模式下（末行留白充足）：时间与状态勾内嵌在最后一行末尾右侧（同一行），不占用额外纵向高度。
 * - 换行模式下（或末行留白不足）：自动在正文下方展开一行（footerOnNewLine），时间与状态勾右对齐。
 * - 始终右对齐，字号、间距与透明度完全对齐 Telegram X。
 *
 * 旧版本兼容要点：
 * - 不覆写 setMaxWidth(int)（TextView.setMaxWidth 为 API 11+ 方法），最大宽度由 XML android:maxWidth 控制。
 * - 状态图标通过 AppCompatResources 加载失败时回退 getResources().getDrawable。
 */
public class BubbleTimeTextView extends TextView {

    private String timeText = "";
    private Drawable statusIcon;
    private String countText = "";
    private int strip;                 // 右侧时间带宽度（px，含 gap）
    private int basePaddingRight;
    private int gap;                   // 时间与最后一行文字之间的间距（px）
    private int iconGap;               // 时间与状态图标之间的间距（px）
    private int countGap;              // 状态图标与已读人数之间的间距（px）
    private int footerLineHeight;
    private boolean footerOnNewLine;
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float density;
    private int iconSize;
    private int customTimeColor = 0;

    public BubbleTimeTextView(Context context) {
        super(context);
        init(context);
    }

    public BubbleTimeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BubbleTimeTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        basePaddingRight = getPaddingRight();
        iconSize = (int) (14f * density + 0.5f);
        timePaint.setColor(0xFF8A949E);
    }

    public void setTimeColor(int color) {
        customTimeColor = color;
        if (color != 0) {
            timePaint.setColor(color);
            if (statusIcon != null) {
                statusIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    /** 适配器绑定调用：时间文本 + 状态图标资源（0 不画）+ 群已读人数（0 不画）。 */
    public void setFooter(String time, int statusRes, int statusCount) {
        setFooter(time, statusRes, statusCount, 0);
    }

    public void setFooter(String time, int statusRes, int statusCount, int timeColor) {
        timeText = time == null ? "" : time;
        Drawable icon = null;
        if (statusRes != 0) {
            try {
                icon = android.support.v7.content.res.AppCompatResources.getDrawable(getContext(), statusRes);
            } catch (Throwable t) {
                try {
                    icon = getResources().getDrawable(statusRes);
                } catch (Throwable ignored) {}
            }
        }
        statusIcon = icon;
        countText = statusCount > 0 ? String.valueOf(statusCount) : "";
        customTimeColor = timeColor;

        // Telegram X 时间字号为 ~11sp（正文约 16sp，比率约 0.68）
        timePaint.setTextSize(Math.max(10f * density, getTextSize() * 0.68f));
        if (timeColor != 0) {
            timePaint.setColor(timeColor);
        } else {
            timePaint.setColor(0xFF8A949E);
        }

        int timeW = timeText.length() == 0 ? 0 : (int) Math.ceil(timePaint.measureText(timeText));
        int iconW = statusIcon != null ? iconSize : 0;
        gap = (timeText.length() > 0 || iconW > 0) ? (int) (5f * density + 0.5f) : 0;
        int countW = countText.length() == 0 ? 0 : (int) Math.ceil(timePaint.measureText(countText));
        iconGap = statusIcon != null && timeText.length() > 0 ? (int) (3.5f * density + 0.5f) : 0;
        countGap = countText.length() > 0 && (timeText.length() > 0 || statusIcon != null)
                ? (int) (2f * density + 0.5f) : 0;

        strip = gap + timeW + iconGap + iconW + countGap + countW;
        if (statusIcon != null) {
            if (timeColor != 0) {
                statusIcon.setColorFilter(timeColor, PorterDuff.Mode.SRC_IN);
            }
            statusIcon.setBounds(0, 0, iconSize, iconSize);
        }
        setPadding(getPaddingLeft(), getPaddingTop(), basePaddingRight, getPaddingBottom());
        requestLayout();
    }

    public void clearFooter() {
        setFooter("", 0, 0, 0);
    }

    /** 视图最大可用宽度：统一读取 TextView 的 maxWidth（API 16+ getMaxWidth；低版本回退 mMaxWidth 反射）。 */
    private int resolveMaxWidthLimit(int specSize) {
        int limit = 0;
        if (Build.VERSION.SDK_INT >= 16) {
            try {
                limit = getMaxWidth();
            } catch (Throwable ignored) {}
        }
        if (limit <= 0 || limit >= Integer.MAX_VALUE / 2) {
            limit = reflectMaxWidth();
        }
        if (specSize > 0 && (limit <= 0 || specSize < limit)) {
            limit = specSize;
        }
        return limit;
    }

    /** API 15 及以下无 getMaxWidth()，反射读取 mMaxWidth 字段；失败返回 0。 */
    private int reflectMaxWidth() {
        try {
            java.lang.reflect.Field f = TextView.class.getDeclaredField("mMaxWidth");
            f.setAccessible(true);
            Object v = f.get(this);
            if (v instanceof Integer) {
                int w = ((Integer) v).intValue();
                return w > 0 && w < Integer.MAX_VALUE / 2 ? w : 0;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        footerOnNewLine = false;
        footerLineHeight = 0;
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (strip <= 0) {
            return;
        }
        Layout layout = getLayout();
        if (layout == null || layout.getLineCount() == 0) {
            return;
        }

        int lineCount = layout.getLineCount();
        int last = lineCount - 1;
        float lastLineWidth = layout.getLineWidth(last);
        float maxLineWidth = 0;
        for (int i = 0; i < lineCount; i++) {
            maxLineWidth = Math.max(maxLineWidth, layout.getLineWidth(i));
        }

        int horizontalPadding = getPaddingLeft() + getPaddingRight();
        int specMode = View.MeasureSpec.getMode(widthMeasureSpec);
        int specSize = View.MeasureSpec.getSize(widthMeasureSpec);
        int maxWidth = resolveMaxWidthLimit(specMode == View.MeasureSpec.UNSPECIFIED ? 0 : specSize);
        if (maxWidth <= 0) {
            maxWidth = getMeasuredWidth();
        }
        int maxAvailableContentWidth = Math.max(0, maxWidth - horizontalPadding);

        int footerWidth = strip - gap;

        // 判断是否强制换行显示时间，或者末行留白不足以放下时间徽章
        boolean forceNewLine = SettingsPrefs.isBubbleTimeNewLineEnabled(getContext());
        boolean fitsOnLastLine = !forceNewLine && (lastLineWidth + gap + footerWidth <= maxAvailableContentWidth);

        Paint.FontMetricsInt metrics = timePaint.getFontMetricsInt();
        int topGap = (int) (2f * density + 0.5f);
        footerLineHeight = topGap + Math.max(iconSize, metrics.descent - metrics.ascent);

        if (fitsOnLastLine) {
            footerOnNewLine = false;
            int neededContentWidth = (int) Math.ceil(Math.max(maxLineWidth, lastLineWidth + gap + footerWidth));
            int desiredWidth = Math.min(maxWidth, horizontalPadding + neededContentWidth);
            if (desiredWidth > getMeasuredWidth()) {
                int exactWidth = View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY);
                super.onMeasure(exactWidth, heightMeasureSpec);
            }
        } else {
            footerOnNewLine = true;
            int desiredWidth = Math.min(maxWidth, Math.max(getMeasuredWidth(), horizontalPadding + footerWidth));
            if (desiredWidth > getMeasuredWidth()) {
                int exactWidth = View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY);
                super.onMeasure(exactWidth, heightMeasureSpec);
            }
            setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + footerLineHeight);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (strip <= 0 || (timeText.length() == 0 && statusIcon == null)) {
            return;
        }
        Layout layout = getLayout();
        if (layout == null || layout.getLineCount() == 0) {
            return;
        }
        int last = layout.getLineCount() - 1;
        Paint.FontMetricsInt m2 = timePaint.getFontMetricsInt();
        float h2 = m2.descent - m2.ascent;
        float x;
        float y;
        int footerWidth = strip - gap;

        if (footerOnNewLine) {
            // 换行模式：底部独立行，右对齐
            x = getWidth() - getPaddingRight() - footerWidth;
            int topGap = (int) (2f * density + 0.5f);
            int rowTop = getPaddingTop() + layout.getHeight() + topGap;
            y = rowTop + (footerLineHeight - topGap - h2) / 2f - m2.ascent;
        } else {
            // 同行模式：贴末行文字末尾（Telegram X 内嵌式）；气泡宽度由 onMeasure 按需扩宽
            x = getPaddingLeft() + layout.getLineRight(last) + gap;
            y = getPaddingTop() + layout.getLineBaseline(last);
        }

        if (timeText.length() > 0) {
            canvas.drawText(timeText, x, y, timePaint);
            x += timePaint.measureText(timeText);
        }
        if (statusIcon != null) {
            x += iconGap;
            int iconTop = (int) (y + m2.ascent + (h2 - iconSize) / 2f + 0.5f);
            statusIcon.setBounds((int) x, iconTop, (int) x + iconSize, iconTop + iconSize);
            statusIcon.draw(canvas);
            x += iconSize;
        }
        if (countText.length() > 0) {
            x += countGap;
            canvas.drawText(countText, x, y, timePaint);
        }
    }
}
