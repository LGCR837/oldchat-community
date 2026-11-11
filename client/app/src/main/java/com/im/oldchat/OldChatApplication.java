package com.im.oldchat;

import android.app.Application;
import android.content.Context;
import android.support.multidex.MultiDex;

/**
 * 应用入口保持极简。
 *
 * Android 2.3 的 Dalvik 会在 Application 创建阶段验证其直接引用的类；若入口类
 * 同时引用 AppCompat、服务、图片缓存等大量组件，任何旧系统不认识的符号都可能在
 * 首个 Activity 出现前触发 VerifyError，且此时还来不及显示应用内崩溃页。
 * 完整初始化由开屏页在界面创建成功后交给 AppStartupInitializer 执行。
 */
public class OldChatApplication extends Application {
    private static Context appContext;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        // AppCompat 矢量图兼容开关需在 Application 阶段开启：
        // 聊天列表气泡里的状态勾（ic_msg_sent/sent/read 等 vector）由
        // ImageView android:src / setImageResource 加载。AppCompat 的
        // setCompatVectorFromResourcesEnabled(true) 仅拦截 srcCompat，
        // 不拦截普通 src；在 API < 21 上会按 PNG 目录回退查找，找不到即
        // Resources.NotFoundException 崩溃。此处关闭「仅 PNG」模式，改由
        // AppCompatResources 走 VectorDrawableCompat 兼容路径绘制。
        try {
            android.support.v7.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(false);
        } catch (Throwable ignored) {
        }
        // Android 2.3 (API <= 10) 的 HttpURLConnection 连接池存在复用空闲连接导致 EOFException / reset 的已知缺陷。
        // 禁用底层 keep-alive 连接池，避免网络连接莫名其妙断开。
        if (android.os.Build.VERSION.SDK_INT <= 10) {
            try {
                System.setProperty("http.keepAlive", "false");
            } catch (Throwable ignored) {
            }
        }
        // 预热动态下载源（rainoss 直链优先），服务端可随时换线路而无需更新客户端。
        try {
            com.im.oldchat.util.DownloadSourcesManager.warmUp(appContext);
        } catch (Throwable ignored) {
        }
    }

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        try {
            com.im.oldchat.util.ImageLoader.clearMemoryCache();
        } catch (Throwable ignored) {
        }
        System.gc();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (android.os.Build.VERSION.SDK_INT >= 14) {
            try {
                com.im.oldchat.util.ImageLoader.trimMemory(level);
            } catch (Throwable ignored) {
            }
        }
    }
}
