package com.im.oldchat.ui.news;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RssFeedRepository {
    public static final String FEED_SOLIDOT = "solidot";
    public static final String FEED_SSPAI = "sspai";
    public static final String FEED_RUANYIFENG = "ruanyifeng";
    public static final String FEED_BBC = "bbc_zh";

    private static final String PREFS = "minimal_news_rss";
    private static final String KEY_CUSTOM = "custom_feeds";
    private static final int MAX_ITEMS = 30;
    private static final long CACHE_TTL = 3L * 60L * 1000L;
    private static final Pattern IMG_PATTERN = Pattern.compile("<img[^>]+src=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final HashMap<String, CacheEntry> CACHE = new HashMap<String, CacheEntry>();

    private RssFeedRepository() {
    }

    public static final class Feed {
        public final String id;
        public final String name;
        public final String url;
        public final boolean custom;

        public Feed(String id, String name, String url, boolean custom) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.custom = custom;
        }
    }

    private static final class CacheEntry {
        final long time;
        final List<MinimalNewsItem> items;

        CacheEntry(long time, List<MinimalNewsItem> items) {
            this.time = time;
            this.items = items;
        }
    }

    public static List<Feed> getFeeds(Context context) {
        List<Feed> feeds = new ArrayList<Feed>();
        feeds.add(new Feed(FEED_SOLIDOT, "科技", "https://www.solidot.org/index.rss", false));
        feeds.add(new Feed(FEED_SSPAI, "少数派", "https://sspai.com/feed", false));
        feeds.add(new Feed(FEED_RUANYIFENG, "开发", "https://www.ruanyifeng.com/blog/atom.xml", false));
        feeds.add(new Feed(FEED_BBC, "国际", "https://feeds.bbci.co.uk/zhongwen/simp/rss.xml", false));
        if (context == null) return feeds;
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, "[]");
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject one = array.optJSONObject(i);
                if (one == null) continue;
                String id = one.optString("id");
                String name = one.optString("name");
                String url = one.optString("url");
                if (id.length() > 0 && name.length() > 0 && isHttpUrl(url)) {
                    feeds.add(new Feed(id, name, url, true));
                }
            }
        } catch (Exception ignored) {
        }
        return feeds;
    }

    public static Feed findFeed(Context context, String id) {
        List<Feed> feeds = getFeeds(context);
        for (int i = 0; i < feeds.size(); i++) {
            if (feeds.get(i).id.equals(id)) return feeds.get(i);
        }
        return feeds.get(0);
    }

    public static Feed addCustomFeed(Context context, String name, String url) throws Exception {
        name = name == null ? "" : name.trim();
        url = url == null ? "" : url.trim();
        if (name.length() == 0 || name.length() > 30) throw new IllegalArgumentException("名称需为 1-30 个字符");
        if (!isHttpUrl(url)) throw new IllegalArgumentException("RSS 地址必须以 http:// 或 https:// 开头");
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray array;
        try { array = new JSONArray(prefs.getString(KEY_CUSTOM, "[]")); } catch (Exception e) { array = new JSONArray(); }
        String id = "custom_" + System.currentTimeMillis();
        JSONObject item = new JSONObject();
        item.put("id", id); item.put("name", name); item.put("url", url);
        array.put(item);
        prefs.edit().putString(KEY_CUSTOM, array.toString()).commit();
        return new Feed(id, name, url, true);
    }

    public static void removeCustomFeed(Context context, String id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray next = new JSONArray();
        try {
            JSONArray old = new JSONArray(prefs.getString(KEY_CUSTOM, "[]"));
            for (int i = 0; i < old.length(); i++) {
                JSONObject one = old.optJSONObject(i);
                if (one != null && !id.equals(one.optString("id"))) next.put(one);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_CUSTOM, next.toString()).commit();
        synchronized (CACHE) { CACHE.remove(id); }
    }

    public static List<MinimalNewsItem> load(Feed feed) {
        if (feed == null) return new ArrayList<MinimalNewsItem>();
        synchronized (CACHE) {
            CacheEntry cached = CACHE.get(feed.id);
            if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL) return copy(cached.items);
        }
        List<MinimalNewsItem> result;
        try {
            result = parse(feed, fetch(feed.url));
        } catch (Throwable ignored) {
            result = new ArrayList<MinimalNewsItem>();
        }
        synchronized (CACHE) { CACHE.put(feed.id, new CacheEntry(System.currentTimeMillis(), copy(result))); }
        return result;
    }

    public static void invalidate(String id) {
        synchronized (CACHE) { CACHE.remove(id); }
    }

    private static byte[] fetch(String address) throws Exception {
        HttpURLConnection connection = null;
        InputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "OldChat-RSS/1.0 Android");
            connection.setRequestProperty("Accept", "application/rss+xml,application/atom+xml,application/xml,text/xml,*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            input = connection.getInputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                if (output.size() > 1536 * 1024) break;
            }
            return output.toByteArray();
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            try { output.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }
    }

    private static List<MinimalNewsItem> parse(Feed feed, byte[] xml) throws Exception {
        List<MinimalNewsItem> items = new ArrayList<MinimalNewsItem>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml), null);
        MinimalNewsItem item = null;
        String rawContent = "";
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT && items.size() < MAX_ITEMS) {
            if (event == XmlPullParser.START_TAG) {
                String tag = local(parser.getName());
                if ("item".equals(tag) || "entry".equals(tag)) {
                    item = new MinimalNewsItem(); item.category = feed.id; item.source = feed.name; rawContent = "";
                } else if (item != null) {
                    if ("title".equals(tag)) item.title = readText(parser);
                    else if ("link".equals(tag)) {
                        String href = parser.getAttributeValue(null, "href");
                        String rel = parser.getAttributeValue(null, "rel");
                        if (href != null && (rel == null || "alternate".equals(rel))) item.articleUrl = href;
                        else if (href == null) item.articleUrl = readText(parser);
                    } else if ("content".equals(tag) && parser.getAttributeValue(null, "url") != null) {
                        String type = parser.getAttributeValue(null, "type");
                        String medium = parser.getAttributeValue(null, "medium");
                        if ((type != null && type.startsWith("image")) || "image".equals(medium)) {
                            item.imageUrl = parser.getAttributeValue(null, "url");
                        }
                    } else if ("description".equals(tag) || "summary".equals(tag) || "content".equals(tag) || "encoded".equals(tag)) {
                        String text = readText(parser); if (text.length() > rawContent.length()) rawContent = text;
                    } else if ("pubdate".equals(tag) || "published".equals(tag) || "updated".equals(tag) || "date".equals(tag)) item.publishTime = readText(parser);
                    else if ("creator".equals(tag) || "author".equals(tag)) { String author = readText(parser); if (author.length() > 0) item.source = author; }
                    else if ("thumbnail".equals(tag)) item.imageUrl = parser.getAttributeValue(null, "url");
                    else if ("enclosure".equals(tag)) { String type = parser.getAttributeValue(null, "type"); if (type != null && type.startsWith("image")) item.imageUrl = parser.getAttributeValue(null, "url"); }
                }
            } else if (event == XmlPullParser.END_TAG && item != null) {
                String tag = local(parser.getName());
                if ("item".equals(tag) || "entry".equals(tag)) {
                    item.title = clean(item.title);
                    item.summary = clip(clean(rawContent), 180);
                    if (item.imageUrl == null || item.imageUrl.length() == 0) item.imageUrl = extractImage(rawContent);
                    item.imageUrl = resolveUrl(feed.url, item.imageUrl);
                    if (item.publishTime == null || item.publishTime.length() == 0) item.publishTime = "最新";
                    if (item.title.length() > 0 && item.articleUrl != null && item.articleUrl.length() > 0) items.add(item);
                    item = null;
                }
            }
            event = parser.next();
        }
        return items;
    }

    private static String readText(XmlPullParser parser) {
        try { return parser.nextText().trim(); } catch (Exception ignored) { return ""; }
    }
    private static String local(String tag) { if (tag == null) return ""; int p = tag.indexOf(':'); return (p >= 0 ? tag.substring(p + 1) : tag).toLowerCase(); }
    private static String clean(String value) { if (value == null) return ""; try { return Html.fromHtml(value).toString().replace('\u00a0', ' ').trim(); } catch (Exception e) { return value.trim(); } }
    private static String clip(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + "..."; }
    private static String extractImage(String html) { if (html == null) return ""; Matcher m = IMG_PATTERN.matcher(html); return m.find() ? m.group(1) : ""; }
    private static String resolveUrl(String base, String value) {
        if (value == null || value.trim().length() == 0) return "";
        try {
            String decoded = value.trim().replace("&amp;", "&");
            URL resolved = new URL(new URL(base), decoded);
            String result = resolved.toString();
            return isHttpUrl(result) ? result : "";
        } catch (Exception ignored) {
            return "";
        }
    }
    private static boolean isHttpUrl(String url) { return url != null && (url.startsWith("https://") || url.startsWith("http://")); }
    private static List<MinimalNewsItem> copy(List<MinimalNewsItem> source) { List<MinimalNewsItem> out = new ArrayList<MinimalNewsItem>(); if (source != null) out.addAll(source); return out; }
}
