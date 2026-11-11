package com.im.oldchat.ui;

import android.view.View;
import android.widget.ProgressBar;

import java.util.Locale;

final class FileUploadUiTextUtil {
    private FileUploadUiTextUtil() {
    }

    static String buildBody(String fileName, long fileSize, String statusLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件: ").append(normalizeName(fileName));
        if (fileSize > 0) {
            sb.append("\n大小: ").append(formatSize(fileSize));
        }
        if (statusLine != null && statusLine.trim().length() > 0) {
            sb.append("\n").append(statusLine.trim());
        }
        return sb.toString();
    }

    static String buildProgressLine(int percent) {
        return buildStateLine(ChatFileUploadHelper.STATE_UPLOADING, percent);
    }

    static String buildStateLine(String state, int percent) {
        int safe = Math.max(0, Math.min(100, percent));
        if (ChatFileUploadHelper.STATE_HASHING.equals(state)) {
            return percent < 0 ? "正在校验文件" : "正在校验文件 " + safe + "%";
        }
        if (ChatFileUploadHelper.STATE_CHECKING.equals(state)) {
            return "正在检查是否可秒传";
        }
        if (ChatFileUploadHelper.STATE_SENDING.equals(state)) {
            return "上传完成，正在发送";
        }
        if (ChatFileUploadHelper.STATE_FAILED.equals(state)) {
            return "上传失败";
        }
        return "上传中 " + safe + "%";
    }

    static void bindProgress(ProgressBar progressBar, String state, int percent) {
        if (progressBar == null) {
            return;
        }
        if (state == null || state.length() == 0 || STATE_FAILED.equals(state)) {
            progressBar.setVisibility(View.GONE);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        boolean indeterminate = ChatFileUploadHelper.STATE_HASHING.equals(state) && percent < 0
                || ChatFileUploadHelper.STATE_CHECKING.equals(state);
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            progressBar.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private static final String STATE_FAILED = ChatFileUploadHelper.STATE_FAILED;

    static String extractStatusLine(String body) {
        if (body == null || body.length() == 0) {
            return "";
        }
        String[] lines = body.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.startsWith("上传中") || line.startsWith("发送中") || line.startsWith("上传失败")
                    || line.startsWith("正在校验") || line.startsWith("正在检查") || line.startsWith("上传完成")) {
                return line;
            }
        }
        return "";
    }

    static boolean hasPendingStatus(String body) {
        return extractStatusLine(body).length() > 0;
    }

    private static String normalizeName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.length() == 0) {
            return "未命名文件";
        }
        return name;
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        double value = (double) bytes;
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value = value / 1024.0;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return String.format(Locale.US, "%d %s", (long) value, units[unitIndex]);
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex]);
    }
}
