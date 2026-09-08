package com.im.oldchat.models;

import java.util.List;

public class Moment {
    public String id;
    public String from_uid;
    public String from_ncuid;
    public String from_name;
    public String from_title;
    public String from_avatar;
    public String body;
    public String image_url;
    public long created_at;
    public int likes;
    public int comments;
    public boolean liked;
    public transient List<String> parsedImageUrls;
}
