package com.im.oldchat.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CoverColorExtractor {

    private static final int[] DEFAULT_COLORS = new int[]{
            Color.parseColor("#FF5B6E"),
            Color.parseColor("#4D8BFF"),
            Color.parseColor("#FFC857")
    };

    private CoverColorExtractor() {
    }

    public static int[] extractAccentColors(Bitmap bitmap, int count) {
        if (bitmap == null || bitmap.isRecycled() || count <= 0) {
            return copyDefaultColors(count);
        }
        Map<Integer, ColorBucket> buckets = new HashMap<Integer, ColorBucket>();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(1, width / 24);
        int stepY = Math.max(1, height / 24);
        float[] hsv = new float[3];

        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int color = bitmap.getPixel(x, y);
                int alpha = Color.alpha(color);
                if (alpha < 180) {
                    continue;
                }
                Color.colorToHSV(color, hsv);
                // Monet 需要保留封面中的中性主色；只过滤近透明、近黑和无信息纯白。
                if (hsv[2] < 0.08f || (hsv[2] > 0.97f && hsv[1] < 0.04f)) {
                    continue;
                }
                int bucketKey = quantizeColor(hsv);
                ColorBucket bucket = buckets.get(Integer.valueOf(bucketKey));
                if (bucket == null) {
                    bucket = new ColorBucket();
                    buckets.put(Integer.valueOf(bucketKey), bucket);
                }
                bucket.add(color, hsv[1], hsv[2]);
            }
        }

        if (buckets.isEmpty()) {
            return copyDefaultColors(count);
        }

        List<ColorBucket> sorted = new ArrayList<ColorBucket>(buckets.values());
        Collections.sort(sorted, new Comparator<ColorBucket>() {
            @Override
            public int compare(ColorBucket left, ColorBucket right) {
                return Float.compare(right.getScore(), left.getScore());
            }
        });

        List<Integer> selected = new ArrayList<Integer>();
        for (int i = 0; i < sorted.size() && selected.size() < count; i++) {
            int color = sorted.get(i).buildColor();
            if (isDistinctEnough(selected, color)) {
                selected.add(Integer.valueOf(color));
            }
        }

        if (selected.isEmpty()) {
            return copyDefaultColors(count);
        }
        int[] palette = new int[Math.max(1, count)];
        for (int i = 0; i < palette.length; i++) {
            if (i < selected.size()) {
                palette[i] = selected.get(i).intValue();
            } else {
                // 封面色不足时从主色生成同源色阶，不再混入无关的固定红/蓝/黄块。
                palette[i] = buildTonalVariant(selected.get(0).intValue(), i);
            }
        }
        return palette;
    }

    private static int[] copyDefaultColors(int count) {
        int size = Math.max(1, count);
        int[] colors = new int[size];
        for (int i = 0; i < size; i++) {
            colors[i] = DEFAULT_COLORS[i % DEFAULT_COLORS.length];
        }
        return colors;
    }

    private static int buildTonalVariant(int color, int index) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float shift = (index % 2 == 0 ? 24f : -24f) * Math.max(1, index / 2);
        hsv[0] = (hsv[0] + shift + 360f) % 360f;
        hsv[1] = Math.max(0.22f, Math.min(0.72f, hsv[1] * (index % 2 == 0 ? 0.82f : 1.08f)));
        hsv[2] = Math.max(0.42f, Math.min(0.88f, hsv[2] * (index % 2 == 0 ? 0.84f : 1.06f)));
        return Color.HSVToColor(hsv);
    }

    private static boolean isDistinctEnough(List<Integer> selected, int candidate) {
        float[] hsvA = new float[3];
        float[] hsvB = new float[3];
        Color.colorToHSV(candidate, hsvA);
        for (int i = 0; i < selected.size(); i++) {
            Color.colorToHSV(selected.get(i).intValue(), hsvB);
            float hueDiff = Math.abs(hsvA[0] - hsvB[0]);
            hueDiff = Math.min(hueDiff, 360f - hueDiff);
            float satDiff = Math.abs(hsvA[1] - hsvB[1]);
            float valDiff = Math.abs(hsvA[2] - hsvB[2]);
            if (hueDiff < 18f && satDiff < 0.22f && valDiff < 0.24f) {
                return false;
            }
        }
        return true;
    }

    private static int quantizeColor(float[] hsv) {
        int hueBucket = Math.max(0, Math.min(23, (int) (hsv[0] / 15f)));
        int satBucket = Math.max(0, Math.min(4, (int) (hsv[1] * 4f)));
        int valBucket = Math.max(0, Math.min(4, (int) (hsv[2] * 4f)));
        return (hueBucket * 100) + (satBucket * 10) + valBucket;
    }

    private static class ColorBucket {
        private int mCount;
        private float mRedSum;
        private float mGreenSum;
        private float mBlueSum;
        private float mSaturationSum;
        private float mValueSum;

        void add(int color, float saturation, float value) {
            mCount++;
            mRedSum += Color.red(color);
            mGreenSum += Color.green(color);
            mBlueSum += Color.blue(color);
            mSaturationSum += saturation;
            mValueSum += value;
        }

        float getScore() {
            if (mCount <= 0) {
                return 0f;
            }
            float sat = mSaturationSum / mCount;
            float val = mValueSum / mCount;
            return (mCount * 0.7f) + (sat * 18f) + (val * 12f);
        }

        int buildColor() {
            if (mCount <= 0) {
                return DEFAULT_COLORS[0];
            }
            int red = clampColor(Math.round(mRedSum / mCount));
            int green = clampColor(Math.round(mGreenSum / mCount));
            int blue = clampColor(Math.round(mBlueSum / mCount));
            return Color.rgb(red, green, blue);
        }

        private int clampColor(int value) {
            if (value < 0) {
                return 0;
            }
            if (value > 255) {
                return 255;
            }
            return value;
        }
    }
}
