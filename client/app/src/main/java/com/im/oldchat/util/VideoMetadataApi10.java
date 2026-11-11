package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.HashMap;

/** 仅由 API 10+ 分支加载，避免 API 9 Dalvik 校验缺失的媒体类。 */
@TargetApi(10)
public final class VideoMetadataApi10 {
    private VideoMetadataApi10() { }

    public static Bitmap readFrame(File file) {
        if (file == null) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getFrameAtTime(1_000_000L);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
    }

    public static long readDurationMs(File file) {
        if (file == null) return 0L;
        return parseDuration(readDurationText(file.getAbsolutePath(), false));
    }

    public static long readDurationMs(Context context, Uri uri) {
        if (context == null || uri == null) return 0L;
        ParcelFileDescriptor descriptor = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) return 0L;
            retriever.setDataSource(descriptor.getFileDescriptor());
            return parseDuration(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
            if (descriptor != null) {
                try { descriptor.close(); } catch (Throwable ignored) { }
            }
        }
    }

    public static String readRemoteDurationText(String url) {
        return readDurationText(url, true);
    }

    private static String readDurationText(String source, boolean remote) {
        if (source == null || source.length() == 0) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (remote && Build.VERSION.SDK_INT >= 14) {
                retriever.setDataSource(source, new HashMap<String, String>());
            } else {
                retriever.setDataSource(source);
            }
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
    }

    private static long parseDuration(String value) {
        if (value == null || value.length() == 0) return 0L;
        try { return Long.parseLong(value); } catch (Throwable ignored) { return 0L; }
    }
}
