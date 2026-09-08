package com.im.oldchat.util;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.support.v7.app.AppCompatDelegate;

import com.im.oldchat.service.MessageService;

/** 在开屏界面成功创建后执行的完整应用初始化。 */
public final class AppStartupInitializer {
    private static boolean initialized;

    private AppStartupInitializer() {
    }

    public static synchronized void initialize(Context context) {
        if (initialized || context == null) {
            return;
        }
        initialized = true;
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) {
            return;
        }
        Application app = (Application) appContext;

        AppDpiManager.applyCurrentDpi(app);
        PrefsSanitizer.sanitize(app);
        ServerBaseUrlManager.applySavedBaseUrl(app);
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        if (!SettingsPrefs.isDarkModeSupported()) {
            SettingsPrefs.setDarkModeEnabled(app, false);
            if (SettingsPrefs.getThemeColorIndex(app) == ThemeColorUtil.THEME_MINIMAL_DARK) {
                SettingsPrefs.setThemeColorIndex(app, ThemeColorUtil.THEME_CLASSIC);
            }
        }
        boolean darkMode = SettingsPrefs.isDarkModeEnabled(app);
        if (ThemeColorUtil.isMinimalDark(app)) {
            darkMode = true;
        }
        AppCompatDelegate.setDefaultNightMode(darkMode
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
        LogManager.getInstance(app);
        MemoryMonitor.getInstance(app).start();
        if (Build.VERSION.SDK_INT >= 14) {
            MemoryTrimApi14.register(app);
        }
        AppState.init(app);
        MessageService.startIfAllowed(app);
    }
}
