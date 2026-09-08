package com.im.oldchat.bili;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * WebView 内存防护：低内存设备上 WebView 极耗内存（可达 30-80MB），
 * 加载前检查堆内存余量和设备内存档位，不足时阻止加载以避免 OOM 崩溃。
 */
public final class BiliWebViewMemoryGuard {
    private static final int WEBVIEW_HEAP_MARGIN_MB = 24;

    private BiliWebViewMemoryGuard() {
    }

    /** 判断当前设备/状态下是否不适合加载 WebView。 */
    public static boolean isMemoryTooLowForWebView(Context context) {
        if (context == null) {
            return false;
        }
        // 1) 应用堆剩余空间不足：WebView 进程共享主进程堆，32-64MB 档位
        //    剩余不足 24MB 时加载页面极可能 OOM。
        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        long freeHeapMb = (maxHeap - usedHeap) / (1024L * 1024L);
        if (freeHeapMb < WEBVIEW_HEAP_MARGIN_MB) {
            return true;
        }
        // 2) 低内存设备（memClass <= 64）且系统整体内存紧张时也阻止加载。
        int memClass = getMemoryClassMb(context);
        if (memClass > 0 && memClass <= 64) {
            return isSystemMemoryLow(context);
        }
        return false;
    }

    private static int getMemoryClassMb(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                return am.getMemoryClass();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean isSystemMemoryLow(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                if (info.lowMemory) {
                    return true;
                }
                if (Build.VERSION.SDK_INT >= 16 && info.availMem > 0) {
                    // 低内存设备可用系统内存不足 200MB 视为紧张。
                    long totalMem;
                    try {
                        totalMem = (Long) ActivityManager.MemoryInfo.class.getField("totalMem").get(info);
                    } catch (Throwable ignored) {
                        totalMem = 0L;
                    }
                    if (totalMem > 0 && info.availMem < totalMem / 8) {
                        return true;
                    }
                    if (info.availMem < 200L * 1024L * 1024L) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
