package com.im.oldchat.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliModels;

public class OldViewVideoDetailActivity extends OldViewVideoDetailSupport7 {
    private OldViewVideoActionController videoActionController;
    private long currentOwnerMid;
    private String currentOwnerName;
    private boolean ownerFollowed;
    private boolean ownerFollowLoading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_old_view_detail);
        logStep("onCreate");

        bindViews();
        videoActionController = new OldViewVideoActionController(this);
        setupHeaderAndList();
        setupActions();
        readIntentArgs();
        loadDetail();
        loadRelatedVideos(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (enteringFullscreen) {
            pausePlaybackForFullscreen();
            return;
        }
        stopPlayback(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (enteringFullscreen) {
            enteringFullscreen = false;
        }
        if (videoActionController != null) {
            videoActionController.refreshLikeState(true);
        }
    }

    @Override
    protected void onDestroy() {
        stopPlayback(false);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FULLSCREEN) {
            enteringFullscreen = false;
            int pos = data != null ? data.getIntExtra(OldViewVideoFullActivity.EXTRA_RESULT_POSITION, 0) : 0;
            boolean completed = data != null && data.getBooleanExtra(OldViewVideoFullActivity.EXTRA_COMPLETED, false);
            if (completed) {
                stopPlayback(true);
                return;
            }
            if (pos > 0) {
                lastPlaybackPos = pos;
            }
            if (currentPlayUrl != null && currentPlayUrl.length() > 0) {
                pausedByScroll = true;
                isPlaying = false;
                hasStarted = true;
                playUrl(currentPlayUrl);
            }
        }
    }

    @Override
    protected void bindDetail(BiliModels.VideoDetailData data) {
        super.bindDetail(data);
        bindOwnerProfileEntry(data);
        resetDescriptionState();
        loadRelatedVideos(false);
    }

    protected void bindViews() {
        pbLoading = findViewByIdCompat(R.id.pbOldViewDetailLoading);
        playerView = findViewByIdCompat(R.id.oldViewPlayerView);
        lvComments = findViewByIdCompat(R.id.lvOldViewDetailComments);
        tvEmpty = findViewByIdCompat(R.id.tvOldViewDetailEmpty);
        btnShare = findViewByIdCompat(R.id.btnOldViewDetailShare);
        etComment = findViewByIdCompat(R.id.etOldViewComment);
        btnSendComment = findViewByIdCompat(R.id.btnOldViewCommentSend);
        layoutCommentInput = findViewByIdCompat(R.id.layoutOldViewCommentInput);
        if (btnShare instanceof ImageView) {
            ((ImageView) btnShare).setColorFilter(getResources().getColor(R.color.color_text_primary));
        }
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");
    }

    protected void setupHeaderAndList() {
        if (lvComments != null) {
            int playerHeight = (int) (getResources().getDisplayMetrics().widthPixels * 9f / 16f);
            playerSpacerView = new View(this);
            playerSpacerView.setLayoutParams(new AbsListView.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, playerHeight));
            lvComments.addHeaderView(playerSpacerView, null, false);
            headerView = getLayoutInflater().inflate(R.layout.view_old_view_detail_header, lvComments, false);
            lvComments.addHeaderView(headerView, null, false);
        }

        if (headerView == null) {
            return;
        }

        if (playerView != null) {
            layoutPlayer = playerView.getPlayerLayout();
            ivCover = playerView.getCoverView();
            vvPlayer = playerView.getVideoView();
            btnPlay = playerView.getPlayButton();
            btnFullscreen = playerView.getFullscreenButton();
            btnRetry = playerView.getRetryButton();
            layoutPlayerLoading = playerView.getLoadingLayout();
            pbPlayerLoading = playerView.getLoadingView();
            tvPlayerHint = playerView.getHintView();
            layoutPlayerControls = playerView.getControlsLayout();
            btnPlayPause = playerView.getPlayPauseButton();
            sbSeek = playerView.getSeekBar();
            tvTime = playerView.getTimeView();
        }
        bindPlayerControls();
        layoutOwner = headerView.findViewById(R.id.layoutOldViewOwner);
        ivOwnerAvatar = (ImageView) headerView.findViewById(R.id.ivOldViewDetailOwnerAvatar);
        tvOwnerName = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailOwnerName);
        tvOwnerTag = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailOwnerTag);
        tvVideoTitle = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailVideoTitle);
        tvMeta = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailMeta);
        layoutStats = headerView.findViewById(R.id.layoutOldViewStats);
        tvStatPlay = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailStatPlay);
        tvStatDanmaku = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailStatDanmaku);
        tvStatReply = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailStatReply);
        tvStatLike = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailStatLike);
        tvDesc = (android.widget.TextView) headerView.findViewById(R.id.tvOldViewDetailDesc);

        // YouTube 式信息层级：播放器 → 标题/数据 → 操作栏 → 作者。
        View actionRow = headerView.findViewById(R.id.layoutOldViewVideoActions);
        View headerRoot = headerView.findViewById(R.id.layoutOldViewDetailHeaderRoot);
        if (headerRoot instanceof android.view.ViewGroup && layoutOwner != null && actionRow != null) {
            android.view.ViewGroup root = (android.view.ViewGroup) headerRoot;
            if (layoutOwner.getParent() == root) {
                root.removeView(layoutOwner);
                int actionIndex = root.indexOfChild(actionRow);
                root.addView(layoutOwner, Math.min(actionIndex + 1, root.getChildCount()));
            }
        }
        View headerShare = headerView.findViewById(R.id.btnOldViewVideoShare);
        if (headerShare != null) {
            headerShare.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openShareMenu();
                }
            });
        }

        if (videoActionController != null) {
            videoActionController.bindHeader(headerView);
        }
        bindBottomTabs(headerView);

        commentAdapter = new OldViewCommentAdapter(this);
        bindCommentActions();
        relatedAdapter = new OldViewVideoAdapter(this);

        if (lvComments != null) {
            lvComments.setAdapter(relatedAdapter);
            lvComments.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (commentTabSelected) {
                        return;
                    }
                    Object obj = parent.getItemAtPosition(position);
                    if (obj instanceof BiliModels.RecommendItem) {
                        openRelatedVideo((BiliModels.RecommendItem) obj);
                    }
                }
            });
            lvComments.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    // 快速滑动停止后再做一次可见性判断，确保视频滚回后恢复播放。
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        updatePlayerVisibility();
                    }
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    updatePlayerCollapse(firstVisibleItem);
                    trackPlaybackPositionDuringScroll();
                    updatePlayerVisibility();
                    if (!commentTabSelected) {
                        return;
                    }
                    if (!hasMoreComments || isLoadingComments || commentPage <= 0 || totalItemCount <= 0) {
                        return;
                    }
                    int last = firstVisibleItem + visibleItemCount;
                    if (last >= totalItemCount - 2) {
                        loadComments(commentPage + 1, false);
                    }
                }
            });
        }
    }

    /**
     * 播放器本身始终附着在 Activity；列表中的透明 spacer 提供初始占位。
     * 向上滚动时仅平移播放器，因此评论可以占满屏幕，同时 Surface 不会被回收。
     */
    protected void updatePlayerCollapse(int firstVisibleItem) {
        if (playerView == null || playerSpacerView == null) return;
        int height = playerView.getHeight();
        if (height <= 0) {
            height = (int) (getResources().getDisplayMetrics().widthPixels * 9f / 16f);
        }
        int collapse;
        if (firstVisibleItem <= 0 && playerSpacerView.getParent() != null) {
            collapse = Math.max(0, -playerSpacerView.getTop());
        } else {
            collapse = height;
        }
        collapse = Math.min(height, collapse);
        android.support.v4.view.ViewCompat.setTranslationY(playerView, -collapse);
    }

    protected void readIntentArgs() {
        Intent intent = getIntent();
        if (intent != null) {
            currentBvid = intent.getStringExtra(EXTRA_BVID);
            currentAid = intent.getLongExtra(EXTRA_AID, 0L);
            currentCid = intent.getLongExtra(EXTRA_CID, 0L);
            preloadUrl = intent.getStringExtra(EXTRA_PRELOAD_URL);
            coverUrl = intent.getStringExtra(EXTRA_COVER);
            String title = intent.getStringExtra(EXTRA_TITLE);
            if (title != null && title.length() > 0 && tvVideoTitle != null) {
                tvVideoTitle.setText(title);
                currentTitle = title;
            }
        }
        if (!isValidBvid(currentBvid)) {
            currentBvid = null;
        }
        if (currentAid <= 0 && currentBvid != null) {
            long aidFromBvid = bvidToAid(currentBvid);
            if (aidFromBvid > 0) {
                currentAid = aidFromBvid;
                logStep("intent: computed aid=" + currentAid + " from bvid");
            }
        }
        logStep("intent: bvid=" + (currentBvid != null ? currentBvid : "") + " aid=" + currentAid
                + " cid=" + currentCid + " preload=" + (preloadUrl != null && preloadUrl.length() > 0));
        if (videoActionController != null) {
            videoActionController.onVideoMeta(currentAid, currentBvid, -1);
        }
    }

    @Override
    protected void bindStats(BiliModels.VideoDetailData data) {
        super.bindStats(data);
        if (data != null) {
            if (data.aid > 0) {
                currentAid = data.aid;
            }
            if (data.bvid != null && data.bvid.trim().length() > 0) {
                currentBvid = data.bvid.trim();
            }
            int likes = data.stat != null ? data.stat.like : -1;
            if (videoActionController != null) {
                videoActionController.onVideoMeta(currentAid, currentBvid, likes);
            }
        }
    }

    /** YouTube 风格底部控制条：播放/暂停、进度拖动、时间显示。 */
    protected void bindPlayerControls() {
        if (layoutPlayerControls == null) {
            return;
        }
        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePlayPause();
                }
            });
        }
        if (sbSeek != null) {
            sbSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        seekPending = true;
                    }
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    if (vvPlayer == null || !hasStarted) {
                        return;
                    }
                    int duration = vvPlayer.getDuration();
                    if (duration <= 0) {
                        return;
                    }
                    int target = (int) (duration * (seekBar.getProgress() / 1000f));
                    try {
                        vvPlayer.seekTo(target);
                    } catch (Exception e) {
                    }
                    seekPending = false;
                }
            });
        }
        startProgressTicker();
    }

    protected boolean seekPending = false;

    /** 播放/暂停切换。 */
    protected void togglePlayPause() {
        if (vvPlayer == null || !hasStarted) {
            return;
        }
        try {
            if (isPlaying) {
                vvPlayer.pause();
                isPlaying = false;
                updatePlayPauseIcon();
            } else {
                vvPlayer.start();
                isPlaying = true;
                pausedByScroll = false;
                updatePlayPauseIcon();
            }
        } catch (Exception e) {
        }
    }

    protected void updatePlayPauseIcon() {
        if (btnPlayPause == null) {
            return;
        }
        btnPlayPause.setImageResource(isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
    }

    /** 进度定时刷新（1秒）。 */
    protected void startProgressTicker() {
        if (progressTickerStarted) {
            return;
        }
        progressTickerStarted = true;
        final Handler handler = new Handler();
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                updatePlayerProgress();
                handler.postDelayed(this, 1000L);
            }
        };
        handler.postDelayed(ticker, 1000L);
    }

    protected boolean progressTickerStarted = false;

    protected void updatePlayerProgress() {
        if (vvPlayer == null) {
            return;
        }
        try {
            if (!hasStarted) {
                return;
            }
            int pos = safeGetPlaybackPosition();
            int duration = vvPlayer.getDuration();
            if (duration <= 0) {
                return;
            }
            // 持续追踪播放位置：滚动暂停或 Surface 回收后仍能恢复到正确进度。
            if (isPlaying && pos > 0) {
                lastPlaybackPos = pos;
            }
            if (!seekPending && sbSeek != null) {
                sbSeek.setProgress((int) (pos * 1000f / duration));
            }
            if (tvTime != null) {
                tvTime.setText(formatDuration(pos) + " / " + formatDuration(duration));
            }
            updatePlayPauseIcon();
            // 播放开始后中央按钮始终隐藏，播放/暂停仅由底部控制条表达。
            if (btnPlay != null) {
                btnPlay.setVisibility(View.GONE);
            }
            if (layoutPlayerControls != null) {
                layoutPlayerControls.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
        }
    }

    protected String formatDuration(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return (min < 10 ? "0" + min : String.valueOf(min)) + ":"
                + (sec < 10 ? "0" + sec : String.valueOf(sec));
    }

    private void bindOwnerProfileEntry(BiliModels.VideoDetailData data) {
        currentOwnerMid = 0L;
        currentOwnerName = "";
        if (data != null && data.owner != null) {
            currentOwnerMid = data.owner.mid;
            currentOwnerName = data.owner.name != null ? data.owner.name : "";
        }
        if (layoutOwner == null) {
            return;
        }
        if (currentOwnerMid <= 0) {
            layoutOwner.setOnClickListener(null);
            layoutOwner.setClickable(false);
            if (ivOwnerAvatar != null) {
                ivOwnerAvatar.setOnClickListener(null);
            }
            if (tvOwnerName != null) {
                tvOwnerName.setOnClickListener(null);
            }
            if (tvOwnerTag != null) {
                tvOwnerTag.setOnClickListener(null);
            }
            return;
        }
        View.OnClickListener openOwner = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(OldViewVideoDetailActivity.this, OldViewUpProfileActivity.class);
                intent.putExtra(OldViewUpProfileActivity.EXTRA_UP_MID, currentOwnerMid);
                intent.putExtra(OldViewUpProfileActivity.EXTRA_UP_NAME, currentOwnerName);
                startActivity(intent);
            }
        };
        layoutOwner.setClickable(true);
        layoutOwner.setOnClickListener(openOwner);
        if (ivOwnerAvatar != null) {
            ivOwnerAvatar.setOnClickListener(openOwner);
        }
        if (tvOwnerName != null) {
            tvOwnerName.setOnClickListener(openOwner);
        }
        if (tvOwnerTag != null) {
            tvOwnerTag.setOnClickListener(openOwner);
        }
        bindSubscribeButton();
    }

    private void bindSubscribeButton() {
        final android.widget.TextView button = headerView == null ? null
                : (android.widget.TextView) headerView.findViewById(R.id.btnOldViewSubscribe);
        if (button == null) return;
        if (currentOwnerMid <= 0) {
            button.setVisibility(View.GONE);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setText("订阅");
        button.setEnabled(false);
        final String accessToken = com.im.oldchat.bili.BiliAuthStore.getAccessToken(this);
        final String cookie = com.im.oldchat.bili.BiliAuthStore.getCookies(this);
        OldViewUpProfileActionHelper.queryFollowState(currentOwnerMid, accessToken, cookie,
                new OldViewUpProfileActionHelper.FollowStateCallback() {
                    @Override
                    public void onDone(boolean ok, String msg, boolean followed) {
                        ownerFollowed = ok && followed;
                        button.setText(ownerFollowed ? "已订阅" : "订阅");
                        button.setEnabled(true);
                    }
                });
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ownerFollowLoading) return;
                ownerFollowLoading = true;
                button.setEnabled(false);
                button.setText("处理中...");
                final boolean targetFollow = !ownerFollowed;
                OldViewUpProfileActionHelper.toggleFollow(currentOwnerMid, targetFollow,
                        accessToken, cookie, new OldViewUpProfileActionHelper.ToggleFollowCallback() {
                            @Override
                            public void onDone(boolean ok, String msg, boolean followedAfter) {
                                ownerFollowLoading = false;
                                if (ok) ownerFollowed = followedAfter;
                                button.setText(ownerFollowed ? "已订阅" : "订阅");
                                button.setEnabled(true);
                                if (msg != null && msg.length() > 0) {
                                    android.widget.Toast.makeText(OldViewVideoDetailActivity.this,
                                            msg, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
            }
        });
    }
}
