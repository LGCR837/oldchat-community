package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.im.oldchat.service.MessageService;

/** 隔离 API 14 的 ActivityLifecycleCallbacks，API 9 启动时不会加载此类。 */
@TargetApi(14)
final class AppStateApi14 {
    private AppStateApi14() { }

    static void init(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) {
                if (AppState.onActivityStarted()) {
                    MessageService.startIfAllowed(activity.getApplicationContext());
                }
            }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { AppState.onActivityStopped(); }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
}
