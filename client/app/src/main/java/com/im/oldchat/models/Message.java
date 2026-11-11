package com.im.oldchat.models;

public class Message {
    public static final int STATUS_NONE = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DELIVERED = 2;
    public static final int STATUS_READ = 3;

    public String id;
    public String thread_id;
    public String from_uid;
    public String body;
    public String msg_type;
    public String media_url;
    public String thumb_url;
    public String original_url;
    public int duration_ms;
    public int burn_after_seconds;
    public long burn_start_at;
    public String burn_preview_text;
    public String burn_preview_type;
    public String burn_preview_media_url;
    public String burn_preview_thumb_url;
    public long created_at;
    public long sort_seq;
    public int status = STATUS_NONE;
    public boolean localPending;
    public boolean localFailed;
    public String localRequestId;
    public String localPreviewUri;
    public int localProgress = -1;
    public transient String localUploadState;
    public String recall_edit_type;
    public String recall_edit_text;
    public String sender_ncuid;
    public transient CharSequence cachedStyledText;
    public transient boolean cachedHasLinks;

    public Message() {}
    public Message(String body) { this.body = body; }
}
