package com.im.oldchat.ui;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;
import com.im.oldchat.bili.BiliQrGenerator;

abstract class OldViewActivitySupport2 extends OldViewActivitySupport3 {
    protected void ensureLoginState() {
        String token = BiliAuthStore.getAccessToken(this);
        boolean expired = BiliAuthStore.isExpired(this);
        logStep("ensureLoginState: token=" + (token != null && token.length() > 0) + " expired=" + expired
                + " loginInProgress=" + loginInProgress + " authCode=" + (authCode != null && authCode.length() > 0));
        if (token != null && token.length() > 0 && !expired) {
            showLoggedInUi();
            if (!searchMode && adapter != null && adapter.getCount() == 0) {
                logStep("ensureLoginState: has token -> loadRecommend");
                loadRecommend(token);
            }
            loginInProgress = false;
            return;
        }
        if (loginInProgress && authCode != null && authCode.length() > 0) {
            if (tvStatus != null) {
                tvStatus.setText(R.string.old_view_status_wait_scan);
            }
            if (!isPolling) {
                logStep("ensureLoginState: resume polling");
                startPolling();
            }
            return;
        }
        logStep("ensureLoginState: guest");
        showGuestUi();
    }

    protected void startLogin(boolean clearToken) {
        stopPolling();
        if (clearToken) {
            BiliAuthStore.clear(this);
        }
        loginInProgress = true;
        guestMode = false;
        qrRequestedAt = System.currentTimeMillis();
        logStep("startLogin: clearToken=" + clearToken);
        showLoginPanel();
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.old_view_empty);
        }
        if (ivQr != null) {
            ivQr.setVisibility(View.VISIBLE);
            ivQr.setImageResource(android.R.drawable.ic_media_play);
        }
        if (tvStatus != null) {
            tvStatus.setText(R.string.old_view_status_fetch_qr);
        }
        if (tvLoginTitle != null) {
            tvLoginTitle.setText(R.string.old_view_login_title);
        }
        if (btnRefresh != null) {
            btnRefresh.setText(R.string.old_view_action_refresh_qr);
            btnRefresh.setVisibility(View.VISIBLE);
        }
        if (adapter != null) {
            adapter.update(null);
        }
        showLoading(false);
        requestQrCode();
    }

    protected void requestQrCode() {
        logStep("requestQrCode: start");
        BiliApi.requestQrAuthCode(new BiliApi.ApiCallback<BiliModels.QRAuthCodeResponse>() {
            @Override
            public void onSuccess(BiliModels.QRAuthCodeResponse data) {
                authCode = data.authCode;
                qrRequestedAt = System.currentTimeMillis();
                logStep("requestQrCode: success authCode=" + (authCode != null && authCode.length() > 0));
                if (tvStatus != null) {
                    tvStatus.setText(R.string.old_view_status_wait_scan);
                }
                if (ivQr != null) {
                    try {
                        int size = (int) (getResources().getDisplayMetrics().density * 220);
                        Bitmap bitmap = BiliQrGenerator.generate(data.url, size);
                        ivQr.setImageBitmap(bitmap);
                    } catch (Exception e) {
                        ivQr.setImageResource(android.R.drawable.ic_media_play);
                    }
                }
                startPolling();
            }

            @Override
            public void onError(String error) {
                loginInProgress = false;
                logError("二维码请求失败: " + (error != null ? error : "unknown"));
                if (tvStatus != null) {
                    tvStatus.setText(error != null && error.length() > 0 ? error : getString(R.string.old_view_status_expired));
                }
                Toast.makeText(OldViewActivitySupport2.this, error != null ? error : "获取二维码失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected void startPolling() {
        if (authCode == null || authCode.length() == 0) {
            return;
        }
        if (qrRequestedAt > 0 && System.currentTimeMillis() - qrRequestedAt > QR_EXPIRE_MS) {
            if (tvStatus != null) {
                tvStatus.setText(R.string.old_view_status_expired);
            }
            loginInProgress = false;
            logStep("startPolling: expired");
            return;
        }
        isPolling = true;
        pollingInFlight = false;
        logStep("startPolling: begin");
        schedulePoll(2000);
    }

    protected void stopPolling() {
        isPolling = false;
        pollingInFlight = false;
        handler.removeCallbacks(pollRunnable);
        logStep("stopPolling");
    }

    @Override
    protected void schedulePoll(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, delayMs);
    }

    @Override
    protected void handlePollResponse(BiliModels.QRPollResult response) {
        if (!isPolling) {
            return;
        }
        if (response == null) {
            logStep("poll: null response");
            schedulePoll(2000);
            return;
        }
        int code = response.code;
        logStep("poll: code=" + code);
        if (code == 0 && response.data != null) {
            stopPolling();
            loginInProgress = false;
            String accessToken = response.data.accessToken;
            String cookies = buildCookieString(response.data.cookieInfo);
            BiliAuthStore.saveAuthWithMid(this, accessToken, cookies, response.data.expiresIn, response.data.mid);
            if (tvStatus != null) {
                tvStatus.setText(R.string.old_view_status_login_ok);
            }
            showLoggedInUi();
            closeDrawerIfOpen();
            logStep("poll: login success -> loadRecommend");
            loadRecommend(accessToken);
            return;
        }
        if (code == 86090) {
            if (tvStatus != null) {
                tvStatus.setText(R.string.old_view_status_scanned);
            }
            schedulePoll(2000);
            return;
        }
        if (code == 86038) {
            if (tvStatus != null) {
                tvStatus.setText(R.string.old_view_status_expired);
            }
            loginInProgress = false;
            stopPolling();
            return;
        }
        if (code == 86039 || code == 86101) {
            if (tvStatus != null) {
                tvStatus.setText(R.string.old_view_status_wait_scan);
            }
            schedulePoll(2000);
            return;
        }
        if (tvStatus != null) {
            String msg = response.message != null ? response.message : "登录失败";
            tvStatus.setText(msg);
        }
        schedulePoll(2000);
    }

    protected void showLoggedInUi() {
        hideLoginPanel();
        guestMode = false;
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.old_view_empty);
        }
        updateSideLoginState();
    }

    /** 同步侧栏登录/退出按钮显隐。 */
    protected void updateSideLoginState() {
        String token = BiliAuthStore.getAccessToken(this);
        boolean expired = BiliAuthStore.isExpired(this);
        boolean loggedIn = token != null && token.length() > 0 && !expired;
        View sideLogin = findViewByIdCompat(R.id.btnOldViewSideLogin);
        View sideLogout = findViewByIdCompat(R.id.btnOldViewSideLogout);
        if (sideLogin != null) {
            sideLogin.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        }
        if (sideLogout != null) {
            sideLogout.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        }
        if (tvOldViewSideStatus != null) {
            tvOldViewSideStatus.setText(loggedIn
                    ? R.string.old_view_status_login_ok
                    : R.string.old_view_status_guest);
        }
    }

    protected void showGuestUi() {
        loginInProgress = false;
        stopPolling();
        guestMode = true;
        if (panelLogin != null) {
            panelLogin.setVisibility(View.VISIBLE);
        }
        if (tvLoginTitle != null) {
            tvLoginTitle.setText(R.string.old_view_login_guest_title);
        }
        if (ivQr != null) {
            ivQr.setVisibility(View.GONE);
        }
        if (btnRefresh != null) {
            btnRefresh.setVisibility(View.GONE);
        }
        if (tvStatus != null) {
            tvStatus.setText(R.string.old_view_status_guest);
        }
        if (tvEmpty != null) {
            tvEmpty.setText(searchMode ? R.string.old_view_search_empty : R.string.old_view_empty_guest);
        }
        updateSideLoginState();
        if (!searchMode && adapter != null && adapter.getCount() == 0) {
            logStep("guest: loadRecommend");
            loadRecommend(null);
        }
    }

    protected void showLoginPanel() {
        if (panelLogin != null) {
            panelLogin.setVisibility(View.VISIBLE);
        }
    }

    protected void hideLoginPanel() {
        if (panelLogin != null) {
            panelLogin.setVisibility(View.GONE);
        }
    }

    protected void closeDrawerIfOpen() {
        try {
            android.support.v4.widget.DrawerLayout dl =
                    (android.support.v4.widget.DrawerLayout) findViewByIdCompat(R.id.drawerOldView);
            if (dl != null && dl.isDrawerOpen(android.support.v4.view.GravityCompat.START)) {
                dl.closeDrawer(android.support.v4.view.GravityCompat.START);
            }
        } catch (Exception e) {
            // 忽略：旧布局无 DrawerLayout 时静默
        }
    }

    protected void showMenu(View anchor) {
        android.support.v7.widget.PopupMenu menu =
                new android.support.v7.widget.PopupMenu(this, anchor);
        menu.getMenuInflater().inflate(R.menu.menu_old_view, menu.getMenu());
        String token = BiliAuthStore.getAccessToken(this);
        boolean expired = BiliAuthStore.isExpired(this);
        boolean loggedIn = token != null && token.length() > 0 && !expired;
        android.view.MenuItem loginItem = menu.getMenu().findItem(R.id.action_login);
        android.view.MenuItem logoutItem = menu.getMenu().findItem(R.id.action_logout);
        if (loginItem != null) {
            loginItem.setVisible(!loggedIn);
        }
        if (logoutItem != null) {
            logoutItem.setVisible(loggedIn);
        }
        menu.setOnMenuItemClickListener(new android.support.v7.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.action_login) {
                    startLogin(false);
                    return true;
                }
                if (id == R.id.action_logout) {
                    BiliAuthStore.clear(OldViewActivitySupport2.this);
                    authCode = null;
                    loginInProgress = false;
                    stopPolling();
                    if (adapter != null) {
                        adapter.update(null);
                    }
                    showGuestUi();
                    return true;
                }
                if (id == R.id.action_history) {
                    startActivity(new android.content.Intent(OldViewActivitySupport2.this, OldViewHistoryActivity.class));
                    return true;
                }
                if (id == R.id.action_favorites) {
                    startActivity(new android.content.Intent(OldViewActivitySupport2.this, OldViewFavoritesActivity.class));
                    return true;
                }
                return false;
            }
        });
        try {
            menu.show();
        } catch (android.view.WindowManager.BadTokenException e) {
            // Activity was finishing when trying to show popup
        }
    }

    protected abstract void loadRecommend(String accessToken);
}
