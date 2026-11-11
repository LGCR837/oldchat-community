package com.im.oldchat.ui.news;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class MinimalNewsRepository {
    public static final String CATEGORY_HOT = "hot";
    public static final String CATEGORY_TECH = "tech";
    public static final String CATEGORY_FINANCE = "finance";
    public static final String CATEGORY_BLOCK = "block";

    private static final String URL_ZHIHU =
            "https://news-at.zhihu.com/api/4/news/latest";
    private static final String URL_ITHOME =
            "https://api.ithome.com/json/newslist/news";
    private static final String URL_CLS_FINANCE =
            "https://www.cls.cn/nodeapi/updateTelegraphList?app=CailianpressWeb&rn=18&os=web&sv=8.4.6";
    private static final String URL_CLS_BLOCK =
            "https://www.cls.cn/nodeapi/updateTelegraphList?app=CailianpressWeb&category=blockchain&rn=18&os=web&sv=8.4.6";

    private static final int MAX_ITEMS = 18;
    private static final long CACHE_TTL_MS = 2L * 60L * 1000L;

    private static final HashMap<String, List<MinimalNewsItem>> MEMORY_CACHE = new HashMap<String, List<MinimalNewsItem>>();
    private static final HashMap<String, Long> CACHE_TIME = new HashMap<String, Long>();

    private MinimalNewsRepository() {
    }

    public static synchronized void invalidate(String category) {
        String key = normalizeCategory(category);
        MEMORY_CACHE.remove(key);
        CACHE_TIME.remove(key);
    }

    public static synchronized List<MinimalNewsItem> load(String category) {
        String key = normalizeCategory(category);
        List<MinimalNewsItem> cached = readCacheIfFresh(key);
        if (cached != null && !cached.isEmpty()) {
            return cloneItems(cached);
        }

        List<MinimalNewsItem> network = new ArrayList<MinimalNewsItem>();
        try {
            if (CATEGORY_HOT.equals(key)) {
                network = parseZhihu(fetchJson(URL_ZHIHU));
            } else if (CATEGORY_TECH.equals(key)) {
                network = parseIthome(fetchJson(URL_ITHOME));
            } else if (CATEGORY_FINANCE.equals(key)) {
                network = parseCls(fetchJson(URL_CLS_FINANCE), false);
            } else {
                network = parseCls(fetchJson(URL_CLS_BLOCK), true);
            }
        } catch (Throwable ignore) {
            network = new ArrayList<MinimalNewsItem>();
        }

        if (network.isEmpty()) {
            network = MinimalNewsFallbackData.getItems(key);
        }
        writeCache(key, network);
        return cloneItems(network);
    }

    private static List<MinimalNewsItem> parseZhihu(String json) throws Exception {
        List<MinimalNewsItem> result = new ArrayList<MinimalNewsItem>();
        JSONObject root = new JSONObject(json);
        JSONArray stories = root.optJSONArray("stories");
        String date = root.optString("date");
        if (stories == null) {
            return result;
        }
        for (int i = 0; i < stories.length() && result.size() < MAX_ITEMS; i++) {
            JSONObject one = stories.optJSONObject(i);
            if (one == null) {
                continue;
            }
            MinimalNewsItem item = new MinimalNewsItem();
            item.category = CATEGORY_HOT;
            item.title = safe(one.optString("title"), "热门简讯");
            item.summary = safe(one.optString("hint"), "知乎日报实时内容");
            item.articleUrl = safe(one.optString("url"), "https://daily.zhihu.com/");
            item.source = "知乎日报";
            item.publishTime = normalizeTime(date);
            JSONArray images = one.optJSONArray("images");
            item.imageUrl = images == null ? "" : safe(images.optString(0), "");
            result.add(item);
        }
        return result;
    }

    private static List<MinimalNewsItem> parseIthome(String json) throws Exception {
        List<MinimalNewsItem> result = new ArrayList<MinimalNewsItem>();
        JSONObject root = new JSONObject(json);
        JSONArray list = root.optJSONArray("newslist");
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.length() && result.size() < MAX_ITEMS; i++) {
            JSONObject one = list.optJSONObject(i);
            if (one == null) {
                continue;
            }
            MinimalNewsItem item = new MinimalNewsItem();
            item.category = CATEGORY_TECH;
            item.title = safe(one.optString("title"), "科技简讯");
            item.summary = safe(one.optString("description"), "IT之家实时内容");
            item.articleUrl = buildIthomeUrl(one.optString("url"));
            item.source = "IT之家";
            item.publishTime = normalizeTime(one.optString("postdate"));
            item.imageUrl = safe(one.optString("image"), "");
            result.add(item);
        }
        return result;
    }

    private static List<MinimalNewsItem> parseCls(String json, boolean blockOnly) throws Exception {
        List<MinimalNewsItem> result = new ArrayList<MinimalNewsItem>();
        JSONObject root = new JSONObject(json);
        JSONObject data = root.optJSONObject("data");
        JSONArray roll = data == null ? null : data.optJSONArray("roll_data");
        if (roll == null) {
            return result;
        }
        for (int i = 0; i < roll.length() && result.size() < MAX_ITEMS; i++) {
            JSONObject one = roll.optJSONObject(i);
            if (one == null) {
                continue;
            }
            String title = safe(one.optString("title"), "");
            String brief = safe(one.optString("brief"), safe(one.optString("content"), ""));
            String all = title + " " + brief;
            if (blockOnly && !isBlockNews(all)) {
                continue;
            }
            MinimalNewsItem item = new MinimalNewsItem();
            item.category = blockOnly ? CATEGORY_BLOCK : CATEGORY_FINANCE;
            item.title = title.length() == 0 ? clip(brief, 30) : title;
            item.summary = safe(brief, "财联社快讯");
            item.articleUrl = safe(one.optString("shareurl"), "https://www.cls.cn/");
            item.source = safe(one.optString("author"), "财联社");
            item.publishTime = normalizeTime(String.valueOf(one.optLong("ctime")));
            item.imageUrl = safe(one.optString("share_img"), "");
            result.add(item);
        }
        return result;
    }

    private static boolean isBlockNews(String text) {
        String t = text == null ? "" : text;
        return t.contains("区块") || t.contains("比特") || t.contains("以太")
                || t.contains("加密") || t.contains("数字货币") || t.contains("稳定币");
    }

    private static String fetchJson(String url) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 2.3)");
            conn.setRequestProperty("Accept", "application/json,*/*");
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("http " + conn.getResponseCode());
            }
            in = conn.getInputStream();
            out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                if (out.size() > 800 * 1024) {
                    break;
                }
            }
            return out.toString("UTF-8");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignore) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String buildIthomeUrl(String raw) {
        String url = safe(raw, "");
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/")) {
            return "https://www.ithome.com" + url;
        }
        return "https://www.ithome.com/";
    }

    private static String normalizeCategory(String category) {
        String text = category == null ? "" : category.trim().toLowerCase(Locale.US);
        if (CATEGORY_TECH.equals(text) || CATEGORY_FINANCE.equals(text) || CATEGORY_BLOCK.equals(text)) {
            return text;
        }
        return CATEGORY_HOT;
    }

    private static synchronized List<MinimalNewsItem> readCacheIfFresh(String category) {
        Long time = CACHE_TIME.get(category);
        if (time == null || System.currentTimeMillis() - time.longValue() > CACHE_TTL_MS) {
            return null;
        }
        return MEMORY_CACHE.get(category);
    }

    private static synchronized void writeCache(String category, List<MinimalNewsItem> data) {
        MEMORY_CACHE.put(category, cloneItems(data));
        CACHE_TIME.put(category, Long.valueOf(System.currentTimeMillis()));
    }

    private static List<MinimalNewsItem> cloneItems(List<MinimalNewsItem> data) {
        List<MinimalNewsItem> copy = new ArrayList<MinimalNewsItem>();
        if (data == null) {
            return copy;
        }
        for (int i = 0; i < data.size(); i++) {
            MinimalNewsItem src = data.get(i);
            if (src == null) {
                continue;
            }
            MinimalNewsItem one = new MinimalNewsItem();
            one.category = src.category;
            one.title = src.title;
            one.summary = src.summary;
            one.imageUrl = src.imageUrl;
            one.articleUrl = src.articleUrl;
            one.source = src.source;
            one.publishTime = src.publishTime;
            copy.add(one);
        }
        return copy;
    }

    private static String normalizeTime(String value) {
        if (value == null) {
            return "刚刚";
        }
        String text = value.trim();
        if (text.length() == 0) {
            return "刚刚";
        }
        if (text.length() > 16) {
            return text.substring(0, 16);
        }
        return text;
    }

    private static String safe(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        return text.length() == 0 ? fallback : text;
    }

    private static String clip(String value, int max) {
        String text = safe(value, "简讯");
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
