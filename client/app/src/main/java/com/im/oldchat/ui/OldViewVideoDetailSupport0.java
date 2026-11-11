package com.im.oldchat.ui;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;
import com.im.oldchat.util.MediaUrlResolver;

abstract class OldViewVideoDetailSupport0 extends OldViewVideoDetailSupport1 {
    private String[] playbackCandidates;
    private int playbackCandidateIndex;

    protected void startPlayback() {
        if (hasStarted && pausedByScroll) {
            resumePlaybackFromScroll();
            return;
        }
        if (isPlaying) {
            return;
        }
        showRetryButton(false);
        setPlayerHint("准备播放...");
        lastPlaybackPos = 0;
        if (preloadUrl != null && preloadUrl.length() > 0) {
            logStep("startPlayback: use preload url");
            playUrl(preloadUrl);
            return;
        }
        if (currentAid <= 0 || currentCid <= 0) {
            Toast.makeText(this, "视频信息不完整", Toast.LENGTH_SHORT).show();
            showRetryButton(true);
            return;
        }
        showLoading(true);
        showPlayerLoading(true, "正在获取播放地址...");
        logStep("startPlayback: aid=" + currentAid + " cid=" + currentCid + " bvid=" + (currentBvid != null ? currentBvid : ""));
        String cookie = BiliAuthStore.getCookies(this);
        BiliApi.requestPlayUrl(currentBvid, currentAid, currentCid, 16, cookie, new BiliApi.ApiCallback<BiliModels.PlayUrlResult>() {
            @Override
            public void onSuccess(BiliModels.PlayUrlResult response) {
                showLoading(false);
                showPlayerLoading(false, null);
                if (response != null && response.code == 0 && response.data != null
                        && response.data.durl != null && !response.data.durl.isEmpty()) {
                    String url = response.data.durl.get(0).url;
                    if (url != null && url.length() > 0) {
                        logStep("startPlayback: got url");
                        playUrl(url);
                        return;
                    }
                }
                String msg = response != null && response.message != null ? response.message : "无法获取播放地址";
                logError("PlayUrl empty: code=" + (response != null ? response.code : -1) + " msg=" + msg
                        + " aid=" + currentAid + " cid=" + currentCid);
                showRetryButton(true);
                setPlayerHint("播放地址获取失败");
                Toast.makeText(OldViewVideoDetailSupport0.this, msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                showPlayerLoading(false, null);
                showRetryButton(true);
                setPlayerHint("网络异常，请重试");
                logError("PlayUrl error: " + (error != null ? error : "unknown"));
                Toast.makeText(OldViewVideoDetailSupport0.this, error != null ? error : "获取播放地址失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected void openFullscreen() {
        if (currentPlayUrl != null && currentPlayUrl.length() > 0) {
            launchFullscreen(currentPlayUrl);
            return;
        }
        if (preloadUrl != null && preloadUrl.length() > 0) {
            launchFullscreen(preloadUrl);
            return;
        }
        if (currentAid <= 0 || currentCid <= 0) {
            Toast.makeText(this, "视频信息不完整", Toast.LENGTH_SHORT).show();
            return;
        }
        showLoading(true);
        showPlayerLoading(true, "正在获取播放地址...");
        String cookie = BiliAuthStore.getCookies(this);
        BiliApi.requestPlayUrl(currentBvid, currentAid, currentCid, 16, cookie, new BiliApi.ApiCallback<BiliModels.PlayUrlResult>() {
            @Override
            public void onSuccess(BiliModels.PlayUrlResult response) {
                showLoading(false);
                showPlayerLoading(false, null);
                if (response != null && response.code == 0 && response.data != null
                        && response.data.durl != null && !response.data.durl.isEmpty()) {
                    String url = response.data.durl.get(0).url;
                    if (url != null && url.length() > 0) {
                        launchFullscreen(url);
                        return;
                    }
                }
                String msg = response != null && response.message != null ? response.message : "无法获取播放地址";
                Toast.makeText(OldViewVideoDetailSupport0.this, msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                showPlayerLoading(false, null);
                Toast.makeText(OldViewVideoDetailSupport0.this, error != null ? error : "获取播放地址失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected void launchFullscreen(String url) {
        if (url == null || url.length() == 0) {
            Toast.makeText(this, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        currentPlayUrl = MediaUrlResolver.resolve(url);
        if (currentPlayUrl == null || currentPlayUrl.length() == 0) {
            Toast.makeText(this, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        int pos = safeGetPlaybackPosition();
        enteringFullscreen = true;
        pausePlaybackForFullscreen();
        Intent intent = new Intent(this, OldViewVideoFullActivity.class);
        intent.putExtra(OldViewVideoFullActivity.EXTRA_URL, currentPlayUrl);
        intent.putExtra(OldViewVideoFullActivity.EXTRA_POSITION, pos);
        intent.putExtra(OldViewVideoFullActivity.EXTRA_TITLE, buildShareTitle());
        startActivityForResult(intent, REQ_FULLSCREEN);
    }

    protected void playUrl(String url) {
        playbackCandidates = MediaUrlResolver.resolveCandidates(url);
        playbackCandidateIndex = 0;
        String first = firstPlaybackCandidate(playbackCandidates);
        playUrlInternal(first, true);
    }

    private String firstPlaybackCandidate(String[] candidates) {
        if (candidates == null) {
            return null;
        }
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] != null && candidates[i].length() > 0) {
                playbackCandidateIndex = i;
                return candidates[i];
            }
        }
        return null;
    }

    private boolean playNextCandidate() {
        if (playbackCandidates == null) {
            return false;
        }
        while (++playbackCandidateIndex < playbackCandidates.length) {
            String next = playbackCandidates[playbackCandidateIndex];
            if (next == null || next.length() == 0 || next.equals(currentPlayUrl)) {
                continue;
            }
            showPlayerLoading(true, "正在切换备用线路...");
            playUrlInternal(next, true);
            return true;
        }
        return false;
    }

    private void playUrlInternal(String url, boolean preferHttps) {
        if (vvPlayer == null) {
            return;
        }
        String resolved = url != null && (url.startsWith("http://") || url.startsWith("https://"))
                ? url : MediaUrlResolver.resolve(url);
        if (resolved == null || resolved.length() == 0) {
            showRetryButton(true);
            setPlayerHint("播放地址无效");
            Toast.makeText(this, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }

        String targetUrl = resolved;
        String fallbackHttpUrl = null;
        if (preferHttps && resolved.startsWith("http://")) {
            targetUrl = "https://" + resolved.substring("http://".length());
            fallbackHttpUrl = resolved;
        }

        currentPlayUrl = resolved;
        hasStarted = true;
        isPlaying = true;
        pausedByScroll = false;
        logStep("playUrl: start target=" + targetUrl);

        showRetryButton(false);
        showPlayerLoading(true, "视频加载中...");

        if (ivCover != null) {
            ivCover.setVisibility(View.GONE);
        }
        if (btnPlay != null) {
            btnPlay.setVisibility(View.GONE);
        }
        vvPlayer.setVisibility(View.VISIBLE);

        try {
            vvPlayer.stopPlayback();
        } catch (Exception e) {
        }

        // 页面已有自定义播放/暂停与 SeekBar；不再绑定 VideoView 原生
        // MediaController，避免屏幕上同时出现两套进度条和控制栏。
        vvPlayer.setMediaController(null);

        final String retryUrl = fallbackHttpUrl;
        vvPlayer.setVideoPath(targetUrl);
        vvPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                logStep("playUrl: prepared");
                showPlayerLoading(false, null);
                setPlayerHint("正在播放");
                showRetryButton(false);
                mp.setLooping(false);
                applyVideoLayout(mp.getVideoWidth(), mp.getVideoHeight());
                mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                        applyVideoLayout(width, height);
                    }
                });
                if (lastPlaybackPos > 0) {
                    vvPlayer.seekTo(lastPlaybackPos);
                }
                if (isPlayerVisible()) {
                    try {
                        vvPlayer.start();
                        isPlaying = true;
                        pausedByScroll = false;
                    } catch (Exception e) {
                        isPlaying = false;
                        pausedByScroll = true;
                    }
                } else {
                    isPlaying = false;
                    pausedByScroll = true;
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            vvPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        showPlayerLoading(true, "缓冲中...");
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                            || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        showPlayerLoading(false, null);
                    }
                    return false;
                }
            });
        }

        vvPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                logStep("playUrl: completed");
                showPlayerLoading(false, null);
                lastPlaybackPos = 0;
                hasStarted = false;
                pausedByScroll = false;
                stopPlayback(true);
            }
        });

        vvPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                logError("playUrl error what=" + what + " extra=" + extra + " retry=" + (retryUrl != null));
                if (retryUrl != null && retryUrl.length() > 0) {
                    showPlayerLoading(true, "正在切换兼容线路...");
                    playUrlInternal(retryUrl, false);
                    return true;
                }
                if (playNextCandidate()) {
                    return true;
                }
                lastPlaybackPos = 0;
                hasStarted = false;
                pausedByScroll = false;
                showPlayerLoading(false, null);
                stopPlayback(true);
                showRetryButton(true);
                setPlayerHint("播放失败，点击重试");
                Toast.makeText(OldViewVideoDetailSupport0.this, "播放失败", Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        vvPlayer.requestFocus();
    }

    protected void applyVideoLayout(final int videoW, final int videoH) {
        if (vvPlayer == null || videoW <= 0 || videoH <= 0) {
            return;
        }
        lastVideoWidth = videoW;
        lastVideoHeight = videoH;
        View container = layoutPlayer != null ? layoutPlayer : vvPlayer;
        int cw = container.getWidth();
        int ch = container.getHeight();
        if (cw <= 0 || ch <= 0) {
            container.post(new Runnable() {
                @Override
                public void run() {
                    applyVideoLayout(videoW, videoH);
                }
            });
            return;
        }
        float videoRatio = videoW / (float) videoH;
        float containerRatio = cw / (float) ch;
        int targetW;
        int targetH;
        if (videoRatio > containerRatio) {
            targetW = cw;
            targetH = (int) (cw / videoRatio);
        } else {
            targetH = ch;
            targetW = (int) (ch * videoRatio);
        }
        if (targetW <= 0 || targetH <= 0) {
            return;
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER);
        vvPlayer.setLayoutParams(params);
        try {
            vvPlayer.getHolder().setFixedSize(videoW, videoH);
        } catch (Exception e) {
        }
    }

    protected void stopPlayback(boolean resetUi) {
        if (vvPlayer == null) {
            return;
        }
        try {
            vvPlayer.stopPlayback();
        } catch (Exception e) {
            try {
                if (vvPlayer.isPlaying()) {
                    vvPlayer.pause();
                }
            } catch (Exception ignored) {
            }
        }
        isPlaying = false;
        hasStarted = false;
        pausedByScroll = false;
        lastPlaybackPos = 0;
        showPlayerLoading(false, null);
        if (resetUi) {
            if (ivCover != null) {
                ivCover.setVisibility(View.VISIBLE);
            }
            if (btnPlay != null) {
                btnPlay.setVisibility(View.VISIBLE);
            }
            showRetryButton(false);
            setPlayerHint("点击播放");
            vvPlayer.setVisibility(View.GONE);
        }
    }

    protected void updatePlayerVisibility() {
        if (vvPlayer == null || layoutPlayer == null || lvComments == null || !hasStarted) {
            return;
        }
        if (!isPlayerVisible()) {
            pausePlaybackForScroll();
        } else if (pausedByScroll) {
            resumePlaybackFromScroll();
        }
    }

    protected boolean isPlayerVisible() {
        if (layoutPlayer == null || lvComments == null) {
            return true;
        }
        if (layoutPlayer.getHeight() <= 0 || lvComments.getHeight() <= 0) {
            return true;
        }
        int[] listPos = new int[2];
        int[] playerPos = new int[2];
        lvComments.getLocationOnScreen(listPos);
        layoutPlayer.getLocationOnScreen(playerPos);
        int listTop = listPos[1];
        int listBottom = listTop + lvComments.getHeight();
        int playerTop = playerPos[1];
        int playerBottom = playerTop + layoutPlayer.getHeight();
        return playerBottom > listTop && playerTop < listBottom;
    }

    /** 滚动过程中持续保存播放进度，Surface 回收后仍能恢复到准确位置。 */
    protected void trackPlaybackPositionDuringScroll() {
        if (vvPlayer == null || !hasStarted || !isPlaying) {
            return;
        }
        int pos = safeGetPlaybackPosition();
        if (pos > 0) {
            lastPlaybackPos = pos;
        }
    }

    protected void pausePlaybackForScroll() {
        if (vvPlayer == null || !hasStarted) {
            return;
        }
        try {
            if (!vvPlayer.isPlaying()) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        // SurfaceView 滚出屏幕后 Surface 会被销毁，此时 getCurrentPosition()
        // 可能返回 0；只在取到有效位置时才覆盖，避免把已有进度清掉。
        int pos = safeGetPlaybackPosition();
        if (pos > 0) {
            lastPlaybackPos = pos;
        }
        pausedByScroll = true;
        isPlaying = false;
        try {
            vvPlayer.pause();
        } catch (Exception e) {
        }
    }

    protected void pausePlaybackForFullscreen() {
        if (vvPlayer == null) {
            return;
        }
        lastPlaybackPos = safeGetPlaybackPosition();
        hasStarted = true;
        pausedByScroll = true;
        isPlaying = false;
        try {
            if (vvPlayer.isPlaying()) {
                vvPlayer.pause();
            }
        } catch (Exception e) {
        }
    }

    protected void resumePlaybackFromScroll() {
        if (vvPlayer == null || !pausedByScroll || !hasStarted || !isPlayerVisible()) {
            return;
        }
        try {
            if (lastPlaybackPos > 0) {
                vvPlayer.seekTo(lastPlaybackPos);
            }
            vvPlayer.start();
            isPlaying = true;
            pausedByScroll = false;
            showPlayerLoading(false, null);
        } catch (Exception e) {
            isPlaying = false;
            pausedByScroll = true;
        }
    }

    protected int safeGetPlaybackPosition() {
        try {
            int pos = vvPlayer != null ? vvPlayer.getCurrentPosition() : 0;
            return Math.max(0, pos);
        } catch (Exception e) {
            return 0;
        }
    }
}
