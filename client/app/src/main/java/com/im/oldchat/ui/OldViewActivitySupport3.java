package com.im.oldchat.ui;

import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliModels;

import java.util.List;

abstract class OldViewActivitySupport3 extends OldViewActivitySupport4 {
    protected void hideKeyboard(View view) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && view != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Exception e) {
        }
    }

    protected String stripHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<[^>]+>", "").replace("&amp;", "&");
    }

    protected void openDetail(BiliModels.RecommendItem item) {
        if (item == null) {
            return;
        }
        long cid = extractCidFromUri(item.uri);
        String preloadUrl = extractPreloadUrlFromUri(item.uri);
        String bvid = extractBvid(item);
        long aid = extractAid(item);
        if ((bvid == null || bvid.length() == 0) && aid <= 0 && (preloadUrl == null || preloadUrl.length() == 0)) {
            Toast.makeText(this, "无法识别该视频", Toast.LENGTH_SHORT).show();
            logError("打开详情失败: 无有效bvid/aid param=" + item.param + " uri=" + item.uri);
            return;
        }
        android.content.Intent intent = new android.content.Intent(this, OldViewVideoDetailActivity.class);
        if (bvid != null) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_BVID, bvid);
        }
        intent.putExtra(OldViewVideoDetailActivity.EXTRA_AID, aid);
        if (cid > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_CID, cid);
        }
        if (preloadUrl != null && preloadUrl.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_PRELOAD_URL, preloadUrl);
        }
        if (item.cover != null && item.cover.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_COVER, item.cover);
        }
        if (item.title != null) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_TITLE, item.title);
        }
        startActivity(intent);
    }

    protected String extractBvid(BiliModels.RecommendItem item) {
        if (item == null) {
            return null;
        }
        if (item.param != null) {
            String direct = item.param.trim();
            if (isValidBvid(direct)) {
                return direct;
            }
            if (isAllDigits(direct) || direct.startsWith("av") || direct.startsWith("AV")) {
                return null;
            }
        }
        if (item.uri != null) {
            int idx = item.uri.indexOf("BV");
            if (idx >= 0) {
                int end = idx;
                while (end < item.uri.length()) {
                    char c = item.uri.charAt(end);
                    if (c == '/' || c == '?' || c == '&') {
                        break;
                    }
                    end++;
                }
                String candidate = item.uri.substring(idx, end);
                return isValidBvid(candidate) ? candidate : null;
            }
            String queryBvid = getQueryParam(item.uri, "bvid");
            if (isValidBvid(queryBvid)) {
                return queryBvid;
            }
        }
        return null;
    }

    protected long extractAid(BiliModels.RecommendItem item) {
        if (item == null) {
            return 0L;
        }
        if (item.args != null && isValidAid(item.args.aid)) {
            return item.args.aid;
        }
        long aid = parseAidToken(item.param);
        if (isValidAid(aid)) {
            return aid;
        }
        aid = parseAidFromUri(item.uri);
        return isValidAid(aid) ? aid : 0L;
    }

    protected long parseAidToken(String text) {
        if (text == null) {
            return 0L;
        }
        String t = text.trim();
        if (t.length() == 0) {
            return 0L;
        }
        if (t.startsWith("av") || t.startsWith("AV")) {
            t = t.substring(2);
        } else if (t.startsWith("aid=")) {
            t = t.substring(4);
        }
        if (isAllDigits(t)) {
            try {
                return Long.parseLong(t);
            } catch (Exception e) {
                return 0L;
            }
        }
        return 0L;
    }

    protected long parseAidFromUri(String uri) {
        if (uri == null) {
            return 0L;
        }
        int idx = uri.indexOf("aid=");
        if (idx >= 0) {
            long aid = parseDigits(uri, idx + 4);
            if (aid > 0) {
                return aid;
            }
        }
        idx = uri.indexOf("video/");
        if (idx >= 0) {
            long aid = parseDigits(uri, idx + 6);
            if (aid > 0) {
                return aid;
            }
        }
        idx = uri.indexOf("av");
        if (idx >= 0) {
            long aid = parseDigits(uri, idx + 2);
            if (aid > 0) {
                return aid;
            }
        }
        return 0L;
    }

    protected long parseDigits(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) {
            return 0L;
        }
        long value = 0L;
        boolean found = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                found = true;
                value = value * 10L + (c - '0');
            } else if (found) {
                break;
            } else {
                return 0L;
            }
        }
        return found ? value : 0L;
    }

    protected boolean isAllDigits(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    protected boolean isValidBvid(String bvid) {
        if (bvid == null) {
            return false;
        }
        String t = bvid.trim();
        if (t.length() < 10) {
            return false;
        }
        return t.startsWith("BV") || t.startsWith("bv");
    }

    protected boolean isValidAid(long aid) {
        return aid > 0;
    }

    protected String safe(String text) {
        return text == null ? "" : text;
    }

    protected long extractCidFromUri(String uri) {
        if (uri == null || uri.length() == 0) {
            return 0L;
        }
        String cid = getQueryParam(uri, "cid");
        if (cid != null && cid.length() > 0) {
            try {
                return Long.parseLong(cid);
            } catch (Exception e) {
                return 0L;
            }
        }
        return 0L;
    }

    protected String extractPreloadUrlFromUri(String uri) {
        if (uri == null || uri.length() == 0) {
            return null;
        }
        String preload = getQueryParam(uri, "player_preload");
        if (preload == null || preload.length() == 0) {
            return null;
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(preload);
            String url = obj.optString("url");
            if (url != null && url.length() > 0) {
                return url.replace("\\u0026", "&");
            }
        } catch (Exception e) {
            int idx = preload.indexOf("\"url\"");
            if (idx >= 0) {
                int colon = preload.indexOf(':', idx);
                if (colon > 0) {
                    int start = preload.indexOf('"', colon + 1);
                    if (start >= 0) {
                        int end = preload.indexOf('"', start + 1);
                        if (end > start) {
                            String url = preload.substring(start + 1, end);
                            if (url.length() > 0) {
                                return url.replace("\\u0026", "&");
                            }
                        }
                    }
                }
            }
            return null;
        }
        return null;
    }

    protected String getQueryParam(String uri, String key) {
        if (uri == null || key == null) {
            return null;
        }
        int q = uri.indexOf('?');
        if (q < 0 || q >= uri.length() - 1) {
            return null;
        }
        String query = uri.substring(q + 1);
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
            String k = query.substring(i, eq);
            if (key.equals(k)) {
                return urlDecode(query.substring(eq + 1, amp));
            }
            i = amp + 1;
        }
        return null;
    }

    protected String urlDecode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    protected List<BiliModels.RecommendItem> filterRecommendItems(List<BiliModels.RecommendItem> data) {
        List<BiliModels.RecommendItem> result = new java.util.ArrayList<BiliModels.RecommendItem>();
        if (data == null) {
            logStep("filterRecommendItems: data=null");
            return result;
        }
        int total = data.size();
        int keep = 0;
        int dropNonVideo = 0;
        int dropNoId = 0;
        int logLimit = 8;
        for (int i = 0; i < data.size(); i++) {
            BiliModels.RecommendItem item = data.get(i);
            if (item == null) {
                continue;
            }
            boolean isVideo = isVideoItem(item);
            String bvid = extractBvid(item);
            long aid = extractAid(item);
            String preloadUrl = extractPreloadUrlFromUri(item.uri);
            long cid = extractCidFromUri(item.uri);
            boolean hasId = aid > 0 || bvid != null;
            boolean hasPreload = preloadUrl != null && preloadUrl.length() > 0;
            boolean keepItem = (isVideo || hasPreload) && (hasId || hasPreload);
            if (logLimit > 0) {
                logStep("item#" + i + " cardType=" + safe(item.cardType) + " goto=" + safe(item.gotoType)
                        + " cardGoto=" + safe(item.cardGoto) + " param=" + safe(item.param)
                        + " uri=" + safe(item.uri) + " argsAid=" + (item.args != null ? item.args.aid : 0)
                        + " aid=" + aid + " bvid=" + safe(bvid)
                        + " cid=" + cid + " preload=" + (hasPreload ? "Y" : "N")
                        + " keep=" + keepItem);
                logLimit--;
            }
            if (keepItem) {
                item.cover = BiliApi.normalizeUrl(item.cover);
                item.face = BiliApi.normalizeUrl(item.face);
                result.add(item);
                keep++;
            } else if (!isVideo) {
                dropNonVideo++;
            } else {
                dropNoId++;
            }
        }
        logStep("filterRecommendItems: total=" + total + " keep=" + keep
                + " dropNonVideo=" + dropNonVideo + " dropNoId=" + dropNoId);
        return result;
    }

    protected boolean isVideoItem(BiliModels.RecommendItem item) {
        if (item == null) {
            return false;
        }
        if ("av".equals(item.gotoType) || "av".equals(item.cardGoto)) {
            return true;
        }
        if ("small_cover_v2".equals(item.cardType) || "small_cover_v1".equals(item.cardType)) {
            return true;
        }
        return isValidBvid(item.param);
    }

    protected String buildCookieString(BiliModels.CookieInfo cookieInfo) {
        if (cookieInfo == null || cookieInfo.cookies == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cookieInfo.cookies.size(); i++) {
            BiliModels.BiliCookie cookie = cookieInfo.cookies.get(i);
            if (cookie == null || cookie.name == null || cookie.value == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(cookie.name).append('=').append(cookie.value);
        }
        return sb.toString();
    }
}
