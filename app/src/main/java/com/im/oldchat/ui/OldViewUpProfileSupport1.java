package com.im.oldchat.ui;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliModels;
import com.im.oldchat.bili.BiliUserSpaceApi;
import com.im.oldchat.bili.BiliWebViewMemoryGuard;
import com.im.oldchat.util.ImageLoader;

abstract class OldViewUpProfileSupport1 extends OldViewUpProfileSupport0 {
    protected void loadProfile() {
        profileLoading = true;
        updateLoadingState();
        BiliUserSpaceApi.requestUpProfile(upMid, cookie, new com.im.oldchat.bili.BiliApi.ApiCallback<BiliUserSpaceApi.UpProfileData>() {
            @Override
            public void onSuccess(BiliUserSpaceApi.UpProfileData data) {
                profileLoading = false;
                updateLoadingState();
                bindProfile(data);
            }

            @Override
            public void onError(String error) {
                profileLoading = false;
                updateLoadingState();
            }
        });
    }

    @Override
    protected void loadVideos(final int page, final boolean clear) {
        if (videosLoading || upMid <= 0 || page <= 0) {
            return;
        }
        videosLoading = true;
        updateLoadingState();
        BiliUserSpaceApi.requestUpVideos(upMid, page, 20, cookie, new com.im.oldchat.bili.BiliApi.ApiCallback<BiliUserSpaceApi.UpVideoPage>() {
            @Override
            public void onSuccess(BiliUserSpaceApi.UpVideoPage data) {
                videosLoading = false;
                updateLoadingState();
                if (clear) {
                    videoItems.clear();
                }
                if (data != null && data.items != null) {
                    videoItems.addAll(data.items);
                    videoPage = data.page > 0 ? data.page : page;
                    hasMoreVideos = data.hasMore;
                } else {
                    hasMoreVideos = false;
                }
                if (videoAdapter != null) {
                    videoAdapter.update(videoItems);
                }
                updateEmptyView();
            }

            @Override
            public void onError(String error) {
                videosLoading = false;
                updateLoadingState();
                if (clear) {
                    videoItems.clear();
                    if (videoAdapter != null) {
                        videoAdapter.update(videoItems);
                    }
                }
                if (error != null && error.length() > 0) {
                    Toast.makeText(OldViewUpProfileSupport1.this, error, Toast.LENGTH_SHORT).show();
                }
                updateEmptyView();
            }
        });
    }

    protected void queryFollowState() {
        if (upMid <= 0) {
            return;
        }
        setFollowLoading(true);
        OldViewUpProfileActionHelper.queryFollowState(upMid, accessToken, cookie,
                new OldViewUpProfileActionHelper.FollowStateCallback() {
                    @Override
                    public void onDone(boolean ok, String msg, boolean isFollowed) {
                        setFollowLoading(false);
                        if (ok) {
                            followed = isFollowed;
                        }
                        updateFollowButton();
                    }
                });
    }

    @Override
    protected void onFollowClick() {
        if (followLoading) {
            return;
        }
        final boolean targetFollow = !followed;
        setFollowLoading(true);
        OldViewUpProfileActionHelper.toggleFollow(upMid, targetFollow, accessToken, cookie,
                new OldViewUpProfileActionHelper.ToggleFollowCallback() {
                    @Override
                    public void onDone(boolean ok, String msg, boolean followedAfter) {
                        setFollowLoading(false);
                        if (ok) {
                            followed = followedAfter;
                            updateFollowButton();
                            if (msg != null && msg.length() > 0) {
                                Toast.makeText(OldViewUpProfileSupport1.this, msg, Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        if (msg == null || msg.length() == 0) {
                            msg = getString(R.string.old_view_up_follow_toggle_failed);
                        }
                        Toast.makeText(OldViewUpProfileSupport1.this, msg, Toast.LENGTH_SHORT).show();
                        updateFollowButton();
                    }
                });
    }

    @Override
    protected void onSpaceTabFirstSelected() {
        bindWebView();
        loadUpPage();
    }

    private void bindProfile(BiliUserSpaceApi.UpProfileData data) {
        if (data == null) {
            return;
        }
        if (data.name != null && data.name.length() > 0) {
            upName = data.name;
            if (tvTitle != null) {
                tvTitle.setText(data.name);
            }
            if (tvUpName != null) {
                tvUpName.setText(data.name);
            }
        }
        if (tvUpSign != null) {
            String sign = data.sign != null ? data.sign.trim() : "";
            tvUpSign.setText(sign.length() > 0 ? sign : "这个 UP 很神秘，暂时没有简介");
        }
        if (tvFollower != null) {
            tvFollower.setText("粉丝 " + formatCount(data.follower));
        }
        if (tvFollowing != null) {
            tvFollowing.setText("关注 " + formatCount(data.following));
        }
        if (tvArchive != null) {
            tvArchive.setText("投稿 " + formatCount(data.archiveCount));
        }
        if (ivAvatar != null && data.face != null && data.face.length() > 0) {
            ImageLoader.loadAvatar(ivAvatar, data.face);
            ivAvatar.setVisibility(View.VISIBLE);
        }
    }

    private void bindWebView() {
        if (webView == null) {
            return;
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        trySetDisplayZoomControls(settings, false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                webLoading = newProgress < 100;
                updateLoadingState();
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null || url.length() == 0) {
                    return true;
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view.loadUrl(url);
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                webLoading = true;
                webLoadFailed = false;
                updateLoadingState();
                updateEmptyView();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                webLoading = false;
                updateLoadingState();
                updateEmptyView();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                webLoading = false;
                webLoadFailed = true;
                updateLoadingState();
                updateEmptyView();
            }
        });
    }

    private void loadUpPage() {
        if (webView == null || upMid <= 0L) {
            return;
        }
        // 低内存设备 WebView 极耗内存（可达 30-80MB），空间页在内存紧张时
        // 加载容易触发 OOM 崩溃。提前检测，内存不足则显示提示而不是崩溃。
        if (BiliWebViewMemoryGuard.isMemoryTooLowForWebView(this)) {
            webLoading = false;
            webLoadFailed = true;
            spaceBlockedByMemory = true;
            updateLoadingState();
            updateEmptyView();
            return;
        }
        webView.loadUrl("https://space.bilibili.com/" + upMid);
    }

    private void trySetDisplayZoomControls(WebSettings settings, boolean show) {
        if (settings == null) {
            return;
        }
        try {
            java.lang.reflect.Method method = WebSettings.class.getMethod("setDisplayZoomControls", Boolean.TYPE);
            method.invoke(settings, Boolean.valueOf(show));
        } catch (Throwable ignored) {
        }
    }
}
