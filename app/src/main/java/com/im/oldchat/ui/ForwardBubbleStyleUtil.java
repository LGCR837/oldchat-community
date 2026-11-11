package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.support.v4.content.ContextCompat;
import android.util.TypedValue;
import android.widget.TextView;

import com.im.oldchat.R;

final class ForwardBubbleStyleUtil {
    private static final int CARD_PADDING_H_DP = 8;
    private static final int CARD_PADDING_V_DP = 6;
    private static final int CARD_STROKE_DP = 1;

    private ForwardBubbleStyleUtil() {
    }

    static void reset(TextView textView) {
        if (textView == null) {
            return;
        }
        textView.setBackgroundResource(0);
        textView.setPadding(0, 0, 0, 0);
    }

    static void applyCard(TextView textView, Context context) {
        if (textView == null || context == null) {
            return;
        }
        int h = dp(context, CARD_PADDING_H_DP);
        int v = dp(context, CARD_PADDING_V_DP);
        textView.setBackgroundDrawable(buildCardBackground(context));
        textView.setPadding(h, v, h, v);
        textView.setTextColor(ContextCompat.getColor(context, R.color.color_text_primary));
    }

    private static GradientDrawable buildCardBackground(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(ContextCompat.getColor(context, R.color.colorReplyBubble));
        float radius = resolveDimenAttrPx(context, R.attr.buttonCornerRadius);
        // 按钮圆角关闭时即使主题为 Round（气泡圆角开着）也不给转发卡片加圆角。
        if (!com.im.oldchat.util.SettingsPrefs.isRoundedButtonsEnabled(context)) {
            radius = 0f;
        }
        if (radius > 0f) {
            shape.setCornerRadius(radius);
        }
        int strokeWidth = Math.max(1, dp(context, CARD_STROKE_DP));
        int strokeColor = ContextCompat.getColor(context, R.color.color_divider);
        shape.setStroke(strokeWidth, strokeColor);
        return shape;
    }

    private static float resolveDimenAttrPx(Context context, int attrRes) {
        if (context == null || context.getTheme() == null) {
            return 0f;
        }
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attrRes, value, true)) {
            return 0f;
        }
        if (value.resourceId != 0) {
            try {
                return context.getResources().getDimension(value.resourceId);
            } catch (Throwable ignored) {
                return 0f;
            }
        }
        if (value.type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimension(value.data,
                    context.getResources().getDisplayMetrics());
        }
        return 0f;
    }

    private static int dp(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (density * dp + 0.5f);
    }
}
