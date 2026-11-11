package com.im.oldchat.ui;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 兼容 API 9 的轻量 Monet 调色板：Lab K-means + 感知去重 + 对比度约束 + 随机纯色生成。 */
public final class DiscoverMonetPalette {
    private static final int MAX_SAMPLES = 7000;
    private static final int CLUSTERS = 8;

    private DiscoverMonetPalette() { }

    public static int[] extract(Bitmap bitmap, int count) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0 || count <= 0) {
            return null;
        }
        List<Sample> samples = sample(bitmap);
        if (samples.isEmpty()) return fallback(count);
        List<Cluster> clusters = cluster(samples, Math.min(CLUSTERS, samples.size()));
        scoreAndSort(clusters, samples.size());
        List<Cluster> seeds = distinctSeeds(clusters);
        if (seeds.isEmpty()) return fallback(count);
        return buildPalette(seeds, count);
    }

    private static List<Sample> sample(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(1, (int) Math.sqrt((width * (double) height) / MAX_SAMPLES));
        List<Sample> out = new ArrayList<Sample>();
        for (int y = step / 2; y < height; y += step) {
            for (int x = step / 2; x < width; x += step) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) < 160) continue;
                double r = Color.red(color) / 255.0;
                double g = Color.green(color) / 255.0;
                double b = Color.blue(color) / 255.0;
                double max = Math.max(r, Math.max(g, b));
                double min = Math.min(r, Math.min(g, b));
                // 仅排除几乎纯黑/纯白；低彩度照片仍可生成中性色系。
                if (max < 0.025 || min > 0.975) continue;
                double[] lab = rgbToLab(r, g, b);
                double chroma = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
                double nx = (x + 0.5) / width - 0.5;
                double ny = (y + 0.5) / height - 0.5;
                double centerWeight = 1.25 - Math.min(0.55, Math.sqrt(nx * nx + ny * ny));
                out.add(new Sample(lab[0], lab[1], lab[2], chroma, centerWeight));
            }
        }
        return out;
    }

    private static List<Cluster> cluster(List<Sample> samples, int k) {
        List<Cluster> clusters = new ArrayList<Cluster>();
        // 确定性 k-means++：先取高彩度适中明度样本，后续取距现有中心最远者。
        Sample first = samples.get(0);
        double firstScore = -1;
        for (int i = 0; i < samples.size(); i++) {
            Sample one = samples.get(i);
            double score = (one.chroma + 8) * toneFitness(one.l) * one.weight;
            if (score > firstScore) { first = one; firstScore = score; }
        }
        clusters.add(new Cluster(first.l, first.a, first.b));
        while (clusters.size() < k) {
            Sample farthest = null;
            double farthestScore = -1;
            for (int i = 0; i < samples.size(); i++) {
                Sample one = samples.get(i);
                double minDistance = Double.MAX_VALUE;
                for (int j = 0; j < clusters.size(); j++) {
                    minDistance = Math.min(minDistance, distanceSq(one, clusters.get(j)));
                }
                double score = minDistance * (0.35 + Math.min(1.0, one.chroma / 55.0));
                if (score > farthestScore) { farthest = one; farthestScore = score; }
            }
            if (farthest == null) break;
            clusters.add(new Cluster(farthest.l, farthest.a, farthest.b));
        }

        for (int iteration = 0; iteration < 12; iteration++) {
            for (int i = 0; i < clusters.size(); i++) clusters.get(i).reset();
            for (int i = 0; i < samples.size(); i++) {
                Sample one = samples.get(i);
                Cluster best = clusters.get(0);
                double bestDistance = distanceSq(one, best);
                for (int j = 1; j < clusters.size(); j++) {
                    double distance = distanceSq(one, clusters.get(j));
                    if (distance < bestDistance) { best = clusters.get(j); bestDistance = distance; }
                }
                best.add(one);
            }
            for (int i = clusters.size() - 1; i >= 0; i--) {
                if (!clusters.get(i).finish()) clusters.remove(i);
            }
            if (clusters.isEmpty()) break;
        }
        return clusters;
    }

    private static void scoreAndSort(List<Cluster> clusters, int total) {
        for (int i = 0; i < clusters.size(); i++) {
            Cluster one = clusters.get(i);
            double population = one.count / (double) Math.max(1, total);
            double chroma = Math.sqrt(one.a * one.a + one.b * one.b);
            double chromaFactor = 0.55 + Math.min(1.25, chroma / 40.0);
            one.score = population * 100.0 * chromaFactor * toneFitness(one.l)
                    * (0.85 + one.averageWeight * 0.15);
        }
        Collections.sort(clusters, new Comparator<Cluster>() {
            @Override public int compare(Cluster left, Cluster right) {
                return left.score > right.score ? -1 : (left.score == right.score ? 0 : 1);
            }
        });
    }

    private static List<Cluster> distinctSeeds(List<Cluster> clusters) {
        List<Cluster> seeds = new ArrayList<Cluster>();
        for (int i = 0; i < clusters.size() && seeds.size() < 4; i++) {
            Cluster candidate = clusters.get(i);
            boolean distinct = true;
            for (int j = 0; j < seeds.size(); j++) {
                if (deltaE(candidate, seeds.get(j)) < 18.0) { distinct = false; break; }
            }
            if (distinct) seeds.add(candidate);
        }
        if (seeds.isEmpty() && !clusters.isEmpty()) seeds.add(clusters.get(0));
        return seeds;
    }

    private static int[] buildPalette(List<Cluster> seeds, int count) {
        Cluster primary = seeds.get(0);
        Cluster secondary = seeds.size() > 1 ? seeds.get(1) : primary;
        Cluster tertiary = seeds.size() > 2 ? seeds.get(2) : secondary;
        int[] tones = new int[]{45, 38, 52, 42, 34, 48, 40, 55, 36, 46, 32, 50};
        int[] palette = new int[count];
        for (int i = 0; i < count; i++) {
            // 主色占约 2/3，副色只在少数位置强调，避免彩虹化。
            Cluster seed = (i == 3 || i == 8) ? secondary : (i == 6 ? tertiary : primary);
            double chromaScale = i % 4 == 0 ? 1.00 : (i % 4 == 1 ? 0.82 : 0.68);
            double targetL = tones[i % tones.length];
            double sourceChroma = Math.sqrt(seed.a * seed.a + seed.b * seed.b);
            double targetChroma = Math.max(18.0, Math.min(62.0, sourceChroma * chromaScale));
            double hue = Math.atan2(seed.b, seed.a);
            double a = Math.cos(hue) * targetChroma;
            double b = Math.sin(hue) * targetChroma;
            int color = labToColor(targetL, a, b);
            color = ensureWhiteContrast(color, 4.5);
            palette[i] = color;
        }
        return palette;
    }

    private static int ensureWhiteContrast(int color, double minimum) {
        double[] lab = colorToLab(color);
        int result = color;
        while (contrastWithWhite(result) < minimum && lab[0] > 24) {
            lab[0] -= 2;
            result = labToColor(lab[0], lab[1], lab[2]);
        }
        return result;
    }

    private static double contrastWithWhite(int color) {
        double luminance = relativeLuminance(color);
        return 1.05 / (luminance + 0.05);
    }

    private static double relativeLuminance(int color) {
        double r = linear(Color.red(color) / 255.0);
        double g = linear(Color.green(color) / 255.0);
        double b = linear(Color.blue(color) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double toneFitness(double l) {
        double distance = Math.abs(l - 58.0) / 58.0;
        return Math.max(0.35, 1.0 - distance * 0.72);
    }

    private static double distanceSq(Sample sample, Cluster cluster) {
        double dl = sample.l - cluster.l, da = sample.a - cluster.a, db = sample.b - cluster.b;
        return dl * dl + da * da + db * db;
    }

    private static double deltaE(Cluster one, Cluster two) {
        double dl = one.l - two.l, da = one.a - two.a, db = one.b - two.b;
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static double[] colorToLab(int color) {
        return rgbToLab(Color.red(color) / 255.0, Color.green(color) / 255.0,
                Color.blue(color) / 255.0);
    }

    private static double[] rgbToLab(double r, double g, double b) {
        r = linear(r); g = linear(g); b = linear(b);
        double x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047;
        double y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750);
        double z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883;
        x = labPivot(x); y = labPivot(y); z = labPivot(z);
        return new double[]{116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }

    private static double labPivot(double value) {
        return value > 0.008856 ? Math.pow(value, 1.0 / 3.0) : 7.787 * value + 16.0 / 116.0;
    }

    private static int labToColor(double l, double a, double b) {
        double y = (l + 16) / 116.0;
        double x = a / 500.0 + y;
        double z = y - b / 200.0;
        x = inverseLabPivot(x) * 0.95047;
        y = inverseLabPivot(y);
        z = inverseLabPivot(z) * 1.08883;
        double r =  3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        double blue = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        r = gamma(r); g = gamma(g); blue = gamma(blue);
        return Color.rgb(clamp255(r * 255), clamp255(g * 255), clamp255(blue * 255));
    }

    private static double inverseLabPivot(double value) {
        double cube = value * value * value;
        return cube > 0.008856 ? cube : (value - 16.0 / 116.0) / 7.787;
    }

    private static double gamma(double value) {
        value = Math.max(0, Math.min(1, value));
        return value <= 0.0031308 ? value * 12.92 : 1.055 * Math.pow(value, 1 / 2.4) - 0.055;
    }

    private static int clamp255(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private static int[] fallback(int count) {
        int[] tones = new int[]{0xFF35618E, 0xFF294D73, 0xFF4777A6, 0xFF3E5F70,
                0xFF24425F, 0xFF527B9C, 0xFF31536A, 0xFF5D83A3};
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = tones[i % tones.length];
        return out;
    }

    /**
     * 生成符合亮度规范的随机纯色：
     * - 浅色模式：R + G + B = 600 (0 <= R, G, B <= 255)
     * - 深色模式：R + G + B = 300 (0 <= R, G, B <= 255)
     *
     * @param isDark 是否深色模式
     * @param rng 随机数生成器，可为 null
     * @return ARGB 颜色整数
     */
    public static int generateRandomSolidColor(boolean isDark, java.util.Random rng) {
        if (rng == null) rng = new java.util.Random();
        if (isDark) {
            // R + G + B = 300
            int minR = 0;
            int maxR = 255;
            int r = minR + rng.nextInt(maxR - minR + 1);
            int rem = 300 - r;
            int minG = Math.max(0, rem - 255);
            int maxG = Math.min(255, rem);
            int g = minG + rng.nextInt(maxG - minG + 1);
            int b = 300 - r - g;
            int[] rgb = new int[]{r, g, b};
            shuffle(rgb, rng);
            return Color.rgb(rgb[0], rgb[1], rgb[2]);
        } else {
            // R + G + B = 600
            // 设 r' = 255 - R, g' = 255 - G, b' = 255 - B => r' + g' + b' = 165
            int rPrime = rng.nextInt(166);
            int remPrime = 165 - rPrime;
            int gPrime = rng.nextInt(remPrime + 1);
            int bPrime = remPrime - gPrime;
            int r = 255 - rPrime;
            int g = 255 - gPrime;
            int b = 255 - bPrime;
            int[] rgb = new int[]{r, g, b};
            shuffle(rgb, rng);
            return Color.rgb(rgb[0], rgb[1], rgb[2]);
        }
    }

    private static void shuffle(int[] arr, java.util.Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int temp = arr[i];
            arr[i] = arr[j];
            arr[j] = temp;
        }
    }

    /**
     * 基于字符串（如 appId）确定性生成符合 R+G+B 规范的纯色
     */
    public static int generateSolidColorForString(String key, boolean isDark) {
        if (key == null) key = "";
        long seed = (long) key.hashCode() * 31L + (isDark ? 10007L : 20011L);
        return generateRandomSolidColor(isDark, new java.util.Random(seed));
    }

    /**
     * 基于整数 ID 确定性生成符合 R+G+B 规范的纯色
     */
    public static int generateSolidColorForId(int id, boolean isDark) {
        long seed = (long) id * 31L + (isDark ? 10007L : 20011L);
        return generateRandomSolidColor(isDark, new java.util.Random(seed));
    }

    /**
     * 判断颜色是否偏亮（感知亮度 >= 160），用于自适应选择白色或深色图标/文字。
     */
    public static boolean isColorLight(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return (0.299 * r + 0.587 * g + 0.114 * b) >= 160;
    }

    private static final class Sample {
        final double l, a, b, chroma, weight;
        Sample(double l, double a, double b, double chroma, double weight) {
            this.l = l; this.a = a; this.b = b; this.chroma = chroma; this.weight = weight;
        }
    }

    private static final class Cluster {
        double l, a, b, score, averageWeight;
        double sumL, sumA, sumB, sumWeight;
        int count;
        Cluster(double l, double a, double b) { this.l = l; this.a = a; this.b = b; }
        void reset() { sumL = sumA = sumB = sumWeight = 0; count = 0; }
        void add(Sample one) { sumL += one.l; sumA += one.a; sumB += one.b; sumWeight += one.weight; count++; }
        boolean finish() {
            if (count <= 0) return false;
            l = sumL / count; a = sumA / count; b = sumB / count; averageWeight = sumWeight / count;
            return true;
        }
    }
}
