package com.im.oldchat.ui;

import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliApiExtra;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;

import java.util.ArrayList;
import java.util.List;

abstract class OldViewVideoDetailSupport7 extends OldViewVideoDetailSupport6
        implements OldViewCommentAdapter.OnCommentActionListener {
    private boolean commentActionBusy = false;

    protected void bindCommentActions() {
        if (commentAdapter != null) {
            commentAdapter.setOnCommentActionListener(this);
        }
    }

    @Override
    public void onLikeCommentRequested(final BiliModels.CommentReply reply, final boolean targetLike) {
        if (reply == null || reply.rpid <= 0 || currentAid <= 0) {
            return;
        }
        if (commentActionBusy) {
            return;
        }
        final String cookie = BiliAuthStore.getCookies(this);
        if (cookie == null || cookie.length() == 0 || cookie.indexOf("bili_jct=") < 0) {
            Toast.makeText(this, getString(R.string.old_view_login_required), Toast.LENGTH_SHORT).show();
            return;
        }
        String accessToken = BiliAuthStore.getAccessToken(this);
        if (BiliAuthStore.isExpired(this)) {
            accessToken = "";
        }
        commentActionBusy = true;
        BiliApiExtra.requestLikeComment(currentAid, reply.rpid, targetLike, accessToken, cookie,
                new BiliApi.ApiCallback<BiliModels.SimpleResult>() {
                    @Override
                    public void onSuccess(BiliModels.SimpleResult response) {
                        commentActionBusy = false;
                        if (response != null && response.code == 0) {
                            reply.likedByMe = targetLike;
                            if (targetLike) {
                                reply.like = reply.like + 1;
                            } else if (reply.like > 0) {
                                reply.like = reply.like - 1;
                            }
                            if (commentAdapter != null) {
                                commentAdapter.notifyDataSetChanged();
                            }
                            return;
                        }
                        String msg = response != null && response.message != null ? response.message : "评论点赞失败";
                        Toast.makeText(OldViewVideoDetailSupport7.this, msg, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String error) {
                        commentActionBusy = false;
                        Toast.makeText(OldViewVideoDetailSupport7.this, error != null ? error : "评论点赞失败", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Override
    public void onReplyCommentRequested(final BiliModels.CommentReply reply) {
        if (reply == null) {
            return;
        }
        final String cookie = BiliAuthStore.getCookies(this);
        if (cookie == null || cookie.length() == 0 || cookie.indexOf("bili_jct=") < 0) {
            Toast.makeText(this, getString(R.string.old_view_login_required), Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(2);
        String name = reply.member != null ? reply.member.uname : "";
        input.setHint(name != null && name.length() > 0 ? ("回复 @" + name) : "输入回复内容");

        new AlertDialog.Builder(this)
                .setTitle("发表回复")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("发送", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String text = input.getText() != null ? input.getText().toString().trim() : "";
                        if (text.length() == 0) {
                            Toast.makeText(OldViewVideoDetailSupport7.this, "请输入回复内容", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        sendReplyComment(reply, text, cookie);
                    }
                })
                .show();
    }

    @Override
    public void onLoadMoreRepliesRequested(final BiliModels.CommentReply reply) {
        if (reply == null || reply.rpid <= 0 || currentAid <= 0) {
            return;
        }
        if (reply.showAllReplies) {
            reply.showAllReplies = false;
            if (commentAdapter != null) {
                commentAdapter.notifyDataSetChanged();
            }
            return;
        }

        List<BiliModels.CommentReply> localReplies = reply.replies != null ? reply.replies : new ArrayList<BiliModels.CommentReply>();
        int total = reply.rcount > 0 ? reply.rcount : localReplies.size();
        if (!localReplies.isEmpty() && localReplies.size() >= Math.min(total, 20)) {
            reply.showAllReplies = true;
            if (commentAdapter != null) {
                commentAdapter.notifyDataSetChanged();
            }
            return;
        }

        final String cookie = BiliAuthStore.getCookies(this);
        String accessToken = BiliAuthStore.getAccessToken(this);
        if (BiliAuthStore.isExpired(this)) {
            accessToken = "";
        }

        BiliApiExtra.requestCommentReplies(currentAid, reply.rpid, 1, 20, accessToken, cookie,
                new BiliApi.ApiCallback<BiliModels.CommentResult>() {
                    @Override
                    public void onSuccess(BiliModels.CommentResult data) {
                        if (data != null && data.code == 0 && data.data != null && data.data.replies != null) {
                            reply.replies = data.data.replies;
                            if (reply.rcount <= 0) {
                                reply.rcount = data.data.replies.size();
                            }
                            reply.showAllReplies = true;
                            if (commentAdapter != null) {
                                commentAdapter.notifyDataSetChanged();
                            }
                            return;
                        }
                        String msg = data != null && data.message != null ? data.message : "获取回复失败";
                        Toast.makeText(OldViewVideoDetailSupport7.this, msg, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(OldViewVideoDetailSupport7.this, error != null ? error : "获取回复失败", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void sendReplyComment(final BiliModels.CommentReply target, final String message, String cookie) {
        if (commentSending) {
            return;
        }
        if (currentAid <= 0) {
            Toast.makeText(this, "视频信息不完整", Toast.LENGTH_SHORT).show();
            return;
        }
        String accessToken = BiliAuthStore.getAccessToken(this);
        if (BiliAuthStore.isExpired(this)) {
            accessToken = "";
        }
        long root = target.root > 0 ? target.root : target.rpid;
        long parent = target.rpid;
        commentSending = true;
        showLoading(true);
        BiliApi.requestAddComment(currentAid, message, root, parent, accessToken, cookie,
                new BiliApi.ApiCallback<BiliModels.SimpleResult>() {
                    @Override
                    public void onSuccess(BiliModels.SimpleResult response) {
                        showLoading(false);
                        commentSending = false;
                        if (response != null && response.code == 0) {
                            Toast.makeText(OldViewVideoDetailSupport7.this, "回复已发送", Toast.LENGTH_SHORT).show();
                            loadComments(1, true);
                            return;
                        }
                        String msg = response != null && response.message != null ? response.message : "回复失败";
                        Toast.makeText(OldViewVideoDetailSupport7.this, msg, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String error) {
                        showLoading(false);
                        commentSending = false;
                        Toast.makeText(OldViewVideoDetailSupport7.this, error != null ? error : "回复失败", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
