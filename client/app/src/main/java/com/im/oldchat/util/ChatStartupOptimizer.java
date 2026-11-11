package com.im.oldchat.util;

import android.os.Handler;
import android.os.Looper;
import android.support.v7.widget.RecyclerView;
import android.view.View;

/**
 * 聊天页面启动优化器
 * 通过延迟初始化和任务调度提升启动速度
 */
public final class ChatStartupOptimizer {
    private static final long DEFERRED_INIT_DELAY_MS = 50L;
    private static final long BACKGROUND_LOAD_DELAY_MS = 80L;
    private static final long NON_CRITICAL_INIT_DELAY_MS = 150L;
    private static final long PREFETCH_DELAY_MS = 200L;
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deferredTasks = new Runnable() {
        @Override
        public void run() {
            runDeferredTasks();
        }
    };
    private final Runnable backgroundTasks = new Runnable() {
        @Override
        public void run() {
            runBackgroundTasks();
        }
    };
    private final Runnable nonCriticalTasks = new Runnable() {
        @Override
        public void run() {
            runNonCriticalTasks();
        }
    };
    private final Runnable prefetchTasks = new Runnable() {
        @Override
        public void run() {
            runPrefetchTasks();
        }
    };
    
    // 任务接口
    public interface CriticalInitTask {
        void run();
    }
    
    public interface DeferredInitTask {
        void run();
    }
    
    public interface BackgroundLoadTask {
        void run();
    }
    
    public interface NonCriticalInitTask {
        void run();
    }
    
    public interface PrefetchTask {
        void run();
    }
    
    private CriticalInitTask criticalTask;
    private DeferredInitTask deferredTask;
    private BackgroundLoadTask backgroundTask;
    private NonCriticalInitTask nonCriticalTask;
    private PrefetchTask prefetchTask;
    
    private boolean isCriticalDone = false;
    private boolean isDeferredScheduled = false;
    private boolean isReleased = false;
    
    /**
     * 设置关键初始化任务（同步执行）
     */
    public void setCriticalInitTask(CriticalInitTask task) {
        this.criticalTask = task;
    }
    
    /**
     * 设置延迟初始化任务（50ms后执行）
     */
    public void setDeferredInitTask(DeferredInitTask task) {
        this.deferredTask = task;
    }
    
    /**
     * 设置背景加载任务（80ms后执行）
     */
    public void setBackgroundLoadTask(BackgroundLoadTask task) {
        this.backgroundTask = task;
    }
    
    /**
     * 设置非关键初始化任务（150ms后执行）
     */
    public void setNonCriticalInitTask(NonCriticalInitTask task) {
        this.nonCriticalTask = task;
    }
    
    /**
     * 设置预取任务（200ms后执行）
     */
    public void setPrefetchTask(PrefetchTask task) {
        this.prefetchTask = task;
    }
    
    /**
     * 执行启动优化流程
     */
    public void executeStartup() {
        if (isReleased) {
            return;
        }
        
        // 1. 立即执行关键任务
        runCriticalTasks();
        
        // 2. 调度延迟任务
        scheduleDeferredTasks();
    }
    
    private void runCriticalTasks() {
        if (isReleased) {
            return;
        }
        if (criticalTask != null) {
            criticalTask.run();
        }
        isCriticalDone = true;
    }
    
    private void scheduleDeferredTasks() {
        if (isReleased || isDeferredScheduled) {
            return;
        }
        isDeferredScheduled = true;
        
        // 延迟初始化（UI相关但非关键）
        if (deferredTask != null) {
            handler.postDelayed(deferredTasks, DEFERRED_INIT_DELAY_MS);
        }
        
        // 背景加载
        if (backgroundTask != null) {
            handler.postDelayed(backgroundTasks, BACKGROUND_LOAD_DELAY_MS);
        }
        
        // 非关键初始化
        if (nonCriticalTask != null) {
            handler.postDelayed(nonCriticalTasks, NON_CRITICAL_INIT_DELAY_MS);
        }
        
        // 预取任务
        if (prefetchTask != null) {
            handler.postDelayed(prefetchTasks, PREFETCH_DELAY_MS);
        }
    }
    
    private void runDeferredTasks() {
        if (isReleased || deferredTask == null) {
            return;
        }
        deferredTask.run();
    }
    
    private void runBackgroundTasks() {
        if (isReleased || backgroundTask == null) {
            return;
        }
        backgroundTask.run();
    }
    
    private void runNonCriticalTasks() {
        if (isReleased || nonCriticalTask == null) {
            return;
        }
        nonCriticalTask.run();
    }
    
    private void runPrefetchTasks() {
        if (isReleased || prefetchTask == null) {
            return;
        }
        prefetchTask.run();
    }
    
    /**
     * 释放资源
     */
    public void release() {
        isReleased = true;
        handler.removeCallbacks(deferredTasks);
        handler.removeCallbacks(backgroundTasks);
        handler.removeCallbacks(nonCriticalTasks);
        handler.removeCallbacks(prefetchTasks);
    }
    
    /**
     * 是否已完成关键初始化
     */
    public boolean isCriticalDone() {
        return isCriticalDone;
    }
    
    /**
     * 优化 RecyclerView 首次显示
     */
    public static void optimizeRecyclerViewForStartup(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        
        // 禁用动画以加快首次渲染
        recyclerView.setItemAnimator(null);
        
        // 增加缓存大小
        recyclerView.setItemViewCacheSize(16);
        
        // 设置 RecyclerView 池
        RecyclerView.RecycledViewPool pool = recyclerView.getRecycledViewPool();
        if (pool != null) {
            pool.setMaxRecycledViews(0, 12);
            pool.setMaxRecycledViews(1, 8);
        }
    }
    
    /**
     * 恢复 RecyclerView 正常状态
     */
    public static void restoreRecyclerViewNormalState(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        // 恢复正常缓存大小
        recyclerView.setItemViewCacheSize(12);
    }
}
