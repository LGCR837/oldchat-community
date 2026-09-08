package com.im.oldchat.util;

import android.graphics.Bitmap;
import android.os.Build;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Bitmap对象池 - 减少GC压力和内存分配
 * 适用于Android 2.3+ (API 9+)
 */
public class BitmapPool {
    private static final int MAX_POOL_SIZE = 32;
    private static final int MAX_SOFT_POOL_SIZE = 64;
    private static final int OLD_DEVICE_POOL_BYTES = 2 * 1024 * 1024;
    private static final int NORMAL_POOL_BYTES = 16 * 1024 * 1024;
    private static int hardPoolBytes;
    
    // 硬引用池 - 快速复用
    private static final List<Bitmap> POOL = new ArrayList<Bitmap>();
    // 软引用池 - 备用缓存
    private static final List<SoftReference<Bitmap>> SOFT_POOL = new ArrayList<SoftReference<Bitmap>>();
    
    private BitmapPool() {
    }
    
    /**
     * 获取可复用的Bitmap
     * @param width 需要的宽度
     * @param height 需要的高度
     * @param config Bitmap配置
     * @return 可复用的Bitmap或null
     */
    public static synchronized Bitmap obtain(int width, int height, Bitmap.Config config) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        
        int neededSize = width * height * getBytesPerPixel(config);
        
        // 先从硬引用池查找
        Iterator<Bitmap> hardIter = POOL.iterator();
        while (hardIter.hasNext()) {
            Bitmap candidate = hardIter.next();
            if (candidate != null && !candidate.isRecycled() 
                && canReuseBitmap(candidate, width, height, config)) {
                hardIter.remove();
                hardPoolBytes = Math.max(0, hardPoolBytes - getBitmapSize(candidate));
                candidate.eraseColor(0);
                return candidate;
            }
        }
        
        // 从软引用池查找
        Iterator<SoftReference<Bitmap>> softIter = SOFT_POOL.iterator();
        while (softIter.hasNext()) {
            SoftReference<Bitmap> ref = softIter.next();
            Bitmap candidate = ref.get();
            if (candidate != null && !candidate.isRecycled() 
                && canReuseBitmap(candidate, width, height, config)) {
                softIter.remove();
                candidate.eraseColor(0);
                return candidate;
            }
            // 仅清理已 GC 的软引用，有效但不匹配的保留在池中
            if (candidate == null || candidate.isRecycled()) {
                softIter.remove();
            }
        }
        
        return null;
    }
    
    /**
     * 回收Bitmap到池中
     * @param bitmap 要回收的Bitmap
     */
    public static synchronized void recycle(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        
        // 只回收可变且较大的Bitmap
        if (!bitmap.isMutable()) {
            bitmap.recycle();
            return;
        }
        
        int size = getBitmapSize(bitmap);
        if (size < 1024 * 10) { // 小于10KB不缓存
            bitmap.recycle();
            return;
        }
        
        int byteBudget = Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2
                ? OLD_DEVICE_POOL_BYTES : NORMAL_POOL_BYTES;
        if (size > byteBudget) {
            bitmap.recycle();
            return;
        }
        // 按字节预算淘汰，而不是只按张数；Android 2.3 的 Bitmap 像素还会占 native heap。
        while (!POOL.isEmpty() && (POOL.size() >= MAX_POOL_SIZE
                || hardPoolBytes + size > byteBudget)) {
            Bitmap removed = POOL.remove(0);
            if (removed != null && !removed.isRecycled()) {
                hardPoolBytes = Math.max(0, hardPoolBytes - getBitmapSize(removed));
                removed.recycle();
            }
        }
        if (hardPoolBytes + size <= byteBudget) {
            POOL.add(bitmap);
            hardPoolBytes += size;
            return;
        }
        
        // 加入软引用池
        if (SOFT_POOL.size() < MAX_SOFT_POOL_SIZE) {
            SOFT_POOL.add(new SoftReference<Bitmap>(bitmap));
            return;
        }
        
        // 池满了，直接回收
        bitmap.recycle();
    }
    
    /**
     * 清空池
     */
    public static synchronized void clear() {
        for (Bitmap bitmap : POOL) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        POOL.clear();
        hardPoolBytes = 0;
        
        for (SoftReference<Bitmap> ref : SOFT_POOL) {
            Bitmap bitmap = ref.get();
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        SOFT_POOL.clear();
    }
    
    /**
     * 根据内存压力修剪池
     * @param level 内存修剪级别
     */
    public static synchronized void trimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            clear();
            return;
        }
        
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // 保留一半
            while (POOL.size() > MAX_POOL_SIZE / 2) {
                Bitmap removed = POOL.remove(POOL.size() - 1);
                if (removed != null && !removed.isRecycled()) {
                    hardPoolBytes = Math.max(0, hardPoolBytes - getBitmapSize(removed));
                    removed.recycle();
                }
            }
            SOFT_POOL.clear();
            return;
        }
        
        // 清理软引用池
        Iterator<SoftReference<Bitmap>> iter = SOFT_POOL.iterator();
        while (iter.hasNext()) {
            SoftReference<Bitmap> ref = iter.next();
            Bitmap bitmap = ref.get();
            if (bitmap == null || bitmap.isRecycled()) {
                iter.remove();
            }
        }
    }
    
    /**
     * 判断Bitmap是否可以复用
     */
    private static boolean canReuseBitmap(Bitmap candidate, int width, int height, Bitmap.Config config) {
        if (candidate == null || candidate.isRecycled() || !candidate.isMutable()) {
            return false;
        }
        
        // Android 4.4+ (API 19+) 支持任意复用
        if (Build.VERSION.SDK_INT >= 19) {
            return candidate.getAllocationByteCount() >= width * height * getBytesPerPixel(config);
        }
        
        // Android 2.3-4.3 需要相同尺寸和配置
        return candidate.getWidth() == width && candidate.getHeight() == height 
               && candidate.getConfig() == config;
    }
    
    /**
     * 获取每像素字节数
     */
    private static int getBytesPerPixel(Bitmap.Config config) {
        if (config == null) {
            return 4;
        }
        switch (config) {
            case ARGB_8888:
                return 4;
            case RGB_565:
            case ARGB_4444:
                return 2;
            case ALPHA_8:
                return 1;
            default:
                return 4;
        }
    }
    
    /**
     * 获取Bitmap大小（字节）
     */
    private static int getBitmapSize(Bitmap bitmap) {
        if (bitmap == null) {
            return 0;
        }
        if (Build.VERSION.SDK_INT >= 19) {
            return bitmap.getAllocationByteCount();
        }
        if (Build.VERSION.SDK_INT >= 12) {
            return bitmap.getByteCount();
        }
        return bitmap.getRowBytes() * bitmap.getHeight();
    }
}
