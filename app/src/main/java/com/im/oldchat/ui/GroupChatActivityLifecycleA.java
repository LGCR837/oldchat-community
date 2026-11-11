package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMemberMetaCache;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.util.ClipboardUtil;
import com.im.oldchat.util.ChatOpenPerfTracer;
import com.im.oldchat.util.MyUidStore;
import com.im.oldchat.util.RecyclerViewOptimizationHelper;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.TypingStatusManager;
import java.util.List;

abstract class GroupChatActivityLifecycleA extends GroupChatActivityLifecycleB {
    @Override
    protected boolean enableSwipeBack() {
        return true;
    }

    private static final long STARTUP_NON_CRITICAL_DELAY_MS = 220L;
    private ChatSyncBannerHelper syncBannerHelper;
    private static final long STARTUP_MEMBER_META_DELAY_MS = 500L;
    private static final long STARTUP_MEMBER_META_REFRESH_DELAY_MS = 200L;
    private static final long PREVIEW_RETURN_RECOVERY_DELAY_MS = 900L;
    private static final long STARTUP_AVATAR_NETWORK_BLOCK_MS = 3000L;
    private static final long STARTUP_INLINE_RESUME_DELAY_MS = 3000L;
    private static final long SCROLL_AVATAR_NETWORK_BLOCK_MS = 600L;
    private static final long RESUME_BACKGROUND_APPLY_DELAY_MS = 180L;
    private boolean initialUnreadSyncDeferred = false;
    private boolean startupDeferredWorkScheduled = false;
    private boolean memberMetaRequested = false;
    private long observedProfileVersion = com.im.oldchat.util.ProfileUpdateSignal.currentVersion();
    private boolean memberMetaLoadScheduled = false;
    private boolean memberMetaRefreshScheduled = false;
    private final Runnable previewReturnRecoveryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || lvMessages == null) {
                return;
            }
            if (token != null && token.length() > 0) {
                WSManager.getInstance().syncGroupUnread(GroupChatActivityLifecycleA.this, token);
            }
            if (typingStatusManager != null && groupId != null && !groupId.isEmpty()
                    && SettingsPrefs.isTypingIndicatorEnabled(GroupChatActivityLifecycleA.this)
                    && !WSManager.getInstance().isConnected()) {
                typingStatusManager.startCheckingTyping(GroupChatActivityLifecycleA.this, token, groupId, true);
            }
        }
    };
    private final Runnable deferredStartupWork = new Runnable() {
        @Override
        public void run() {
            startupDeferredWorkScheduled = false;
            if (isFinishing()) {
                return;
            }
            long _t0 = android.os.SystemClock.uptimeMillis();
            refreshGroupAnnouncement();
            android.util.Log.w("GroupChatPerf", "deferredStartup: announcement=" + (android.os.SystemClock.uptimeMillis()-_t0) + "ms");
        }
    };
    private final Runnable startupInlineResumeRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || lvMessages == null) {
                return;
            }
            if (!inlineLoadsPausedByScroll) {
                return;
            }
            if (!inlineImageScrollIdle) {
                lvMessages.removeCallbacks(startupInlineResumeRunnable);
                lvMessages.postDelayed(startupInlineResumeRunnable, INLINE_IMAGE_RESUME_DELAY_MS);
                return;
            }
            scheduleInlineImageResume();
        }
    };
    private final Runnable memberMetaLoadRunnable = new Runnable() {
        @Override
        public void run() {
            memberMetaLoadScheduled = false;
            loadMemberMetaImmediate();
        }
    };
    private final Runnable memberMetaUiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            memberMetaRefreshScheduled = false;
            if (isFinishing() || adapter == null) {
                return;
            }
            if (chatOpenPerfTracer != null) {
                chatOpenPerfTracer.trace("member_meta_ui_refresh", "来源=群聊 延后刷新可见项");
            }
            adapter.refreshVisibleRows();
        }
    };
    private final Runnable resumeBackgroundApplyRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            applyChatBackground();
        }
    };

    /** 同步预加载消息的成员信息：先读 SQLite LIVE（lookup 已持久化），再读内存缓存。 */
    private void prewarmVisibleMemberMeta() {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        java.util.Map<String, String> avatars = new java.util.HashMap<>();
        java.util.Map<String, String> titles = new java.util.HashMap<>();
        java.util.Map<String, Integer> roles = new java.util.HashMap<>();
        int count = Math.min(messageList.size(), 300);
        int start = Math.max(0, messageList.size() - count);
        // 收集已加载消息的所有发送者（ncuid 与 uid 全部收集）
        java.util.LinkedHashSet<String> identities = new java.util.LinkedHashSet<String>();
        for (int i = start; i < messageList.size(); i++) {
            GroupMessage msg = messageList.get(i);
            if (msg == null) continue;
            if (msg.sender_ncuid != null && msg.sender_ncuid.length() > 0) {
                identities.add(msg.sender_ncuid);
            }
            if (msg.from_uid != null && msg.from_uid.length() > 0) {
                identities.add(msg.from_uid);
            }
        }
        if (!identities.isEmpty()) {
            // 同步查 SQLite LIVE（毫秒级），已持久化的成员立即命中。
            java.util.List<GroupMember> cachedRows =
                    GroupMemberMetaCache.getMembersByNcuids(this, groupId, new java.util.ArrayList<String>(identities));
            if (cachedRows != null) {
                for (int i = 0; i < cachedRows.size(); i++) {
                    GroupMember m = cachedRows.get(i);
                    if (m == null) continue;
                    String name = com.im.oldchat.models.FriendNameResolver.resolve(
                            "", m.display_name, m.username, m.uid);
                    if (name != null && !name.isEmpty()) {
                        if (m.ncuid != null && !m.ncuid.isEmpty()) names.put(m.ncuid, name);
                        if (m.uid != null && !m.uid.isEmpty()) names.put(m.uid, name);
                    }
                    if (m.avatar_url != null && !m.avatar_url.isEmpty()) {
                        if (m.ncuid != null && !m.ncuid.isEmpty()) avatars.put(m.ncuid, m.avatar_url);
                        if (m.uid != null && !m.uid.isEmpty()) avatars.put(m.uid, m.avatar_url);
                    }
                    if (m.user_title != null && !m.user_title.isEmpty()) {
                        if (m.ncuid != null && !m.ncuid.isEmpty()) titles.put(m.ncuid, m.user_title);
                        if (m.uid != null && !m.uid.isEmpty()) titles.put(m.uid, m.user_title);
                    }
                    if (m.role != 0) {
                        if (m.ncuid != null && !m.ncuid.isEmpty()) roles.put(m.ncuid, Integer.valueOf(m.role));
                        if (m.uid != null && !m.uid.isEmpty()) roles.put(m.uid, Integer.valueOf(m.role));
                    }
                }
            }
        }
        // 内存缓存兜底（按 uid/ncuid 键）
        for (int i = start; i < messageList.size(); i++) {
            GroupMessage msg = messageList.get(i);
            if (msg == null) continue;
            String uid = msg.from_uid;
            String ncuid = msg.sender_ncuid;
            if (uid != null && !uid.isEmpty()) {
                if (!names.containsKey(uid)) {
                    String name = com.im.oldchat.models.UserNameCache.getName(this, uid);
                    if (name != null && !name.isEmpty()) names.put(uid, name);
                }
                if (!titles.containsKey(uid)) {
                    String title = com.im.oldchat.models.UserTitleCache.getTitle(this, uid);
                    if (title != null && !title.isEmpty()) titles.put(uid, title);
                }
                if (!avatars.containsKey(uid)) {
                    String avatar = com.im.oldchat.util.GroupAvatarCache.getCachedAvatar(this, uid);
                    if (avatar != null && !avatar.isEmpty()) avatars.put(uid, avatar);
                }
            }
            if (ncuid != null && !ncuid.isEmpty()) {
                if (!names.containsKey(ncuid)) {
                    String name = com.im.oldchat.models.UserNameCache.getName(this, ncuid);
                    if (name != null && !name.isEmpty()) names.put(ncuid, name);
                }
                if (!titles.containsKey(ncuid)) {
                    String title = com.im.oldchat.models.UserTitleCache.getTitle(this, ncuid);
                    if (title != null && !title.isEmpty()) titles.put(ncuid, title);
                }
                if (!avatars.containsKey(ncuid)) {
                    String avatar = com.im.oldchat.util.GroupAvatarCache.getCachedAvatar(this, ncuid);
                    if (avatar != null && !avatar.isEmpty()) avatars.put(ncuid, avatar);
                }
            }
        }
        if (!names.isEmpty()) {
            if (nameMap != null) nameMap.putAll(names);
            if (avatarMap != null) avatarMap.putAll(avatars);
            if (titleMap != null) titleMap.putAll(titles);
            if (roleMap != null) roleMap.putAll(roles);
            if (adapter != null) {
                // 同步立即应用到 Adapter，避免第一帧因异步 post 导致闪现 UID
                adapter.updateMemberMeta(nameMap, avatarMap, titleMap, roleMap, false);
            }
        }
    }

    void prewarmLoadedMemberMeta() {
        // 消息首次加载完成（或滚动加载）后调用：
        // 此时发送者列表已就绪，立即同步读 SQLite LIVE（prewarmVisibleMemberMeta 已做），
        // 并强制触发一次 SQLite 查询 + lookup 补缺（绕过 memberMetaRequested 去重，
        // 因为 500ms 时消息可能未加载完导致首次空跑）。
        prewarmVisibleMemberMeta();
        if (token == null || token.isEmpty() || groupId == null || groupId.isEmpty()
                || adapter == null) {
            return;
        }
        final String fToken = token;
        final String fGid = groupId;
        final GroupMessageAdapter fAdapter = adapter;
        final java.util.Map<String, String> fNames = nameMap;
        final java.util.Map<String, String> fAvatars = avatarMap;
        final java.util.Map<String, String> fTitles = titleMap;
        final java.util.Map<String, Integer> fRoles = roleMap;
        final java.util.List<GroupMember> fMembers = mentionMembers;
        lvMessages.post(new java.lang.Runnable() {
            @Override
            public void run() {
                if (isFinishing() || fAdapter == null) {
                    return;
                }
                GroupMemberLoader.loadMemberNames(GroupChatActivityLifecycleA.this, fToken, fGid,
                        fAdapter, fNames, fAvatars, fTitles, fRoles, fMembers, null);
            }
        });
    }

    private void loadMemberMetaImmediate() {
        if (memberMetaRequested || token == null || token.isEmpty() || groupId == null || groupId.isEmpty()) {
            return;
        }
        memberMetaRequested = true;
        if (chatOpenPerfTracer != null) {
            chatOpenPerfTracer.trace("member_meta_request_start", "来源=群聊 开始拉取成员资料");
        }
        manageApi = new GroupManageApi();
        GroupMemberLoader.loadMemberNames(GroupChatActivityLifecycleA.this, token, groupId, adapter,
                nameMap, avatarMap, titleMap, roleMap, mentionMembers, new Runnable() {
                    @Override
                    public void run() {
                        if (chatOpenPerfTracer != null) {
                            chatOpenPerfTracer.trace("member_meta_request_done",
                                    "来源=群聊 成员资料已加载 members=" + mentionMembers.size());
                        }
                        // @选择器由 SQLite 按关键词查询，不再把全群成员灌入内存 Adapter。
                        // 不强制刷新 UI — adapter 会懒加载，下次滚动时自动显示新数据
                        // 避免 refreshVisibleRows 导致全量 rebind 卡顿
                    }
                });
    }

    private void scheduleMemberMetaLoad(long delayMs) {
        if (memberMetaRequested || memberMetaLoadScheduled || lvMessages == null) {
            return;
        }
        memberMetaLoadScheduled = true;
        lvMessages.removeCallbacks(memberMetaLoadRunnable);
        lvMessages.postDelayed(memberMetaLoadRunnable, delayMs);
    }

    private void scheduleMemberMetaUiRefresh(long delayMs) {
        if (adapter == null) {
            return;
        }
        if (lvMessages == null) {
            adapter.refreshVisibleRows();
            return;
        }
        memberMetaRefreshScheduled = true;
        lvMessages.removeCallbacks(memberMetaUiRefreshRunnable);
        lvMessages.postDelayed(memberMetaUiRefreshRunnable, delayMs);
    }

    private void scheduleDeferredStartupWork() {
        if (lvMessages == null || startupDeferredWorkScheduled) {
            return;
        }
        startupDeferredWorkScheduled = true;
        lvMessages.removeCallbacks(deferredStartupWork);
        lvMessages.postDelayed(deferredStartupWork, STARTUP_NON_CRITICAL_DELAY_MS);
    }

    private void scheduleStartupInlineResume() {
        if (lvMessages == null) {
            return;
        }
        lvMessages.removeCallbacks(startupInlineResumeRunnable);
        lvMessages.postDelayed(startupInlineResumeRunnable, STARTUP_INLINE_RESUME_DELAY_MS);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 不主动 trimMemory，保留 LRU 缓存避免大群头像重新加载
        setContentView(R.layout.activity_group_chat);
        groupId = getIntent().getStringExtra("group_id");
        groupName = getIntent().getStringExtra("group_name");
        groupAvatar = getIntent().getStringExtra("group_avatar");
        myRole = getIntent().getIntExtra("group_role", 0);
        if (groupId != null) {
            groupId = groupId.trim().toUpperCase();
        }
        if (groupAvatar == null || groupAvatar.isEmpty() || groupName == null || groupName.isEmpty()) {
            com.im.oldchat.models.Group cachedGroup = com.im.oldchat.models.GroupCache.getGroup(this, groupId);
            if (cachedGroup != null) {
                if (groupName == null || groupName.isEmpty()) {
                    groupName = cachedGroup.name;
                }
                if (groupAvatar == null || groupAvatar.isEmpty()) {
                    groupAvatar = cachedGroup.avatar_url;
                }
                if (myRole == 0) {
                    myRole = cachedGroup.role;
                }
            }
        }
        if (groupAvatar == null || groupAvatar.isEmpty() || groupName == null || groupName.isEmpty()) {
            GroupRecentChatCache.RecentGroup recentGroup = GroupRecentChatCache.getGroup(this, groupId);
            if (recentGroup != null) {
                if (groupName == null || groupName.isEmpty()) {
                    groupName = recentGroup.groupName;
                }
                if (groupAvatar == null || groupAvatar.isEmpty()) {
                    groupAvatar = recentGroup.avatarUrl;
                }
            }
        }
        chatOpenPerfTracer = new ChatOpenPerfTracer(this, "GroupChatOpen", groupId);
        chatOpenPerfTracer.trace("views_bound", null);
        
        // 初始化同步横幅
        syncBannerHelper = new ChatSyncBannerHelper(this);
        syncBannerHelper.show("正在同步消息...");

        tvTitle = (TextView) findViewByIdCompat(R.id.tvGroupTitle);
        if (tvTitle != null) {
            tvTitle.setText(groupName == null ? "" : groupName);
        }
        ImageView ivAvatar = (ImageView) findViewByIdCompat(R.id.ivGroupAvatar);
        if (ivAvatar != null) {
            if (com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(this)) {
                ivAvatar.setVisibility(View.GONE);
                ivAvatar.setOnClickListener(null);
            } else {
                ivAvatar.setVisibility(View.VISIBLE);
                com.im.oldchat.util.ImageLoader.loadAvatarCacheFirstTryOnce(ivAvatar, groupAvatar);
                ivAvatar.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openManage();
                    }
                });
            }
        }
        View btnManage = (View) findViewByIdCompat(R.id.btnGroupManage);
        if (btnManage != null) {
            btnManage.setVisibility(View.VISIBLE);
            btnManage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openManage();
                }
            });
        }

        lvMessages = findViewByIdCompat(R.id.lvMessages);
        chatOpenLoadingMask = findViewByIdCompat(R.id.chat_open_loading_mask);
        etInput = findViewByIdCompat(R.id.etInput);
        // 输入框剩余字符计数（最大 400 字符，右下角显示剩余）
        ChatCharCounter.bind(etInput,
                (android.widget.TextView) findViewByIdCompat(R.id.tvCharCount));
        ChatInputMenuHelper.bind(etInput, new ChatInputMenuHelper.SendAction() {
            @Override
            public void onSend() {
                sendFromInput();
            }
        });
        registerForContextMenu(etInput);
        setupMentionInput();
        typingStatusManager = TypingStatusManager.getInstance();
        btnSend = findViewByIdCompat(R.id.btnSend);
        pbMessagesLoading = findViewByIdCompat(R.id.pbMessagesLoading);
        backDot = findViewByIdCompat(R.id.vBackDot);

        View btnBack = (View) findViewByIdCompat(R.id.btnGroupChatBack);
        if (btnBack instanceof ImageView) {
            ((ImageView) btnBack).setColorFilter(getResources().getColor(R.color.colorTextPrimary));
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
        }

        // 头像飞入与顶栏切换动画
        com.im.oldchat.util.ChatHeaderEnterAnimHelper.runEnterAnimation(this, ivAvatar, tvTitle, btnBack, lvMessages);

        quotePreview = findViewByIdCompat(R.id.quote_preview);
        tvQuotePreview = findViewByIdCompat(R.id.tvQuotePreview);
        btnQuoteClose = findViewByIdCompat(R.id.btnQuoteClose);
        announcementBanner = findViewByIdCompat(R.id.group_announcement_banner);
        tvAnnouncementBanner = findViewByIdCompat(R.id.tvGroupAnnouncementBanner);
        if (announcementBanner != null) {
            announcementBanner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (announcementMode == ANNOUNCEMENT_MODE_OPTIONAL) {
                        markAnnouncementRead();
                        hideAnnouncementBanner();
                    }
                }
            });
        }
        if (btnQuoteClose != null) {
            btnQuoteClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearQuoteDraft();
                }
            });
        }

        View btnToggleVoice = (View) findViewByIdCompat(R.id.btnToggleVoice);
        View btnPlus = (View) findViewByIdCompat(R.id.btnPlus);
        View btnHoldToTalk = (View) findViewByIdCompat(R.id.btnHoldToTalk);
        View btnActionImage = (View) findViewByIdCompat(R.id.btnActionImage);
        View btnActionVideo = (View) findViewByIdCompat(R.id.btnActionVideo);
        View btnActionEmoji = (View) findViewByIdCompat(R.id.btnActionEmoji);
        View btnActionRedPacket = (View) findViewByIdCompat(R.id.btnActionRedPacket);
        View actionPanel = (View) findViewByIdCompat(R.id.action_panel);

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        myUID = prefs.getString("my_uid", "");
        lastDraft = ChatDraftHelper.restoreDraft(this, etInput, getDraftKey());
        setupTypingWatcher();
        // 历史消息由上滑提前预取，不再显示“加载更多”头部按钮。
        com.im.oldchat.util.ImageLoader.blockAvatarNetworkFor(STARTUP_AVATAR_NETWORK_BLOCK_MS);
        inlineImageScrollIdle = true;
        List<GroupMessage> preloaded = com.im.oldchat.models.GroupMessagePreloadManager.peekPreloaded(groupId);
        if (preloaded != null && !preloaded.isEmpty()) {
            messageList.clear();
            messageIds.clear();
            messageList.addAll(preloaded);
            for (int i = 0; i < preloaded.size(); i++) {
                GroupMessage m = preloaded.get(i);
                if (m != null && m.id != null) {
                    messageIds.add(m.id);
                }
            }
        }

        adapter = new GroupMessageAdapter(this, messageList, myUID);
        adapter.setOnAvatarCacheMiss(new Runnable() {
            @Override
            public void run() {
                GroupMemberLoader.loadMemberNames(GroupChatActivityLifecycleA.this, token, groupId, adapter,
                        nameMap, avatarMap, titleMap, roleMap, mentionMembers, null);
            }
        });
        adapter.attachRecyclerView(lvMessages, lvMessages.getHeaderViewsCount());
        if (chatOpenLoadingMask != null) {
            chatOpenLoadingMask.setVisibility(View.GONE);
        }
        adapter.setMyRole(myRole);
        adapter.setActionListener(new GroupMessageAdapter.GroupMessageActionListener() {
            @Override
            public void onQuote(GroupMessage message, String displayName) {
                quoteMessage(message, displayName);
            }

            @Override
            public void onMention(GroupMessage message, String displayName) {
                mentionUser(message, displayName);
            }

            @Override
            public void onCopy(String text) {
                ClipboardUtil.copyText(GroupChatActivityLifecycleA.this, text);
            }

            @Override
            public void onRecall(GroupMessage message) {
                recallMessage(message);
            }

            @Override
            public void onReEdit(GroupMessage message) {
                reEditRecalledMessage(message);
            }

            @Override
            public void onVoiceTranscribe(GroupMessage message) {
            }

            @Override
            public void onForward(GroupMessage message) {
                forwardSingleMessage(message);
            }

            @Override
            public void onEnterMultiSelect(GroupMessage message) {
                enterForwardMultiSelect(message);
            }
        });
        adapter.setSelectionModeListener(new GroupMessageAdapter.SelectionModeListener() {
            @Override
            public void onSelectionModeChanged(boolean enabled, int count) {
                onForwardSelectionStateChanged(enabled, count);
            }
        });
        setupForwardMultiSelectBar();
        lvMessages.setAdapter(adapter);
        if (!messageList.isEmpty()) {
            lvMessages.scrollToBottomCompat();
        }
        // RecyclerView 优化统一在 GroupMessageRecyclerView.init() 中配置。
        // 成员缓存预热延后到消息列表真正加载完成，避免此处对空列表做无效工作。

        btnNewMessage = findViewByIdCompat(R.id.btnNewMessage);
        listHelper = new GroupChatListHelper(this, lvMessages, adapter, messageList, messageIds,
                btnLoadMore, btnNewMessage, pbMessagesLoading, groupId, groupName, myUID,
                chatOpenPerfTracer);
        adapter.setQuoteClickListener(new GroupMessageAdapter.QuoteClickListener() {
            @Override
            public void onQuoteClick(com.im.oldchat.util.MessagePayload.Quote quote) {
                if (quote == null || quote.id == null || quote.id.isEmpty()) {
                    return;
                }
                if (listHelper != null) {
                    listHelper.jumpToMessageId(token, quote.id);
                }
            }
        });

        btnJumpToUnread = findViewByIdCompat(R.id.btnJumpToUnread);
        if (listHelper != null && btnJumpToUnread != null) {
            listHelper.setJumpToUnreadButton(btnJumpToUnread);
            int unreadCount = getIntent().getIntExtra("unread_count", 0);
            if (unreadCount > 0) {
                listHelper.setInitialUnreadCount(unreadCount);
            }
            btnJumpToUnread.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listHelper != null) {
                        listHelper.jumpToEarliestUnread(token);
                    }
                }
            });
        }

        messageSender = new GroupMessageSender(this, listHelper, btnLoadMore, etInput, token, groupId, groupName,
                myUID,
                new GroupMessageSender.SendStateListener() {
                    @Override
                    public void onSendState(boolean sending) {
                        setSending(sending);
                    }
                },
                new GroupMessageSender.QuoteClearListener() {
                    @Override
                    public void onClearQuote() {
                        clearQuoteDraft();
                    }
                });
        if (btnNewMessage != null) {
            btnNewMessage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listHelper != null) {
                        listHelper.scrollToBottom();
                    }
                }
            });
        }
        lvMessages.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL
                        && listHelper != null) {
                    listHelper.cancelPendingBottomScroll();
                }
                inlineImageScrollIdle = scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
                if (!inlineImageScrollIdle) {
                    inlineLoadsPausedByScroll = true;
                    com.im.oldchat.util.ImageLoader.blockAvatarNetworkFor(SCROLL_AVATAR_NETWORK_BLOCK_MS);
                    com.im.oldchat.models.MessageHistoryCache.pauseSave();
                    if (lvMessages != null) {
                        lvMessages.removeCallbacks(resumeInlineImagesRunnable);
                        lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
                    }
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
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (listHelper != null) {
                    listHelper.onUserScroll(listHelper.isAtBottom());
                    // 无感预取：拖动或惯性上滑距离顶部 12 条时提前加载；IDLE 初始布局不会触发。
                    if (lvMessages.getScrollState() != android.support.v7.widget.RecyclerView.SCROLL_STATE_IDLE
                            && firstVisibleItem <= 12 && visibleItemCount > 0
                            && !messageList.isEmpty() && listHelper.canLoadMore()) {
                        long before = messageList.get(0).created_at;
                        listHelper.loadMessages(token, true, before, true);
                    }
                }
            }
        });

        wsListener = new GroupChatWsListener(asGroupChatActivity(), groupId, listHelper, messageIds, messageList, adapter, lvMessages,
                new Runnable() {
                    @Override
                    public void run() {
                        if (listHelper != null) {
                            listHelper.onNewMessageReceived();
                        }
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        if (listHelper != null) {
                            listHelper.markRead(token);
                        }
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        if (listHelper == null || token == null || token.length() == 0) {
                            return;
                        }
                        boolean keepPosition = !listHelper.isAtBottom();
                        if (syncBannerHelper != null) {
                            syncBannerHelper.show("同步中");
                        }
                        listHelper.loadMessages(token, false, 0, true, keepPosition);
                    }
                });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendFromInput();
            }
        });
        pbSend = findViewByIdCompat(R.id.pbSend);
        ChatSendButtonUi.update(btnSend, etInput, false, false);
        View videoProgressContainer = findViewByIdCompat(R.id.video_progress_container);
        ProgressBar pbVideoProgress = findViewByIdCompat(R.id.pbVideoProgress);
        TextView tvVideoProgress = findViewByIdCompat(R.id.tvVideoProgress);
        final GroupFileUploadBubbleController groupFileUploadBubbleController =
                new GroupFileUploadBubbleController(messageList, messageIds, adapter,
                        listHelper, messageSender, myUID,
                        new GroupFileUploadBubbleController.QuoteProvider() {
                            @Override
                            public com.im.oldchat.util.MessagePayload.Quote getQuoteDraft() {
                                return quoteDraft;
                            }
                        });
        final GroupImageUploadBubbleController groupImageUploadBubbleController =
                new GroupImageUploadBubbleController(listHelper, messageSender, myUID,
                        new GroupImageUploadBubbleController.QuoteProvider() {
                            @Override
                            public com.im.oldchat.util.MessagePayload.Quote getQuoteDraft() {
                                return quoteDraft;
                            }
                        },
                        new GroupImageUploadBubbleController.QuoteClearer() {
                            @Override
                            public void clearQuoteDraft() {
                                GroupChatActivityLifecycleA.this.clearQuoteDraft();
                            }
                        });
        mediaHelper = new ChatMediaHelper(this, token, etInput, btnSend, btnToggleVoice, btnPlus,
                btnHoldToTalk, actionPanel, btnActionImage, btnActionVideo,
                videoProgressContainer, pbVideoProgress, tvVideoProgress,
                new ChatMediaHelper.MediaCallback() {
                    @Override
                    public void onMediaReady(String type, String url, String thumbUrl, int durationMs) {
                        if (messageSender != null) {
                            messageSender.sendMedia(type, url, thumbUrl, durationMs, quoteDraft);
                        }
                    }
                },
                new ChatMediaHelper.SendStateListener() {
                    @Override
                    public void onSendState(boolean sending) {
                        setSending(sending);
                    }
                },
                groupFileUploadBubbleController);
        mediaHelper.setImageUploadListener(groupImageUploadBubbleController);
        mediaHelper.bind();
        mediaHelper.syncBurnModeUiState();
        if (btnActionEmoji != null) {
            btnActionEmoji.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEmojiPicker();
                }
            });
        }
        if (btnActionRedPacket != null) {
            btnActionRedPacket.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openRedPacketSend();
                }
            });
        }

        chatBackground = findViewByIdCompat(R.id.chat_background);
        backgroundHelper = new ChatBackgroundHelper(this, chatBackground, groupId, true);
        applyChatBackground();
        if (chatOpenPerfTracer != null) {
            chatOpenPerfTracer.trace("background_applied", null);
        }
        applyFontScale();
        
        scheduleMemberMetaLoad(STARTUP_MEMBER_META_DELAY_MS);
        scheduleStartupInlineResume();
        
        String jumpMessageId = getIntent().getStringExtra("jump_message_id");
        if (jumpMessageId != null && !jumpMessageId.isEmpty()) {
            listHelper.jumpToMessageId(token, jumpMessageId);
        } else {
            boolean hasPreloaded = !messageList.isEmpty();
            listHelper.loadMessages(token, false, 0, hasPreloaded, false);
        }
        android.util.Log.w("GroupChatPerf", "onCreate done, groupId=" + groupId);
        scheduleDeferredStartupWork();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean returnedFromImagePreview = ImagePreviewReturnTracker.consumeRecentReturn();
        if (returnedFromImagePreview) {
            previewReturnInlineResumeBlockedUntilMs = System.currentTimeMillis()
                    + PREVIEW_RETURN_RECOVERY_DELAY_MS;
            previewReturnPrefetchBlockedUntilMs = System.currentTimeMillis()
                    + PREVIEW_RETURN_PREFETCH_BLOCK_MS;
            com.im.oldchat.util.ImageLoader.blockAvatarNetworkFor(PREVIEW_RETURN_PREFETCH_BLOCK_MS);
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
                        android.util.Log.w("GroupChatPerf", "syncGroupUnread: starting");
                        finalWsManager.syncGroupUnread(GroupChatActivityLifecycleA.this, finalToken);
                    }
                }, STARTUP_NON_CRITICAL_DELAY_MS);
            } else {
                wsManager.syncGroupUnread(this, token);
            }
        }
        updateBackDot();
        if (!returnedFromImagePreview) {
            refreshMyUID();
            applyFontScale();
            long profileVersion = com.im.oldchat.util.ProfileUpdateSignal.currentVersion();
            if (profileVersion != observedProfileVersion) {
                observedProfileVersion = profileVersion;
                memberMetaRequested = false;
                memberMetaLoadScheduled = false;
                loadMemberMetaImmediate();
            }
        }
        if (lvMessages != null) {
            lvMessages.removeCallbacks(resumeBackgroundApplyRunnable);
        }
        if (!returnedFromImagePreview) {
            if (lvMessages != null) {
                lvMessages.postDelayed(resumeBackgroundApplyRunnable, RESUME_BACKGROUND_APPLY_DELAY_MS);
            } else {
                applyChatBackground();
            }
            scheduleDeferredStartupWork();
            scheduleMemberMetaLoad(STARTUP_MEMBER_META_DELAY_MS);
        }
        if (needsRefreshOnResume && !returnedFromImagePreview) {
            boolean keepPosition = listHelper != null && !listHelper.isAtBottom();
            WSManager.getInstance().consumeReconnectedInBackground();
            if (listHelper != null && token != null && token.length() > 0) {
                if (syncBannerHelper != null) {
                    syncBannerHelper.show("正在同步消息...");
                }
                listHelper.loadMessages(token, false, 0, true, keepPosition);
            }
        }
        needsRefreshOnResume = false;
        boolean typingEnabled = SettingsPrefs.isTypingIndicatorEnabled(this);
        if (typingStatusManager != null && groupId != null && !groupId.isEmpty()) {
            typingStatusManager.unregisterListener(groupId);
            typingStatusManager.registerListener(groupId, new TypingStatusManager.TypingListener() {
                @Override
                public void onTypingStatusChanged(String uid, boolean typing) {
                    if (uid == null || uid.isEmpty()) {
                        return;
                    }
                    if (!SettingsPrefs.isTypingIndicatorEnabled(GroupChatActivityLifecycleA.this)) {
                        clearTypingState();
                        return;
                    }
                    if (MyUidStore.isMyUid(GroupChatActivityLifecycleA.this, uid, myUID)) {
                        return;
                    }
                    if (typing) {
                        typingUsers.remove(uid);
                        typingUsers.add(uid);
                    } else {
                        typingUsers.remove(uid);
                    }
                    updateTypingIndicator();
                }
            });
            if (returnedFromImagePreview) {
                typingStatusManager.stopCheckingTyping(groupId);
            } else if (typingEnabled && !wsManager.isConnected()) {
                typingStatusManager.startCheckingTyping(this, token, groupId, true);
            } else {
                typingStatusManager.stopCheckingTyping(groupId);
                clearTypingState();
            }
        }
    }

    @Override
    protected void onPause() {
        if (lvMessages != null) {
            lvMessages.removeCallbacks(previewReturnRecoveryRunnable);
            lvMessages.removeCallbacks(resumeInlineImagesRunnable);
            lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
            lvMessages.removeCallbacks(resumeBackgroundApplyRunnable);
            lvMessages.removeCallbacks(memberMetaUiRefreshRunnable);
            lvMessages.removeCallbacks(startupInlineResumeRunnable);
        }
        pendingInlineResumeFirst = -1;
        pendingInlineResumeLast = -1;
        pendingInlineResumeOffset = -1;
        if (isFinishing() && chatOpenLoadingController != null) {
            chatOpenLoadingController.release();
        }
        if (lvMessages != null) {
            lvMessages.removeCallbacks(deferredStartupWork);
            lvMessages.removeCallbacks(memberMetaLoadRunnable);
        }
        startupDeferredWorkScheduled = false;
        memberMetaLoadScheduled = false;
        memberMetaRefreshScheduled = false;
        super.onPause();
    }
    
    @Override
    protected void onDestroy() {
        if (groupId != null && groupId.length() > 0) {
            GroupMemberLoader.cancelSync(groupId);
        }
        if (lvMessages != null) {
            lvMessages.removeCallbacks(previewReturnRecoveryRunnable);
            lvMessages.removeCallbacks(resumeInlineImagesRunnable);
            lvMessages.removeCallbacks(resumeInlineImagesBatchRunnable);
            lvMessages.removeCallbacks(resumeBackgroundApplyRunnable);
            lvMessages.removeCallbacks(memberMetaUiRefreshRunnable);
            lvMessages.removeCallbacks(startupInlineResumeRunnable);
            lvMessages.removeCallbacks(deferredStartupWork);
            lvMessages.removeCallbacks(memberMetaLoadRunnable);
        }
        if (adapter != null) {
            adapter.setOnAvatarCacheMiss(null);
            adapter.setActionListener(null);
            adapter.setSelectionModeListener(null);
            adapter.setQuoteClickListener(null);
        }
        if (listHelper != null) listHelper.dispose();
        com.im.oldchat.api.WSManager.getInstance().removeListener(wsListener);
        com.im.oldchat.api.WSManager.getInstance().removeListener(backDotListener);
        typingHandler.removeCallbacksAndMessages(null);
        if (chatOpenLoadingController != null) chatOpenLoadingController.release();
        if (syncBannerHelper != null) syncBannerHelper.release();
        // 释放聊天背景大图，减少 Android 2.3 返回主页时的堆压力（避免 inflate OOM）。
        try {
            if (chatBackground != null) {
                chatBackground.setBackgroundDrawable(null);
                chatBackground = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /**
     * 显示同步消息横幅
     */
    void showSyncBanner(String text) {
        if (syncBannerHelper != null) {
            syncBannerHelper.show(text);
        }
        // 同步期间暂停图片加载，避免同步完成时多张图片同时解码卡顿
        inlineLoadsPausedByScroll = true;
        com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(true);
    }

    /**
     * 隐藏同步消息横幅
     */
    void hideSyncBanner() {
        if (syncBannerHelper != null) {
            syncBannerHelper.startFinishAnimation();
        }
        // 同步完成后恢复图片加载
        inlineLoadsPausedByScroll = false;
        com.im.oldchat.util.ImageLoader.setInlineLoadsPaused(false);
    }
}
