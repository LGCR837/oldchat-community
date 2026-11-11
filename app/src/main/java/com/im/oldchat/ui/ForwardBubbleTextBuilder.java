package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import com.im.oldchat.R;
import com.im.oldchat.util.MessagePayload;

final class ForwardBubbleTextBuilder {
    private static final int PREVIEW_COUNT = 2;
    private static final int PREVIEW_NAME_LIMIT = 12;
    private static final int PREVIEW_TEXT_LIMIT = 28;
    private static final float TITLE_SIZE_SCALE = 1.04f;
    private static final float FOOTER_SIZE_SCALE = 0.88f;

    private ForwardBubbleTextBuilder() {
    }

    static CharSequence build(Context context, MessagePayload.ForwardBundle bundle) {
        if (bundle == null || bundle.items == null || bundle.items.isEmpty()) {
            return "";
        }
        String title = resolveTitle(context, bundle.title);
        int count = bundle.items.size();
        int previewCount = Math.min(PREVIEW_COUNT, count);

        SpannableStringBuilder out = new SpannableStringBuilder();
        appendBoldTitle(out, title);
        for (int i = 0; i < previewCount; i++) {
            appendPreviewLine(out, context, bundle.items.get(i));
        }
        appendFooter(out, context, count, previewCount);
        return out;
    }

    private static void appendBoldTitle(SpannableStringBuilder out, String title) {
        int start = out.length();
        out.append(title);
        int end = out.length();
        if (end > start) {
            out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(TITLE_SIZE_SCALE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void appendPreviewLine(SpannableStringBuilder out,
                                          Context context,
                                          MessagePayload.ForwardItem item) {
        String name = resolveName(context, item);
        String text = resolveText(context, item);
        out.append('\n').append("- ");
        int nameStart = out.length();
        out.append(name);
        int nameEnd = out.length();
        if (nameEnd > nameStart) {
            out.setSpan(new StyleSpan(Typeface.BOLD), nameStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        out.append(": ").append(text);
    }

    private static void appendFooter(SpannableStringBuilder out, Context context, int count, int previewCount) {
        String footer;
        if (count > previewCount) {
            if (context != null) {
                footer = context.getString(R.string.forward_bubble_more_count, count);
            } else {
                footer = "查看" + count + "条聊天记录 >";
            }
        } else {
            if (context != null) {
                footer = context.getString(R.string.forward_bubble_tap_to_open);
            } else {
                footer = "点击查看聊天记录 >";
            }
        }
        if (footer.length() == 0) {
            return;
        }
        int start = out.length();
        out.append('\n').append(footer);
        int end = out.length();
        if (end > start) {
            out.setSpan(new RelativeSizeSpan(FOOTER_SIZE_SCALE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String resolveTitle(Context context, String rawTitle) {
        String title = toSingleLine(rawTitle);
        if (title.length() > 0) {
            return title;
        }
        if (context != null) {
            return context.getString(R.string.forward_bundle_title);
        }
        return "聊天记录";
    }

    private static String resolveName(Context context, MessagePayload.ForwardItem item) {
        String name = item == null ? "" : toSingleLine(item.fromName);
        if (name.length() == 0) {
            name = item == null ? "" : toSingleLine(item.fromUid);
        }
        if (name.length() == 0) {
            if (context != null) {
                name = context.getString(R.string.forward_sender_unknown);
            } else {
                name = "成员";
            }
        }
        return limit(name, PREVIEW_NAME_LIMIT);
    }

    private static String resolveText(Context context, MessagePayload.ForwardItem item) {
        String text = item == null ? "" : toSingleLine(item.text);
        if (text.length() == 0) {
            if (context != null) {
                text = context.getString(R.string.forward_preview_empty);
            } else {
                text = "...";
            }
        }
        return limit(text, PREVIEW_TEXT_LIMIT);
    }

    private static String toSingleLine(String value) {
        String out = safe(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        while (out.indexOf("  ") >= 0) {
            out = out.replace("  ", " ");
        }
        return out.trim();
    }

    private static String limit(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (maxLen <= 0 || value.length() <= maxLen) {
            return value;
        }
        if (maxLen <= 3) {
            return value.substring(0, maxLen);
        }
        return value.substring(0, maxLen - 3) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
