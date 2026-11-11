package com.oldchat.core.model;

import java.util.ArrayList;
import java.util.List;

/** 动态（朋友圈）单条。 */
public class Moment {
    public String id;
    public String authorUid;
    public String text = "";
    public long time;
    public List<String> imageColors = new ArrayList<>();  // 占位：真实为图片 URL
}
