package com.im.oldchat.ui;

import android.content.Intent;
import android.support.v4.view.ViewCompat;
import android.view.View;
import android.webkit.WebView;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.bili.BiliModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

abstract class OldViewUpProfileSupport0 extends BaseActivity {
    protected ProgressBar pbLoading;
    protected TextView tvTitle;
    protected TextView tvEmpty;
    protected TextView tvUpName;
    protected TextView tvUpUid;
    protected TextView tvUpSign;
    protected TextView tvFollower;
    protected TextView tvFollowing;
    protected TextView tvArchive;
    protected TextView btnFollow;
    protected TextView tabVideos;
    protected TextView tabSpace;
    protected ImageView ivAvatar;
    protected ListView lvVideos;
    protected WebView webView;

    protected long upMid;
    protected String upName;
    protected String accessToken;
    protected String cookie;
    protected boolean followed;
    protected boolean followLoading;
    protected boolean profileLoading;
    protected boolean videosLoading;
    protected boolean webLoading;
    protected boolean webLoaded;
    protected boolean webLoadFailed;
    protected boolean spaceBlockedByMemory;
    protected boolean showingSpace;
    protected int videoPage = 0;
    protected boolean hasMoreVideos = true;

    protected final List<BiliModels.RecommendItem> videoItems = new ArrayList<BiliModels.RecommendItem>();
    protected OldViewVideoAdapter videoAdapter;

    @Override
    public void onBackPressed() {
        if (showingSpace && webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }

    protected void bindViews() {
        pbLoading = findViewByIdCompat(R.id.pbOldViewUpProfileLoading);
        tvTitle = findViewByIdCompat(R.id.tvOldViewUpProfileTitle);
        tvEmpty = findViewByIdCompat(R.id.tvOldViewUpProfileEmpty);
        tvUpName = findViewByIdCompat(R.id.tvOldViewUpProfileName);
        tvUpUid = findViewByIdCompat(R.id.tvOldViewUpProfileUid);
        tvUpSign = findViewByIdCompat(R.id.tvOldViewUpProfileSign);
        tvFollower = findViewByIdCompat(R.id.tvOldViewUpProfileFollower);
        tvFollowing = findViewByIdCompat(R.id.tvOldViewUpProfileFollowing);
        tvArchive = findViewByIdCompat(R.id.tvOldViewUpProfileArchive);
        btnFollow = findViewByIdCompat(R.id.btnOldViewUpProfileFollow);
        tabVideos = findViewByIdCompat(R.id.tabOldViewUpProfileVideos);
        tabSpace = findViewByIdCompat(R.id.tabOldViewUpProfileSpace);
        ivAvatar = findViewByIdCompat(R.id.ivOldViewUpProfileAvatar);
        lvVideos = findViewByIdCompat(R.id.lvOldViewUpProfileVideos);
        webView = findViewByIdCompat(R.id.wvOldViewUpProfile);
    }

    protected void bindActions() {
        View btnBack = findViewByIdCompat(R.id.btnOldViewUpProfileBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (btnFollow != null) {
            btnFollow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onFollowClick();
                }
            });
        }
        if (tabVideos != null) {
            tabVideos.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(false);
                }
            });
        }
        if (tabSpace != null) {
            tabSpace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(true);
                }
            });
        }
    }

    protected void readArgs() {
        Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        upMid = intent.getLongExtra(OldViewUpProfileActivity.EXTRA_UP_MID, 0L);
        upName = intent.getStringExtra(OldViewUpProfileActivity.EXTRA_UP_NAME);
    }

    protected void renderBaseInfo() {
        if (tvTitle != null) {
            tvTitle.setText(resolveUpName());
        }
        if (tvUpName != null) {
            tvUpName.setText(resolveUpName());
        }
        if (tvUpUid != null) {
            tvUpUid.setText(getString(R.string.old_view_up_uid_prefix, String.valueOf(upMid)));
        }
    }

    protected void setupVideoList() {
        if (lvVideos == null) {
            return;
        }
        videoAdapter = new OldViewVideoAdapter(this);
        lvVideos.setAdapter(videoAdapter);
        lvVideos.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object obj = videoAdapter != null ? videoAdapter.getItem(position) : null;
                if (obj instanceof BiliModels.RecommendItem) {
                    OldViewUpProfileVideoNavigator.openVideoDetail(OldViewUpProfileSupport0.this, (BiliModels.RecommendItem) obj);
                }
            }
        });
        lvVideos.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (showingSpace || videosLoading || !hasMoreVideos || totalItemCount <= 0) {
                    return;
                }
                int last = firstVisibleItem + visibleItemCount;
                if (last >= totalItemCount - 2) {
                    loadVideos(videoPage + 1, false);
                }
            }
        });
    }

    protected void switchTab(boolean showSpace) {
        showingSpace = showSpace;
        renderTabStyle();
        if (lvVideos != null) {
            lvVideos.setVisibility(showSpace ? View.GONE : View.VISIBLE);
        }
        if (webView != null) {
            webView.setVisibility(showSpace ? View.VISIBLE : View.GONE);
        }
        if (showSpace && !webLoaded) {
            onSpaceTabFirstSelected();
            webLoaded = true;
        }
        updateEmptyView();
    }

    protected void updateFollowButton() {
        if (btnFollow == null) {
            return;
        }
        if (followLoading) {
            btnFollow.setText(getString(R.string.old_view_up_following));
            btnFollow.setEnabled(false);
            ViewCompat.setAlpha(btnFollow, 0.7f);
            return;
        }
        btnFollow.setText(getString(followed ? R.string.old_view_up_followed : R.string.old_view_up_follow));
        btnFollow.setEnabled(true);
        ViewCompat.setAlpha(btnFollow, 1f);
    }

    protected void setFollowLoading(boolean loading) {
        followLoading = loading;
        updateFollowButton();
    }

    protected void updateLoadingState() {
        if (pbLoading != null) {
            boolean show = profileLoading || videosLoading || webLoading;
            pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    protected void updateEmptyView() {
        if (tvEmpty == null) {
            return;
        }
        if (showingSpace) {
            if (webLoadFailed) {
                if (spaceBlockedByMemory) {
                    tvEmpty.setText(getString(R.string.old_view_up_profile_memory_low));
                } else {
                    tvEmpty.setText(getString(R.string.old_view_up_profile_load_failed));
                }
                tvEmpty.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
            return;
        }
        if (!videosLoading && videoItems.isEmpty()) {
            tvEmpty.setText("这个 UP 暂时还没有投稿");
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
    }

    protected String formatCount(int count) {
        if (count >= 10000) {
            return String.format(Locale.US, "%.1f万", count / 10000f);
        }
        return String.valueOf(Math.max(0, count));
    }

    protected String resolveUpName() {
        if (upName != null && upName.length() > 0) {
            return upName;
        }
        return getString(R.string.old_view_up_profile_title);
    }

    private void renderTabStyle() {
        if (tabVideos != null) {
            tabVideos.setBackgroundResource(showingSpace ? R.drawable.bg_old_view_tab_inactive : R.drawable.bg_old_view_tab_active);
            tabVideos.setTextColor(getResources().getColor(showingSpace ? R.color.color_text_secondary : R.color.color_on_primary));
        }
        if (tabSpace != null) {
            tabSpace.setBackgroundResource(showingSpace ? R.drawable.bg_old_view_tab_active : R.drawable.bg_old_view_tab_inactive);
            tabSpace.setTextColor(getResources().getColor(showingSpace ? R.color.color_on_primary : R.color.color_text_secondary));
        }
    }

    protected abstract void onFollowClick();

    protected abstract void loadVideos(int page, boolean clear);

    protected abstract void onSpaceTabFirstSelected();
}
