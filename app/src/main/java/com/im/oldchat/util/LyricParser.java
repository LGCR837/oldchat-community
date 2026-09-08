package com.im.oldchat.util;

import com.im.oldchat.models.LyricLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricParser {

    private static final Pattern PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*?)");
    private static final Pattern TTML_PARAGRAPH_PATTERN = Pattern.compile("<p\\b([^>]*)>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TTML_ATTRIBUTE_PATTERN = Pattern.compile("(begin|end|dur)=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TTML_TRANSLATION_PATTERN = Pattern.compile("<span\\b[^>]*(ttm:role|role|class)=\"([^\"]*translation[^\"]*)\"[^>]*>(.*?)</span>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TTML_SPAN_PATTERN = Pattern.compile("<span\\b([^>]*)>(.*?)</span>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TTML_TAG_PATTERN = Pattern.compile("<[^>]+>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ENTITY_PATTERN =
            Pattern.compile("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);");
    public static final String TRANSLATION_SEPARATOR = "<EM_TRANSLATION>";

    public static List<LyricLine> parse(String lrc) {
        List<LyricLine> list = new ArrayList<LyricLine>();
        if (lrc == null || lrc.length() == 0) {
            return list;
        }
        if (looksLikeTtml(lrc)) {
            return parseTtml(lrc);
        }
        String[] lines = lrc.split("\\n");
        for (String line : lines) {
            line = line.trim();
            // 同一行可包含多个时间戳（同句重复唱）：分别生成多条，但共享文本
            Matcher m = PATTERN.matcher(line);
            int lastTextStart = 0;
            boolean hasAny = false;
            while (m.find()) {
                hasAny = true;
                // 最后一个时间戳之后的文本才是歌词正文
                lastTextStart = m.end();
            }
            if (!hasAny) {
                continue;
            }
            String text = line.substring(lastTextStart);
            if (text != null) {
                text = text.trim().replace(TRANSLATION_SEPARATOR, "\n");
                if (text.indexOf('\\') >= 0) {
                    text = text.replace("\\n", "\n").replace("\\r", "");
                }
            }
            if (text == null || text.length() == 0) {
                continue;
            }
            // 重新匹配所有时间戳生成条目
            Matcher m2 = PATTERN.matcher(line);
            while (m2.find()) {
                long time = parseTime(m2.group(1), m2.group(2), m2.group(3));
                list.add(new LyricLine(time, text));
            }
        }
        Collections.sort(list, new Comparator<LyricLine>() {
            @Override
            public int compare(LyricLine o1, LyricLine o2) {
                if (o1.getTime() < o2.getTime()) return -1;
                if (o1.getTime() > o2.getTime()) return 1;
                return 0;
            }
        });
        // 相邻行时间戳 ≤250ms 视为同时（合唱/双声部），共享同一活动区间
        markOverlappingLrcLines(list);
        return list;
    }

    /** LRC 没有行区间：把时间戳接近（≤250ms）的相邻行标记为重叠组。 */
    private static void markOverlappingLrcLines(List<LyricLine> lines) {
        final long OVERLAP_TOLERANCE_MS = 250L;
        int nextGroup = 1;
        for (int i = 1; i < lines.size(); i++) {
            LyricLine prev = lines.get(i - 1);
            LyricLine cur = lines.get(i);
            if (cur.getTime() - prev.getTime() <= OVERLAP_TOLERANCE_MS) {
                long groupEnd = Math.max(prev.getEndTime(), cur.getEndTime());
                // 共享同一结束区间，组号传播（连续多行重叠时 A/B/C 同组）
                prev.setEndTime(groupEnd);
                cur.setEndTime(groupEnd);
                if (prev.getOverlapGroup() == 0) {
                    prev.setOverlapGroup(nextGroup++);
                }
                cur.setOverlapGroup(prev.getOverlapGroup());
            }
        }
    }

    private static boolean looksLikeTtml(String lyricText) {
        String text = lyricText.trim();
        return text.startsWith("<?xml") || text.startsWith("<tt") || text.contains("<tt ") || text.contains("<p ");
    }

    private static List<LyricLine> parseTtml(String ttml) {
        List<LyricLine> list = new ArrayList<LyricLine>();
        Matcher paragraphMatcher = TTML_PARAGRAPH_PATTERN.matcher(ttml);
        long lastBeginMs = 0L;
        while (paragraphMatcher.find()) {
            String attrs = paragraphMatcher.group(1);
            String body = paragraphMatcher.group(2);
            long beginMs = parseTtmlBegin(attrs);
            long endMs = parseTtmlAttrTime(attrs, "end");
            if (beginMs < 0) {
                // Apple 风格 <p dur=".."> 无 begin：用上一行 begin + dur 推导
                long durMs = parseTtmlAttrTime(attrs, "dur");
                if (durMs > 0) {
                    beginMs = lastBeginMs;
                    endMs = lastBeginMs + durMs;
                }
            }
            if (beginMs >= 0) {
                lastBeginMs = beginMs;
            }
            if (beginMs < 0) {
                continue;
            }
            String translation = extractTtmlTranslation(body);
            List<LyricLine.TimedWord> timedWords = extractTimedWords(body);
            String mainText = buildTimedWordText(timedWords);
            if (mainText.length() == 0) {
                mainText = cleanupTtmlText(removeTranslationSpan(body));
            }
            if (mainText.length() == 0 && translation.length() == 0) {
                continue;
            }
            if (translation.length() > 0 && mainText.length() > 0 && !translation.equals(mainText)) {
                mainText = mainText + "\n" + translation;
            } else if (mainText.length() == 0) {
                mainText = translation;
            }
            if (endMs < beginMs && timedWords != null && !timedWords.isEmpty()) {
                endMs = timedWords.get(timedWords.size() - 1).getEndTime();
            }
            if (endMs < beginMs) {
                endMs = beginMs;
            }
            list.add(new LyricLine(beginMs, endMs, mainText, timedWords));
        }
        Collections.sort(list, new Comparator<LyricLine>() {
            @Override
            public int compare(LyricLine o1, LyricLine o2) {
                if (o1.getTime() < o2.getTime()) return -1;
                if (o1.getTime() > o2.getTime()) return 1;
                return 0;
            }
        });
        return list;
    }

    private static long parseTtmlBegin(String attrs) {
        Matcher matcher = TTML_ATTRIBUTE_PATTERN.matcher(attrs == null ? "" : attrs);
        while (matcher.find()) {
            if ("begin".equalsIgnoreCase(matcher.group(1))) {
                return parseTtmlTime(matcher.group(2));
            }
        }
        return -1;
    }

    private static long parseTtmlTime(String timeText) {
        if (timeText == null) {
            return -1;
        }
        String value = timeText.trim();
        try {
            if (value.endsWith("ms")) {
                return Long.parseLong(value.substring(0, value.length() - 2));
            }
            if (value.endsWith("s")) {
                return (long) (Float.parseFloat(value.substring(0, value.length() - 1)) * 1000f);
            }
            if (value.matches("\\d+(\\.\\d+)?")) {
                return (long) (Float.parseFloat(value) * 1000f);
            }
            String[] parts = value.split(":");
            if (parts.length == 3) {
                long hour = Long.parseLong(parts[0]);
                long minute = Long.parseLong(parts[1]);
                float second = Float.parseFloat(parts[2]);
                return (long) (((hour * 3600L) + (minute * 60L)) * 1000L + second * 1000f);
            }
            if (parts.length == 2) {
                long minute = Long.parseLong(parts[0]);
                float second = Float.parseFloat(parts[1]);
                return (long) ((minute * 60L) * 1000L + second * 1000f);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String extractTtmlTranslation(String body) {
        Matcher matcher = TTML_TRANSLATION_PATTERN.matcher(body == null ? "" : body);
        if (matcher.find()) {
            return cleanupTtmlText(matcher.group(3));
        }
        return "";
    }

    private static String removeTranslationSpan(String body) {
        if (body == null) {
            return "";
        }
        return TTML_TRANSLATION_PATTERN.matcher(body).replaceAll("");
    }

    private static String cleanupTtmlText(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replaceAll("(?i)<br\\s*/?>", "\n");
        result = TTML_TAG_PATTERN.matcher(result).replaceAll("");
        result = decodeXmlEntities(result);
        // 兼容字面 "\n"（反斜杠+n）分隔，转换为真实换行
        if (result.indexOf('\\') >= 0) {
            result = result.replace("\\n", "\n").replace("\\r", "");
        }
        return result.trim();
    }

    /**
     * 解码 XML/TTML 文本中的字符实体。
     * 支持数字字符引用（十进制 &#39; / 十六进制 &#x27;）和常用命名实体
     * （&amp; &lt; &gt; &quot; &apos; &nbsp; 等）。未知实体原样保留。
     * 一次遍历完成，避免 "&amp;lt;" 被二次解码成 "<"。
     */
    private static String decodeXmlEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = XML_ENTITY_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String entity = matcher.group(1);
            String decoded = decodeEntity(entity);
            out.append(decoded != null ? decoded : matcher.group(0));
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static String decodeEntity(String entity) {
        if (entity == null || entity.length() == 0) {
            return null;
        }
        char first = entity.charAt(0);
        if (first == '#') {
            try {
                int codePoint;
                if (entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                    codePoint = Integer.parseInt(entity.substring(2), 16);
                } else {
                    codePoint = Integer.parseInt(entity.substring(1), 10);
                }
                // 过滤非法/控制码点，仅还原可显示的 Unicode 字符
                if (codePoint > 0 && codePoint <= 0x10FFFF
                        && !(codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                    return new String(Character.toChars(codePoint));
                }
            } catch (NumberFormatException ignored) {
            }
            return null;
        }
        if (entity.equals("amp")) return "&";
        if (entity.equals("lt")) return "<";
        if (entity.equals("gt")) return ">";
        if (entity.equals("quot")) return "\"";
        if (entity.equals("apos")) return "'";
        if (entity.equals("nbsp")) return "\u00A0";
        if (entity.equals("ensp")) return "\u2002";
        if (entity.equals("emsp")) return "\u2003";
        if (entity.equals("copy")) return "\u00A9";
        if (entity.equals("reg")) return "\u00AE";
        if (entity.equals("trade")) return "\u2122";
        if (entity.equals("hellip")) return "\u2026";
        if (entity.equals("mdash")) return "\u2014";
        if (entity.equals("ndash")) return "\u2013";
        if (entity.equals("lsquo")) return "\u2018";
        if (entity.equals("rsquo")) return "\u2019";
        if (entity.equals("ldquo")) return "\u201C";
        if (entity.equals("rdquo")) return "\u201D";
        if (entity.equals("middot")) return "\u00B7";
        if (entity.equals("bull")) return "\u2022";
        if (entity.equals("eacute")) return "\u00E9";
        if (entity.equals("egrave")) return "\u00E8";
        if (entity.equals("agrave")) return "\u00E0";
        if (entity.equals("ccedil")) return "\u00E7";
        if (entity.equals("uuml")) return "\u00FC";
        return null;
    }

    private static List<LyricLine.TimedWord> extractTimedWords(String body) {
        ArrayList<LyricLine.TimedWord> words = new ArrayList<LyricLine.TimedWord>();
        Matcher matcher = TTML_SPAN_PATTERN.matcher(removeTranslationSpan(body));
        while (matcher.find()) {
            String attrs = matcher.group(1);
            String wordText = cleanupTtmlText(matcher.group(2));
            if (wordText.length() == 0) {
                continue;
            }
            long start = parseTtmlAttrTime(attrs, "begin");
            long end = parseTtmlAttrTime(attrs, "end");
            if (start < 0) {
                continue;
            }
            if (end < start) {
                end = start + 220L;
            }
            words.add(new LyricLine.TimedWord(wordText, start, end));
        }
        return words;
    }

    private static long parseTtmlAttrTime(String attrs, String key) {
        Matcher matcher = TTML_ATTRIBUTE_PATTERN.matcher(attrs == null ? "" : attrs);
        while (matcher.find()) {
            if (key.equalsIgnoreCase(matcher.group(1))) {
                return parseTtmlTime(matcher.group(2));
            }
        }
        return -1;
    }

    private static String buildTimedWordText(List<LyricLine.TimedWord> timedWords) {
        if (timedWords == null || timedWords.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < timedWords.size(); i++) {
            String word = timedWords.get(i).getText();
            if (word == null || word.length() == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(word);
        }
        return builder.toString().trim();
    }

    private static long parseTime(String min, String sec, String frac) {
        try {
            int minute = Integer.parseInt(min);
            float second = Float.parseFloat(sec);
            long millis = 0L;
            if (frac != null && frac.length() > 0) {
                // 按位数补零到毫秒："5"→500, "05"→50, "000"→0
                StringBuilder ms = new StringBuilder(frac);
                while (ms.length() < 3) {
                    ms.append('0');
                }
                millis = Long.parseLong(ms.substring(0, 3));
            }
            return (long) ((minute * 60 + second) * 1000) + millis;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int findCurrentLine(List<LyricLine> list, long time) {
        if (list == null || list.size() == 0) return -1;
        int left = 0;
        int right = list.size() - 1;
        while (left <= right) {
            int mid = (left + right) / 2;
            LyricLine line = list.get(mid);
            if (line.getTime() <= time) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return right;
    }
}
