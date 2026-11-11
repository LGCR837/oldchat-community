package com.im.oldchat.ui;
import android.content.Intent;
import android.widget.Toast;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliApiExtra;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;
import java.util.ArrayList;
import java.util.List;
abstract class OldViewVideoDetailSupport4 extends OldViewVideoDetailSupport0 {
    private boolean isLoadingRelated;
    private String relatedLoadedKey = "";
    protected void loadRelatedVideos(boolean force) {
        if (relatedAdapter == null) {
            return;
        }
        String key = buildCurrentVideoKey();
        if (!force && key.equals(relatedLoadedKey) && relatedAdapter.getCount() > 0) {
            return;
        }
        if (isLoadingRelated) {
            return;
        }
        isLoadingRelated = true;
        final String requestKey = key;
        final String cookie = BiliAuthStore.getCookies(this);
        String accessToken = BiliAuthStore.getAccessToken(this);
        if (BiliAuthStore.isExpired(this)) {
            accessToken = "";
        }
        final String finalAccessToken = accessToken;
        if (currentAid > 0) {
            BiliApiExtra.requestRelatedVideos(currentAid, cookie, new BiliApi.ApiCallback<List<BiliModels.RecommendItem>>() {
                @Override
                public void onSuccess(List<BiliModels.RecommendItem> data) {
                    List<BiliModels.RecommendItem> filtered = filterRelatedItems(data);
                    if (!filtered.isEmpty()) {
                        isLoadingRelated = false;
                        relatedLoadedKey = requestKey;
                        relatedAdapter.update(filtered);
                        return;
                    }
                    requestFallbackRecommend(finalAccessToken, cookie, requestKey);
                }
                @Override
                public void onError(String error) {
                    requestFallbackRecommend(finalAccessToken, cookie, requestKey);
                }
            });
            return;
        }
        requestFallbackRecommend(finalAccessToken, cookie, requestKey);
    }
    protected void openRelatedVideo(BiliModels.RecommendItem item) {
        if (item == null) {
            return;
        }
        String bvid = extractBvid(item);
        long aid = extractAid(item);
        long cid = extractCidFromUri(item.uri);
        String preloadUrl = extractPreloadUrlFromUri(item.uri);
        if ((bvid == null || bvid.length() == 0) && aid <= 0 && (preloadUrl == null || preloadUrl.length() == 0)) {
            Toast.makeText(this, "无法识别该视频", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isSameVideo(aid, bvid)) {
            return;
        }
        Intent intent = new Intent(this, OldViewVideoDetailActivity.class);
        if (bvid != null && bvid.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_BVID, bvid);
        }
        if (aid > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_AID, aid);
        }
        if (cid > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_CID, cid);
        }
        if (preloadUrl != null && preloadUrl.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_PRELOAD_URL, preloadUrl);
        }
        if (item.cover != null && item.cover.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_COVER, item.cover);
        }
        if (item.title != null && item.title.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_TITLE, item.title);
        }
        startActivity(intent);
    }
    private List<BiliModels.RecommendItem> filterRelatedItems(List<BiliModels.RecommendItem> source) {
        List<BiliModels.RecommendItem> result = new ArrayList<BiliModels.RecommendItem>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (int i = 0; i < source.size(); i++) {
            BiliModels.RecommendItem item = source.get(i);
            if (item == null) {
                continue;
            }
            String bvid = extractBvid(item);
            long aid = extractAid(item);
            if ((bvid == null || bvid.length() == 0) && aid <= 0) {
                continue;
            }
            if (isSameVideo(aid, bvid)) {
                continue;
            }
            result.add(item);
            if (result.size() >= 24) {
                break;
            }
        }
        return result;
    }
    private boolean isSameVideo(long aid, String bvid) {
        if (aid > 0 && currentAid > 0 && aid == currentAid) {
            return true;
        }
        if (bvid != null && bvid.length() > 0 && currentBvid != null && currentBvid.length() > 0) {
            return bvid.equalsIgnoreCase(currentBvid);
        }
        return false;
    }
    private String buildCurrentVideoKey() {
        String bvid = currentBvid != null ? currentBvid.trim() : "";
        return bvid + "#" + currentAid;
    }
    private String extractBvid(BiliModels.RecommendItem item) {
        if (item == null) {
            return null;
        }
        if (isValidBvid(item.param)) {
            return item.param.trim();
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
                if (isValidBvid(candidate)) {
                    return candidate;
                }
            }
            String queryBvid = getQueryParam(item.uri, "bvid");
            if (isValidBvid(queryBvid)) {
                return queryBvid;
            }
        }
        return null;
    }
    private long extractAid(BiliModels.RecommendItem item) {
        if (item == null) {
            return 0L;
        }
        if (item.args != null && item.args.aid > 0) {
            return item.args.aid;
        }
        long aid = parseAidToken(item.param);
        if (aid > 0) {
            return aid;
        }
        return parseAidFromUri(item.uri);
    }
    private long parseAidToken(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        String t = text.trim();
        if (t.startsWith("av") || t.startsWith("AV")) {
            t = t.substring(2);
        }
        if (t.startsWith("aid=")) {
            t = t.substring(4);
        }
        return parseLongSafe(t);
    }
    private long parseAidFromUri(String uri) {
        if (uri == null || uri.length() == 0) {
            return 0L;
        }
        long aid = parseLongSafe(getQueryParam(uri, "aid"));
        if (aid > 0) {
            return aid;
        }
        int idx = uri.indexOf("/av");
        if (idx >= 0) {
            return parseDigits(uri, idx + 3);
        }
        idx = uri.indexOf("video/");
        if (idx >= 0) {
            return parseDigits(uri, idx + 6);
        }
        return 0L;
    }
    private long parseLongSafe(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return 0L;
            }
        }
        try {
            return Long.parseLong(text);
        } catch (Exception e) {
            return 0L;
        }
    }
    private long parseDigits(String text, int start) {
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
    private long extractCidFromUri(String uri) {
        return parseLongSafe(getQueryParam(uri, "cid"));
    }
    private String extractPreloadUrlFromUri(String uri) {
        String url = getQueryParam(uri, "url");
        if (url == null || url.length() == 0) {
            return "";
        }
        String normalized = BiliApi.normalizeUrl(url);
        return normalized != null ? normalized : "";
    }
    private String getQueryParam(String url, String key) {
        if (url == null || key == null || key.length() == 0) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0 || q >= url.length() - 1) {
            return null;
        }
        String query = url.substring(q + 1);
        String[] parts = query.split("&");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = part.substring(0, eq);
            if (key.equals(k)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }
    private void requestFallbackRecommend(String accessToken, String cookie, final String requestKey) {
        BiliApi.requestRecommendVideos(accessToken, cookie, new BiliApi.ApiCallback<List<BiliModels.RecommendItem>>() {
            @Override
            public void onSuccess(List<BiliModels.RecommendItem> data) {
                isLoadingRelated = false;
                relatedLoadedKey = requestKey;
                relatedAdapter.update(filterRelatedItems(data));
            }
            @Override
            public void onError(String error) {
                isLoadingRelated = false;
                if (relatedAdapter.getCount() == 0) {
                    relatedAdapter.update(new ArrayList<BiliModels.RecommendItem>());
                }
                if (error != null && error.length() > 0) {
                    logError("loadRelatedVideos failed: " + error);
                }
            }
        });
    }
}
