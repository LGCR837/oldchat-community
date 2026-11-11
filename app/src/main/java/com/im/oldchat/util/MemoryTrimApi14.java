package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;

import com.im.oldchat.ui.UiMemoryCleaner;

/** 将 API 14 的 ComponentCallbacks2 完全隔离，避免 API 9 校验 Application 失败。 */
@TargetApi(14)
public final class MemoryTrimApi14 {
    private MemoryTrimApi14() { }

    public static void register(Application application) {
        if (application == null) return;
        application.registerComponentCallbacks(new ComponentCallbacks2() {
            @Override public void onTrimMemory(int level) {
                ImageLoader.trimMemory(level);
                BitmapPool.trimMemory(level);
                ViewHolderPool.trimMemory(level);
                UiMemoryCleaner.trimMemory(level);
            }
            @Override public void onConfigurationChanged(Configuration configuration) { }
            @Override public void onLowMemory() { }
        });
    }
}
