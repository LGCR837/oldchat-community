package com.im.oldchat.util;

import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;

/**
 * RecyclerView 优化工具 - 提升聊天列表滑动流畅度
 */
public class RecyclerViewOptimizationHelper {
    
    /**
     * 为聊天 RecyclerView 应用基础性能优化
     */
    public static void applyChatOptimizations(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        
        // 启用固定大小模式，提升性能
        recyclerView.setHasFixedSize(true);
        
        // 设置适当的 View 缓存数量 - 增加到12以支持快速滑动
        recyclerView.setItemViewCacheSize(12);
        
        // 关闭变化动画，减少 CPU 使用
        recyclerView.setItemAnimator(null);
        
        // 设置RecyclerView池大小
        RecyclerView.RecycledViewPool pool = recyclerView.getRecycledViewPool();
        if (pool != null) {
            // 为每种ViewType设置最大缓存数
            pool.setMaxRecycledViews(0, 10);
            pool.setMaxRecycledViews(1, 10);
            pool.setMaxRecycledViews(2, 5);
        }
        
        // 启用嵌套滚动优化
        recyclerView.setNestedScrollingEnabled(false);
    }
    
    /**
     * 应用高性能模式 - 用于消息列表等频繁更新的场景
     */
    public static void applyHighPerformanceMode(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        
        applyChatOptimizations(recyclerView);
        
        // 设置预加载优化
        RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            LinearLayoutManager llm = (LinearLayoutManager) lm;
            // 启用平滑滚动优化
            llm.setSmoothScrollbarEnabled(true);
        }
        
        // 禁用过度绘制优化
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }
    
    /**
     * 设置滚动监听器来优化图片加载
     */
    public static void attachScrollOptimizer(final RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                
                switch (newState) {
                    case RecyclerView.SCROLL_STATE_IDLE:
                        // 停止滚动，恢复图片加载
                        ImageLoader.setGlobalPauseForScroll(false);
                        break;
                    case RecyclerView.SCROLL_STATE_DRAGGING:
                    case RecyclerView.SCROLL_STATE_SETTLING:
                        // 正在滚动，暂停图片加载
                        ImageLoader.setGlobalPauseForScroll(true);
                        break;
                }
            }
            
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                // 快速滑动时暂停加载
                if (Math.abs(dy) > 30) {
                    ImageLoader.setGlobalPauseForScroll(true);
                }
            }
        });
    }
    
    /**
     * 高效批量更新 - 避免频繁刷新
     */
    public static void notifyDataSetChangedEfficient(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        
        RecyclerView.Adapter adapter = recyclerView.getAdapter();
        if (adapter != null) {
            // ViewGroup.suppressLayout 直到 API 29 才公开，旧系统直接调用会崩溃。
            // RecyclerView 自己会在 notifyDataSetChanged 后合并布局请求。
            adapter.notifyDataSetChanged();
        }
    }
    
    /**
     * 清理RecyclerView缓存
     */
    public static void clearCaches(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        
        RecyclerView.RecycledViewPool pool = recyclerView.getRecycledViewPool();
        if (pool != null) {
            pool.clear();
        }
        
        recyclerView.clearOnScrollListeners();
    }
}