package com.im.oldchat.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Telegram 风格按钮流式布局：按钮按内容宽度排列，
 * 超出最大行宽自动换行，同一行按钮均分剩余空间填满整行。
 * Android 2.3+（API 9）兼容，不依赖 androidx。
 */
public class ButtonFlowLayout extends LinearLayout {
    private int maxRowWidth = 0;
    private int gapH = 0;
    private int gapV = 0;
    private int[][] rowChildren = new int[0][];

    public ButtonFlowLayout(Context context) {
        super(context);
        init(context);
    }

    public ButtonFlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        gapH = Math.round(6 * d);
        gapV = Math.round(6 * d);
    }

    /** 设置单行最大宽度（像素）。 */
    public void setMaxRowWidth(int px) {
        if (px != maxRowWidth) {
            maxRowWidth = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (maxRowWidth > 0 && w > maxRowWidth) {
            w = maxRowWidth;
        }
        int n = getChildCount();
        if (n == 0) {
            setMeasuredDimension(w, 0);
            rowChildren = new int[0][];
            return;
        }
        int[] naturalW = new int[n];
        int[] childH = new int[n];
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            measureChild(child,
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            naturalW[i] = child.getMeasuredWidth();
            childH[i] = child.getMeasuredHeight();
        }

        // 按自然宽度分组换行
        int[] rowStart = new int[n];
        int[] rowEnd = new int[n];
        int rows = 0;
        int start = 0;
        int cur = 0;
        for (int i = 0; i < n; i++) {
            if (i > start && cur + gapH + naturalW[i] > w) {
                rowStart[rows] = start;
                rowEnd[rows] = i - 1;
                rows++;
                start = i;
                cur = naturalW[i];
            } else {
                cur = cur == 0 ? naturalW[i] : cur + gapH + naturalW[i];
            }
        }
        rowStart[rows] = start;
        rowEnd[rows] = n - 1;
        rows++;

        // 保存分组，供 onLayout 使用
        rowChildren = new int[rows][];
        for (int rr = 0; rr < rows; rr++) {
            int cnt = rowEnd[rr] - rowStart[rr] + 1;
            int[] idxs = new int[cnt];
            for (int k = 0; k < cnt; k++) {
                idxs[k] = rowStart[rr] + k;
            }
            rowChildren[rr] = idxs;
        }

        // 行内均分剩余空间，二次 measure
        int totalH = 0;
        for (int rr = 0; rr < rows; rr++) {
            int[] idxs = rowChildren[rr];
            int sum = 0;
            int maxH = 0;
            for (int k = 0; k < idxs.length; k++) {
                sum += naturalW[idxs[k]];
            }
            int cnt = idxs.length;
            int gaps = gapH * (cnt - 1);
            int extra = w - sum - gaps;
            if (extra < 0) {
                extra = 0;
            }
            int addEach = cnt > 0 ? extra / cnt : 0;
            for (int k = 0; k < cnt; k++) {
                int idx = idxs[k];
                View child = getChildAt(idx);
                int cw = naturalW[idx] + addEach;
                child.measure(
                        MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(childH[idx], MeasureSpec.EXACTLY));
                if (child.getMeasuredHeight() > maxH) {
                    maxH = child.getMeasuredHeight();
                }
            }
            totalH += maxH;
            if (rr < rows - 1) {
                totalH += gapV;
            }
        }
        setMeasuredDimension(w, totalH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) {
            return;
        }
        int y = 0;
        for (int rr = 0; rr < rowChildren.length; rr++) {
            int[] idxs = rowChildren[rr];
            int x = 0;
            int maxH = 0;
            for (int k = 0; k < idxs.length; k++) {
                View child = getChildAt(idxs[k]);
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                child.layout(x, y, x + cw, y + ch);
                x += cw + gapH;
                if (ch > maxH) {
                    maxH = ch;
                }
            }
            y += maxH + gapV;
        }
    }
}
