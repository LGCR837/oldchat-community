package com.im.oldchat.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class VideoUtils {
    private static final long MAX_TRIM_DURATION_MS = 30_000L;

    private VideoUtils() {
    }

    public static File prepareVideo(Context context, Uri uri, long maxBytes, int maxSize, String mimeType) {
        if (context == null || uri == null) {
            return null;
        }
        String ext = resolveExtension(mimeType);
        File source = copyToCache(context, uri, ext);
        if (source == null || !source.exists()) {
            return null;
        }
        boolean needTrim = maxBytes > 0 && source.length() > maxBytes;
        boolean canTrim = needTrim
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                && ".mp4".equals(ext);
        if (!canTrim) {
            return source;
        }
        File out = new File(context.getCacheDir(), "video_trim_" + System.currentTimeMillis() + ".mp4");
        boolean trimmed = VideoTrimCompat.trimVideo(source, out, MAX_TRIM_DURATION_MS * 1000L);
        if (trimmed && out.exists() && out.length() > 0) {
            if (source.length() > 0 && out.length() >= source.length()) {
                out.delete();
                return source;
            }
            source.delete();
            return out;
        }
        out.delete();
        return source;
    }

    public static File buildVideoThumbnail(Context context, File videoFile, int maxSize,
                                           int quality, long maxBytes) {
        if (context == null || videoFile == null || !videoFile.exists()) {
            return null;
        }
        if (Build.VERSION.SDK_INT < 10) {
            return null;
        }
        Bitmap frame = VideoMetadataApi10.readFrame(videoFile);
        if (frame == null) {
            return null;
        }
        int width = frame.getWidth();
        int height = frame.getHeight();
        int max = Math.max(width, height);
        Bitmap scaled = frame;
        if (maxSize > 0 && max > maxSize) {
            float ratio = maxSize / (float) max;
            int targetW = Math.round(width * ratio);
            int targetH = Math.round(height * ratio);
            scaled = Bitmap.createScaledBitmap(frame, targetW, targetH, true);
        }
        if (scaled != frame) {
            frame.recycle();
        }
        File out = new File(context.getCacheDir(), "video_thumb_" + System.currentTimeMillis() + ".jpg");
        int targetQuality = Math.max(40, Math.min(quality, 100));
        boolean ok = writeJpeg(scaled, out, targetQuality);
        if (ok && maxBytes > 0) {
            for (int i = 0; i < 6 && out.length() > maxBytes && targetQuality > 40; i++) {
                targetQuality = Math.max(40, targetQuality - 10);
                ok = writeJpeg(scaled, out, targetQuality);
            }
        }
        if (scaled != null) {
            scaled.recycle();
        }
        return ok ? out : null;
    }

    public static long getDurationMs(Context context, File file) {
        if (context == null || file == null || !file.exists()) {
            return 0;
        }
        if (Build.VERSION.SDK_INT < 10) {
            return 0;
        }
        return VideoMetadataApi10.readDurationMs(file);
    }

    private static File copyToCache(Context context, Uri uri, String ext) {
        File dir = new File(context.getCacheDir(), "video_cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safeExt = ext == null || ext.isEmpty() ? ".mp4" : ext;
        File out = new File(dir, "video_" + System.currentTimeMillis() + safeExt);
        InputStream in = null;
        OutputStream outStream = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            outStream = new FileOutputStream(out);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                outStream.write(buffer, 0, count);
            }
            outStream.flush();
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (outStream != null) {
                try {
                    outStream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String resolveExtension(String mimeType) {
        if (mimeType == null) {
            return ".mp4";
        }
        String lower = mimeType.toLowerCase();
        if (lower.contains("3gpp") || lower.contains("3gp")) {
            return ".3gp";
        }
        if (lower.contains("mp4") || lower.contains("m4v")) {
            return ".mp4";
        }
        return ".mp4";
    }

    private static boolean writeJpeg(Bitmap bitmap, File out, int quality) {
        if (bitmap == null || out == null) {
            return false;
        }
        try {
            FileOutputStream output = new FileOutputStream(out);
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
            output.flush();
            output.close();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
