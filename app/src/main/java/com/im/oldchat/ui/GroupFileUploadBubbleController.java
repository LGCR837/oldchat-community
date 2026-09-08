package com.im.oldchat.ui;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.MessagePayload;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class GroupFileUploadBubbleController implements ChatFileUploadHelper.FileUploadListener {
    interface QuoteProvider {
        MessagePayload.Quote getQuoteDraft();
    }

    private static class PendingItem {
        String localId;
        String fileName;
        long fileSize;
        GroupMessage message;
    }

    private final List<GroupMessage> messageList;
    private final HashSet<String> messageIds;
    private final GroupMessageAdapter adapter;
    private final GroupChatListHelper listHelper;
    private final GroupMessageSender messageSender;
    private final String myUid;
    private final QuoteProvider quoteProvider;
    private final Map<String, PendingItem> pendingItems = new HashMap<String, PendingItem>();

    GroupFileUploadBubbleController(List<GroupMessage> messageList,
                                    HashSet<String> messageIds,
                                    GroupMessageAdapter adapter,
                                    GroupChatListHelper listHelper,
                                    GroupMessageSender messageSender,
                                    String myUid,
                                    QuoteProvider quoteProvider) {
        this.messageList = messageList;
        this.messageIds = messageIds;
        this.adapter = adapter;
        this.listHelper = listHelper;
        this.messageSender = messageSender;
        this.myUid = myUid;
        this.quoteProvider = quoteProvider;
    }

    @Override
    public void onQueued(String localId, String fileName, long fileSize) {
        PendingItem item = new PendingItem();
        item.localId = localId;
        item.fileName = fileName;
        item.fileSize = fileSize;
        item.message = new GroupMessage();
        item.message.id = localId;
        item.message.from_uid = myUid;
        item.message.msg_type = "resource";
        item.message.localPending = true;
        item.message.localRequestId = localId;
        item.message.media_url = "";
        item.message.thumb_url = "";
        item.message.duration_ms = 0;
        item.message.created_at = System.currentTimeMillis() / 1000L;
        item.message.read_count = 0;
        item.message.localUploadState = ChatFileUploadHelper.STATE_HASHING;
        item.message.localProgress = 0;
        item.message.body = FileUploadUiTextUtil.buildBody(fileName, fileSize,
                FileUploadUiTextUtil.buildStateLine(ChatFileUploadHelper.STATE_HASHING, 0));

        pendingItems.put(localId, item);
        messageList.add(item.message);
        if (localId != null && localId.length() > 0) {
            messageIds.add(localId);
        }
        refreshList();
    }

    @Override
    public void onStatus(String localId, String fileName, long fileSize, String state, int percent) {
        PendingItem item = pendingItems.get(localId);
        if (item == null || item.message == null) {
            return;
        }
        if (fileSize > 0) {
            item.fileSize = fileSize;
        }
        item.message.localUploadState = state;
        item.message.localProgress = percent;
        item.message.body = FileUploadUiTextUtil.buildBody(item.fileName, item.fileSize,
                FileUploadUiTextUtil.buildStateLine(state, percent));
        refreshItem(item);
    }

    @Override
    public void onProgress(String localId, String fileName, long fileSize, int percent) {
        onStatus(localId, fileName, fileSize, ChatFileUploadHelper.STATE_UPLOADING, percent);
    }

    @Override
    public void onUploaded(String localId, String fileName, long fileSize, String downloadUrl) {
        PendingItem item = pendingItems.get(localId);
        if (item != null && item.message != null) {
            item.message.localUploadState = ChatFileUploadHelper.STATE_SENDING;
            item.message.localProgress = 100;
            item.message.body = FileUploadUiTextUtil.buildBody(item.fileName, item.fileSize, "上传完成，正在发送");
            refreshItem(item);
        }
        if (messageSender == null || downloadUrl == null || downloadUrl.length() == 0) {
            return;
        }
        pendingItems.remove(localId);
        MessagePayload.Quote quote = quoteProvider == null ? null : quoteProvider.getQuoteDraft();
        messageSender.sendFileResource(downloadUrl, fileName, fileSize, quote, localId);
    }

    @Override
    public void onFailed(String localId, String fileName, long fileSize, String error) {
        PendingItem item = pendingItems.get(localId);
        if (item == null || item.message == null) {
            return;
        }
        String text = error == null || error.length() == 0 ? "上传失败" : error;
        if (!text.startsWith("上传失败")) {
            text = "上传失败";
        }
        item.message.localUploadState = ChatFileUploadHelper.STATE_FAILED;
        item.message.localProgress = -1;
        item.message.body = FileUploadUiTextUtil.buildBody(item.fileName, item.fileSize, text);
        refreshItem(item);
    }

    private void removePending(String localId) {
        PendingItem item = pendingItems.remove(localId);
        if (item == null || item.message == null) {
            return;
        }
        messageList.remove(item.message);
        if (item.message.id != null && item.message.id.length() > 0) {
            messageIds.remove(item.message.id);
        }
        refreshList();
    }

    private void refreshItem(PendingItem item) {
        if (adapter == null || item == null || item.message == null) {
            return;
        }
        int index = messageList.indexOf(item.message);
        if (index >= 0) {
            adapter.notifyRecyclerItemChanged(index);
        }
    }

    private void refreshList() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (listHelper != null) {
            listHelper.scrollToBottom();
        }
    }
}
