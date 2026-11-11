package com.im.oldchat.ui;

import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.models.MusicItem;
import com.im.oldchat.util.ImageLoader;

import java.util.Locale;

final class MusicItemDisplay {
    private MusicItemDisplay() {
    }

    static String title(MusicItem item) {
        String value = item == null ? "" : clean(item.name);
        return value.length() == 0 ? "未命名歌曲" : value;
    }

    static String owner(MusicItem item) {
        String value = item == null ? "" : clean(item.ownerName);
        if (value.length() == 0 && item != null) value = clean(item.ownerUid);
        if (value.length() == 0) value = "未知用户";
        return "上传者 · " + value;
    }

    static String metadata(MusicItem item) {
        return duration(item == null ? 0 : item.durationMs) + " · " + size(item == null ? 0L : item.sizeBytes);
    }

    static String stats(MusicItem item) {
        int plays = item == null ? 0 : Math.max(0, item.playCount);
        int likes = item == null ? 0 : Math.max(0, item.likes);
        int comments = item == null ? 0 : Math.max(0, item.comments);
        return "播放 " + plays + " · 赞 " + likes + " · 评论 " + comments;
    }

    static String duration(int durationMs) {
        if (durationMs <= 0) return "时长未知";
        int totalSeconds = durationMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    static String size(long bytes) {
        if (bytes <= 0L) return "大小未知";
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
        }
        return Math.max(1L, bytes / 1024L) + " KB";
    }

    static void loadCover(ImageView view, MusicItem item) {
        if (view == null) return;
        String cover = item == null ? "" : clean(item.coverUrl);
        if (cover.length() == 0) {
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        view.setImageResource(R.drawable.bg_image_placeholder);
        ImageLoader.load(view, cover);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
