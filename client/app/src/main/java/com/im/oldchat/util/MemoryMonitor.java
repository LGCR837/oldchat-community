package com.im.oldchat.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存监控工具 - 实时监控内存使用
 * 自动触发内存优化
 */
public class MemoryMonitor {
    private static final long MONITOR_INTERVAL_MS = 5000L;
    private static final float MEMORY_WARNING_THRESHOLD = 0.80f;
    private static final float MEMORY_CRITICAL_THRESHOLD = 0.90f;
    
    private static volatile MemoryMonitor INSTANCE;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Context context;
    private Thread monitorThread;
    
    // 内存状态监听
    private MemoryStateListener stateListener;
    
    public interface MemoryStateListener {
        void onMemoryWarning(long used, long total);
        void onMemoryCritical(long used, long total);
    }
    
    public static MemoryMonitor getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MemoryMonitor.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MemoryMonitor(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }
    
    private MemoryMonitor(Context context) {
        this.context = context;
    }
    
    /**
     * 设置内存状态监听
     */
    public void setStateListener(MemoryStateListener listener) {
        this.stateListener = listener;
    }
    
    /**
     * 启动监控
     */
    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        
        monitorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get()) {
                    try {
                        checkMemory();
                        Thread.sleep(MONITOR_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        // 忽略异常，继续监控
                    }
                }
            }
        }, "MemoryMonitor");
        monitorThread.setDaemon(true);
        monitorThread.setPriority(Thread.MIN_PRIORITY);
        monitorThread.start();
    }
    
    /**
     * 停止监控
     */
    public void stop() {
        running.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }
    
    /**
     * 检查内存状态
     */
    private void checkMemory() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        float usageRatio = (float) usedMemory / maxMemory;
        
        if (usageRatio >= MEMORY_CRITICAL_THRESHOLD) {
            // 严重内存不足
            performEmergencyCleanup();
            if (stateListener != null) {
                stateListener.onMemoryCritical(usedMemory, maxMemory);
            }
        } else if (usageRatio >= MEMORY_WARNING_THRESHOLD) {
            // 内存警告
            performSoftCleanup();
            if (stateListener != null) {
                stateListener.onMemoryWarning(usedMemory, maxMemory);
            }
        }
    }
    
    /**
     * 执行紧急清理
     */
    private void performEmergencyCleanup() {
        // 先清理图片缓存(LRU逐出→BitmapPool.recycle)
        ImageLoader.clearMemoryCache();
        
        // 再清理Bitmap池
        BitmapPool.clear();
        
        // 清理ViewHolder池
        ViewHolderPool.clear();
        
        // 请求GC
        System.gc();
    }
    
    /**
     * 执行软清理
     */
    private void performSoftCleanup() {
        // 清理软引用
        ImageLoader.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
        BitmapPool.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE);
        ViewHolderPool.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE);
    }
    
    /**
     * 获取内存信息
     */
    public MemoryInfo getMemoryInfo() {
        MemoryInfo info = new MemoryInfo();
        
        // Runtime内存
        Runtime runtime = Runtime.getRuntime();
        info.maxMemory = runtime.maxMemory();
        info.totalMemory = runtime.totalMemory();
        info.freeMemory = runtime.freeMemory();
        info.usedMemory = info.totalMemory - info.freeMemory;
        
        // 应用内存
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            info.systemAvailableMemory = mi.availMem;
            info.systemTotalMemory = readSystemTotalMemory(mi);
            info.isLowMemory = mi.lowMemory;
        }
        
        // 应用堆内存
        info.appMemoryClass = am != null ? am.getMemoryClass() : 0;
        
        return info;
    }
    
    private long readSystemTotalMemory(ActivityManager.MemoryInfo info) {
        if (info == null || android.os.Build.VERSION.SDK_INT < 16) return 0L;
        try {
            return ActivityManager.MemoryInfo.class.getField("totalMem").getLong(info);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 获取原生堆内存
     */
    public long getNativeHeapSize() {
        return Debug.getNativeHeapSize();
    }
    
    /**
     * 获取已用原生堆内存
     */
    public long getNativeHeapAllocatedSize() {
        return Debug.getNativeHeapAllocatedSize();
    }
    
    /**
     * 获取进程内存信息
     */
    public ProcessMemoryInfo getProcessMemoryInfo() {
        ProcessMemoryInfo info = new ProcessMemoryInfo();
        
        int pid = android.os.Process.myPid();
        String path = "/proc/" + pid + "/status";
        
        try {
            BufferedReader reader = new BufferedReader(new FileReader(path));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    info.vmRss = parseVmSize(line);
                } else if (line.startsWith("VmSize:")) {
                    info.vmSize = parseVmSize(line);
                } else if (line.startsWith("Threads:")) {
                    info.threadCount = parseThreadCount(line);
                }
            }
            reader.close();
        } catch (Exception e) {
            // 忽略
        }
        
        return info;
    }
    
    private long parseVmSize(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                long kb = Long.parseLong(parts[1]);
                return kb * 1024; // 转换为字节
            }
        } catch (Exception e) {
        }
        return 0;
    }
    
    private int parseThreadCount(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                return Integer.parseInt(parts[1]);
            }
        } catch (Exception e) {
        }
        return 0;
    }
    
    /**
     * 内存信息
     */
    public static class MemoryInfo {
        public long maxMemory;
        public long totalMemory;
        public long freeMemory;
        public long usedMemory;
        public long systemAvailableMemory;
        public long systemTotalMemory;
        public int appMemoryClass;
        public boolean isLowMemory;
        
        public float getUsageRatio() {
            return (float) usedMemory / maxMemory;
        }
        
        public String toString() {
            return String.format("App: %.1f/%.1f MB (%.1f%%), System: %.1f/%.1f MB",
                usedMemory / 1024f / 1024f,
                maxMemory / 1024f / 1024f,
                getUsageRatio() * 100,
                systemAvailableMemory / 1024f / 1024f,
                systemTotalMemory / 1024f / 1024f);
        }
    }
    
    /**
     * 进程内存信息
     */
    public static class ProcessMemoryInfo {
        public long vmRss;
        public long vmSize;
        public int threadCount;
        
        public String toString() {
            return String.format("RSS: %.1f MB, VM: %.1f MB, Threads: %d",
                vmRss / 1024f / 1024f,
                vmSize / 1024f / 1024f,
                threadCount);
        }
    }
}
