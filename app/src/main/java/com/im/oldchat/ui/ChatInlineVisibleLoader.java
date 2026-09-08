package com.im.oldchat.ui;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;

import java.util.List;

final class ChatInlineVisibleLoader {
    private ChatInlineVisibleLoader() {
    }

    static void resumeDirectVisible(Context context, RecyclerView recyclerView,
                                    List<Message> messages, int first, int last) {
        if (context == null || recyclerView == null || messages == null || messages.isEmpty()) {
            return;
        }
        int safeFirst = Math.max(0, first);
        int safeLast = Math.min(last, messages.size() - 1);
        for (int position = safeFirst; position <= safeLast; position++) {
            Message message = messages.get(position);
            if (!shouldResumeDirect(message)) {
                continue;
            }
            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (holder == null || holder.itemView == null) {
                continue;
            }
            bindInlineRow(context, holder.itemView,
                    resolveThumbUrl(message.thumb_url, message.media_url));
        }
    }

    static int resumeDirectVisibleBatch(Context context, RecyclerView recyclerView,
                                        List<Message> messages, int first, int last,
                                        int resumeOffset, int maxCount) {
        if (context == null || recyclerView == null || messages == null || messages.isEmpty()
                || maxCount <= 0) {
            return -1;
        }
        int safeFirst = Math.max(0, first);
        int safeLast = Math.min(last, messages.size() - 1);
        int matched = 0;
        int processed = 0;
        int safeOffset = Math.max(0, resumeOffset);
        for (int position = safeFirst; position <= safeLast; position++) {
            Message message = messages.get(position);
            if (!shouldResumeDirect(message)) {
                continue;
            }
            if (matched < safeOffset) {
                matched++;
                continue;
            }
            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null && holder.itemView != null) {
                bindInlineRow(context, holder.itemView,
                        resolveThumbUrl(message.thumb_url, message.media_url));
            }
            matched++;
            processed++;
            if (processed >= maxCount) {
                return matched;
            }
        }
        return -1;
    }

    static void resumeGroupVisible(Context context, GroupMessageRecyclerView listView,
                                   List<GroupMessage> messages, int first, int last) {
        if (context == null || listView == null || messages == null || messages.isEmpty()) {
            return;
        }
        int safeFirst = Math.max(0, first);
        int safeLast = Math.min(last, messages.size() - 1);
        int firstVisible = listView.getFirstVisiblePosition();
        int headerCount = listView.getHeaderViewsCount();
        for (int childIndex = 0; childIndex < listView.getChildCount(); childIndex++) {
            int position = firstVisible + childIndex - headerCount;
            if (position > safeLast) {
                break;
            }
            if (position < safeFirst) {
                continue;
            }
            GroupMessage message = messages.get(position);
            if (!shouldResumeGroup(message)) {
                continue;
            }
            View child = listView.getChildAt(childIndex);
            if (child == null) {
                continue;
            }
            bindInlineRow(context, child,
                    resolveThumbUrl(message.thumb_url, message.media_url));
        }
    }

    static int resumeGroupVisibleBatch(Context context, GroupMessageRecyclerView listView,
                                       List<GroupMessage> messages, int first, int last,
                                       int resumeOffset, int maxCount) {
        if (context == null || listView == null || messages == null || messages.isEmpty()
                || maxCount <= 0) {
            return -1;
        }
        int safeFirst = Math.max(0, first);
        int safeLast = Math.min(last, messages.size() - 1);
        int firstVisible = listView.getFirstVisiblePosition();
        int headerCount = listView.getHeaderViewsCount();
        int matched = 0;
        int processed = 0;
        int safeOffset = Math.max(0, resumeOffset);
        for (int childIndex = 0; childIndex < listView.getChildCount(); childIndex++) {
            int position = firstVisible + childIndex - headerCount;
            if (position > safeLast) {
                break;
            }
            if (position < safeFirst) {
                continue;
            }
            GroupMessage message = messages.get(position);
            if (!shouldResumeGroup(message)) {
                continue;
            }
            if (matched < safeOffset) {
                matched++;
                continue;
            }
            View child = listView.getChildAt(childIndex);
            if (child != null) {
                bindInlineRow(context, child,
                        resolveThumbUrl(message.thumb_url, message.media_url));
            }
            matched++;
            processed++;
            if (processed >= maxCount) {
                return matched;
            }
        }
        return -1;
    }

    private static void bindInlineRow(Context context, View itemView, String url) {
        if (itemView == null || url == null || url.length() == 0) {
            return;
        }
        ImageView imageView = (ImageView) itemView.findViewById(R.id.ivMessageImage);
        if (imageView == null) {
            return;
        }
        View loadingView = itemView.findViewById(R.id.pbImageLoading);
        Object currentTag = imageView.getTag();
        if (url.equals(currentTag)
                && imageView.getDrawable() != null
                && (loadingView == null || loadingView.getVisibility() != View.VISIBLE)) {
            return;
        }
        ChatInlineImagePrefetcher.bindInlineImage(context, imageView, loadingView, url, false);
    }

    private static boolean shouldResumeDirect(Message message) {
        if (message == null || !isInlineMediaType(message.msg_type)) {
            return false;
        }
        return !hasLocalPreview(message.msg_type, message.localPreviewUri,
                message.localPending, message.localFailed);
    }

    private static boolean shouldResumeGroup(GroupMessage message) {
        if (message == null || !isInlineMediaType(message.msg_type)) {
            return false;
        }
        return !hasLocalPreview(message.msg_type, message.localPreviewUri,
                message.localPending, message.localFailed);
    }

    private static boolean hasLocalPreview(String type, String localPreviewUri,
                                           boolean localPending, boolean localFailed) {
        return !isVideoType(type)
                && localPreviewUri != null
                && localPreviewUri.length() > 0
                && (localPending || localFailed);
    }

    private static String resolveThumbUrl(String thumbUrl, String mediaUrl) {
        if (thumbUrl != null && thumbUrl.length() > 0) {
            return thumbUrl;
        }
        return mediaUrl;
    }

    private static boolean isInlineMediaType(String type) {
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase();
        return "image".equals(lower) || "video".equals(lower);
    }

    private static boolean isVideoType(String type) {
        return type != null && "video".equals(type.toLowerCase());
    }
}
