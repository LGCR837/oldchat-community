package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;

import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.models.Message;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.ClipboardUtil;
import com.im.oldchat.util.ChatOpenPerfTracer;
import com.im.oldchat.util.ChatStartupOptimizer;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.RecyclerViewOptimizationHelper;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.TypingStatusManager;
import com.im.oldchat.util.UiPerformance;

abstract class ChatActivityLifecycle extends ChatActivityCallbacks {
    @Override
    protected boolean enableSwipeBack() {
        return true;
    }

    private static final long INLINE_IMAGE_RESUME_DELAY_MS = 180L;
    private static final long PREVIEW_RETURN_SYNC_DELAY_MS = 260L;
    private static final long PREVIEW_RETURN_RECOVERY_DELAY_MS = 900L;
    private static final long PREVIEW_RETURN_PREFETCH_BLOCK_MS = 2200L;
    private static final long PREVIEW_RETURN_INLINE_BATCH_DELAY_MS = 90L;
    private static final int PREVIEW_RETURN_INLINE_BATCH_SIZE = 2;
    private static final long STARTUP_SYNC_DELAY_MS = 220L;
    private static final long RESUME_BACKGROUND_APPLY_DELAY_MS = 180L;
    private boolean inlineLoadsPausedByScroll = false;
    private boolean initialUnreadSyncDeferred = false;
    private long previewReturnInlineResumeBlockedUntilMs = 0L;
    private long previewReturnPrefetchBlockedUntilMs = 0L;
    private int pendingInlineResumeFirst = -1;
    private int pendingInlineResumeLast = -1;
    private int pendingInlineResumeOffset = -1;
    private final Runnable previewReturnRecoveryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || lvMessages == null) {
                return;
            }
            if (token != null && token.length() > 0) {
                WSManager.getInstance().syncUnread(ChatActivityLifecycle.this, token);
            }
            if (typingStatusManager != null && friendUID != null && !friendUID.isEmpty()
                    && SettingsPrefs.isTypingIndicatorEnabled(ChatActivityLifecycle.this)
                    && !WSManager.getInstance().isConnected()) {
                typingStatusManager.startCheckingTyping(ChatActivityLifecycle.this, token, friendUID, false);
            }
        }
    };
    private final Runnable resumeBackgroundApplyRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || backgroundHelper == null) {
                return;
            }
            backgroundHelper.applyBackground();
        }
    };
    private final Runnable resumeInlineImagesRunnable = new Runnable() {
        @Override
        public void run() {
            if (lvMessages == null || lvMessages.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                return;
            }
            if (!inlineLoadsPausedByScroll) {
                return;
            }
            inlineLoadsPausedByScroll = false;
            com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(false);
            LinearLayoutManager lm = (LinearLayoutManager) lvMessages.getLayoutManager();
            if (lm == null || adapter == null) {
                return;
            }
            int first = lm.findFirstVisibleItemPosition();
            int last = lm.findLastVisibleItemPosition();
            if (first >= 0 && last >= first
                    && ChatInlineImagePrefetcher.hasDirectInlineMedia(messageList, first, last)) {
                if (!isPreviewReturnPrefetchBlocked()) {
                    ChatInlineImagePrefetcher.prefetchDirectVisible(ChatActivityLifecycle.this,
                            messageList, first, last);
                }
                resumeDirectVisibleImages(first, last);
            }
        }
    };
    private final Runnable resumeInlineImagesBatchRunnable = new Runnable() {
        @Override
        public void run() {
            if (lvMessages == null || lvMessages.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                return;
            }
            if (pendingInlineResumeFirst < 0 || pendingInlineResumeLast < pendingInlineResumeFirst) {
                cancelInlineResumeBatch();
                return;
            }
            int nextOffset = ChatInlineVisibleLoader.resumeDirectVisibleBatch(ChatActivityLifecycle.this,
                    lvMessages, messageList, pendingInlineResumeFirst, pendingInlineResumeLast,
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

    private void resumeDirectVisibleImages(int first, int last) {
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
        ChatInlineVisibleLoader.resumeDirectVisible(ChatActivityLifecycle.this,
                lvMessages, messageList, first, last);
    }

    private void cancelInlineResumeBatch() {
        pendingInlineResumeFirst = -1;
        pendingInlineResumeLast = -1;
        pendingInlineResumeOffset = -1;
        if (lvMessages != null) {
            lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
        }
    }

    private void scheduleInlineImageResume() {
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

    private ChatStartupOptimizer startupOptimizer;
    private ChatSyncBannerHelper syncBannerHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        
        // 初始化性能追踪器
        friendUID = getIntent().getStringExtra("friend_uid");
        if (friendUID == null || friendUID.isEmpty()) {
            friendUID = getIntent().getStringExtra("friend_id");
        }
        friendNcuid = getIntent().getStringExtra("friend_ncuid");
        if (friendNcuid == null) {
            friendNcuid = "";
        }
        friendName = getIntent().getStringExtra("friend_name");
        friendAvatar = getIntent().getStringExtra("friend_avatar");
        chatOpenPerfTracer = new ChatOpenPerfTracer(this, "DirectChatOpen", friendUID);
        
        // 初始化同步横幅（先创建实例，稍后在关键任务中显示）
        syncBannerHelper = new ChatSyncBannerHelper(this);
        
        // 初始化启动优化器
        startupOptimizer = new ChatStartupOptimizer();
        setupStartupOptimizer();
        startupOptimizer.executeStartup();
    }
    
    private void setupStartupOptimizer() {
        final View btnActionImage = findViewByIdCompat(R.id.btnActionImage);
        final View btnActionVideo = findViewByIdCompat(R.id.btnActionVideo);
        final View btnActionEmoji = findViewByIdCompat(R.id.btnActionEmoji);
        final View btnActionRedPacket = findViewByIdCompat(R.id.btnActionRedPacket);
        final View btnToggleVoice = findViewByIdCompat(R.id.btnToggleVoice);
        final View btnPlus = findViewByIdCompat(R.id.btnPlus);
        final View btnHoldToTalk = findViewByIdCompat(R.id.btnHoldToTalk);
        final View actionPanel = findViewByIdCompat(R.id.action_panel);
        final View videoProgressContainer = findViewByIdCompat(R.id.video_progress_container);
        final ProgressBar pbVideoProgress = findViewByIdCompat(R.id.pbVideoProgress);
        final TextView tvVideoProgress = findViewByIdCompat(R.id.tvVideoProgress);
        
        // 1. 关键初始化任务 - 立即执行
        startupOptimizer.setCriticalInitTask(new ChatStartupOptimizer.CriticalInitTask() {
            @Override
            public void run() {
                initCriticalViews();
                initBackground();
                initRecyclerView();
                initAdapter();
                initListHelper();
                initBasicListeners(btnActionRedPacket);
                // 显示正在同步消息横幅
                if (syncBannerHelper != null) {
                    syncBannerHelper.show("正在同步消息...");
                }
                loadInitialMessages();
            }
        });
        
        // 2. 延迟初始化任务 - 50ms后执行
        startupOptimizer.setDeferredInitTask(new ChatStartupOptimizer.DeferredInitTask() {
            @Override
            public void run() {
                initInputAndDraft();
                initTypingWatcher();
                initScrollListener();
                initQuotePreview();
            }
        });
        
        // 3. 背景加载任务 - 移除延迟，首帧已直接渲染
        startupOptimizer.setBackgroundLoadTask(new ChatStartupOptimizer.BackgroundLoadTask() {
            @Override
            public void run() {
            }
        });
        
        // 4. 非关键初始化任务 - 150ms后执行
        startupOptimizer.setNonCriticalInitTask(new ChatStartupOptimizer.NonCriticalInitTask() {
            @Override
            public void run() {
                initMediaHelper(btnActionImage, btnActionVideo, btnToggleVoice, btnPlus, 
                        btnHoldToTalk, actionPanel, videoProgressContainer, pbVideoProgress, tvVideoProgress);
                initEmojiButton(btnActionEmoji);
                initChatMenuButton();
                setupForwardMultiSelectBar();
                applyFontScale();
            }
        });
        
        // 5. 预取任务 - 200ms后执行
        startupOptimizer.setPrefetchTask(new ChatStartupOptimizer.PrefetchTask() {
            @Override
            public void run() {
                prefetchVisibleImages();
            }
        });
    }
    
    private void initCriticalViews() {
        ChatActivityLifecycleDelegate.initCriticalViews(this);
    }
    
    private void initRecyclerView() {
        ChatActivityLifecycleDelegate.initRecyclerView(this);
    }
    
    private void initAdapter() {
        ChatActivityLifecycleDelegate.initAdapter(this);
    }
    
    private void initListHelper() {
        ChatActivityLifecycleDelegate.initListHelper(this);
    }
    
    private void initBasicListeners(View btnActionRedPacket) {
        ChatActivityLifecycleDelegate.initBasicListeners(this, btnActionRedPacket);
    }
    
    private void loadInitialMessages() {
        ChatActivityLifecycleDelegate.loadInitialMessages(this);
    }
    
    private void initInputAndDraft() {
        ChatActivityLifecycleDelegate.initInputAndDraft(this);
    }
    
    private void initTypingWatcher() {
        setupTypingWatcher();
    }
    
    private void initScrollListener() {
        lvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && listHelper != null) {
                    listHelper.cancelPendingBottomScroll();
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    inlineLoadsPausedByScroll = true;
                    com.im.oldchat.util.ImageLoader.blockAvatarNetworkFor(1800L);
                    com.im.oldchat.models.MessageHistoryCache.pauseSave();
                    recyclerView.removeCallbacks(resumeInlineImagesRunnable);
                    recyclerView.removeCallbacks(resumeInlineImagesBatchRunnable);
                    pendingInlineResumeFirst = -1;
                    pendingInlineResumeLast = -1;
                    pendingInlineResumeOffset = -1;
                    com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(true);
                    return;
                }
                com.im.oldchat.models.MessageHistoryCache.resumeSave();
                scheduleInlineImageResume();
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (listHelper != null) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm != null) {
                        listHelper.onUserScroll(listHelper.isAtBottom());
                        int firstVisibleItem = lm.findFirstVisibleItemPosition();
                        // 无感预取：距离顶部 12 条时提前请求，结果前插后由锚点恢复保持画面稳定。
                        if (dy < 0 && firstVisibleItem <= 12 && listHelper.canLoadMore()) {
                            long before = listHelper.getOldestTimestamp();
                            listHelper.loadMessages(token, true, before, true);
                        }
                    }
                }
            }
        });
    }
    
    private void initQuotePreview() {
        ChatActivityLifecycleDelegate.initQuotePreview(this);
    }
    
    private void initBackground() {
        ChatActivityLifecycleDelegate.initBackground(this);
    }
    
    private void initMediaHelper(View btnActionImage, View btnActionVideo, View btnToggleVoice,
                                 View btnPlus, View btnHoldToTalk, View actionPanel,
                                 View videoProgressContainer, ProgressBar pbVideoProgress, TextView tvVideoProgress) {
        ChatActivityLifecycleDelegate.initMediaHelper(this, btnActionImage, btnActionVideo, btnToggleVoice,
                btnPlus, btnHoldToTalk, actionPanel, videoProgressContainer, pbVideoProgress, tvVideoProgress);
    }
    
    private void initEmojiButton(View btnActionEmoji) {
        ChatActivityLifecycleDelegate.initEmojiButton(this, btnActionEmoji);
    }
    
    private void initChatMenuButton() {
        ChatActivityLifecycleDelegate.initChatMenuButton(this);
    }
    
    private void prefetchVisibleImages() {
        // 预加载可见区域的图片
        if (lvMessages == null || messageList.isEmpty()) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) lvMessages.getLayoutManager();
        if (lm == null) {
            return;
        }
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first >= 0 && last >= first) {
            ChatInlineImagePrefetcher.prefetchDirectVisible(ChatActivityLifecycle.this, messageList, first, last);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // 首次启动完成后恢复正常 RecyclerView 状态
        if (startupOptimizer != null && startupOptimizer.isCriticalDone()) {
            ChatStartupOptimizer.restoreRecyclerViewNormalState(lvMessages);
        }
        
        boolean returnedFromImagePreview = ImagePreviewReturnTracker.consumeRecentReturn();
        if (returnedFromImagePreview) {
            previewReturnInlineResumeBlockedUntilMs = System.currentTimeMillis()
                    + PREVIEW_RETURN_RECOVERY_DELAY_MS;
            previewReturnPrefetchBlockedUntilMs = System.currentTimeMillis()
                    + PREVIEW_RETURN_PREFETCH_BLOCK_MS;
        } else {
            previewReturnInlineResumeBlockedUntilMs = 0L;
            previewReturnPrefetchBlockedUntilMs = 0L;
        }
        WSManager wsManager = WSManager.getInstance();
        wsManager.addListener(wsListener);
        wsManager.addListener(backDotListener);
        wsManager.start(this);
        if (token != null && token.length() > 0) {
            if (returnedFromImagePreview && lvMessages != null) {
                lvMessages.removeCallbacks(previewReturnRecoveryRunnable);
                lvMessages.postDelayed(previewReturnRecoveryRunnable, PREVIEW_RETURN_RECOVERY_DELAY_MS);
            } else if (!initialUnreadSyncDeferred && lvMessages != null) {
                initialUnreadSyncDeferred = true;
                final WSManager finalWsManager = wsManager;
                final String finalToken = token;
                lvMessages.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finalWsManager.syncUnread(ChatActivityLifecycle.this, finalToken);
                    }
                }, STARTUP_SYNC_DELAY_MS);
            } else {
                wsManager.syncUnread(this, token);
            }
        }
        updateBackDot();
        if (!returnedFromImagePreview) {
            refreshMyUID();
            applyFontScale();
        }
        if (lvMessages != null) {
            lvMessages.removeCallbacks(resumeBackgroundApplyRunnable);
        }
        if (backgroundHelper != null && !returnedFromImagePreview) {
            if (lvMessages != null) {
                lvMessages.postDelayed(resumeBackgroundApplyRunnable, RESUME_BACKGROUND_APPLY_DELAY_MS);
            } else {
                backgroundHelper.applyBackground();
            }
        }
        if (needsRefreshOnResume && !returnedFromImagePreview) {
            boolean keepPosition = listHelper != null && !listHelper.isAtBottom();
            WSManager.getInstance().consumeReconnectedInBackground();
            if (listHelper != null && token != null && token.length() > 0) {
                listHelper.loadMessages(token, false, 0, true, keepPosition);
            }
        }
        needsRefreshOnResume = false;
        boolean typingEnabled = SettingsPrefs.isTypingIndicatorEnabled(this);
        if (typingStatusManager != null && friendUID != null && !friendUID.isEmpty()) {
            typingStatusManager.unregisterListener(friendUID);
            typingStatusManager.registerListener(friendUID, new TypingStatusManager.TypingListener() {
                @Override
                public void onTypingStatusChanged(String uid, boolean typing) {
                    if (uid == null || uid.isEmpty()) {
                        return;
                    }
                    if (!SettingsPrefs.isTypingIndicatorEnabled(ChatActivityLifecycle.this)) {
                        setTypingIndicatorVisible(false);
                        return;
                    }
                    if (MyUidStore.isMyUid(ChatActivityLifecycle.this, uid, myUID)) {
                        return;
                    }
                    if (friendUID != null && !friendUID.equals(uid)) {
                        return;
                    }
                    setTypingIndicatorVisible(typing);
                }
            });
            if (returnedFromImagePreview) {
                typingStatusManager.stopCheckingTyping(friendUID);
            } else if (typingEnabled && !wsManager.isConnected()) {
                typingStatusManager.startCheckingTyping(this, token, friendUID, false);
            } else {
                typingStatusManager.stopCheckingTyping(friendUID);
                setTypingIndicatorVisible(false);
            }
        }
    }

    /** 显示标题下方的同步状态。 */
    public void showSyncStatus() {
        if (syncBannerHelper != null) syncBannerHelper.show("同步中");
    }

    /**
     * 隐藏同步消息横幅
     */
    public void hideSyncBanner() {
        if (syncBannerHelper != null && syncBannerHelper.isVisible()) {
            syncBannerHelper.startFinishAnimation();
        }
    }
    
    /**
     * 更新同步消息状态文字
     */
    public void updateSyncBannerText(String text) {
        if (syncBannerHelper != null && syncBannerHelper.isVisible()) {
            syncBannerHelper.updateText(text);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) {
            if (chatOpenLoadingController != null) {
                chatOpenLoadingController.release();
            }
            if (startupOptimizer != null) {
                startupOptimizer.release();
            }
            if (syncBannerHelper != null) {
                syncBannerHelper.release();
            }
        }
        if (lvMessages != null) {
            lvMessages.removeCallbacks(previewReturnRecoveryRunnable);
            lvMessages.removeCallbacks(resumeInlineImagesRunnable);
            lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
            lvMessages.removeCallbacks(resumeBackgroundApplyRunnable);
        }
        pendingInlineResumeFirst = -1;
        pendingInlineResumeLast = -1;
        pendingInlineResumeOffset = -1;
        previewReturnPrefetchBlockedUntilMs = 0L;
        lastDraft = ChatDraftHelper.saveDraftFromInput(this, etInput, getDraftKey(), lastDraft);
        updateRecentFromLastMessage();
        if (listHelper != null) {
            listHelper.recordScrollPosition();
        }
        if (mediaHelper != null) {
            mediaHelper.onPause();
        }
        if (adapter != null) {
            adapter.stopVoice();
        }
        WSManager.getInstance().removeListener(wsListener);
        WSManager.getInstance().removeListener(backDotListener);
        if (typingStatusManager != null && friendUID != null && !friendUID.isEmpty()) {
            if (isTyping) {
                typingStatusManager.stopTyping(this, token, friendUID, false);
                isTyping = false;
            }
            typingStatusManager.stopCheckingTyping(friendUID);
            typingStatusManager.unregisterListener(friendUID);
        }
        typingHandler.removeCallbacks(typingIdleRunnable);
        setTypingIndicatorVisible(false);
        if (lvMessages != null) {
            lvMessages.removeCallbacks(resumeInlineImagesRunnable);
        }
        inlineLoadsPausedByScroll = false;
        com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(false);
        lastPauseAtMs = System.currentTimeMillis();
        needsRefreshOnResume = !ImagePreviewReturnTracker.isLaunchPending();
    }

    @Override
    protected void onDestroy() {
        if (listHelper != null) listHelper.dispose();
        WSManager.getInstance().removeListener(wsListener);
        WSManager.getInstance().removeListener(backDotListener);
        typingHandler.removeCallbacksAndMessages(null);
        if (startupOptimizer != null) startupOptimizer.release();
        if (chatOpenLoadingController != null) chatOpenLoadingController.release();
        if (syncBannerHelper != null) syncBannerHelper.release();
        super.onDestroy();
    }

    private boolean isPreviewReturnPrefetchBlocked() {
        return previewReturnPrefetchBlockedUntilMs > System.currentTimeMillis();
    }
    
    // Public getter methods for ChatActivityLifecycleDelegate
    public android.support.v7.widget.RecyclerView getLvMessages() {
        return lvMessages;
    }
    
    public android.widget.EditText getEtInput() {
        return etInput;
    }
    
    public android.widget.Button getBtnSend() {
        return btnSend;
    }
    
    public android.widget.TextView getBtnNewMessage() {
        return btnNewMessage;
    }
    
    public android.widget.TextView getBtnJumpToUnread() {
        return btnJumpToUnread;
    }
    
    public android.widget.TextView getBtnLoadMore() {
        return btnLoadMore;
    }
    
    public android.widget.ProgressBar getPbMessagesLoading() {
        return pbMessagesLoading;
    }
    
    public android.widget.ProgressBar getPbSend() {
        return pbSend;
    }
    
    public android.view.View getChatOpenLoadingMask() {
        return chatOpenLoadingMask;
    }
    
    public android.view.View getBackDot() {
        return backDot;
    }
    
    public android.view.View getQuotePreview() {
        return quotePreview;
    }
    
    public android.widget.TextView getTvQuotePreview() {
        return tvQuotePreview;
    }
    
    public DirectChatListHelper getListHelper() {
        return listHelper;
    }
    
    public void setListHelper(DirectChatListHelper helper) {
        this.listHelper = helper;
    }
    
    public MessageAdapter getAdapter() {
        return adapter;
    }
    
    public void setAdapter(MessageAdapter adapter) {
        this.adapter = adapter;
    }
    
    public DirectMessageSender getMessageSender() {
        return messageSender;
    }
    
    public void setMessageSender(DirectMessageSender sender) {
        this.messageSender = sender;
    }
    
    public ChatMediaHelper getMediaHelper() {
        return mediaHelper;
    }
    
    public void setMediaHelper(ChatMediaHelper helper) {
        this.mediaHelper = helper;
    }
    
    public ChatWsListener getWsListener() {
        return wsListener;
    }
    
    public void setWsListener(ChatWsListener listener) {
        this.wsListener = listener;
    }

}
