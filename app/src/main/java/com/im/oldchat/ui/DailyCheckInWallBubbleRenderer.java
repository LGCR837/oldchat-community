package com.im.oldchat.ui;

import android.app.Activity;
import android.support.v4.view.ViewCompat;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.DailyCheckInWallMessage;
import com.im.oldchat.util.ImageLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DailyCheckInWallBubbleRenderer {
    private static final float[][] POSITIONS = new float[][]{
            {0.08f, 0.08f}, {0.56f, 0.09f}, {0.18f, 0.33f},
            {0.62f, 0.37f}, {0.10f, 0.66f}, {0.54f, 0.69f}
    };

    private final Activity activity;
    private final FrameLayout container;
    private final List<DailyCheckInWallMessage> messages = new ArrayList<DailyCheckInWallMessage>();
    private final ArrayList<View> bubbleViews = new ArrayList<View>();

    DailyCheckInWallBubbleRenderer(Activity activity, FrameLayout container) {
        this.activity = activity;
        this.container = container;
    }

    void setMessages(List<DailyCheckInWallMessage> items) {
        messages.clear();
        if (items != null) {
            messages.addAll(items);
        }
        renderNext();
    }

    boolean hasMessages() {
        return !messages.isEmpty();
    }

    void renderNext() {
        if (container == null) {
            return;
        }
        if (container.getWidth() <= 0 || container.getHeight() <= 0) {
            container.post(new Runnable() {
                @Override
                public void run() {
                    renderNext();
                }
            });
            return;
        }
        container.removeAllViews();
        bubbleViews.clear();
        if (messages.isEmpty()) {
            return;
        }
        ArrayList<DailyCheckInWallMessage> pool = new ArrayList<DailyCheckInWallMessage>(messages);
        Collections.shuffle(pool);
        int count = Math.min(Math.min(pool.size(), POSITIONS.length), 10);
        for (int i = 0; i < count; i++) {
            View bubble = createBubble(pool.get(i), i);
            bubbleViews.add(bubble);
            container.addView(bubble);
            bubble.startAnimation(buildAnimation(i));
        }
    }

    private View createBubble(final DailyCheckInWallMessage item, int index) {
        LinearLayout bubble = new LinearLayout(activity);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundResource(R.drawable.bg_daily_checkin_bubble);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        int width = item.hasImage() ? dp(132) : dp(146);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int maxLeft = Math.max(0, container.getWidth() - width - dp(8));
        float[] anchor = POSITIONS[index % POSITIONS.length];
        lp.leftMargin = Math.max(dp(8), Math.min((int) (container.getWidth() * anchor[0]), maxLeft));
        lp.topMargin = Math.max(dp(8), (int) (container.getHeight() * anchor[1]));
        bubble.setLayoutParams(lp);

        TextView tvName = new TextView(activity);
        tvName.setText(item.getDisplayName());
        tvName.setTextColor(activity.getResources().getColor(R.color.color_text_primary));
        tvName.setTextSize(12f);
        tvName.setSingleLine(true);
        tvName.setEllipsize(TextUtils.TruncateAt.END);
        bubble.addView(tvName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (item.hasImage()) {
            final String openUrl = item.imageUrl;
            ImageView imageView = new ImageView(activity);
            LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
            imageLp.topMargin = dp(8);
            imageView.setLayoutParams(imageLp);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackgroundColor(activity.getResources().getColor(R.color.color_divider));
            ImageLoader.load(imageView, item.getPreviewUrl());
            imageView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ImagePreviewActivity.start(activity, openUrl);
                }
            });
            bubble.addView(imageView);
        } else {
            TextView tvText = new TextView(activity);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textLp.topMargin = dp(6);
            tvText.setLayoutParams(textLp);
            tvText.setText(item.contentText == null ? "" : item.contentText);
            tvText.setTextColor(activity.getResources().getColor(R.color.color_text_secondary));
            tvText.setTextSize(13f);
            tvText.setMaxLines(4);
            tvText.setEllipsize(TextUtils.TruncateAt.END);
            bubble.addView(tvText);
        }

        TextView tvTag = new TextView(activity);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(8);
        tvTag.setLayoutParams(tagLp);
        tvTag.setGravity(Gravity.CENTER);
        tvTag.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvTag.setText(item.hasImage() ? "图片签到" : "文字签到");
        tvTag.setTextSize(10f);
        tvTag.setTextColor(activity.getResources().getColor(R.color.color_primary));
        tvTag.setBackgroundResource(R.drawable.bg_daily_checkin_tag);
        bubble.addView(tvTag);

        ViewCompat.setAlpha(bubble, 0.96f);
        return bubble;
    }

    private AnimationSet buildAnimation(int index) {
        AnimationSet set = new AnimationSet(true);
        set.setDuration(520L);
        set.setStartOffset(index * 90L);
        set.addAnimation(new AlphaAnimation(0f, 1f));
        set.addAnimation(new TranslateAnimation(0f, 0f, dp(14), 0f));
        return set;
    }

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) (density * value + 0.5f);
    }
}
