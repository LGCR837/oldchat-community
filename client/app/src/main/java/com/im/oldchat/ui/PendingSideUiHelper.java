package com.im.oldchat.ui;

import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.R;

final class PendingSideUiHelper {
    private PendingSideUiHelper() {
    }

    static void bind(View pendingSide, ProgressBar spinner, TextView failedView,
                     View statusRow, View statusRowMedia,
                     boolean isMine, boolean localPending, boolean localFailed, int localProgress) {
        if (pendingSide == null) {
            return;
        }
        ProgressBar determinate = (ProgressBar) pendingSide.findViewById(R.id.pbMessagePendingDeterminate);
        TextView progressText = (TextView) pendingSide.findViewById(R.id.tvMessagePendingProgress);
        boolean showProgress = isMine && localPending && localProgress >= 0;
        boolean showSpinner = isMine && localPending && localProgress < 0;
        boolean showFailed = isMine && localFailed;

        if (!showProgress && !showSpinner && !showFailed) {
            pendingSide.setVisibility(View.GONE);
            hide(spinner);
            hide(determinate);
            hide(progressText);
            hide(failedView);
            return;
        }

        // 普通发送中的圆圈已移动到气泡底部对勾位置；
        // 外侧区域只保留上传百分比和失败提示。
        pendingSide.setVisibility((showProgress || showFailed) ? View.VISIBLE : View.GONE);
        if (spinner != null) {
            spinner.setVisibility(showSpinner ? View.VISIBLE : View.GONE);
        }
        if (determinate != null) {
            determinate.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            if (showProgress) {
                determinate.setIndeterminate(false);
                determinate.setMax(100);
                determinate.setProgress(clamp(localProgress));
            }
        }
        if (progressText != null) {
            progressText.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            if (showProgress) {
                progressText.setText(clamp(localProgress) + "%");
            }
        }
        if (failedView != null) {
            failedView.setVisibility(showFailed ? View.VISIBLE : View.GONE);
            if (showFailed) {
                failedView.setText(R.string.message_send_failed);
            }
        }
        if (statusRow != null) {
            statusRow.setVisibility(View.GONE);
        }
        if (statusRowMedia != null) {
            statusRowMedia.setVisibility(View.GONE);
        }
    }

    private static int clamp(int progress) {
        if (progress < 0) {
            return 0;
        }
        if (progress > 100) {
            return 100;
        }
        return progress;
    }

    private static void hide(View view) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }
}
