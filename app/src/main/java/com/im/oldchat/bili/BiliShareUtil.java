package com.im.oldchat.bili;

import java.net.URLEncoder;
import java.net.URLDecoder;

public final class BiliShareUtil {
    public static final String SHARE_PREFIX = "oldchat://bili";

    private BiliShareUtil() {
    }

    public static boolean isShareUrl(String url) {
        return url != null && url.startsWith(SHARE_PREFIX);
    }

    public static String buildShareUrl(String bvid, long aid, long cid, String title, String cover, long duration) {
        StringBuilder sb = new StringBuilder();
        sb.append(SHARE_PREFIX);
        boolean hasParam = false;
        if (bvid != null && bvid.length() > 0) {
            hasParam = appendParam(sb, "bvid", bvid, hasParam);
        }
        if (aid > 0) {
            hasParam = appendParam(sb, "aid", String.valueOf(aid), hasParam);
        }
        if (cid > 0) {
            hasParam = appendParam(sb, "cid", String.valueOf(cid), hasParam);
        }
        if (title != null && title.length() > 0) {
            hasParam = appendParam(sb, "title", title, hasParam);
        }
        if (cover != null && cover.length() > 0) {
            hasParam = appendParam(sb, "cover", cover, hasParam);
        }
        if (duration > 0) {
            appendParam(sb, "duration", String.valueOf(duration), hasParam);
        }
        return sb.toString();
    }

    private static boolean appendParam(StringBuilder sb, String key, String value, boolean hasParam) {
        if (!hasParam) {
            sb.append('?');
            hasParam = true;
        } else {
            sb.append('&');
        }
        sb.append(key);
        sb.append('=');
        sb.append(urlEncode(value));
        return hasParam;
    }

    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String urlDecode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    public static ShareInfo parseShareUrl(String url) {
        ShareInfo info = new ShareInfo();
        if (url == null) {
            return info;
        }
        int q = url.indexOf('?');
        if (q < 0 || q >= url.length() - 1) {
            return info;
        }
        String query = url.substring(q + 1);
        int len = query.length();
        int i = 0;
        while (i < len) {
            int amp = query.indexOf('&', i);
            if (amp < 0) {
                amp = len;
            }
            int eq = query.indexOf('=', i);
            if (eq < 0 || eq > amp) {
                i = amp + 1;
                continue;
            }
            String key = query.substring(i, eq);
            String value = urlDecode(query.substring(eq + 1, amp));
            if ("bvid".equals(key)) {
                info.bvid = value;
            } else if ("aid".equals(key)) {
                info.aid = parseLong(value);
            } else if ("cid".equals(key)) {
                info.cid = parseLong(value);
            } else if ("title".equals(key)) {
                info.title = value;
            } else if ("cover".equals(key)) {
                info.cover = value;
            } else if ("duration".equals(key)) {
                info.duration = parseLong(value);
            }
            i = amp + 1;
        }
        return info;
    }

    private static long parseLong(String value) {
        if (value == null || value.length() == 0) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static class ShareInfo {
        public String bvid;
        public long aid;
        public long cid;
        public String title;
        public String cover;
        public long duration;
    }
}
