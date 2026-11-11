package com.im.oldchat.ui;

import android.view.View;
import android.widget.ListAdapter;
import android.widget.Toast;

abstract class OldViewVideoDetailSupport6 extends OldViewVideoDetailSupport5 {
    private int relatedListPosition = -1;
    private int relatedListTop = 0;
    private int commentListPosition = -1;
    private int commentListTop = 0;

    protected void setupActions() {
        bindDescriptionToggle();
        if (btnSendComment != null) {
            btnSendComment.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String text = etComment != null && etComment.getText() != null ? etComment.getText().toString().trim() : "";
                    if (text.length() == 0) {
                        Toast.makeText(OldViewVideoDetailSupport6.this, "请输入评论内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sendComment(text);
                }
            });
        }

        View btnBack = findViewByIdCompat(com.im.oldchat.R.id.btnOldViewDetailBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (btnShare != null) {
            btnShare.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openShareMenu();
                }
            });
        }
        if (btnPlay != null) {
            btnPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    logStep("play click");
                    startPlayback();
                }
            });
        }
        if (btnRetry != null) {
            btnRetry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRetryButton(false);
                    if (currentPlayUrl != null && currentPlayUrl.length() > 0) {
                        playUrl(currentPlayUrl);
                    } else {
                        startPlayback();
                    }
                }
            });
        }
        if (btnFullscreen != null) {
            btnFullscreen.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openFullscreen();
                }
            });
        }
    }

    @Override
    protected void onBottomTabChanged(boolean showComments) {
        if (lvComments == null) {
            return;
        }
        saveCurrentListState();
        ListAdapter targetAdapter = showComments ? commentAdapter : relatedAdapter;
        if (targetAdapter != null && lvComments.getAdapter() != targetAdapter) {
            lvComments.setAdapter(targetAdapter);
        }
        if (layoutCommentInput != null) {
            layoutCommentInput.setVisibility(showComments ? View.VISIBLE : View.GONE);
        }
        updateEmpty(false, null);
        restoreListState(showComments);
        if (showComments && commentPage <= 0 && !isLoadingComments) {
            loadComments(1, true);
        }
    }

    private void saveCurrentListState() {
        if (lvComments == null) {
            return;
        }
        int first = lvComments.getFirstVisiblePosition();
        View firstView = lvComments.getChildCount() > 0 ? lvComments.getChildAt(0) : null;
        int top = firstView != null ? firstView.getTop() : 0;
        if (lvComments.getAdapter() == commentAdapter) {
            commentListPosition = first;
            commentListTop = top;
        } else {
            relatedListPosition = first;
            relatedListTop = top;
        }
    }

    private void restoreListState(boolean showComments) {
        if (lvComments == null) {
            return;
        }
        int savedPosition = showComments ? commentListPosition : relatedListPosition;
        int savedTop = showComments ? commentListTop : relatedListTop;
        // 首次切换 Tab 时继承当前折叠位置，避免播放器突然重新展开。
        if (savedPosition < 0) {
            savedPosition = showComments ? relatedListPosition : commentListPosition;
            savedTop = showComments ? relatedListTop : commentListTop;
        }
        final int targetPosition = Math.max(0, savedPosition);
        final int targetTop = savedTop;
        lvComments.post(new Runnable() {
            @Override
            public void run() {
                if (lvComments == null || lvComments.getAdapter() == null) {
                    return;
                }
                int count = lvComments.getAdapter().getCount();
                if (count <= 0) {
                    return;
                }
                int safePos = targetPosition;
                if (safePos < 0) {
                    safePos = 0;
                } else if (safePos >= count) {
                    safePos = count - 1;
                }
                lvComments.setSelectionFromTop(safePos, targetTop);
            }
        });
    }
}
