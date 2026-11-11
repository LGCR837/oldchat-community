package com.im.oldchat.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的线程池管理器 - 优化线程使用
 * 避免创建过多线程，提高性能
 */
public class ThreadPoolManager {
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE_SECONDS = 30;
    
    // 单例实例
    private static volatile ThreadPoolManager INSTANCE;
    
    // 线程池
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor ioExecutor;
    private final ThreadPoolExecutor computeExecutor;
    private final ThreadPoolExecutor singleExecutor;
    
    public static ThreadPoolManager getInstance() {
        if (INSTANCE == null) {
            synchronized (ThreadPoolManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ThreadPoolManager();
                }
            }
        }
        return INSTANCE;
    }
    
    private ThreadPoolManager() {
        // 通用线程池
        this.executor = createExecutor(
            CORE_POOL_SIZE,
            MAXIMUM_POOL_SIZE,
            KEEP_ALIVE_SECONDS,
            new LinkedBlockingQueue<Runnable>(128),
            "general",
            true
        );
        
        // IO线程池 - 更多线程
        this.ioExecutor = createExecutor(
            CORE_POOL_SIZE * 2,
            MAXIMUM_POOL_SIZE * 2,
            KEEP_ALIVE_SECONDS,
            new LinkedBlockingQueue<Runnable>(256),
            "io",
            true
        );
        
        // 计算密集型线程池 - 较少线程
        this.computeExecutor = createExecutor(
            Math.max(1, CPU_COUNT - 1),
            CPU_COUNT,
            KEEP_ALIVE_SECONDS,
            new LinkedBlockingQueue<Runnable>(64),
            "compute",
            true
        );
        
        // 单线程串行执行 - 核心线程不过期
        this.singleExecutor = createExecutor(
            1,
            1,
            KEEP_ALIVE_SECONDS,
            new LinkedBlockingQueue<Runnable>(64),
            "single",
            false
        );
    }
    
    private ThreadPoolExecutor createExecutor(int corePoolSize, int maximumPoolSize, 
                                               long keepAliveSeconds, BlockingQueue<Runnable> queue, 
                                               String name, boolean allowCoreTimeout) {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
            corePoolSize,
            maximumPoolSize,
            keepAliveSeconds,
            TimeUnit.SECONDS,
            queue,
            new DefaultThreadFactory(name),
            new DiscardOldestPolicy()
        );
        if (allowCoreTimeout) {
            exec.allowCoreThreadTimeOut(true);
        }
        return exec;
    }
    
    /**
     * 执行通用任务
     */
    public void execute(Runnable task) {
        if (task != null) {
            executor.execute(task);
        }
    }
    
    /**
     * 执行IO任务（网络、文件）
     */
    public void executeIo(Runnable task) {
        if (task != null) {
            ioExecutor.execute(task);
        }
    }
    
    /**
     * 执行计算密集型任务
     */
    public void executeCompute(Runnable task) {
        if (task != null) {
            computeExecutor.execute(task);
        }
    }
    
    /**
     * 串行执行任务
     */
    public void executeSerial(Runnable task) {
        if (task != null) {
            singleExecutor.execute(task);
        }
    }
    
    /**
     * 获取活跃线程数
     */
    public int getActiveCount() {
        return executor.getActiveCount() 
            + ioExecutor.getActiveCount() 
            + computeExecutor.getActiveCount()
            + singleExecutor.getActiveCount();
    }
    
    /**
     * 获取队列大小
     */
    public int getQueueSize() {
        return executor.getQueue().size() 
            + ioExecutor.getQueue().size() 
            + computeExecutor.getQueue().size()
            + singleExecutor.getQueue().size();
    }
    
    /**
     * 关闭所有线程池
     */
    public void shutdown() {
        executor.shutdown();
        ioExecutor.shutdown();
        computeExecutor.shutdown();
        singleExecutor.shutdown();
    }
    
    /**
     * 默认线程工厂
     */
    private static class DefaultThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(1);
        private final String name;
        
        DefaultThreadFactory(String name) {
            this.name = name;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "Pool-" + name + "-" + count.getAndIncrement());
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
    
    /**
     * 丢弃最老任务的策略
     */
    private static class DiscardOldestPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
