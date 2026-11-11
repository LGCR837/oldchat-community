package com.im.oldchat.ui;

import android.support.v4.view.ViewCompat;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliAuthStore;

final class OldViewVideoActionController {
    private final OldViewVideoDetailActivity activity;
    private TextView btnLike;
    private TextView btnFavorite;
    private TextView tvHint;
    private TextView tvStatLike;

    private String currentBvid = "";
    private long currentAid;
    private int likeCount = -1;
    private boolean liked;
    private boolean likedLoaded;
    private boolean likeBusy;
    private boolean favoriteBusy;
    private boolean favoriteDone;

    OldViewVideoActionController(OldViewVideoDetailActivity activity) {
        this.activity = activity;
    }

    void bindHeader(View headerView) {
        if (headerView == null) {
            return;
        }
        btnLike = (TextView) headerView.findViewById(R.id.btnOldViewVideoLike);
        btnFavorite = (TextView) headerView.findViewById(R.id.btnOldViewVideoFavorite);
        tvHint = (TextView) headerView.findViewById(R.id.tvOldViewVideoActionHint);
        tvStatLike = (TextView) headerView.findViewById(R.id.tvOldViewDetailStatLike);
        bindActions();
        renderUi();
    }

    void onVideoMeta(long aid, String bvid, int likes) {
        if (aid > 0) {
            currentAid = aid;
        }
        if (bvid != null && bvid.trim().length() > 0) {
            currentBvid = bvid.trim();
        }
        if (likes >= 0) {
            likeCount = likes;
        }
        renderUi();
        refreshLikeState(false);
    }

    void refreshLikeState(boolean force) {
        if (btnLike == null || likeBusy || empty(currentBvid)) {
            return;
        }
        if (!force && likedLoaded) {
            return;
        }
        String cookie = BiliAuthStore.getCookies(activity);
        if (empty(cookie)) {
            return;
        }
        OldViewVideoActionHelper.queryLiked(currentBvid, cookie, new OldViewVideoActionHelper.BoolCallback() {
            @Override
            public void onDone(boolean ok, String msg, boolean value) {
                if (!ok) {
                    return;
                }
                liked = value;
                likedLoaded = true;
                renderUi();
            }
        });
    }

    private void bindActions() {
        if (btnLike != null) {
            btnLike.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleLike();
                }
            });
        }
        if (btnFavorite != null) {
            btnFavorite.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    favoriteVideo();
                }
            });
        }
    }

    private void toggleLike() {
        if (likeBusy) {
            return;
        }
        if (empty(currentBvid)) {
            toast("视频信息未就绪");
            return;
        }
        String cookie = BiliAuthStore.getCookies(activity);
        if (empty(cookie)) {
            toast("请先登录B站账号");
            return;
        }
        String access = BiliAuthStore.getAccessToken(activity);
        final boolean targetLike = !liked;
        likeBusy = true;
        showHint("正在提交点赞...");
        renderUi();
        OldViewVideoActionHelper.toggleLike(currentBvid, targetLike, access, cookie,
                new OldViewVideoActionHelper.SimpleCallback() {
                    @Override
                    public void onDone(boolean ok, String msg) {
                        likeBusy = false;
                        if (ok) {
                            liked = targetLike;
                            likedLoaded = true;
                            if (likeCount < 0) {
                                likeCount = 0;
                            }
                            if (targetLike) {
                                likeCount++;
                            } else if (likeCount > 0) {
                                likeCount--;
                            }
                        }
                        if (!empty(msg)) {
                            showHint(msg);
                        }
                        renderUi();
                    }
                });
    }

    private void favoriteVideo() {
        if (favoriteBusy) {
            return;
        }
        long aid = currentAid;
        if (aid <= 0 && !empty(currentBvid)) {
            aid = activity.bvidToAid(currentBvid);
            if (aid > 0) {
                currentAid = aid;
            }
        }
        if (aid <= 0) {
            toast("视频信息未就绪");
            return;
        }
        String cookie = BiliAuthStore.getCookies(activity);
        if (empty(cookie)) {
            toast("请先登录B站账号");
            return;
        }
        String access = BiliAuthStore.getAccessToken(activity);
        favoriteBusy = true;
        showHint("正在收藏...");
        renderUi();
        OldViewVideoActionHelper.favoriteVideo(aid, access, cookie,
                new OldViewVideoActionHelper.SimpleCallback() {
                    @Override
                    public void onDone(boolean ok, String msg) {
                        favoriteBusy = false;
                        if (ok) {
                            favoriteDone = true;
                        }
                        if (!empty(msg)) {
                            showHint(msg);
                        }
                        renderUi();
                    }
                });
    }

    private void renderUi() {
        if (btnLike != null) {
            btnLike.setEnabled(!likeBusy);
            btnLike.setText(likeBusy ? "处理中" : (liked ? "已点赞" : "点赞"));
            btnLike.setBackgroundResource(liked ? R.drawable.bg_old_view_action_active : R.drawable.bg_old_view_action_inactive);
            btnLike.setTextColor(activity.getResources().getColor(liked ? R.color.color_on_primary : R.color.color_text_primary));
            ViewCompat.setAlpha(btnLike, likeBusy ? 0.7f : 1f);
        }
        if (btnFavorite != null) {
            btnFavorite.setEnabled(!favoriteBusy);
            btnFavorite.setText(favoriteBusy ? "处理中" : (favoriteDone ? "已收藏" : "收藏"));
            btnFavorite.setBackgroundResource(favoriteDone ? R.drawable.bg_old_view_action_active : R.drawable.bg_old_view_action_inactive);
            btnFavorite.setTextColor(activity.getResources().getColor(favoriteDone ? R.color.color_on_primary : R.color.color_text_primary));
            ViewCompat.setAlpha(btnFavorite, favoriteBusy ? 0.7f : 1f);
        }
        if (tvStatLike != null && likeCount >= 0) {
            tvStatLike.setText("点赞 " + activity.formatCount(likeCount));
        }
    }

    private void showHint(String text) {
        if (tvHint != null && !empty(text)) {
            tvHint.setText(text);
        }
    }

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
    }

    private boolean empty(String text) {
        return text == null || text.trim().length() == 0;
    }
}
