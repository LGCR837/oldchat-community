package com.im.oldchat.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliModels;

abstract class OldViewActivitySupport4 extends BaseActivity {
    protected static final String LOG_PREFIX = "【bilibili 测试报错！！！！！】";
    protected static final long QR_EXPIRE_MS = 180000L;

    protected ImageView ivQr;
    protected TextView tvStatus;
    protected TextView tvLoginTitle;
    protected View panelLogin;
    protected View btnMenu;
    protected View btnSearch;
    protected android.widget.Button btnRefresh;
    protected ProgressBar pbLoading;
    protected ListView lvVideos;
    protected TextView tvEmpty;
    protected TextView tvOldViewSideStatus;

    protected OldViewVideoAdapter adapter;

    protected String authCode;
    protected boolean isPolling = false;
    protected boolean pollingInFlight = false;
    protected boolean loginInProgress = false;
    protected boolean guestMode = false;
    protected long qrRequestedAt = 0L;
    protected boolean searchMode = false;
    protected String searchKeyword = "";
    protected int searchPage = 0;
    protected boolean searchLoading = false;
    protected boolean searchHasMore = true;

    protected final Handler handler = new Handler(Looper.getMainLooper());
    protected final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling || pollingInFlight || authCode == null || authCode.length() == 0) {
                return;
            }
            pollingInFlight = true;
            BiliApi.pollQrLoginStatus(authCode, new BiliApi.ApiCallback<BiliModels.QRPollResult>() {
                @Override
                public void onSuccess(BiliModels.QRPollResult response) {
                    pollingInFlight = false;
                    handlePollResponse(response);
                }

                @Override
                public void onError(String error) {
                    pollingInFlight = false;
                    if (isPolling) {
                        schedulePoll(2000);
                    }
                }
            });
        }
    };

    protected abstract void schedulePoll(long delayMs);

    protected abstract void handlePollResponse(BiliModels.QRPollResult response);

    protected void showLoading(boolean show) {
        if (pbLoading != null) {
            pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (tvEmpty != null) {
            if (show) {
                tvEmpty.setText(R.string.old_view_loading);
            } else {
                tvEmpty.setText(guestMode ? R.string.old_view_empty_guest : R.string.old_view_empty);
            }
        }
    }

    protected void logError(String detail) {
        Log.e("OldView", LOG_PREFIX + " " + (detail != null ? detail : ""));
    }

    protected void logStep(String detail) {
        Log.d("OldView", LOG_PREFIX + " " + (detail != null ? detail : ""));
    }
}
