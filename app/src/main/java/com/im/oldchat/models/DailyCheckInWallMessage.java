package com.im.oldchat.models;

public class DailyCheckInWallMessage {
    public String id;
    public String checkinDate;
    public String messageType;
    public String contentText;
    public String imageUrl;
    public String thumbUrl;
    public String userId;
    public String userUid;
    public String username;
    public String displayName;
    public String avatarUrl;
    public String createdAt;
    public int likeCount;
    public int commentCount;
    public boolean likedByMe;

    public String getDisplayName() {
        String value = displayName;
        if (value == null || value.trim().length() == 0) {
            value = username;
        }
        if (value == null || value.trim().length() == 0) {
            value = userUid;
        }
        return value == null ? "" : value.trim();
    }

    public boolean hasImage() {
        return imageUrl != null && imageUrl.trim().length() > 0;
    }

    public String getPreviewUrl() {
        if (thumbUrl != null && thumbUrl.trim().length() > 0) {
            return thumbUrl.trim();
        }
        return imageUrl == null ? "" : imageUrl.trim();
    }
}
