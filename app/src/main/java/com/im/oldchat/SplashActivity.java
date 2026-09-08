package com.im.oldchat;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.im.oldchat.util.LegacyPlatformCompat;
import android.widget.TextView;

import com.im.oldchat.api.WSManager;
import com.im.oldchat.models.FriendCache;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.service.MessageService;
import com.im.oldchat.ui.LoginActivity;
import com.im.oldchat.util.AppStartupInitializer;
import com.im.oldchat.util.CrashHandler;

/**
 * 开屏页。无动画、无等待，仅并行预热本地缓存、消息同步和 WebSocket 后立即进入主界面。
 */
public class SplashActivity extends Activity {
    private static final long SPLASH_DURATION_MS = 0L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long createdAtMs;
    private boolean routed;
    private String token;

    private final Runnable routeTask = new Runnable() {
        @Override
        public void run() {
            routeToNextPage();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdAtMs = SystemClock.uptimeMillis();
        token = readToken();
        setContentView(buildSplashView());
        // 先让原生界面创建成功，再加载完整应用组件。Android 2.3 保持零预热启动。
        CrashHandler.install(this);
        // API 9/10 不执行完整预热，但必须在首次 HTTPS 请求前补齐 TLS 1.2
        // 并开启 Support Library 的 vector 资源兼容。
        LegacyPlatformCompat.install(this);
        if (Build.VERSION.SDK_INT > 10) {
            AppStartupInitializer.initialize(this);
        }
        startPreparation();

        // 无开屏动画，尽快路由到登录或主界面。
        long elapsed = SystemClock.uptimeMillis() - createdAtMs;
        mainHandler.postDelayed(routeTask, Math.max(0L, SPLASH_DURATION_MS - elapsed));
    }

    private View buildSplashView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getResources().getColor(R.color.colorBackground));

        ImageView logo = new ImageView(this);
        logo.setId(R.id.splash_logo);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int logoSize = dp(112);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(logoSize, logoSize);
        logoParams.gravity = Gravity.CENTER;
        root.addView(logo, logoParams);

        TextView provider = new TextView(this);
        provider.setText("云计算服务由\nFreecafe.org\n（一家公益云服务提供商）\n提供");
        provider.setTextColor(getResources().getColor(R.color.colorTextSecondary));
        provider.setTextSize(13f);
        provider.setGravity(Gravity.CENTER);
        provider.setLineSpacing(dp(2), 1f);
        FrameLayout.LayoutParams providerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        providerParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        providerParams.leftMargin = dp(24);
        providerParams.rightMargin = dp(24);
        providerParams.bottomMargin = dp(32);
        root.addView(provider, providerParams);
        return root;
    }

    private void startPreparation() {
        if (token == null || token.length() == 0) {
            return;
        }
        // Android 2.3 完全不预热，先保证能够进入登录页或主页。
        if (Build.VERSION.SDK_INT <= 10) {
            return;
        }
        // MessageService 与 WSManager 都具有幂等保护；这里尽早开始鉴权、未读同步和建连。
        MessageService.startIfAllowed(getApplicationContext());
        WSManager.getInstance().start(getApplicationContext());
        com.im.oldchat.lua.LuaAppSyncManager.sync(getApplicationContext(), null);

        // 首屏会读取这些 JSON 缓存，提前在后台转为内存对象，降低进入主页后的抖动。
        final android.content.Context appContext = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FriendCache.getFriends(appContext);
                    GroupCache.getGroups(appContext);
                    RecentChatCache.getRecentChats(appContext);
                } catch (Throwable ignored) {
                    // 预热失败不影响正常进入应用，主页仍会按原流程加载。
                }
            }
        }, "oldchat-splash-prewarm").start();
    }

    private String readToken() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String value = prefs.getString("access_token", null);
        if (value == null || value.length() == 0) {
            value = prefs.getString("token", null);
        }
        return value;
    }

    private void routeToNextPage() {
        if (routed || isFinishing()) {
            return;
        }
        routed = true;
        // 首次启动优先展示引导页（配置 UI 外观），之后才进入登录/主界面。
        if (!com.im.oldchat.util.SettingsPrefs.isAppDpiFirstPromptShown(this)) {
            startActivity(new Intent(this, com.im.oldchat.ui.WelcomeGuideActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }
        Intent intent = new Intent(this,
                token == null || token.length() == 0 ? LoginActivity.class : MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(routeTask);
        super.onDestroy();
    }
}
