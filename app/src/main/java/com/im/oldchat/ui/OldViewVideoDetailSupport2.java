package com.im.oldchat.ui;

import android.view.View;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;
import com.im.oldchat.util.ImageLoader;

import java.util.ArrayList;
import java.util.List;

abstract class OldViewVideoDetailSupport2 extends OldViewVideoDetailSupport3 {
    protected void loadDetail() {
        if ((currentBvid == null || currentBvid.length() == 0) && currentAid <= 0) {
            showLoading(false);
            bindFallbackDetail();
            updateEmpty(true, getString(R.string.old_view_comments_empty));
            logStep("loadDetail: skip (no aid/bvid), use preload");
            return;
        }
        showLoading(true);
        logStep("loadDetail: start");
        String cookie = BiliAuthStore.getCookies(this);
        BiliApi.requestVideoDetail(currentBvid, currentAid, cookie, new BiliApi.ApiCallback<BiliModels.VideoDetailResult>() {
            @Override
            public void onSuccess(BiliModels.VideoDetailResult response) {
                if (response != null && response.code == 0 && response.data != null) {
                    bindDetail(response.data);
                    currentAid = response.data.aid;
                    currentBvid = response.data.bvid;
                    currentCid = extractCid(response.data);
                    logStep("loadDetail: success aid=" + currentAid + " bvid=" + currentBvid + " cid=" + currentCid);
                    commentPage = 0;
                    hasMoreComments = true;
                    loadComments(1, true);
                } else if (response != null) {
                    showLoading(false);
                    logError("详情请求失败: code=" + response.code + " msg=" + (response.message != null ? response.message : "unknown")
                            + " bvid=" + currentBvid + " aid=" + currentAid);
                    bindFallbackDetail();
                    updateEmpty(true, getString(R.string.old_view_comments_empty));
                    if (currentAid <= 0 && currentBvid != null) {
                        long aidFromBvid = bvidToAid(currentBvid);
                        if (aidFromBvid > 0) {
                            currentAid = aidFromBvid;
                            logStep("loadDetail: use aid from bvid=" + currentAid);
                        }
                    }
                    if (currentAid > 0 && !isLoadingComments) {
                        logStep("loadDetail: fallback -> loadComments");
                        loadComments(1, true);
                    }
                    Toast.makeText(OldViewVideoDetailSupport2.this,
                            response.message != null ? response.message : "获取详情失败", Toast.LENGTH_SHORT).show();
                } else {
                    showLoading(false);
                    updateEmpty(true, getString(R.string.old_view_comments_empty));
                }
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                logError("详情请求异常: " + (error != null ? error : "unknown"));
                bindFallbackDetail();
                updateEmpty(true, getString(R.string.old_view_comments_empty));
                if (currentAid <= 0 && currentBvid != null) {
                    long aidFromBvid = bvidToAid(currentBvid);
                    if (aidFromBvid > 0) {
                        currentAid = aidFromBvid;
                        logStep("loadDetail: error -> use aid from bvid=" + currentAid);
                    }
                }
                if (currentAid > 0 && !isLoadingComments) {
                    logStep("loadDetail: error -> loadComments");
                    loadComments(1, true);
                }
                Toast.makeText(OldViewVideoDetailSupport2.this, error != null ? error : "获取详情失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected void bindDetail(BiliModels.VideoDetailData data) {
        if (data == null) {
            return;
        }
        if (tvVideoTitle != null) {
            tvVideoTitle.setText(data.title != null ? data.title : "");
        }
        if (data.title != null && data.title.length() > 0) {
            currentTitle = data.title;
        }
        if (data.duration > 0) {
            currentDuration = data.duration;
        }
        if (tvDesc != null) {
            String desc = data.desc != null ? data.desc : "";
            if (desc.length() == 0) {
                desc = "暂无简介";
            }
            tvDesc.setText(desc);
        }
        if (tvMeta != null) {
            tvMeta.setText(buildMeta(data));
        }
        bindOwner(data);
        bindStats(data);
        if (ivCover != null) {
            String cover = BiliApi.normalizeUrl(data.pic);
            if (cover != null && cover.length() > 0) {
                currentCover = cover;
                ImageLoader.load(ivCover, cover);
            } else if (coverUrl != null && coverUrl.length() > 0) {
                ImageLoader.load(ivCover, BiliApi.normalizeUrl(coverUrl));
            } else {
                ivCover.setImageResource(android.R.drawable.ic_media_play);
            }
        }
        if (btnPlay != null) {
            // 详情请求可能在播放已经开始后才返回；此时不能重新显示中央按钮。
            btnPlay.setVisibility(hasStarted ? View.GONE : View.VISIBLE);
        }
    }

    protected void bindFallbackDetail() {
        if (layoutOwner != null) {
            layoutOwner.setVisibility(View.GONE);
        }
        if (layoutStats != null) {
            layoutStats.setVisibility(View.GONE);
        }
        if (tvVideoTitle != null && tvVideoTitle.getText() != null && tvVideoTitle.getText().length() == 0) {
            tvVideoTitle.setText("视频详情");
        }
        if (currentTitle == null || currentTitle.length() == 0) {
            CharSequence title = tvVideoTitle != null ? tvVideoTitle.getText() : null;
            if (title != null && title.length() > 0) {
                currentTitle = title.toString();
            }
        }
        if (tvDesc != null && tvDesc.getText() != null && tvDesc.getText().length() == 0) {
            tvDesc.setText("暂无简介");
        }
        if (tvMeta != null && tvMeta.getText() != null && tvMeta.getText().length() == 0) {
            tvMeta.setText("暂无更多信息");
        }
        if (ivCover != null) {
            if (coverUrl != null && coverUrl.length() > 0) {
                currentCover = BiliApi.normalizeUrl(coverUrl);
                ImageLoader.load(ivCover, BiliApi.normalizeUrl(coverUrl));
            } else {
                ivCover.setImageResource(android.R.drawable.ic_media_play);
            }
        }
        if (btnPlay != null) {
            btnPlay.setVisibility(hasStarted ? View.GONE : View.VISIBLE);
        }
    }

    protected void bindOwner(BiliModels.VideoDetailData data) {
        if (layoutOwner == null) {
            return;
        }
        if (data == null || data.owner == null) {
            layoutOwner.setVisibility(View.GONE);
            return;
        }
        if (tvOwnerName != null) {
            tvOwnerName.setText(data.owner.name != null ? data.owner.name : "");
        }
        if (tvOwnerTag != null) {
            tvOwnerTag.setText(getString(R.string.old_view_owner_tag));
        }
        if (ivOwnerAvatar != null) {
            String avatar = BiliApi.normalizeUrl(data.owner.face);
            if (avatar != null && avatar.length() > 0) {
                ImageLoader.loadAvatar(ivOwnerAvatar, avatar);
            } else {
                ivOwnerAvatar.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        }
        layoutOwner.setVisibility(View.VISIBLE);
    }

    protected void bindStats(BiliModels.VideoDetailData data) {
        if (layoutStats == null) {
            return;
        }
        if (data == null || data.stat == null) {
            layoutStats.setVisibility(View.GONE);
            return;
        }
        if (tvStatPlay != null) {
            tvStatPlay.setText("播放 " + formatCount(data.stat.view));
        }
        if (tvStatDanmaku != null) {
            tvStatDanmaku.setText("弹幕 " + formatCount(data.stat.danmaku));
        }
        if (tvStatReply != null) {
            tvStatReply.setText("评论 " + formatCount(data.stat.reply));
        }
        if (tvStatLike != null) {
            tvStatLike.setText("点赞 " + formatCount(data.stat.like));
        }
        layoutStats.setVisibility(View.VISIBLE);
    }

    protected void loadComments(final int page, final boolean clear) {
        if (currentAid <= 0 && currentBvid != null) {
            long aidFromBvid = bvidToAid(currentBvid);
            if (aidFromBvid > 0) {
                currentAid = aidFromBvid;
                logStep("loadComments: computed aid=" + currentAid + " from bvid");
            }
        }
        if (currentAid <= 0) {
            showLoading(false);
            updateEmpty(true, getString(R.string.old_view_comments_empty));
            return;
        }
        if (isLoadingComments) {
            return;
        }
        isLoadingComments = true;
        logStep("loadComments: page=" + page + " clear=" + clear);
        String cookie = BiliAuthStore.getCookies(this);
        String accessToken = BiliAuthStore.getAccessToken(this);
        if (BiliAuthStore.isExpired(this)) {
            accessToken = "";
        }
        BiliApi.requestComments(currentAid, page, accessToken, cookie, new BiliApi.ApiCallback<BiliModels.CommentResult>() {
            @Override
            public void onSuccess(BiliModels.CommentResult response) {
                showLoading(false);
                isLoadingComments = false;
                if (response != null && response.code == 0 && response.data != null) {
                    List<BiliModels.CommentReply> replies = buildCommentList(response.data, page);
                    if (commentAdapter != null) {
                        if (clear) {
                            commentAdapter.update(replies);
                        } else if (!replies.isEmpty()) {
                            List<BiliModels.CommentReply> merged = new ArrayList<BiliModels.CommentReply>();
                            int count = commentAdapter.getCount();
                            for (int i = 0; i < count; i++) {
                                Object obj = commentAdapter.getItem(i);
                                if (obj instanceof BiliModels.CommentReply) {
                                    merged.add((BiliModels.CommentReply) obj);
                                }
                            }
                            merged.addAll(replies);
                            commentAdapter.update(merged);
                        }
                    }
                    commentPage = page;
                    hasMoreComments = hasMore(response.data.page, replies);
                    updateEmpty(clear && replies.isEmpty(), getString(R.string.old_view_comments_empty));
                    logStep("loadComments: success size=" + replies.size() + " hasMore=" + hasMoreComments);
                } else if (response != null) {
                    logError("评论请求失败: code=" + response.code + " msg=" + (response.message != null ? response.message : "unknown")
                            + " aid=" + currentAid);
                    if (clear) {
                        updateEmpty(true, getString(R.string.old_view_comments_empty));
                    }
                    Toast.makeText(OldViewVideoDetailSupport2.this,
                            response.message != null ? response.message : "获取评论失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                isLoadingComments = false;
                if (error != null && error.length() > 0) {
                    logError("评论请求异常: " + error);
                    if (clear) {
                        updateEmpty(true, getString(R.string.old_view_comments_empty));
                    }
                    Toast.makeText(OldViewVideoDetailSupport2.this, error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    protected void sendComment(final String message) {
        if (commentSending) {
            return;
        }
        if (currentAid <= 0) {
            Toast.makeText(this, "视频信息不完整", Toast.LENGTH_SHORT).show();
            return;
        }
        String cookie = BiliAuthStore.getCookies(this);
        if (cookie == null || cookie.length() == 0 || cookie.indexOf("bili_jct=") < 0) {
            Toast.makeText(this, getString(R.string.old_view_login_required), Toast.LENGTH_SHORT).show();
            return;
        }
        String accessToken = BiliAuthStore.getAccessToken(this);
        if (BiliAuthStore.isExpired(this)) {
            accessToken = "";
        }
        commentSending = true;
        showLoading(true);
        logStep("sendComment: start");
        BiliApi.requestAddComment(currentAid, message, 0, 0, accessToken, cookie, new BiliApi.ApiCallback<BiliModels.SimpleResult>() {
            @Override
            public void onSuccess(BiliModels.SimpleResult response) {
                showLoading(false);
                commentSending = false;
                if (response != null && response.code == 0) {
                    Toast.makeText(OldViewVideoDetailSupport2.this, "评论已发送", Toast.LENGTH_SHORT).show();
                    if (etComment != null) {
                        etComment.setText("");
                    }
                    hideKeyboard(etComment);
                    loadComments(1, true);
                } else if (response != null) {
                    String msg = response.message != null ? response.message : "评论失败";
                    Toast.makeText(OldViewVideoDetailSupport2.this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(OldViewVideoDetailSupport2.this, "评论失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                commentSending = false;
                Toast.makeText(OldViewVideoDetailSupport2.this, error != null ? error : "评论失败", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
