package com.im.oldchat.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 磁盘缓存存在性提示 - 轻量级同步实现
 */
final class ImageDiskCacheHint {
    private static final int MAX_ENTRIES = 1024;
    private static final Map<String, Boolean> PRESENT_CACHE = new LinkedHashMap<String, Boolean>(MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ImageDiskCacheHint() {
    }

    /**
     * 检查缓存键是否已知存在
     */
    static boolean isKnownPresent(String cacheKey) {
        if (cacheKey == null || cacheKey.length() == 0) {
            return false;
        }
        synchronized (PRESENT_CACHE) {
            return PRESENT_CACHE.containsKey(cacheKey);
        }
    }

    /**
     * 标记缓存键存在
     */
    static void markPresent(String cacheKey) {
        if (cacheKey == null || cacheKey.length() == 0) {
            return;
        }
        synchronized (PRESENT_CACHE) {
            PRESENT_CACHE.put(cacheKey, Boolean.TRUE);
        }
    }

    /**
     * 清理所有缓存
     */
    static void clear() {
        synchronized (PRESENT_CACHE) {
            PRESENT_CACHE.clear();
        }
    }
}
