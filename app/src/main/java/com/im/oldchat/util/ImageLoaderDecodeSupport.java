package com.im.oldchat.util;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;

import java.util.Locale;

final class ImageLoaderDecodeSupport {
    private static final int MAX_ATTEMPTS = 4;

    private ImageLoaderDecodeSupport() {
    }

    static Bitmap decodeFile(Context context, String url, String path, int maxSize, int maxBytes) {
        if (path == null || path.length() == 0) {
            return null;
        }
        BitmapFactory.Options bound = new BitmapFactory.Options();
        bound.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bound);
        if (bound.outWidth <= 0 || bound.outHeight <= 0) {
            ImageLoadPerfLogger.logDecode(context, "decode_file", url, 0L, bound.outWidth, bound.outHeight,
                    maxSize, maxBytes, 1, Bitmap.Config.RGB_565, 0, "invalid_bounds", null);
            return null;
        }
        return decodeFileAttempts(context, url, path, maxSize, maxBytes, bound);
    }

    static Bitmap decodeBytes(Context context, String url, byte[] data, int maxSize, int maxBytes) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options bound = new BitmapFactory.Options();
        bound.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bound);
        if (bound.outWidth <= 0 || bound.outHeight <= 0) {
            ImageLoadPerfLogger.logDecode(context, "decode_bytes", url, 0L, bound.outWidth, bound.outHeight,
                    maxSize, maxBytes, 1, Bitmap.Config.RGB_565, 0, "invalid_bounds", null);
            return null;
        }
        return decodeBytesAttempts(context, url, data, maxSize, maxBytes, bound);
    }

    private static Bitmap decodeFileAttempts(Context context, String url, String path,
                                             int maxSize, int maxBytes,
                                             BitmapFactory.Options bound) {
        DecodePlan plan = buildPlan(context, bound.outWidth, bound.outHeight, bound.outMimeType, maxSize, maxBytes);
        int sample = plan.sampleSize;
        Bitmap.Config config = plan.config;
        Throwable lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = SystemClock.uptimeMillis();
            Bitmap reusable = null;
            try {
                BitmapFactory.Options opts = newDecodeOptions(sample, config, bound.outWidth, bound.outHeight);
                Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
                long cost = SystemClock.uptimeMillis() - started;
                if (bitmap != null) {
                    ImageLoadPerfLogger.logDecode(context, "decode_file", url, cost, bound.outWidth, bound.outHeight,
                            maxSize, plan.budgetBytes, sample, config, attempt, "ok", null);
                    return bitmap;
                }
                // 解码失败，回收复用的Bitmap
                if (reusable != null) {
                    BitmapPool.recycle(reusable);
                }
                ImageLoadPerfLogger.logDecode(context, "decode_file", url, cost, bound.outWidth, bound.outHeight,
                        maxSize, plan.budgetBytes, sample, config, attempt, "null", null);
            } catch (OutOfMemoryError oom) {
                // 回收复用的Bitmap
                if (reusable != null) {
                    BitmapPool.recycle(reusable);
                }
                lastError = oom;
                long cost = SystemClock.uptimeMillis() - started;
                ImageLoadPerfLogger.logDecode(context, "decode_file", url, cost, bound.outWidth, bound.outHeight,
                        maxSize, plan.budgetBytes, sample, config, attempt, "oom", oom);
            } catch (Throwable error) {
                // 回收复用的Bitmap
                if (reusable != null) {
                    BitmapPool.recycle(reusable);
                }
                long cost = SystemClock.uptimeMillis() - started;
                ImageLoadPerfLogger.logDecode(context, "decode_file", url, cost, bound.outWidth, bound.outHeight,
                        maxSize, plan.budgetBytes, sample, config, attempt, "error", error);
                return null;
            }
            sample = growSample(sample);
            if (config != Bitmap.Config.RGB_565) {
                config = Bitmap.Config.RGB_565;
            }
        }
        if (lastError != null) {
            ImageLoadPerfLogger.logFailure(context, "decode_file_exhausted", url, lastError);
        }
        return null;
    }

    private static Bitmap decodeBytesAttempts(Context context, String url, byte[] data,
                                              int maxSize, int maxBytes,
                                              BitmapFactory.Options bound) {
        DecodePlan plan = buildPlan(context, bound.outWidth, bound.outHeight, bound.outMimeType, maxSize, maxBytes);
        int sample = plan.sampleSize;
        Bitmap.Config config = plan.config;
        Throwable lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = SystemClock.uptimeMillis();
            Bitmap reusable = null;
            try {
                BitmapFactory.Options opts = newDecodeOptions(sample, config, bound.outWidth, bound.outHeight);
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                long cost = SystemClock.uptimeMillis() - started;
                if (bitmap != null) {
                    ImageLoadPerfLogger.logDecode(context, "decode_bytes", url, cost, bound.outWidth, bound.outHeight,
                            maxSize, plan.budgetBytes, sample, config, attempt, "ok", null);
                    return bitmap;
                }
                // 解码失败，回收复用的Bitmap
                if (reusable != null) {
                    BitmapPool.recycle(reusable);
                }
                ImageLoadPerfLogger.logDecode(context, "decode_bytes", url, cost, bound.outWidth, bound.outHeight,
                        maxSize, plan.budgetBytes, sample, config, attempt, "null", null);
            } catch (OutOfMemoryError oom) {
                // 回收复用的Bitmap
                if (reusable != null) {
                    BitmapPool.recycle(reusable);
                }
                lastError = oom;
                long cost = SystemClock.uptimeMillis() - started;
                ImageLoadPerfLogger.logDecode(context, "decode_bytes", url, cost, bound.outWidth, bound.outHeight,
                        maxSize, plan.budgetBytes, sample, config, attempt, "oom", oom);
            } catch (Throwable error) {
                // 回收复用的Bitmap
                if (reusable != null) {
                    BitmapPool.recycle(reusable);
                }
                long cost = SystemClock.uptimeMillis() - started;
                ImageLoadPerfLogger.logDecode(context, "decode_bytes", url, cost, bound.outWidth, bound.outHeight,
                        maxSize, plan.budgetBytes, sample, config, attempt, "error", error);
                return null;
            }
            sample = growSample(sample);
            if (config != Bitmap.Config.RGB_565) {
                config = Bitmap.Config.RGB_565;
            }
        }
        if (lastError != null) {
            ImageLoadPerfLogger.logFailure(context, "decode_bytes_exhausted", url, lastError);
        }
        return null;
    }

    private static DecodePlan buildPlan(Context context, int width, int height, String mimeType,
                                        int maxSize, int maxBytes) {
        Bitmap.Config config = pickConfigByMimeType(mimeType);
        int budgetBytes = resolveBudgetBytes(context, maxSize, maxBytes, config);
        int sample = computeSampleSize(width, height, maxSize, budgetBytes, config);
        return new DecodePlan(config, sample, budgetBytes);
    }

    private static BitmapFactory.Options newDecodeOptions(int sample, Bitmap.Config config, int width, int height) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = config == null ? Bitmap.Config.RGB_565 : config;
        opts.inDither = true;
        
        // 聊天列表中的 Bitmap 可能仍被 ImageView 引用。禁用 inBitmap 原地像素复用，
        // 避免后一张图片解码覆盖前一张仍在屏幕上的可变 Bitmap，造成相邻图片串图。
        // 采样尺寸和 RGB_565 已足以控制内存，不值得用显示正确性换取少量分配优化。
        // 不要触碰 Options.inBitmap：该字段 API 11 才存在，API 9 会抛 NoSuchFieldError。
        if (Build.VERSION.SDK_INT < 11) {
            opts.inPurgeable = true;
            opts.inInputShareable = true;
        }
        return opts;
    }

    private static Bitmap.Config pickConfigByMimeType(String mimeType) {
        if (mimeType == null) {
            return Bitmap.Config.RGB_565;
        }
        String lower = mimeType.toLowerCase(Locale.US);
        if (lower.indexOf("png") >= 0 || lower.indexOf("webp") >= 0 || lower.indexOf("gif") >= 0) {
            return Bitmap.Config.ARGB_8888;
        }
        return Bitmap.Config.RGB_565;
    }

    private static int computeSampleSize(int width, int height, int maxSize, int maxBytes, Bitmap.Config config) {
        int sample = 1;
        if (maxSize > 0) {
            while (Math.max(1, width / sample) > maxSize || Math.max(1, height / sample) > maxSize) {
                sample = growSample(sample);
            }
        }
        if (maxBytes > 0) {
            long bytesPerPixel = config == Bitmap.Config.ARGB_8888 ? 4L : 2L;
            while ((long) Math.max(1, width / sample) * (long) Math.max(1, height / sample) * bytesPerPixel > maxBytes) {
                sample = growSample(sample);
            }
        }
        return Math.max(1, sample);
    }

    private static int resolveBudgetBytes(Context context, int maxSize, int maxBytes, Bitmap.Config config) {
        if (maxBytes > 0) {
            return maxBytes;
        }
        if (config != Bitmap.Config.ARGB_8888 || !isLowMemoryDevice(context)) {
            return 0;
        }
        if (maxSize <= 320) {
            return 512 * 1024;
        }
        if (maxSize <= 480) {
            return 1024 * 1024;
        }
        if (maxSize <= 640) {
            return 1536 * 1024;
        }
        if (maxSize <= 1024) {
            return 2 * 1024 * 1024;
        }
        return 3 * 1024 * 1024;
    }

    private static boolean isLowMemoryDevice(Context context) {
        if (context == null) {
            return false;
        }
        int memClass = 0;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                memClass = am.getMemoryClass();
            }
        } catch (Exception e) {
            memClass = 0;
        }
        if (Build.VERSION.SDK_INT <= 10) {
            return memClass > 0 && memClass <= 32;
        }
        return memClass > 0 && memClass <= 64;
    }

    private static int growSample(int sample) {
        if (sample <= 0) {
            return 1;
        }
        if (sample > Integer.MAX_VALUE / 2) {
            return sample;
        }
        return sample * 2;
    }

    private static final class DecodePlan {
        final Bitmap.Config config;
        final int sampleSize;
        final int budgetBytes;

        DecodePlan(Bitmap.Config config, int sampleSize, int budgetBytes) {
            this.config = config;
            this.sampleSize = sampleSize;
            this.budgetBytes = budgetBytes;
        }
    }
}
