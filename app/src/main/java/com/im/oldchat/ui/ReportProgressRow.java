package com.im.oldchat.ui;

public class ReportProgressRow {
    public String title;
    public String body;
    public String meta;
    public String status;
    public int statusType;
    public int bodyPreviewLimit;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_WARNING = 2;
}
