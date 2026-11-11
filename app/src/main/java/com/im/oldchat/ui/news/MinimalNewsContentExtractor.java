package com.im.oldchat.ui.news;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MinimalNewsContentExtractor {
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_HTML_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 8000;

    private static final Pattern STORY_ID_PATTERN = Pattern.compile("/story/(\\d+)");
    private static final Pattern BODY_PATTERN = Pattern.compile("(?is)<body[^>]*>(.*?)</body>");

    private MinimalNewsContentExtractor() {
    }

    public static String extractFromUrl(String rawUrl) {
        String url = safe(rawUrl, "");
        if (url.length() == 0) {
            return "原文链接为空，无法提取。";
        }

        String zhihu = tryExtractZhihuStory(url);
        if (isUseful(zhihu)) {
            return zhihu;
        }

        String html = downloadText(url);
        if (html.length() == 0) {
            return "原文提取失败：网络异常或源站不可访问。";
        }

        String block = pickMainBlock(html);
        String text = htmlToPlainText(block);
        if (!isUseful(text)) {
            text = htmlToPlainText(html);
        }
        if (!isUseful(text)) {
            return "原文提取失败：该页面结构复杂，可点“外部浏览器打开”查看。";
        }
        return truncate(text);
    }

    private static String tryExtractZhihuStory(String url) {
        try {
            Matcher matcher = STORY_ID_PATTERN.matcher(url);
            if (!matcher.find()) {
                return "";
            }
            String id = matcher.group(1);
            if (id == null || id.length() == 0) {
                return "";
            }
            String api = "https://news-at.zhihu.com/api/4/news/" + id;
            String json = downloadText(api);
            if (json.length() == 0) {
                return "";
            }
            JSONObject obj = new JSONObject(json);
            String title = safe(obj.optString("title"), "");
            String imageSource = safe(obj.optString("image_source"), "");
            String bodyHtml = safe(obj.optString("body"), "");
            String body = htmlToPlainText(bodyHtml);
            if (!isUseful(body)) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (title.length() > 0) {
                sb.append(title).append("\n\n");
            }
            if (imageSource.length() > 0) {
                sb.append("图片来源：").append(imageSource).append("\n\n");
            }
            sb.append(body);
            return truncate(sb.toString());
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String downloadText(String urlText) {
        HttpURLConnection conn = null;
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 2.3; Mobile)");
            conn.setRequestProperty("Accept", "text/html,application/json,*/*");
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return "";
            }
            in = conn.getInputStream();
            out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                if (out.size() > MAX_HTML_BYTES) {
                    break;
                }
            }
            return out.toString("UTF-8");
        } catch (Throwable ignore) {
            return "";
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

    private static String pickMainBlock(String html) {
        String source = safe(html, "");
        if (source.length() == 0) {
            return "";
        }

        String[] patterns = new String[] {
                "(?is)<article[^>]*>(.*?)</article>",
                "(?is)<div[^>]+(?:id|class)=[\"'][^\"']*(?:content|article|detail|entry|正文|post)[^\"']*[\"'][^>]*>(.*?)</div>",
                "(?is)<section[^>]*>(.*?)</section>"
        };

        String best = "";
        for (int i = 0; i < patterns.length; i++) {
            Pattern p = Pattern.compile(patterns[i]);
            Matcher m = p.matcher(source);
            while (m.find()) {
                String one = safe(m.group(1), "");
                if (one.length() > best.length()) {
                    best = one;
                }
            }
        }

        if (best.length() > 0) {
            return best;
        }

        Matcher bodyMatcher = BODY_PATTERN.matcher(source);
        if (bodyMatcher.find()) {
            return safe(bodyMatcher.group(1), source);
        }
        return source;
    }

    private static String htmlToPlainText(String html) {
        String text = safe(html, "");
        if (text.length() == 0) {
            return "";
        }

        text = text.replaceAll("(?is)<!--.*?-->", " ");
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        text = text.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        text = text.replaceAll("(?is)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)</p>", "\n\n");
        text = text.replaceAll("(?is)</div>", "\n");
        text = text.replaceAll("(?is)</li>", "\n");
        text = text.replaceAll("(?is)</h[1-6]>", "\n\n");
        text = text.replaceAll("(?is)<li[^>]*>", "- ");
        text = text.replaceAll("(?is)<[^>]+>", " ");
        text = decodeEntities(text);
        text = text.replace('\r', '\n');
        text = text.replace('\t', ' ');

        String[] lines = text.split("\\n");
        StringBuilder sb = new StringBuilder();
        int emptyCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = compactSpace(lines[i]);
            if (line.length() == 0) {
                emptyCount++;
                if (emptyCount <= 1 && sb.length() > 0) {
                    sb.append("\n");
                }
                continue;
            }
            emptyCount = 0;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private static String decodeEntities(String text) {
        String result = text;
        result = result.replace("&nbsp;", " ");
        result = result.replace("&amp;", "&");
        result = result.replace("&lt;", "<");
        result = result.replace("&gt;", ">");
        result = result.replace("&quot;", "\"");
        result = result.replace("&#39;", "'");
        result = result.replace("&ldquo;", "\"");
        result = result.replace("&rdquo;", "\"");
        result = result.replace("&mdash;", "-");
        result = result.replace("&hellip;", "...");

        Pattern p = Pattern.compile("&#(\\d+);");
        Matcher m = p.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String num = m.group(1);
            char ch;
            try {
                ch = (char) Integer.parseInt(num);
            } catch (Exception e) {
                ch = ' ';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String compactSpace(String line) {
        if (line == null) {
            return "";
        }
        String t = line.replaceAll("[ ]+", " ").trim();
        if (isNoiseLine(t)) {
            return "";
        }
        return t;
    }

    private static boolean isNoiseLine(String text) {
        if (text == null || text.length() == 0) {
            return true;
        }
        if (text.length() <= 2) {
            return false;
        }
        String low = text.toLowerCase();
        return low.startsWith("copyright")
                || low.contains("cookie")
                || low.contains("javascript")
                || low.contains("window.")
                || low.contains("function(");
    }

    private static boolean isUseful(String text) {
        return text != null && text.trim().length() >= 60;
    }

    private static String truncate(String text) {
        String source = safe(text, "").trim();
        if (source.length() <= MAX_OUTPUT_CHARS) {
            return source;
        }
        return source.substring(0, MAX_OUTPUT_CHARS)
                + "\n\n[内容过长，已截断。可点“外部浏览器打开”查看完整原文]";
    }

    private static String safe(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        return text.length() == 0 ? fallback : text;
    }
}
