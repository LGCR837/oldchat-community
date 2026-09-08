package com.im.oldchat.models;

public class GroupMessage {
    public String id;
    public String group_id;
    public String from_uid;
    public String body;
    public String msg_type;
    public String media_url;
    public String thumb_url;
    public String original_url;
    public int duration_ms;
    public int burn_after_seconds;
    public String burn_preview_text;
    public String burn_preview_type;
    public String burn_preview_media_url;
    public String burn_preview_thumb_url;
    public long created_at;
    public long sort_seq;
    public long group_seq;
    public int read_count;
    public boolean localPending;
    public boolean localFailed;
    public String localRequestId;
    public String localPreviewUri;
    public int localProgress = -1;
    public transient String localUploadState;

    public String recall_edit_type;
    public String recall_edit_text;
    public String sender_ncuid;
    
    public transient com.im.oldchat.util.MessagePayload cachedPayload;
    public transient int cachedMemberMetaVersion;
    public transient String cachedMemberMetaSourceUid;
    public transient String cachedCanonicalUid;
    public transient String cachedSenderName;
    public transient String cachedSenderTitle;
    public transient int cachedSenderRole;
    public transient String cachedAvatarSourceUid;
    public transient String cachedAvatarUrl;
    public transient Object cachedResolvedMemberMeta;
    public transient CharSequence cachedStyledText;
    public transient boolean cachedHasLinks;
}
