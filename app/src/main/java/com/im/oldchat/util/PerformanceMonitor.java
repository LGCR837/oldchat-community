package com.im.oldchat.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 性能监控工具 - 跟踪UI卡顿和耗时操作
 */
public class PerformanceMonitor {
    private static final String TAG = "PerfMonitor";
    private static final long FRAME_TIME_THRESHOLD_MS = 16; // 60fps = 16ms/frame
    private static final long SLOW_FRAME_THRESHOLD_MS = 33; // 30fps
    private static final long BLOCK_THRESHOLD_MS = 100; // 阻塞阈值
    
    private static volatile PerformanceMonitor INSTANCE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    
    // 帧率监控
    private long lastFrameTime;
    private int frameCount;
    private int slowFrameCount;
    private int blockCount;
    
    // 耗时操作统计
    private final Map<String, OperationStats> operationStats = new HashMap<String, OperationStats>();
    
    // 监听器
    private PerformanceListener listener;
    
    public interface PerformanceListener {
        void onSlowFrame(long frameTimeMs, String info);
        void onBlockDetected(String operation, long durationMs);
        void onPerformanceReport(PerformanceReport report);
    }
    
    public static PerformanceMonitor getInstance() {
        if (INSTANCE == null) {
            synchronized (PerformanceMonitor.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PerformanceMonitor();
                }
            }
        }
        return INSTANCE;
    }
    
    private PerformanceMonitor() {
    }
    
    /**
     * 设置性能监听器
     */
    public void setListener(PerformanceListener listener) {
        this.listener = listener;
    }
    
    /**
     * 开始帧率监控
     */
    public void startFrameMonitoring() {
        if (monitoring.getAndSet(true)) {
            return;
        }
        
        lastFrameTime = SystemClock.uptimeMillis();
        mainHandler.post(frameRunnable);
    }
    
    /**
     * 停止帧率监控
     */
    public void stopFrameMonitoring() {
        monitoring.set(false);
        mainHandler.removeCallbacks(frameRunnable);
    }
    
    /**
     * 开始追踪耗时操作
     */
    public OperationTrace startTrace(String operation) {
        return new OperationTrace(operation);
    }
    
    /**
     * 记录操作耗时
     */
    public void recordOperation(String operation, long durationMs) {
        synchronized (operationStats) {
            OperationStats stats = operationStats.get(operation);
            if (stats == null) {
                stats = new OperationStats(operation);
                operationStats.put(operation, stats);
            }
            stats.add(durationMs);
        }
        
        if (durationMs > BLOCK_THRESHOLD_MS && listener != null) {
            listener.onBlockDetected(operation, durationMs);
        }
    }
    
    /**
     * 获取性能报告
     */
    public PerformanceReport getReport() {
        PerformanceReport report = new PerformanceReport();
        report.frameCount = frameCount;
        report.slowFrameCount = slowFrameCount;
        report.blockCount = blockCount;
        
        synchronized (operationStats) {
            for (OperationStats stats : operationStats.values()) {
                report.operations.add(stats.copy());
            }
        }
        
        return report;
    }
    
    /**
     * 重置统计
     */
    public void resetStats() {
        frameCount = 0;
        slowFrameCount = 0;
        blockCount = 0;
        synchronized (operationStats) {
            operationStats.clear();
        }
    }
    
    /**
     * 打印当前性能报告
     */
    public void printReport() {
        PerformanceReport report = getReport();
        Log.i(TAG, "=== Performance Report ===");
        Log.i(TAG, "Total frames: " + report.frameCount);
        Log.i(TAG, "Slow frames: " + report.slowFrameCount + " (" + 
              (report.frameCount > 0 ? (report.slowFrameCount * 100 / report.frameCount) : 0) + "%)");
        Log.i(TAG, "Blocks: " + report.blockCount);
        Log.i(TAG, "Operations:");
        for (OperationStats stats : report.operations) {
            Log.i(TAG, "  " + stats.name + ": count=" + stats.count + 
                  ", avg=" + stats.getAverage() + "ms, max=" + stats.max + "ms");
        }
    }
    
    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitoring.get()) {
                return;
            }
            
            long now = SystemClock.uptimeMillis();
            long frameTime = now - lastFrameTime;
            lastFrameTime = now;
            
            frameCount++;
            
            if (frameTime > SLOW_FRAME_THRESHOLD_MS) {
                slowFrameCount++;
                if (frameTime > BLOCK_THRESHOLD_MS) {
                    blockCount++;
                }
                if (listener != null) {
                    listener.onSlowFrame(frameTime, getCurrentOperation());
                }
            }
            
            // 下一帧
            mainHandler.postDelayed(this, FRAME_TIME_THRESHOLD_MS);
        }
    };
    
    private String getCurrentOperation() {
        // 简化实现，返回空字符串
        return "";
    }
    
    /**
     * 操作追踪器
     */
    public class OperationTrace {
        private final String operation;
        private final long startTime;
        
        OperationTrace(String operation) {
            this.operation = operation;
            this.startTime = SystemClock.uptimeMillis();
        }
        
        /**
         * 结束追踪并记录
         */
        public void end() {
            long duration = SystemClock.uptimeMillis() - startTime;
            recordOperation(operation, duration);
        }
        
        /**
         * 结束追踪并记录（带额外信息）
         */
        public void end(String extraInfo) {
            long duration = SystemClock.uptimeMillis() - startTime;
            recordOperation(operation + "_" + extraInfo, duration);
        }
    }
    
    /**
     * 操作统计数据
     */
    public static class OperationStats {
        public final String name;
        public int count;
        public long total;
        public long min = Long.MAX_VALUE;
        public long max;
        
        OperationStats(String name) {
            this.name = name;
        }
        
        void add(long duration) {
            count++;
            total += duration;
            if (duration < min) min = duration;
            if (duration > max) max = duration;
        }
        
        long getAverage() {
            return count > 0 ? total / count : 0;
        }
        
        OperationStats copy() {
            OperationStats copy = new OperationStats(name);
            copy.count = count;
            copy.total = total;
            copy.min = min;
            copy.max = max;
            return copy;
        }
    }
    
    /**
     * 性能报告
     */
    public static class PerformanceReport {
        public int frameCount;
        public int slowFrameCount;
        public int blockCount;
        public java.util.List<OperationStats> operations = new java.util.ArrayList<OperationStats>();
        
        public float getSlowFrameRate() {
            return frameCount > 0 ? (float) slowFrameCount / frameCount : 0;
        }
    }
}
