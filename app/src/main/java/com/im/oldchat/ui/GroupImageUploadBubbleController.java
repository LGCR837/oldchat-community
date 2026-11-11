package com.im.oldchat.ui;

import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MessagePayloadBuilder;

import java.util.HashMap;
import java.util.Map;

final class GroupImageUploadBubbleController implements ChatMediaHelper.ImageUploadListener {
    interface QuoteProvider {
        MessagePayload.Quote getQuoteDraft();
    }

    interface QuoteClearer {
        void clearQuoteDraft();
    }

    private static class PendingItem {
        String body;
        int burnAfterSeconds;
    }

    private final GroupChatListHelper listHelper;
    private final GroupMessageSender messageSender;
    private final String myUid;
    private final QuoteProvider quoteProvider;
    private final QuoteClearer quoteClearer;
    private final Map<String, PendingItem> pendingItems = new HashMap<String, PendingItem>();

    GroupImageUploadBubbleController(GroupChatListHelper listHelper,
                                     GroupMessageSender messageSender,
                                     String myUid,
                                     QuoteProvider quoteProvider,
                                     QuoteClearer quoteClearer) {
        this.listHelper = listHelper;
        this.messageSender = messageSender;
        this.myUid = myUid;
        this.quoteProvider = quoteProvider;
        this.quoteClearer = quoteClearer;
    }

    @Override
    public void onQueued(String localId, String previewUri) {
        if (localId == null || localId.length() == 0) {
            return;
        }
        PendingItem item = new PendingItem();
        MessagePayload.Quote quote = quoteProvider == null ? null : quoteProvider.getQuoteDraft();
        item.body = MessagePayloadBuilder.buildBody("", quote, null, null);
        item.burnAfterSeconds = messageSender == null ? 0 : messageSender.getBurnAfterSeconds();
        pendingItems.put(localId, item);
        GroupPendingMediaHelper.enqueueImage(listHelper, myUid, localId,
                item.body, previewUri, item.burnAfterSeconds);
        if (quoteClearer != null) {
            quoteClearer.clearQuoteDraft();
        }
    }

    @Override
    public void onProgress(String localId, int percent) {
        GroupPendingMediaHelper.updateProgress(listHelper, localId, percent);
    }

    @Override
    public void onUploaded(String localId, String url, String thumbUrl) {
        onUploaded(localId, url, thumbUrl, null);
    }

    @Override
    public void onUploaded(String localId, String url, String thumbUrl, String originalUrl) {
        PendingItem item = pendingItems.remove(localId);
        if (url == null || url.length() == 0) {
            GroupPendingMessageHelper.failText(listHelper, localId);
            return;
        }
        GroupPendingMediaHelper.markUploadReady(listHelper, localId, url, thumbUrl, originalUrl);
        if (item == null || messageSender == null) {
            return;
        }
        messageSender.sendPendingMedia("image", url, thumbUrl, 0,
                item.body, item.burnAfterSeconds, localId, originalUrl);
    }

    @Override
    public void onFailed(String localId, String error) {
        pendingItems.remove(localId);
        GroupPendingMessageHelper.failText(listHelper, localId);
    }
}
