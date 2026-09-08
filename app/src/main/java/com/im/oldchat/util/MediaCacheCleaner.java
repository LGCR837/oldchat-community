package com.im.oldchat.util;

import android.content.Context;

import java.io.File;

public final class MediaCacheCleaner {
    private MediaCacheCleaner() {
    }

    public static long calculateDeepMediaCacheBytes(Context context) {
        if (context == null) {
            return 0L;
        }
        long total = 0L;
        File cacheRoot = context.getCacheDir();
        File filesRoot = context.getFilesDir();

        total += getPathSize(new File(cacheRoot, "img_cache"));
        total += getPathSize(new File(filesRoot, "voice_cache"));
        total += getPathSize(new File(cacheRoot, "voice_cache"));
        total += getPathSize(new File(filesRoot, "music_player_cache"));
        total += getPathSize(new File(cacheRoot, "music_player_cache"));
        total += getPathSize(new File(cacheRoot, "video_cache"));
        total += getPathSize(new File(cacheRoot, "video_play_cache"));
        total += getPathSize(new File(cacheRoot, "upload_cache"));
        total += getPrefixFilesSize(cacheRoot, "video_trim_");
        total += getPrefixFilesSize(cacheRoot, "video_thumb_");
        total += getPrefixFilesSize(cacheRoot, "emoji_pkg_");
        return total;
    }

    public static void clearDeepMediaCache(Context context) {
        if (context == null) {
            return;
        }
        ImageLoader.clearDiskCache(context);

        File cacheRoot = context.getCacheDir();
        File filesRoot = context.getFilesDir();

        deleteRecursive(new File(cacheRoot, "img_cache"));
        deleteRecursive(new File(filesRoot, "voice_cache"));
        deleteRecursive(new File(cacheRoot, "voice_cache"));
        deleteRecursive(new File(filesRoot, "music_player_cache"));
        deleteRecursive(new File(cacheRoot, "music_player_cache"));
        deleteRecursive(new File(cacheRoot, "video_cache"));
        deleteRecursive(new File(cacheRoot, "video_play_cache"));
        deleteRecursive(new File(cacheRoot, "upload_cache"));

        deleteFilesByPrefix(cacheRoot, "video_trim_");
        deleteFilesByPrefix(cacheRoot, "video_thumb_");
        deleteFilesByPrefix(cacheRoot, "emoji_pkg_");
    }

    private static long getPrefixFilesSize(File root, String prefix) {
        if (root == null || !root.exists() || prefix == null) {
            return 0L;
        }
        File[] files = root.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (int i = 0; i < files.length; i++) {
            File one = files[i];
            if (one == null || !one.isFile()) {
                continue;
            }
            String name = one.getName();
            if (name != null && name.startsWith(prefix)) {
                total += Math.max(0L, one.length());
            }
        }
        return total;
    }

    private static void deleteFilesByPrefix(File root, String prefix) {
        if (root == null || !root.exists() || prefix == null) {
            return;
        }
        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            File one = files[i];
            if (one == null || !one.isFile()) {
                continue;
            }
            String name = one.getName();
            if (name != null && name.startsWith(prefix)) {
                try {
                    one.delete();
                } catch (Exception e) {
                }
            }
        }
    }

    private static long getPathSize(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return Math.max(0L, file.length());
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (int i = 0; i < children.length; i++) {
            total += getPathSize(children[i]);
        }
        return total;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursive(children[i]);
                }
            }
        }
        try {
            file.delete();
        } catch (Exception e) {
        }
    }
}
