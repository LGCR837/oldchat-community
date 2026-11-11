package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.im.oldchat.ui.ImagePreviewActivity;
import com.im.oldchat.ui.MomentGalleryActivity;
import com.im.oldchat.util.ImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态页九宫格图片排版（微信朋友圈风格）：
 *  - 1 张：单图自适应（横图宽、竖图高，最大限宽）
 *  - 2-9 张：三列网格等距排列
 *  - 超过 9 张显示 "+N" 角标
 *  - 点击第 N 张从第 N 张打开画廊
 */
public class MomentImageView extends FrameLayout {
    private static final int MAX_COLUMNS = 3;
    private static final int MAX_VISIBLE = 9;
    private static final int ROUND_DP = 8;
    private static final int GAP_DP = 3;

    private final float density;
    private final List<String> urls = new ArrayList<String>();

    public MomentImageView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
    }

    public MomentImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
    }

    /** 设置图片列表并重建排版。 */
    public void setImageUrls(List<String> imageUrls) {
        urls.clear();
        if (imageUrls != null) {
            for (int i = 0; i < imageUrls.size(); i++) {
                String u = imageUrls.get(i);
                if (u != null && u.length() > 0) {
                    urls.add(u);
                }
            }
        }
        rebuild();
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private void rebuild() {
        removeAllViews();
        if (urls.isEmpty()) {
            setVisibility(View.GONE);
            return;
        }
        setVisibility(View.VISIBLE);
        if (urls.size() == 1) {
            buildSingle();
        } else {
            buildGrid();
        }
    }

    /** 单图：自适应宽高比（横图宽、竖图高），最大限宽。 */
    private void buildSingle() {
        final String url = urls.get(0);
        final com.im.oldchat.ui.widget.RoundedImageView rounded =
                new com.im.oldchat.ui.widget.RoundedImageView(getContext());
        rounded.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded.setCornerRadiusPx(dp(ROUND_DP));
        rounded.setBackgroundColor(Color.parseColor("#1A1F26"));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(200));
        addView(rounded, lp);
        // 加载完成后根据图片实际宽高比调整高度（横图宽、竖图高）
        ImageLoader.load(rounded, url, new ImageLoader.ImageLoadResultListener() {
            @Override
            public void onResult(String loadedUrl, boolean success) {
                if (!success || rounded.getDrawable() == null) {
                    return;
                }
                android.graphics.drawable.BitmapDrawable bd =
                        rounded.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                                ? (android.graphics.drawable.BitmapDrawable) rounded.getDrawable() : null;
                if (bd == null || bd.getBitmap() == null) {
                    return;
                }
                int bw = bd.getBitmap().getWidth();
                int bh = bd.getBitmap().getHeight();
                if (bw <= 0 || bh <= 0) {
                    return;
                }
                int targetHeight;
                float ratio = bw / (float) bh;
                if (ratio > 1.5f) {
                    targetHeight = dp(170);
                } else if (ratio < 0.7f) {
                    targetHeight = dp(260);
                } else {
                    targetHeight = dp(200);
                }
                LayoutParams cur = (LayoutParams) rounded.getLayoutParams();
                if (cur.height != targetHeight) {
                    cur.height = targetHeight;
                    rounded.setLayoutParams(cur);
                }
            }

            @Override
            public void onComplete(String loadedUrl) {
            }
        });
        rounded.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ImagePreviewActivity.start(getContext(), url);
            }
        });
    }

    /** 多图：三列网格。 */
    private void buildGrid() {
        int count = Math.min(urls.size(), MAX_VISIBLE);
        int cols = MAX_COLUMNS;
        int rows = (count + cols - 1) / cols;
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(96) - (cols - 1) * dp(GAP_DP)) / cols;
        if (cell > dp(120)) {
            cell = dp(120);
        }

        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        LayoutParams gridLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        gridLp.gravity = Gravity.START;
        addView(grid, gridLp);

        int index = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r > 0) {
                rowLp.topMargin = dp(GAP_DP);
            }
            grid.addView(row, rowLp);
            for (int c = 0; c < cols && index < count; c++) {
                final int position = index;
                final String url = urls.get(index);
                com.im.oldchat.ui.widget.RoundedImageView rounded =
                        new com.im.oldchat.ui.widget.RoundedImageView(getContext());
                rounded.setScaleType(ImageView.ScaleType.CENTER_CROP);
                rounded.setCornerRadiusPx(dp(ROUND_DP));
                rounded.setBackgroundColor(Color.parseColor("#1A1F26"));
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(cell, cell);
                if (c > 0) {
                    cellLp.leftMargin = dp(GAP_DP);
                }
                row.addView(rounded, cellLp);
                ImageLoader.load(rounded, url);
                rounded.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (urls.size() > 1) {
                            MomentGalleryActivity.start(getContext(), new ArrayList<String>(urls), position);
                        } else {
                            ImagePreviewActivity.start(getContext(), url);
                        }
                    }
                });
                index++;
            }
        }

        // 超过 9 张的 "+N" 角标
        if (urls.size() > MAX_VISIBLE) {
            int extra = urls.size() - MAX_VISIBLE;
            TextView badge = new TextView(getContext());
            badge.setText("+" + extra);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(12f);
            badge.setGravity(Gravity.CENTER);
            badge.setBackgroundColor(0x99000000);
            LayoutParams badgeLp = new LayoutParams(dp(44), dp(44));
            badgeLp.gravity = Gravity.END | Gravity.BOTTOM;
            badgeLp.rightMargin = dp(8);
            badgeLp.bottomMargin = dp(8);
            addView(badge, badgeLp);
        }
    }
}
