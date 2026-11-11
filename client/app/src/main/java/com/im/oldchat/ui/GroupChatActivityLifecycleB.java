package com.im.oldchat.ui;

import android.content.Intent;
import android.net.Uri;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMessage;

abstract class GroupChatActivityLifecycleB extends GroupChatActivitySupport2 {
    protected static final long INLINE_IMAGE_RESUME_DELAY_MS = 180L;
    protected static final long PREVIEW_RETURN_SYNC_DELAY_MS = 260L;
    protected static final long PREVIEW_RETURN_PREFETCH_BLOCK_MS = 2200L;
    protected static final long PREVIEW_RETURN_INLINE_BATCH_DELAY_MS = 90L;
    protected static final int PREVIEW_RETURN_INLINE_BATCH_SIZE = 2;
    protected boolean inlineImageScrollIdle = true;
    protected boolean inlineLoadsPausedByScroll = false;
    protected long previewReturnInlineResumeBlockedUntilMs = 0L;
    protected long previewReturnPrefetchBlockedUntilMs = 0L;
    protected int pendingInlineResumeFirst = -1;
    protected int pendingInlineResumeLast = -1;
    protected int pendingInlineResumeOffset = -1;
    protected final Runnable resumeInlineImagesRunnable = new Runnable() {
        @Override
        public void run() {
            if (lvMessages == null || !inlineImageScrollIdle) {
                return;
            }
            if (!inlineLoadsPausedByScroll) {
                return;
            }
            inlineLoadsPausedByScroll = false;
            com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(false);
            int headerCount = lvMessages.getHeaderViewsCount();
            int first = lvMessages.getFirstVisiblePosition() - headerCount;
            int last = lvMessages.getLastVisiblePosition() - headerCount;
            int visibleCount = lvMessages.getChildCount();
            if (first < 0) {
                first = 0;
            }
            if (last < first) {
                last = first - 1;
            }
            if (visibleCount > 0
                    && ChatInlineImagePrefetcher.hasGroupInlineMedia(messageList, first, last)) {
                if (!isPreviewReturnPrefetchBlocked()) {
                    ChatInlineImagePrefetcher.prefetchGroupVisible(GroupChatActivityLifecycleB.this,
                            messageList, first, last);
                }
            }
            if (adapter != null && visibleCount > 0
                    && ChatInlineImagePrefetcher.hasGroupInlineMedia(messageList, first, last)) {
                resumeGroupVisibleImages(first, last);
            }
        }
    };
    protected final Runnable resumeInlineImagesBatchRunnable = new Runnable() {
        @Override
        public void run() {
            if (lvMessages == null || !inlineImageScrollIdle) {
                return;
            }
            if (pendingInlineResumeFirst < 0 || pendingInlineResumeLast < pendingInlineResumeFirst) {
                cancelInlineResumeBatch();
                return;
            }
            int nextOffset = ChatInlineVisibleLoader.resumeGroupVisibleBatch(
                    GroupChatActivityLifecycleB.this, lvMessages, messageList,
                    pendingInlineResumeFirst, pendingInlineResumeLast,
                    pendingInlineResumeOffset, PREVIEW_RETURN_INLINE_BATCH_SIZE);
            if (nextOffset < 0) {
                cancelInlineResumeBatch();
                return;
            }
            pendingInlineResumeOffset = nextOffset;
            lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
            lvMessages.postDelayed(resumeInlineImagesBatchRunnable, PREVIEW_RETURN_INLINE_BATCH_DELAY_MS);
        }
    };

    protected void scheduleInlineImageResume() {
        if (lvMessages == null) {
            return;
        }
        if (!inlineLoadsPausedByScroll) {
            return;
        }
        com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(true);
        lvMessages.removeCallbacks(resumeInlineImagesRunnable);
        long delay = INLINE_IMAGE_RESUME_DELAY_MS;
        long now = System.currentTimeMillis();
        if (previewReturnInlineResumeBlockedUntilMs > now) {
            delay = Math.max(delay, previewReturnInlineResumeBlockedUntilMs - now);
        }
        lvMessages.postDelayed(resumeInlineImagesRunnable, delay);
    }

    protected void resumeGroupVisibleImages(int first, int last) {
        if (isPreviewReturnPrefetchBlocked()) {
            pendingInlineResumeFirst = first;
            pendingInlineResumeLast = last;
            pendingInlineResumeOffset = 0;
            if (lvMessages != null) {
                lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
                lvMessages.post(resumeInlineImagesBatchRunnable);
            }
            return;
        }
        cancelInlineResumeBatch();
        ChatInlineVisibleLoader.resumeGroupVisible(GroupChatActivityLifecycleB.this,
                lvMessages, messageList, first, last);
    }

    protected void cancelInlineResumeBatch() {
        pendingInlineResumeFirst = -1;
        pendingInlineResumeLast = -1;
        pendingInlineResumeOffset = -1;
        if (lvMessages != null) {
            lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        lastDraft = ChatDraftHelper.saveDraftFromInput(this, etInput, getDraftKey(), lastDraft);
        updateRecentFromLastMessage();
        cancelForwardMultiSelect();
        if (listHelper != null) {
            listHelper.recordScrollPosition();
        }
        if (mentionDialog != null && mentionDialog.isShowing()) {
            mentionDialog.dismiss();
        }
        if (mediaHelper != null) {
            mediaHelper.onPause();
        }
        if (adapter != null) {
            adapter.stopVoice();
        }
        com.im.oldchat.api.WSManager.getInstance().removeListener(wsListener);
        com.im.oldchat.api.WSManager.getInstance().removeListener(backDotListener);
        if (typingStatusManager != null && groupId != null && !groupId.isEmpty()) {
            if (isTyping) {
                typingStatusManager.stopTyping(this, token, groupId, true);
                isTyping = false;
            }
            typingStatusManager.stopCheckingTyping(groupId);
            typingStatusManager.unregisterListener(groupId);
        }
        typingHandler.removeCallbacks(typingIdleRunnable);
        clearTypingState();
        inlineImageScrollIdle = true;
        inlineLoadsPausedByScroll = false;
        if (lvMessages != null) {
            lvMessages.removeCallbacks(resumeInlineImagesRunnable);
            lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
        }
        pendingInlineResumeFirst = -1;
        pendingInlineResumeLast = -1;
        pendingInlineResumeOffset = -1;
        com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(false);
        previewReturnInlineResumeBlockedUntilMs = 0L;
        previewReturnPrefetchBlockedUntilMs = 0L;
        lastPauseAtMs = System.currentTimeMillis();
        needsRefreshOnResume = !ImagePreviewReturnTracker.isLaunchPending();
    }

    protected boolean isPreviewReturnPrefetchBlocked() {
        return previewReturnPrefetchBlockedUntilMs > System.currentTimeMillis();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (v == etInput) {
            ChatInputMenuHelper.fillContextMenu(menu);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (ChatInputMenuHelper.handleContextItem(etInput, item)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (mediaHelper != null && mediaHelper.handlePermissionsResult(requestCode, grantResults)) {
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GROUP_MANAGE && resultCode == RESULT_OK) {
            finish();
            return;
        }
        if (requestCode == REQ_GROUP_MANAGE && resultCode == RESULT_FIRST_USER) {
            if (data != null) {
                String name = data.getStringExtra("group_name");
                String avatar = data.getStringExtra("group_avatar");
                if (name != null && !name.isEmpty() && tvTitle != null) {
                    groupName = name;
                    tvTitle.setText(name);
                }
                if (avatar != null && !avatar.isEmpty()) {
                    groupAvatar = avatar;
                    ImageView ivAvatar = (ImageView) findViewByIdCompat(R.id.ivGroupAvatar);
                    if (ivAvatar != null) {
                        com.im.oldchat.util.ImageLoader.loadAvatarCacheFirstTryOnce(ivAvatar, groupAvatar);
                    }
                }
            }
            return;
        }
        if (requestCode == REQ_PICK_EMOJI && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(EmojiPickerActivity.EXTRA_EMOJI_PATH);
            boolean isGif = data.getBooleanExtra(EmojiPickerActivity.EXTRA_EMOJI_IS_GIF, false);
            if (messageSender != null) {
                messageSender.sendEmojiFromPath(path, isGif, quoteDraft);
            }
            return;
        }
        if (requestCode == REQ_PICK_CHAT_BG && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (backgroundHelper != null) {
                backgroundHelper.handlePickResult(uri);
            }
            return;
        }
        if (requestCode == REQ_SEND_RED_PACKET && resultCode == RESULT_OK && data != null) {
            String payload = data.getStringExtra(RedPacketSendActivity.EXTRA_MESSAGE_JSON);
            if (payload != null && !payload.isEmpty() && listHelper != null) {
                GroupMessage sent = listHelper.parseMessageFromResponse(payload);
                if (sent != null) {
                    listHelper.appendMessage(sent, true);
                } else {
                    listHelper.loadMessages(token, false, 0, false);
                }
            }
            return;
        }
        if (mediaHelper != null && mediaHelper.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
    }
}
