package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.ui.widget.BubbleTimeTextView;
import com.im.oldchat.util.MessagePayload;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 消息气泡文本的换行、链接和提及排版。 */
final class BubbleTextLayout {
    private static final char ZERO_WIDTH_SPACE = '\u200B';
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(?i)(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]{0,62})\\.)+[a-z]{2,63}"
                    + "(?::\\d{1,5})?(?:/[a-z0-9\\-._~:/?#\\[\\]@!$&'*+,;=%]*)?");
    private BubbleTextLayout() {
    }

    static void bind(TextView view, Context context, String text,
                     List<MessagePayload.Mention> mentions) {
        bind(view, context, text, mentions, null);
    }

    static void bind(TextView view, Context context, String text,
                     List<MessagePayload.Mention> mentions, String footerTime) {
        bind(view, context, text, mentions, footerTime, 0, 0);
    }

    static int resolveFooterTimeColor(boolean isMine) {
        return isMine ? 0xCCFFFFFF : 0xFF8A949E;
    }

    static void bind(TextView view, Context context, String text,
                     List<MessagePayload.Mention> mentions, String footerTime,
                     int statusDrawableRes, int statusCount) {
        bind(view, context, (com.im.oldchat.models.GroupMessage) null, text, mentions, footerTime,
                statusDrawableRes, statusCount, false);
    }

    static void bind(TextView view, Context context, com.im.oldchat.models.GroupMessage msg,
                     String text, List<MessagePayload.Mention> mentions, String footerTime,
                     int statusDrawableRes, int statusCount) {
        bind(view, context, msg, text, mentions, footerTime, statusDrawableRes, statusCount, false);
    }

    static void bind(TextView view, Context context, com.im.oldchat.models.GroupMessage msg,
                     String text, List<MessagePayload.Mention> mentions, String footerTime,
                     int statusDrawableRes, int statusCount, boolean isMine) {
        if (view == null) {
            return;
        }
        int timeColor = resolveFooterTimeColor(isMine);
        if (msg != null && msg.cachedStyledText != null) {
            view.setText(msg.cachedStyledText);
            view.setLinksClickable(msg.cachedHasLinks);
            view.setHighlightColor(0x336FACC9);
            view.setMovementMethod(msg.cachedHasLinks ? LinkMovementMethod.getInstance() : null);
            if (view instanceof BubbleTimeTextView) {
                ((BubbleTimeTextView) view).setFooter(footerTime, statusDrawableRes, statusCount, timeColor);
            }
            return;
        }
        String source = text == null ? "" : text;
        SpannableStringBuilder styled = new SpannableStringBuilder(source);
        boolean hasLink = applyDomainLinks(styled, source);
        applyMentionStyles(styled, source, mentions,
                context.getResources().getColor(R.color.colorOldchatIcon));
        addParenthesizedBreakOpportunities(styled, source);
        if (msg != null) {
            msg.cachedStyledText = styled;
            msg.cachedHasLinks = hasLink;
        }
        view.setText(styled);
        view.setLinksClickable(hasLink);
        view.setHighlightColor(0x336FACC9);
        view.setMovementMethod(hasLink ? LinkMovementMethod.getInstance() : null);
        if (view instanceof BubbleTimeTextView) {
            ((BubbleTimeTextView) view).setFooter(footerTime, statusDrawableRes, statusCount, timeColor);
        }
    }

    static void bind(TextView view, Context context, com.im.oldchat.models.Message msg,
                     String text, List<MessagePayload.Mention> mentions, String footerTime,
                     int statusDrawableRes, int statusCount) {
        bind(view, context, msg, text, mentions, footerTime, statusDrawableRes, statusCount, false);
    }

    static void bind(TextView view, Context context, com.im.oldchat.models.Message msg,
                     String text, List<MessagePayload.Mention> mentions, String footerTime,
                     int statusDrawableRes, int statusCount, boolean isMine) {
        if (view == null) {
            return;
        }
        int timeColor = resolveFooterTimeColor(isMine);
        if (msg != null && msg.cachedStyledText != null) {
            view.setText(msg.cachedStyledText);
            view.setLinksClickable(msg.cachedHasLinks);
            view.setHighlightColor(0x336FACC9);
            view.setMovementMethod(msg.cachedHasLinks ? LinkMovementMethod.getInstance() : null);
            if (view instanceof BubbleTimeTextView) {
                ((BubbleTimeTextView) view).setFooter(footerTime, statusDrawableRes, statusCount, timeColor);
            }
            return;
        }
        String source = text == null ? "" : text;
        SpannableStringBuilder styled = new SpannableStringBuilder(source);
        boolean hasLink = applyDomainLinks(styled, source);
        applyMentionStyles(styled, source, mentions,
                context.getResources().getColor(R.color.colorOldchatIcon));
        addParenthesizedBreakOpportunities(styled, source);
        if (msg != null) {
            msg.cachedStyledText = styled;
            msg.cachedHasLinks = hasLink;
        }
        view.setText(styled);
        view.setLinksClickable(hasLink);
        view.setHighlightColor(0x336FACC9);
        view.setMovementMethod(hasLink ? LinkMovementMethod.getInstance() : null);
        if (view instanceof BubbleTimeTextView) {
            ((BubbleTimeTextView) view).setFooter(footerTime, statusDrawableRes, statusCount, timeColor);
        }
    }

    static void bindStyled(TextView view, Context context, CharSequence content, String footerTime,
                           int statusDrawableRes, int statusCount) {
        bindStyled(view, context, content, footerTime, statusDrawableRes, statusCount, false);
    }

    static void bindStyled(TextView view, Context context, CharSequence content, String footerTime,
                           int statusDrawableRes, int statusCount, boolean isMine) {
        if (view == null) {
            return;
        }
        int timeColor = resolveFooterTimeColor(isMine);
        SpannableStringBuilder styled = new SpannableStringBuilder(content == null ? "" : content);
        view.setText(styled);
        view.setLinksClickable(false);
        view.setMovementMethod(null);
        if (view instanceof BubbleTimeTextView) {
            ((BubbleTimeTextView) view).setFooter(footerTime, statusDrawableRes, statusCount, timeColor);
        }
    }

    private static boolean applyDomainLinks(SpannableStringBuilder styled, String source) {
        if (source == null || source.length() < 4) {
            return false;
        }
        // 快速前置过滤：绝大多数普通聊天消息不含点号或协议头，跳过耗时的正则回溯
        if (source.indexOf('.') < 0 && source.indexOf("://") < 0) {
            return false;
        }
        Matcher matcher = DOMAIN_PATTERN.matcher(source);
        boolean found = false;
        while (matcher.find()) {
            String shown = matcher.group();
            String target = shown.regionMatches(true, 0, "http://", 0, 7)
                    || shown.regionMatches(true, 0, "https://", 0, 8)
                    ? shown : "https://" + shown;
            // 先规范化，避免包含空格等异常字符时交给浏览器错误解析。
            target = Uri.parse(target).toString();
            styled.setSpan(new UnderlinedURLSpan(target), matcher.start(), matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            found = true;
        }
        return found;
    }

    /** URL 只添加下划线，保留消息气泡原本的文字颜色。 */
    private static final class UnderlinedURLSpan extends URLSpan {
        UnderlinedURLSpan(String url) {
            super(url);
        }

        @Override
        public void updateDrawState(TextPaint drawState) {
            if (drawState != null) {
                drawState.setUnderlineText(true);
            }
        }
    }

    private static void applyMentionStyles(SpannableStringBuilder styled, String source,
                                           List<MessagePayload.Mention> mentions, int color) {
        if (mentions != null) {
            for (int i = 0; i < mentions.size(); i++) {
                MessagePayload.Mention mention = mentions.get(i);
                if (mention == null) {
                    continue;
                }
                String name = mention.name == null || mention.name.trim().length() == 0
                        ? (mention.uid != null && mention.uid.length() > 0 ? mention.uid : mention.ncuid)
                        : mention.name.trim();
                if (name == null || name.length() == 0) {
                    continue;
                }
                String token = "@" + name;
                int from = 0;
                while ((from = source.indexOf(token, from)) >= 0) {
                    applyMentionSpan(styled, from, from + token.length(), color);
                    from += token.length();
                }
            }
        }
    }

    private static void applyMentionSpan(SpannableStringBuilder text, int start, int end, int color) {
        text.setSpan(new ForegroundColorSpan(color), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final String ZERO_WIDTH_SPACE_STR = "\u200B";

    private static void addParenthesizedBreakOpportunities(SpannableStringBuilder styled,
                                                            String source) {
        if (source == null || (source.indexOf('(') < 0 && source.indexOf('（') < 0
                && source.indexOf(')') < 0 && source.indexOf('）') < 0)) {
            return;
        }
        boolean inAsciiParentheses = false;
        // 从尾部插入，保持所有原始索引和已设置 span 的范围正确。
        for (int i = source.length() - 1; i >= 0; i--) {
            char current = source.charAt(i);
            if (current == ')' || current == '）') {
                inAsciiParentheses = true;
                continue;
            }
            if (current == '(' || current == '（') {
                inAsciiParentheses = false;
                continue;
            }
            if (inAsciiParentheses && current > 0x20 && current < 0x7f
                    && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next != ')' && next != '）' && next > 0x20 && next < 0x7f) {
                    styled.insert(i + 1, ZERO_WIDTH_SPACE_STR);
                }
            }
        }
    }
}
