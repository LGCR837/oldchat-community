package com.im.oldchat.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ViewHolder池 - 优化RecyclerView性能
 * 减少View创建和findViewById调用
 */
@Deprecated
public class ViewHolderPool {
    private static final int MAX_POOL_SIZE = 20;
    private static final int MAX_PER_TYPE = 5;
    
    // 按ViewType分类的池
    private static final Object POOL_LOCK = new Object();
    private static final Map<Integer, List<View>> POOL = new HashMap<Integer, List<View>>();
    // ViewHolder缓存
    private static final Map<View, ViewHolder> HOLDER_CACHE = new HashMap<View, ViewHolder>();
    
    private ViewHolderPool() {
    }
    
    /**
     * 获取ViewHolder
     * @param parent 父ViewGroup
     * @param viewType View类型
     * @param factory View创建工厂
     * @return 复用的或新创建的ViewHolder
     */
    public static ViewHolder obtain(ViewGroup parent, int viewType, ViewFactory factory) {
        View view = obtainView(viewType);
        if (view == null) {
            view = factory.createView(parent, viewType);
        }
        
        synchronized (POOL_LOCK) {
            ViewHolder holder = HOLDER_CACHE.get(view);
            if (holder == null) {
                holder = new ViewHolder(view);
                HOLDER_CACHE.put(view, holder);
            }
            return holder;
        }
    }
    
    /**
     * 回收ViewHolder
     * @param holder 要回收的ViewHolder
     * @param viewType View类型
     */
    public static void recycle(ViewHolder holder, int viewType) {
        if (holder == null || holder.itemView == null) {
            return;
        }
        synchronized (POOL_LOCK) {
            recycleView(holder.itemView, viewType);
        }
    }
    
    /**
     * 清空池
     */
    public static void clear() {
        synchronized (POOL_LOCK) {
            POOL.clear();
            HOLDER_CACHE.clear();
        }
    }
    
    /**
     * 根据内存压力修剪池
     */
    public static void trimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            clear();
            return;
        }
        
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            synchronized (POOL_LOCK) {
                // 减半
                for (List<View> list : POOL.values()) {
                    while (list.size() > MAX_PER_TYPE / 2) {
                        list.remove(list.size() - 1);
                    }
                }
            }
        }
    }
    
    private static View obtainView(int viewType) {
        synchronized (POOL_LOCK) {
            List<View> list = POOL.get(viewType);
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.remove(list.size() - 1);
        }
    }
    
    private static void recycleView(View view, int viewType) {
        List<View> list = POOL.get(viewType);
        if (list == null) {
            list = new ArrayList<View>();
            POOL.put(viewType, list);
        }
        
        if (list.size() < MAX_PER_TYPE && getTotalSize() < MAX_POOL_SIZE) {
            list.add(view);
        }
    }
    
    private static int getTotalSize() {
        synchronized (POOL_LOCK) {
            int total = 0;
            for (List<View> list : POOL.values()) {
                total += list.size();
            }
            return total;
        }
    }
    
    /**
     * View创建工厂接口
     */
    public interface ViewFactory {
        View createView(ViewGroup parent, int viewType);
    }
    
    /**
     * 轻量级ViewHolder实现
     */
    public static class ViewHolder {
        public final View itemView;
        private final Map<Integer, View> viewCache = new HashMap<Integer, View>();
        
        public ViewHolder(View itemView) {
            this.itemView = itemView;
        }
        
        /**
         * 获取子View（带缓存）
         */
        public View findViewById(int id) {
            View view = viewCache.get(id);
            if (view == null) {
                view = itemView.findViewById(id);
                if (view != null) {
                    viewCache.put(id, view);
                }
            }
            return view;
        }
        
        /**
         * 获取TextView（带缓存）
         */
        public TextView getTextView(int id) {
            View view = findViewById(id);
            return view instanceof TextView ? (TextView) view : null;
        }
        
        /**
         * 清空缓存
         */
        public void clearCache() {
            viewCache.clear();
        }
    }
}
