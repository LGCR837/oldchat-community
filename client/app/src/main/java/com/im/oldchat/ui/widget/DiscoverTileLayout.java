package com.im.oldchat.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows Phone 风格发现页磁贴容器。
 * 可用宽度映射为 30 个 n 单位，并使用半单位坐标排版：
 * 小 4n、中 9n、大 19n；左右各留 n/2，磁贴间隔统一为 n。
 */
public class DiscoverTileLayout extends ViewGroup {
    public static final int SIZE_SMALL = 0;
    public static final int SIZE_MEDIUM = 1;
    public static final int SIZE_LARGE = 2;

    private static final int BASE_COLUMN_UNITS = 20;
    private static final int BAND_UNITS = 38;
    private static final int BAND_STEP_UNITS = 40;

    private final List<PlacedTile> placedTiles = new ArrayList<PlacedTile>();
    private boolean editing;

    public DiscoverTileLayout(Context context) {
        this(context, null);
    }

    public DiscoverTileLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DiscoverTileLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        // 容器只负责排版，不参与按压和点击；动效只属于真正的磁贴子项。
        setClickable(false);
        setWillNotDraw(true);
    }

    public void setEditing(boolean editing) {
        this.editing = editing;
    }

    public boolean isEditing() {
        return editing;
    }

    public void setTileSize(View child, int size) {
        if (child == null) {
            return;
        }
        ViewGroup.LayoutParams raw = child.getLayoutParams();
        LayoutParams params = raw instanceof LayoutParams
                ? (LayoutParams) raw : new LayoutParams(raw == null
                ? generateDefaultLayoutParams() : raw);
        params.tileSize = normalizeSize(size);
        child.setLayoutParams(params);
        requestLayout();
    }

    public int getTileSize(View child) {
        if (child != null && child.getLayoutParams() instanceof LayoutParams) {
            return ((LayoutParams) child.getLayoutParams()).tileSize;
        }
        return SIZE_MEDIUM;
    }

    private int calculateColumnCount(int contentWidth) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        // 目标单列中等磁贴宽度约为 115dp，保证各种尺寸屏幕上磁贴视觉适中且充满屏幕
        int targetColWidthPx = (int) (115f * density);
        if (targetColWidthPx <= 0) {
            return 3;
        }
        int cols = contentWidth / targetColWidthPx;
        if (cols < 3) {
            cols = 3;
        } else if (cols > 10) {
            cols = 10;
        }
        return cols;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int measuredWidth = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            measuredWidth = getSuggestedMinimumWidth();
        }
        int contentWidth = Math.max(1, measuredWidth - getPaddingLeft() - getPaddingRight());
        int columns = calculateColumnCount(contentWidth);
        int gridUnits = columns * BASE_COLUMN_UNITS;

        placeVisibleChildren(columns, gridUnits);
        int maxBottomUnit = 0;
        for (int i = 0; i < placedTiles.size(); i++) {
            PlacedTile tile = placedTiles.get(i);
            int left = unitToPx(tile.leftUnit, contentWidth, gridUnits);
            int top = unitToPx(tile.topUnit, contentWidth, gridUnits);
            int right = unitToPx(tile.rightUnit, contentWidth, gridUnits);
            int bottom = unitToPx(tile.bottomUnit, contentWidth, gridUnits);
            tile.leftPx = left;
            tile.topPx = top;
            tile.rightPx = right;
            tile.bottomPx = bottom;
            tile.child.measure(
                    MeasureSpec.makeMeasureSpec(Math.max(1, right - left), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1, bottom - top), MeasureSpec.EXACTLY));
            maxBottomUnit = Math.max(maxBottomUnit, tile.bottomUnit);
        }

        int desiredHeight = getPaddingTop() + getPaddingBottom()
                + unitToPx(maxBottomUnit, contentWidth, gridUnits);
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int offsetX = getPaddingLeft();
        int offsetY = getPaddingTop();
        for (int i = 0; i < placedTiles.size(); i++) {
            PlacedTile tile = placedTiles.get(i);
            tile.child.layout(offsetX + tile.leftPx, offsetY + tile.topPx,
                    offsetX + tile.rightPx, offsetY + tile.bottomPx);
        }
    }

    private void placeVisibleChildren(int columns, int gridUnits) {
        placedTiles.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            int size = getTileSize(child);
            int units = unitsForSize(size);
            PlacedTile placed = findPosition(child, size, units, columns, gridUnits);
            placedTiles.add(placed);
        }
    }

    private PlacedTile findPosition(View child, int size, int units, int columns, int gridUnits) {
        for (int band = 0; band < 1000; band++) {
            int bandTop = band * BAND_STEP_UNITS;
            int[] xs;
            int[] ys;
            if (size == SIZE_LARGE) {
                int largeCols = Math.max(1, columns - 1);
                xs = new int[largeCols];
                for (int c = 0; c < largeCols; c++) {
                    xs[c] = 1 + c * BASE_COLUMN_UNITS;
                }
                ys = new int[]{bandTop};
            } else if (size == SIZE_SMALL) {
                xs = new int[columns * 2];
                for (int c = 0; c < columns; c++) {
                    xs[c * 2] = 1 + c * BASE_COLUMN_UNITS;
                    xs[c * 2 + 1] = 11 + c * BASE_COLUMN_UNITS;
                }
                ys = new int[]{bandTop, bandTop + 10, bandTop + 20, bandTop + 30};
            } else {
                xs = new int[columns];
                for (int c = 0; c < columns; c++) {
                    xs[c] = 1 + c * BASE_COLUMN_UNITS;
                }
                ys = new int[]{bandTop, bandTop + 20};
            }
            for (int yIndex = 0; yIndex < ys.length; yIndex++) {
                for (int xIndex = 0; xIndex < xs.length; xIndex++) {
                    int x = xs[xIndex];
                    int y = ys[yIndex];
                    if (x + units > gridUnits || y + units > bandTop + BAND_UNITS) {
                        continue;
                    }
                    if (!intersectsPlaced(x, y, x + units, y + units)) {
                        return new PlacedTile(child, x, y, x + units, y + units);
                    }
                }
            }
        }
        return new PlacedTile(child, 0, 0, units, units);
    }

    private boolean intersectsPlaced(int left, int top, int right, int bottom) {
        for (int i = 0; i < placedTiles.size(); i++) {
            PlacedTile tile = placedTiles.get(i);
            if (left < tile.rightUnit && right > tile.leftUnit
                    && top < tile.bottomUnit && bottom > tile.topUnit) {
                return true;
            }
        }
        return false;
    }

    private int unitToPx(int unit, int contentWidth, int gridUnits) {
        return Math.round(unit * contentWidth / (float) gridUnits);
    }

    private int unitsForSize(int size) {
        if (size == SIZE_SMALL) {
            return 8;
        }
        if (size == SIZE_LARGE) {
            return 38;
        }
        return 18;
    }

    private int normalizeSize(int size) {
        if (size == SIZE_SMALL || size == SIZE_LARGE) {
            return size;
        }
        return SIZE_MEDIUM;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new LayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public int tileSize = SIZE_MEDIUM;

        public LayoutParams(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
            if (source instanceof LayoutParams) {
                tileSize = ((LayoutParams) source).tileSize;
            }
        }
    }

    private static final class PlacedTile {
        final View child;
        final int leftUnit;
        final int topUnit;
        final int rightUnit;
        final int bottomUnit;
        int leftPx;
        int topPx;
        int rightPx;
        int bottomPx;

        PlacedTile(View child, int leftUnit, int topUnit, int rightUnit, int bottomUnit) {
            this.child = child;
            this.leftUnit = leftUnit;
            this.topUnit = topUnit;
            this.rightUnit = rightUnit;
            this.bottomUnit = bottomUnit;
        }
    }
}
