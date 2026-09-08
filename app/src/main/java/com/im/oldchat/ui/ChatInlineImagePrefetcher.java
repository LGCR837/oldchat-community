package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.UiPerformance;

import java.util.List;
import java.util.WeakHashMap;

final class ChatInlineImagePrefetcher {
    private static final int PREFETCH_BEFORE = 2;
    private static final int PREFETCH_AFTER = 3;
    private static final int REDUCED_PREFETCH_BEFORE = 0;
    private static final int REDUCED_PREFETCH_AFTER = 1;
    private static final int IMAGE_FADE_DURATION_MS = 180;
    private static final long WINDOW_REPEAT_THROTTLE_MS = 800L;
    private static final int WINDOW_ANCHOR_SLOP = 3;
    private static final Object WINDOW_LOCK = new Object();
    private static final WeakHashMap<Object, PrefetchWindow> RECENT_WINDOWS = new WeakHashMap<Object, PrefetchWindow>();

    // 图片消息自适应尺寸（Telegram/DeltaChat 风格，dp）
    private static final int IMG_BASE_DP = 180;   // 接近方图时的基准边
    private static final int IMG_MAX_W_DP = 240;  // 横图最大宽
    private static final int IMG_MAX_H_DP = 300;  // 竖图最大高
    private static final int IMG_MIN_DP = 120;    // 最小边

    private ChatInlineImagePrefetcher() {
    }

    static void prefetchDirectAround(Context context, List<Message> messages, int anchorPosition) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (shouldSkipPrefetch(context)) {
            return;
        }
        int before = resolvePrefetchBefore(context);
        int after = resolvePrefetchAfter(context);
        int start = anchorPosition - before;
        int end = anchorPosition + after;
        if (!shouldPrefetch(messages, start, end, anchorPosition)) {
            return;
        }
        prefetchDirectRange(context, messages, start, end, anchorPosition);
    }

    static void prefetchDirectVisible(Context context, List<Message> messages, int firstVisible, int lastVisible) {
        if (messages == null || messages.isEmpty() || firstVisible < 0 || lastVisible < firstVisible) {
            return;
        }
        if (shouldSkipPrefetch(context)) {
            return;
        }
        int before = resolvePrefetchBefore(context);
        int after = resolvePrefetchAfter(context);
        int anchor = (firstVisible + lastVisible) / 2;
        int start = firstVisible - before;
        int end = lastVisible + after;
        if (!shouldPrefetch(messages, start, end, anchor)) {
            return;
        }
        prefetchDirectRange(context, messages, start, end, anchor);
    }

    static void prefetchGroupAround(Context context, List<GroupMessage> messages, int anchorPosition) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (shouldSkipPrefetch(context)) {
            return;
        }
        int before = resolvePrefetchBefore(context);
        int after = resolvePrefetchAfter(context);
        int start = anchorPosition - before;
        int end = anchorPosition + after;
        if (!shouldPrefetch(messages, start, end, anchorPosition)) {
            return;
        }
        prefetchGroupRange(context, messages, start, end, anchorPosition);
    }

    static void prefetchGroupVisible(Context context, List<GroupMessage> messages, int firstVisible, int lastVisible) {
        if (messages == null || messages.isEmpty() || firstVisible < 0 || lastVisible < firstVisible) {
            return;
        }
        if (shouldSkipPrefetch(context)) {
            return;
        }
        int before = resolvePrefetchBefore(context);
        int after = resolvePrefetchAfter(context);
        int anchor = (firstVisible + lastVisible) / 2;
        int start = firstVisible - before;
        int end = lastVisible + after;
        if (!shouldPrefetch(messages, start, end, anchor)) {
            return;
        }
        prefetchGroupRange(context, messages, start, end, anchor);
    }

    static boolean hasDirectInlineMedia(List<Message> messages, int start, int end) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(messages.size() - 1, end);
        for (int i = safeStart; i <= safeEnd; i++) {
            Message one = messages.get(i);
            if (isInlineMediaType(one == null ? null : one.msg_type)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasGroupInlineMedia(List<GroupMessage> messages, int start, int end) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(messages.size() - 1, end);
        for (int i = safeStart; i <= safeEnd; i++) {
            GroupMessage one = messages.get(i);
            if (isInlineMediaType(one == null ? null : one.msg_type)) {
                return true;
            }
        }
        return false;
    }

    static void bindInlineImage(Context context, final ImageView imageView, final View loadingView,
                                String url, boolean boundLocalPreview) {
        if (imageView == null) {
            return;
        }
        if (boundLocalPreview) {
            imageView.clearAnimation();
            if (loadingView != null) {
                loadingView.setVisibility(View.GONE);
            }
            return;
        }
        if (url == null || url.length() == 0) {
            imageView.setTag(null);
            imageView.setImageResource(R.drawable.bg_image_placeholder);
            imageView.clearAnimation();
            if (loadingView != null) {
                loadingView.setVisibility(View.GONE);
            }
            return;
        }
        String resolvedUrl = MediaUrlResolver.resolve(url);
        Object currentTag = imageView.getTag();
        boolean sameTag = resolvedUrl != null && resolvedUrl.equals(currentTag);
        if (!sameTag) {
            imageView.setTag(resolvedUrl);
            imageView.setImageDrawable(null);
            imageView.setImageResource(R.drawable.bg_image_placeholder);
            // 复用新消息：先回到基准占位尺寸，图片加载完成后按真实宽高比调整。
            resetAdaptiveImageSize(imageView);
        }
        // 聊天图片统一走 ImageLoader 的 normal 缓存，避免 WarmCache 与普通缓存
        // 同时持有可变 Bitmap 导致相邻 ImageView 在滑动复用时发生像素串图。
        final boolean immediate = ImageLoader.isCached(context, resolvedUrl);
        if (loadingView != null) {
            loadingView.setVisibility(immediate ? View.GONE : View.VISIBLE);
        }
        if (ImageLoader.isInlineLoadsPaused()) {
            ImageLoader.loadCachedOnly(imageView, url, new ImageLoader.ImageLoadListener() {
                @Override
                public void onComplete(String loadedUrl) {
                    Object tag = imageView.getTag();
                    boolean same = tag == null ? (loadedUrl == null || loadedUrl.length() == 0)
                            : tag.equals(loadedUrl);
                    if (!same) return;
                    if (loadingView != null && imageView.getDrawable() != null) {
                        loadingView.setVisibility(View.GONE);
                    }
                    imageView.clearAnimation();
                    applyAdaptiveImageSize(imageView);
                }
            });
            imageView.clearAnimation();
            return;
        }
        final boolean animateOnComplete = !immediate && shouldAnimateInlineImage(context, imageView);
        ImageLoader.load(imageView, url, new ImageLoader.ImageLoadListener() {
            @Override
            public void onComplete(String loadedUrl) {
                Object tag = imageView.getTag();
                boolean same = tag == null ? (loadedUrl == null || loadedUrl.length() == 0)
                        : tag.equals(loadedUrl);
                if (!same) return;
                if (loadingView != null) {
                    loadingView.setVisibility(View.GONE);
                }
                if (animateOnComplete) {
                    android.view.animation.Animation animation = new android.view.animation.AlphaAnimation(0.35f, 1.0f);
                    animation.setDuration(IMAGE_FADE_DURATION_MS);
                    imageView.startAnimation(animation);
                } else {
                    imageView.clearAnimation();
                }
                // 图片真实加载完成后按宽高比自适应尺寸（横图宽、竖图高）。
                applyAdaptiveImageSize(imageView);
            }
        });
    }

    /** 图片消息自适应：横图撑宽、竖图撑高，保持原始宽高比，限制在合理区间。 */
    private static void applyAdaptiveImageSize(ImageView imageView) {
        if (imageView == null) {
            return;
        }
        Drawable drawable = imageView.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) {
            return;
        }
        BitmapDrawable bd = (BitmapDrawable) drawable;
        int w = bd.getBitmap() == null ? 0 : bd.getBitmap().getWidth();
        int h = bd.getBitmap() == null ? 0 : bd.getBitmap().getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float density = imageView.getResources().getDisplayMetrics().density;
        int base = Math.round(IMG_BASE_DP * density);
        int maxW = Math.round(IMG_MAX_W_DP * density);
        int maxH = Math.round(IMG_MAX_H_DP * density);
        int min = Math.round(IMG_MIN_DP * density);

        float ratio = w / (float) h;
        int targetW;
        int targetH;
        if (ratio >= 1.0f) {
            targetW = ratio > 1.4f ? maxW : base;
            targetH = Math.round(targetW / ratio);
        } else {
            targetH = ratio < 0.7f ? maxH : base;
            targetW = Math.round(targetH * ratio);
        }
        if (targetW < min) targetW = min;
        if (targetH < min) targetH = min;
        if (targetW > maxW) targetW = maxW;
        if (targetH > maxH) targetH = maxH;

        ViewGroup.LayoutParams lp = imageView.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW;
            lp.height = targetH;
            imageView.setLayoutParams(lp);
        }
    }

    /** 复用新消息时把图片重置回基准占位尺寸（180dp），等待新图加载后重新自适应。 */
    private static void resetAdaptiveImageSize(ImageView imageView) {
        if (imageView == null) {
            return;
        }
        ViewGroup.LayoutParams lp = imageView.getLayoutParams();
        if (lp == null) {
            return;
        }
        int base = Math.round(IMG_BASE_DP * imageView.getResources().getDisplayMetrics().density);
        if (lp.width != base || lp.height != base) {
            lp.width = base;
            lp.height = base;
            imageView.setLayoutParams(lp);
        }
    }

    private static void prefetchDirectRange(Context context, List<Message> messages, int start, int end,
                                            int anchorPosition) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(messages.size() - 1, end);
        for (int i = safeStart; i <= safeEnd; i++) {
            if (i == anchorPosition) {
                continue;
            }
            Message one = messages.get(i);
            prefetchInlineMessage(context,
                    one == null ? null : one.msg_type,
                    one == null ? null : one.thumb_url,
                    one == null ? null : one.media_url);
        }
    }

    private static void prefetchGroupRange(Context context, List<GroupMessage> messages, int start, int end,
                                           int anchorPosition) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(messages.size() - 1, end);
        for (int i = safeStart; i <= safeEnd; i++) {
            if (i == anchorPosition) {
                continue;
            }
            GroupMessage one = messages.get(i);
            prefetchInlineMessage(context,
                    one == null ? null : one.msg_type,
                    one == null ? null : one.thumb_url,
                    one == null ? null : one.media_url);
        }
    }

    private static void prefetchInlineMessage(Context context, String type, String thumbUrl, String mediaUrl) {
        if (context == null || !isInlineMediaType(type)) {
            return;
        }
        String url = thumbUrl;
        if (url == null || url.length() == 0) {
            url = mediaUrl;
        }
        if (url == null || url.length() == 0) {
            return;
        }
        ImageLoader.prefetchInline(context, url);
    }

    private static boolean isInlineMediaType(String type) {
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase();
        return "image".equals(lower) || "video".equals(lower);
    }

    private static boolean shouldPrefetch(Object key, int start, int end, int anchorPosition) {
        if (key == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (WINDOW_LOCK) {
            PrefetchWindow last = RECENT_WINDOWS.get(key);
            if (last != null
                    && now - last.atMs < WINDOW_REPEAT_THROTTLE_MS
                    && Math.abs(anchorPosition - last.anchorPosition) <= WINDOW_ANCHOR_SLOP
                    && start >= last.start
                    && end <= last.end) {
                return false;
            }
            RECENT_WINDOWS.put(key, new PrefetchWindow(start, end, anchorPosition, now));
            return true;
        }
    }

    private static boolean shouldAnimateInlineImage(Context context, ImageView imageView) {
        Context targetContext = context != null ? context : (imageView == null ? null : imageView.getContext());
        return targetContext != null && !UiPerformance.shouldReduceMotion(targetContext);
    }

    private static boolean shouldSkipPrefetch(Context context) {
        return context != null && UiPerformance.shouldReduceMotion(context);
    }

    private static int resolvePrefetchBefore(Context context) {
        if (context != null && UiPerformance.shouldReduceMotion(context)) {
            return REDUCED_PREFETCH_BEFORE;
        }
        return PREFETCH_BEFORE;
    }

    private static int resolvePrefetchAfter(Context context) {
        if (context != null && UiPerformance.shouldReduceMotion(context)) {
            return REDUCED_PREFETCH_AFTER;
        }
        return PREFETCH_AFTER;
    }

    private static final class PrefetchWindow {
        final int start;
        final int end;
        final int anchorPosition;
        final long atMs;

        PrefetchWindow(int start, int end, int anchorPosition, long atMs) {
            this.start = start;
            this.end = end;
            this.anchorPosition = anchorPosition;
            this.atMs = atMs;
        }
    }
}
