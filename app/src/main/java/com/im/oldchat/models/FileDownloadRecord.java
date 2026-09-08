package com.im.oldchat.models;

import org.json.JSONObject;

public class FileDownloadRecord {
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    public String id;
    public String url;
    public String fileName;
    public String mimeType;
    public String status;
    public int progress;
    public long downloadedBytes;
    public long totalBytes;
    public String savedPath;
    public String savedUri;
    public String error;
    public long updatedAt;

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("url", url);
            obj.put("file_name", fileName);
            obj.put("mime_type", mimeType);
            obj.put("status", status);
            obj.put("progress", progress);
            obj.put("downloaded_bytes", downloadedBytes);
            obj.put("total_bytes", totalBytes);
            obj.put("saved_path", savedPath);
            obj.put("saved_uri", savedUri);
            obj.put("error", error);
            obj.put("updated_at", updatedAt);
        } catch (Exception ignored) {
        }
        return obj;
    }

    public static FileDownloadRecord fromJson(JSONObject obj) {
        if (obj == null) return null;
        FileDownloadRecord item = new FileDownloadRecord();
        item.id = obj.optString("id");
        item.url = obj.optString("url");
        item.fileName = obj.optString("file_name");
        item.mimeType = obj.optString("mime_type", "application/octet-stream");
        item.status = obj.optString("status", STATUS_FAILED);
        item.progress = obj.optInt("progress", 0);
        item.downloadedBytes = obj.optLong("downloaded_bytes", 0L);
        item.totalBytes = obj.optLong("total_bytes", 0L);
        item.savedPath = obj.optString("saved_path");
        item.savedUri = obj.optString("saved_uri");
        item.error = obj.optString("error");
        item.updatedAt = obj.optLong("updated_at", 0L);
        return item;
    }
}
