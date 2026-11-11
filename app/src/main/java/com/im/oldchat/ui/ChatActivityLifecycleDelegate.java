package com.im.oldchat.ui;

import android.app.Activity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.ClipboardUtil;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.MyUidStore;

/**
 * ChatActivityLifecycle 的委托类 - 处理启动初始化任务
 * 将长方法拆分以符合代码规范（单文件不超过300行）
 */

final class ChatActivityLifecycleDelegate {
    private ChatActivityLifecycleDelegate() {}
    
    /**
     * 初始化关键视图
     */
    static void initCriticalViews(final ChatActivityLifecycle activity) {
        activity.chatOpenPerfTracer.trace("init_critical_start", null);
        
        // 标题栏
        TextView tvTitle = (TextView) activity.findViewById(R.id.tvChatTitle);
        if (tvTitle != null) {
            String title = activity.friendName;
            if (title == null || title.isEmpty()) {
                title = activity.friendUID;
            }
            tvTitle.setText(title == null ? "" : title);
        }

        ImageView ivAvatar = (ImageView) activity.findViewById(R.id.ivChatAvatar);
        if (ivAvatar != null) {
            activity.ivChatAvatar = ivAvatar;
            if (com.im.oldchat.util.SettingsPrefs.isHideAvatarEnabled(activity)) {
                ivAvatar.setVisibility(View.GONE);
                ivAvatar.setOnClickListener(null);
            } else {
                ivAvatar.setVisibility(View.VISIBLE);
                com.im.oldchat.util.ImageLoader.loadAvatarCacheFirstTryOnce(ivAvatar, activity.friendAvatar);
                ivAvatar.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.openFriendSpace();
                    }
                });
            }
        }
        
        View header = (View) activity.findViewById(R.id.chat_header);
        if (header != null) {
            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activity.openFriendSpace();
                }
            });
        }
        
        // 返回按钮
        View btnBack = (View) activity.findViewById(R.id.btnChatBack);
        if (btnBack instanceof ImageView) {
            ((ImageView) btnBack).setColorFilter(activity.getResources().getColor(R.color.colorTextPrimary));
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activity.onBackPressed();
                }
            });
        }

        activity.lvMessages = (android.support.v7.widget.RecyclerView) activity.findViewById(R.id.lvMessages);

        // 头像飞入与顶栏切换动画
        com.im.oldchat.util.ChatHeaderEnterAnimHelper.runEnterAnimation(activity, ivAvatar, tvTitle, btnBack, activity.lvMessages);
        activity.chatOpenLoadingMask = (android.view.View) activity.findViewById(R.id.chat_open_loading_mask);
        activity.backDot = (android.view.View) activity.findViewById(R.id.vBackDot);
        activity.btnNewMessage = (android.widget.TextView) activity.findViewById(R.id.btnNewMessage);
        activity.btnJumpToUnread = (android.widget.TextView) activity.findViewById(R.id.btnJumpToUnread);
        activity.pbSend = (android.widget.ProgressBar) activity.findViewById(R.id.pbSend);
        
        // 加载token和UID
        android.content.SharedPreferences prefs = activity.getSharedPreferences("auth", Activity.MODE_PRIVATE);
        activity.token = prefs.getString("access_token", "");
        activity.myUID = prefs.getString("my_uid", "");
        
        activity.typingStatusManager = com.im.oldchat.util.TypingStatusManager.getInstance();
        
        if (activity.chatOpenLoadingMask != null) {
            activity.chatOpenLoadingMask.setVisibility(View.GONE);
        }
        
        activity.chatOpenPerfTracer.trace("init_critical_done", null);
    }
    
    /**
     * 初始化RecyclerView
     */
    static void initRecyclerView(ChatActivityLifecycle activity) {
        com.im.oldchat.util.ChatStartupOptimizer.optimizeRecyclerViewForStartup(activity.lvMessages);
        
        com.im.oldchat.ui.widget.SafeLinearLayoutManager layoutManager = new com.im.oldchat.ui.widget.SafeLinearLayoutManager(activity);
        layoutManager.setStackFromEnd(true);
        activity.lvMessages.setLayoutManager(layoutManager);
    }
    
    /**
     * 初始化适配器
     */
    static void initAdapter(final ChatActivityLifecycle activity) {
        activity.adapter = new MessageAdapter(activity, activity.messageList, activity.myUID);
        activity.adapter.setFriendUid(activity.friendUID);
        
        activity.adapter.setActionListener(new MessageAdapter.MessageActionListener() {
            @Override
            public void onQuote(Message message) {
                activity.quoteMessage(message);
            }

            @Override
            public void onCopy(String text) {
                ClipboardUtil.copyText(activity, text);
            }

            @Override
            public void onRecall(Message message) {
                activity.recallMessage(message);
            }

            @Override
            public void onReEdit(Message message) {
                activity.reEditRecalledMessage(message);
            }

            @Override
            public void onVoiceTranscribe(Message message) {
            }

            @Override
            public void onForward(Message message) {
                activity.forwardSingleMessage(message);
            }

            @Override
            public void onEnterMultiSelect(Message message) {
                activity.enterForwardMultiSelect(message);
            }
        });
        
        activity.adapter.setSelectionModeListener(new MessageAdapter.SelectionModeListener() {
            @Override
            public void onSelectionModeChanged(boolean enabled, int count) {
                activity.onForwardSelectionStateChanged(enabled, count);
            }
        });
        
        activity.lvMessages.setAdapter(activity.adapter);
    }
    
    /**
     * 初始化列表帮助类
     */
    static void initListHelper(final ChatActivityLifecycle activity) {
        activity.btnLoadMore = null;
        activity.pbMessagesLoading = (android.widget.ProgressBar) activity.findViewById(R.id.pbMessagesLoading);
        
        activity.listHelper = new DirectChatListHelper(activity, activity.lvMessages, activity.adapter, 
                activity.messageList, activity.messageIds,
                activity.btnLoadMore, activity.btnNewMessage, activity.pbMessagesLoading,
                activity.friendUID, activity.friendName, activity.friendAvatar, activity.myUID, 
                activity.chatOpenPerfTracer);
        activity.listHelper.friendNcuid = activity.friendNcuid;
        
        activity.adapter.setQuoteClickListener(new MessageAdapter.QuoteClickListener() {
            @Override
            public void onQuoteClick(MessagePayload.Quote quote) {
                if (quote == null || quote.id == null || quote.id.isEmpty()) {
                    return;
                }
                if (activity.listHelper != null) {
                    activity.listHelper.jumpToMessageId(activity.token, quote.id);
                }
            }
        });
        
        if (activity.listHelper != null && activity.btnJumpToUnread != null) {
            activity.listHelper.setJumpToUnreadButton(activity.btnJumpToUnread);
            int unreadCount = activity.getIntent().getIntExtra("unread_count", 0);
            if (unreadCount > 0) {
                activity.listHelper.setInitialUnreadCount(unreadCount);
            }
            activity.btnJumpToUnread.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (activity.listHelper != null) {
                        activity.listHelper.jumpToEarliestUnread(activity.token);
                    }
                }
            });
        }
        
        if (activity.btnNewMessage != null) {
            activity.btnNewMessage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (activity.listHelper != null) {
                        activity.listHelper.scrollToBottom();
                    }
                }
            });
        }
    }
    
    /**
     * 初始化基本监听器
     */
    static void initBasicListeners(final ChatActivityLifecycle activity, View btnActionRedPacket) {
        if (btnActionRedPacket != null) {
            btnActionRedPacket.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activity.openRedPacketSend();
                }
            });
        }
        
        activity.wsListener = new ChatWsListener(activity.asChatActivity(), activity.friendUID, 
                activity.myUID, activity.messageIds, activity.messageList, activity.adapter, 
                activity.lvMessages, activity.listHelper,
                new Runnable() {
                    @Override
                    public void run() {
                        if (activity.listHelper != null) {
                            activity.listHelper.markRead(activity.token);
                        }
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        if (activity.listHelper != null) {
                            activity.listHelper.onNewMessageReceived();
                        }
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        if (activity.listHelper == null || activity.token == null || activity.token.length() == 0) {
                            return;
                        }
                        boolean keepPosition = !activity.listHelper.isAtBottom();
                        activity.showSyncStatus();
                        activity.listHelper.loadMessages(activity.token, false, 0, true, keepPosition);
                    }
                });
    }
    
    /**
     * 加载初始消息
     */
    static void loadInitialMessages(ChatActivityLifecycle activity) {
        String jumpMessageId = activity.getIntent().getStringExtra("jump_message_id");
        if (jumpMessageId != null && !jumpMessageId.isEmpty()) {
            activity.listHelper.jumpToMessageId(activity.token, jumpMessageId);
        } else {
            activity.listHelper.loadMessages(activity.token, false, 0, false);
        }
    }
    
    /**
     * 初始化输入框和草稿
     */
    static void initInputAndDraft(final ChatActivityLifecycle activity) {
        activity.etInput = (android.widget.EditText) activity.findViewById(R.id.etInput);
        activity.btnSend = (android.widget.Button) activity.findViewById(R.id.btnSend);
        
        // 输入框剩余字符计数（最大 400 字符，右下角显示剩余）
        ChatCharCounter.bind(activity.etInput,
                (android.widget.TextView) activity.findViewById(R.id.tvCharCount));
        
        ChatInputMenuHelper.bind(activity.etInput, new ChatInputMenuHelper.SendAction() {
            @Override
            public void onSend() {
                activity.sendFromInput();
            }
        });
        activity.registerForContextMenu(activity.etInput);
        
        activity.lastDraft = ChatDraftHelper.restoreDraft(activity, activity.etInput, activity.getDraftKey());
        
        activity.btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.sendFromInput();
            }
        });
        ChatSendButtonUi.update(activity.btnSend, activity.etInput, false, false);
    }
    
    /**
     * 初始化引用预览
     */
    static void initQuotePreview(final ChatActivityLifecycle activity) {
        activity.quotePreview = (android.view.View) activity.findViewById(R.id.quote_preview);
        activity.tvQuotePreview = (android.widget.TextView) activity.findViewById(R.id.tvQuotePreview);
        View btnQuoteClose = activity.findViewById(R.id.btnQuoteClose);
        if (btnQuoteClose != null) {
            btnQuoteClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activity.clearQuoteDraft();
                }
            });
        }
    }
    
    /**
     * 初始化背景
     */
    static void initBackground(ChatActivityLifecycle activity) {
        View chatBackground = (View) activity.findViewById(R.id.chat_background);
        activity.backgroundHelper = new ChatBackgroundHelper(activity, chatBackground, activity.friendUID, false);
        if (activity.backgroundHelper != null) {
            activity.backgroundHelper.applyBackground();
        }
        if (activity.chatOpenPerfTracer != null) {
            activity.chatOpenPerfTracer.trace("background_applied", null);
        }
    }
    
    /**
     * 初始化媒体帮助类
     */
    static void initMediaHelper(final ChatActivityLifecycle activity,
                                 View btnActionImage, View btnActionVideo, View btnToggleVoice,
                                 View btnPlus, View btnHoldToTalk, View actionPanel,
                                 View videoProgressContainer, ProgressBar pbVideoProgress,
                                 TextView tvVideoProgress) {
        // 先创建 messageSender，因为 bubble controller 需要引用它
        activity.messageSender = new DirectMessageSender(activity, activity.listHelper, activity.btnLoadMore,
                activity.etInput, activity.token, activity.friendUID, activity.friendNcuid, activity.myUID,
                new DirectMessageSender.SendStateListener() {
                    @Override
                    public void onSendState(boolean sending) {
                        activity.setSending(sending);
                    }
                },
                new DirectMessageSender.QuoteClearListener() {
                    @Override
                    public void onClearQuote() {
                        activity.clearQuoteDraft();
                    }
                });
        final DirectFileUploadBubbleController directFileUploadBubbleController =
                new DirectFileUploadBubbleController(activity.messageList, activity.messageIds, activity.adapter,
                        activity.listHelper, activity.messageSender, activity.myUID,
                        new DirectFileUploadBubbleController.QuoteProvider() {
                            @Override
                            public com.im.oldchat.util.MessagePayload.Quote getQuoteDraft() {
                                return activity.quoteDraft;
                            }
                        });
        final DirectImageUploadBubbleController directImageUploadBubbleController =
                new DirectImageUploadBubbleController(activity.listHelper, activity.messageSender, activity.myUID,
                        new DirectImageUploadBubbleController.QuoteProvider() {
                            @Override
                            public com.im.oldchat.util.MessagePayload.Quote getQuoteDraft() {
                                return activity.quoteDraft;
                            }
                        },
                        new DirectImageUploadBubbleController.QuoteClearer() {
                            @Override
                            public void clearQuoteDraft() {
                                activity.clearQuoteDraft();
                            }
                        });
        
        activity.mediaHelper = new ChatMediaHelper(activity, activity.token, activity.etInput, activity.btnSend, 
                btnToggleVoice, btnPlus, btnHoldToTalk, actionPanel, btnActionImage, btnActionVideo,
                videoProgressContainer, pbVideoProgress, tvVideoProgress,
                new ChatMediaHelper.MediaCallback() {
                    @Override
                    public void onMediaReady(String type, String url, String thumbUrl, int durationMs) {
                        if (activity.messageSender != null) {
                            activity.messageSender.sendMedia(type, url, thumbUrl, durationMs, activity.quoteDraft);
                        }
                    }
                },
                new ChatMediaHelper.SendStateListener() {
                    @Override
                    public void onSendState(boolean sending) {
                        activity.setSending(sending);
                    }
                }, directFileUploadBubbleController);
        activity.mediaHelper.setImageUploadListener(directImageUploadBubbleController);
        activity.mediaHelper.bind();
        activity.mediaHelper.syncBurnModeUiState();
    }
    
    /**
     * 初始化表情按钮
     */
    static void initEmojiButton(final Activity activity, View btnActionEmoji) {
        if (btnActionEmoji != null) {
            btnActionEmoji.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (activity instanceof ChatActivityLifecycle) {
                        ((ChatActivityLifecycle) activity).openEmojiPicker();
                    }
                }
            });
        }
    }
    
    /**
     * 初始化聊天菜单按钮
     */
    static void initChatMenuButton(final ChatActivityLifecycle activity) {
        View btnChatMenu = (View) activity.findViewById(R.id.btnChatMenu);
        if (btnChatMenu instanceof ImageView) {
            ((ImageView) btnChatMenu).setColorFilter(activity.getResources().getColor(R.color.colorTextPrimary));
        }
        if (btnChatMenu != null) {
            btnChatMenu.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activity.openChatSettings();
                }
            });
        }
    }
}
