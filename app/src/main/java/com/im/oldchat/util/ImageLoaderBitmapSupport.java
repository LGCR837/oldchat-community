package com.im.oldchat.util;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.support.v4.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

class ImageLoaderBitmapSupport {
    private static final int DEFAULT_CACHE_KB = 24 * 1024;
    private static final int MEDIUM_CACHE_KB = 16 * 1024;
    private static final int LOW_MEMORY_CACHE_KB = 8 * 1024;
    private static final int MIN_CACHE_KB = 1024;

    private static final int MAX_NETWORK_BYTES_AVATAR = 600 * 1024;
    private static final int MAX_NETWORK_BYTES_NORMAL = 2 * 1024 * 1024;
    private static final int MAX_NETWORK_BYTES_LARGE = 4 * 1024 * 1024;
    private static final int MAX_NETWORK_BYTES_HUGE = 6 * 1024 * 1024;

    private static final long DISK_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long DISK_CACHE_LIMIT_LOW_BYTES = 48L * 1024L * 1024L;
    private static final long DISK_CACHE_LIMIT_NORMAL_BYTES = 96L * 1024L * 1024L;
    private static final long DISK_CACHE_LIMIT_HIGH_BYTES = 128L * 1024L * 1024L;
    private static final long CACHE_CLEANUP_INTERVAL_MS = 2L * 60L * 60L * 1000L;
    private static final long TMP_FILE_TTL_MS = 20L * 60L * 1000L;

    private static final String IMAGE_ACCEPT_HEADER = "image/*,*/*;q=0.8";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 12000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 20000;
    private static final int DATA_SERVER_FALLBACK_TIMEOUT_MS = 4000;
    private static final Object CACHE_LOCK = new Object();
    private static final Object FILE_LOCK_GUARD = new Object();
    private static final HashMap<String, Object> FILE_LOCKS = new HashMap<String, Object>();
    private static final HashMap<String, Integer> FILE_LOCK_COUNTS = new HashMap<String, Integer>();
    private static final Object BITMAP_IN_FLIGHT_GUARD = new Object();
    private static final HashMap<String, BitmapInFlight> BITMAP_IN_FLIGHT = new HashMap<String, BitmapInFlight>();
    private static volatile long lastCleanupAt;
    private static LruCache<String, Bitmap> CACHE;

    private static class BitmapInFlight {
        boolean running;
        int waiters;
        Bitmap result;
    }

    private ImageLoaderBitmapSupport() {
    }

    static Bitmap downloadBitmap(Context context, String cacheDir, String url, int maxSize, int maxBytes) {
        if (url == null || url.length() == 0) {
            return null;
        }
        String inFlightKey = buildInFlightKey(cacheDir, url, maxSize, maxBytes);
        boolean owner = false;

        while (!owner) {
            synchronized (BITMAP_IN_FLIGHT_GUARD) {
                BitmapInFlight inFlight = BITMAP_IN_FLIGHT.get(inFlightKey);
                if (inFlight == null) {
                    inFlight = new BitmapInFlight();
                    inFlight.running = true;
                    BITMAP_IN_FLIGHT.put(inFlightKey, inFlight);
                    owner = true;
                    break;
                }
                if (!inFlight.running) {
                    Bitmap shared = inFlight.result;
                    BITMAP_IN_FLIGHT.remove(inFlightKey);
                    if (shared != null && !shared.isRecycled()) {
                        return shared;
                    }
                    inFlight.running = true;
                    inFlight.result = null;
                    BITMAP_IN_FLIGHT.put(inFlightKey, inFlight);
                    owner = true;
                    break;
                }
                inFlight.waiters++;
                try {
                    BITMAP_IN_FLIGHT_GUARD.wait(12000L);
                } catch (InterruptedException e) {
                    inFlight.waiters--;
                    if (inFlight.waiters <= 0 && !inFlight.running) {
                        BITMAP_IN_FLIGHT.remove(inFlightKey);
                    }
                    Thread.currentThread().interrupt();
                    return null;
                }
                inFlight.waiters--;
                if (!inFlight.running) {
                    Bitmap shared = inFlight.result;
                    if (inFlight.waiters <= 0) {
                        BITMAP_IN_FLIGHT.remove(inFlightKey);
                    }
                    if (shared != null && !shared.isRecycled()) {
                        return shared;
                    }
                }
            }
        }

        Bitmap result = null;
        try {
            result = downloadBitmapInternal(context, cacheDir, url, maxSize, maxBytes);
            return result;
        } finally {
            synchronized (BITMAP_IN_FLIGHT_GUARD) {
                BitmapInFlight inFlight = BITMAP_IN_FLIGHT.get(inFlightKey);
                if (inFlight != null) {
                    inFlight.result = result;
                    inFlight.running = false;
                    if (inFlight.waiters <= 0) {
                        BITMAP_IN_FLIGHT.remove(inFlightKey);
                    }
                }
                BITMAP_IN_FLIGHT_GUARD.notifyAll();
            }
        }
    }

    static Bitmap loadCachedBitmap(Context context, String cacheDir, String url, int maxSize, int maxBytes) {
        if (context == null || cacheDir == null || cacheDir.length() == 0 || url == null || url.length() == 0) {
            return null;
        }
        File cached = ImageCacheUtil.getCacheFile(context, cacheDir, url);
        if (cached == null || !cached.exists()) {
            return null;
        }
        if (isExpired(cached)) {
            cached.delete();
            return null;
        }
        long startedAt = SystemClock.uptimeMillis();
        Bitmap bitmap = decodeSampledBitmap(context, url, cached.getAbsolutePath(), maxSize, maxBytes);
        ImageLoadPerfLogger.logLoad(context, "cache_decode", url, "disk",
                startedAt, cached.length(), maxSize, maxBytes, bitmap != null);
        if (bitmap != null) {
            touch(cached);
            return bitmap;
        }
        cached.delete();
        return null;
    }

    private static String buildInFlightKey(String cacheDir, String url, int maxSize, int maxBytes) {
        String dir = cacheDir == null ? "" : cacheDir;
        return dir + "#" + ImageCacheUtil.buildCacheKey(url) + "#" + maxSize + "#" + maxBytes;
    }

    private static Bitmap downloadBitmapInternal(Context context, String cacheDir, String url, int maxSize, int maxBytes) {
        maybeTrimDiskCache(context, cacheDir);

        File cached = ImageCacheUtil.getCacheFile(context, cacheDir, url);
        String lockKey = cached != null ? cached.getAbsolutePath() : url;
        Object fileLock = acquireFileLock(lockKey);

        synchronized (fileLock) {
            long loadStartedAt = SystemClock.uptimeMillis();
            try {
                if (cached != null && cached.exists()) {
                    if (isExpired(cached)) {
                        cached.delete();
                    } else {
                        long cacheStartedAt = SystemClock.uptimeMillis();
                        Bitmap bitmap = decodeSampledBitmap(context, url, cached.getAbsolutePath(), maxSize, maxBytes);
                        ImageLoadPerfLogger.logLoad(context, "cache_decode", url, "disk",
                                cacheStartedAt, cached.length(), maxSize, maxBytes, bitmap != null);
                        if (bitmap != null) {
                            touch(cached);
                            return bitmap;
                        }
                        cached.delete();
                    }
                }

                HttpURLConnection conn = null;
                try {
                    long networkStartedAt = SystemClock.uptimeMillis();
                    boolean useFastFailoverTimeout = shouldUseFastFailoverTimeout(url);
                    int connectTimeoutMs = useFastFailoverTimeout
                            ? DATA_SERVER_FALLBACK_TIMEOUT_MS : DEFAULT_CONNECT_TIMEOUT_MS;
                    int readTimeoutMs = useFastFailoverTimeout
                            ? DATA_SERVER_FALLBACK_TIMEOUT_MS : DEFAULT_READ_TIMEOUT_MS;
                    long requestDeadlineAt = useFastFailoverTimeout
                            ? (System.currentTimeMillis() + DATA_SERVER_FALLBACK_TIMEOUT_MS) : 0L;

                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(connectTimeoutMs);
                    conn.setReadTimeout(readTimeoutMs);
                    conn.setUseCaches(true);
                    conn.setInstanceFollowRedirects(true);
                    applyImageRequestHeaders(conn, url);
                    conn.connect();
                    int responseCode = conn.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                "http_" + responseCode, networkStartedAt, 0L,
                                maxSize, maxBytes, false);
                        return null;
                    }

                    int maxDownloadBytes = resolveMaxDownloadBytes(maxSize);
                    final int expectedLength = conn.getContentLength();
                    if (expectedLength > 0 && expectedLength > maxDownloadBytes) {
                        ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                "too_large", networkStartedAt, expectedLength,
                                maxSize, maxBytes, false);
                        return null;
                    }
                    if (requestDeadlineAt > 0 && System.currentTimeMillis() > requestDeadlineAt) {
                        ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                "deadline", networkStartedAt, 0L,
                                maxSize, maxBytes, false);
                        return null;
                    }

                    File file = cached != null ? cached : ImageCacheUtil.getCacheFile(context, cacheDir, url);
                    if (file == null) {
                        InputStream is = null;
                        try {
                            is = conn.getInputStream();
                            byte[] data = readToBytes(is, maxDownloadBytes, requestDeadlineAt);
                            if (data == null || data.length == 0) {
                                ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                        "memory_empty", networkStartedAt, 0L,
                                        maxSize, maxBytes, false);
                                return null;
                            }
                            Bitmap bitmap = decodeSampledBitmap(context, url, data, maxSize, maxBytes);
                            ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                    "memory", networkStartedAt, data.length,
                                    maxSize, maxBytes, bitmap != null);
                            return bitmap;
                        } finally {
                            if (is != null) {
                                try {
                                    is.close();
                                } catch (Exception e) {
                                }
                            }
                        }
                    }

                    File tmp = new File(file.getAbsolutePath() + ".tmp");
                    if (tmp.exists()) {
                        tmp.delete();
                    }

                    InputStream is = null;
                    FileOutputStream out = null;
                    long totalRead = 0;
                    boolean overLimit = false;
                    boolean timeoutReached = false;
                    try {
                        is = conn.getInputStream();
                        out = new FileOutputStream(tmp);
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            if (requestDeadlineAt > 0 && System.currentTimeMillis() > requestDeadlineAt) {
                                timeoutReached = true;
                                break;
                            }
                            totalRead += read;
                            if (totalRead > maxDownloadBytes) {
                                overLimit = true;
                                break;
                            }
                            out.write(buffer, 0, read);
                        }
                        if (overLimit || timeoutReached) {
                            ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                    overLimit ? "too_large" : "deadline", networkStartedAt,
                                    totalRead, maxSize, maxBytes, false);
                            tmp.delete();
                            return null;
                        }
                        out.flush();
                        try {
                            out.getFD().sync();
                        } catch (Throwable ignored) {
                        }
                    } finally {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (Exception e) {
                            }
                        }
                        if (out != null) {
                            try {
                                out.close();
                            } catch (Exception e) {
                            }
                        }
                    }

                    if (!tmp.exists() || totalRead <= 0) {
                        ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                "disk_empty", networkStartedAt, totalRead,
                                maxSize, maxBytes, false);
                        tmp.delete();
                        return null;
                    }
                    if (expectedLength > 0 && totalRead < expectedLength) {
                        ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                                "partial", networkStartedAt, totalRead,
                                maxSize, maxBytes, false);
                        tmp.delete();
                        return null;
                    }

                    Bitmap bitmap = decodeSampledBitmap(context, url, tmp.getAbsolutePath(), maxSize, maxBytes);
                    ImageLoadPerfLogger.logLoad(context, "network_bitmap", url,
                            "disk", networkStartedAt, totalRead,
                            maxSize, maxBytes, bitmap != null);
                    if (bitmap == null) {
                        tmp.delete();
                        if (file.exists()) {
                            file.delete();
                        }
                        return null;
                    }

                    if (file.exists()) {
                        file.delete();
                    }
                    if (tmp.renameTo(file)) {
                        touch(file);
                    } else {
                        tmp.delete();
                    }
                    maybeTrimDiskCache(context, cacheDir);
                    return bitmap;
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            } catch (Throwable e) {
                ImageLoadPerfLogger.logLoad(context, "bitmap_load", url, "exception",
                        loadStartedAt, 0L, maxSize, maxBytes, false);
                ImageLoadPerfLogger.logFailure(context, "download_bitmap_internal", url, e);
                return null;
            } finally {
                releaseFileLock(lockKey);
            }
        }
    }

    private static void applyImageRequestHeaders(HttpURLConnection conn, String url) {
        conn.setRequestProperty("Accept", IMAGE_ACCEPT_HEADER);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        if (!isBiliImageUrl(url)) {
            return;
        }
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.0.4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36");
        conn.setRequestProperty("Referer", "https://www.bilibili.com/");
        conn.setRequestProperty("Origin", "https://www.bilibili.com");
    }

    private static boolean isBiliImageUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.indexOf("hdslb.com") >= 0 || lower.indexOf("biliimg.com") >= 0 || lower.indexOf("bilibili.com") >= 0;
    }

    static Bitmap decodeSampledBitmap(Context context, String url, String path, int maxSize, int maxBytes) {
        return ImageLoaderDecodeSupport.decodeFile(context, url, path, maxSize, maxBytes);
    }

    static Bitmap decodeSampledBitmap(Context context, String url, byte[] data, int maxSize, int maxBytes) {
        return ImageLoaderDecodeSupport.decodeBytes(context, url, data, maxSize, maxBytes);
    }

    static Bitmap decodeSampledBitmap(String path, int maxSize, int maxBytes) {
        return decodeSampledBitmap(null, null, path, maxSize, maxBytes);
    }

    static Bitmap decodeSampledBitmap(byte[] data, int maxSize, int maxBytes) {
        return decodeSampledBitmap(null, null, data, maxSize, maxBytes);
    }

    static LruCache<String, Bitmap> getCache(Context context) {
        if (CACHE != null) {
            return CACHE;
        }
        synchronized (CACHE_LOCK) {
            if (CACHE == null) {
                int cacheKb = resolveCacheSizeKb(context);
                CACHE = new LruCache<String, Bitmap>(cacheKb) {
                    @Override
                    protected int sizeOf(String key, Bitmap value) {
                        return getBitmapSizeKb(value);
                    }
                    @Override
                    protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                        // LruCache 淘汰不代表 ImageView 已经释放该 Bitmap。
                        // 若此时放入 inBitmap 池，下一张图片解码会原地覆盖像素，
                        // 所有仍引用该对象的相邻 ImageView 都会显示成后一张图片。
                        // 没有完整引用计数前，交给 GC 回收最安全。
                    }
                };
            }
        }
        return CACHE;
    }

    static void clearMemoryCache() {
        synchronized (CACHE_LOCK) {
            if (CACHE != null) {
                CACHE.evictAll();
            }
        }
    }

    static void trimMemory(int level) {
        LruCache<String, Bitmap> cache = CACHE;
        if (cache == null) {
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            cache.evictAll();
            return;
        }
        int maxSize = cache.maxSize();
        if (maxSize <= 0) {
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            cache.trimToSize(Math.max(1, maxSize / 8));
            return;
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            cache.trimToSize(Math.max(1, maxSize / 4));
            return;
        }
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            cache.trimToSize(Math.max(1, maxSize / 2));
        }
    }

    static int resolveMaxBitmapSize(Context context) {
        int memClass = getMemoryClassMb(context);
        if (android.os.Build.VERSION.SDK_INT <= 10) {
            if (memClass > 0 && memClass <= 32) {
                return 240;
            }
            return 360;
        }
        if (memClass > 0 && memClass <= 48) {
            return 320;
        }
        if (memClass > 0 && memClass <= 64) {
            return 400;
        }
        if (memClass > 0 && memClass <= 128) {
            return 640;
        }
        return 1024;
    }

    static int resolveAvatarMaxSize(Context context) {
        int memClass = getMemoryClassMb(context);
        // 头像显示尺寸通常只有 48-64dp，解码过大浪费内存和 GPU 上传时间
        if (memClass > 0 && memClass <= 48) {
            return 48;
        }
        if (memClass > 0 && memClass <= 64) {
            return 64;
        }
        if (memClass > 0 && memClass <= 128) {
            return 80;
        }
        return 96;
    }

    private static int getBitmapSizeKb(Bitmap value) {
        if (value == null) {
            return 0;
        }
        int bytes;
        if (android.os.Build.VERSION.SDK_INT >= 12) {
            bytes = value.getByteCount();
        } else {
            bytes = value.getRowBytes() * value.getHeight();
        }
        return Math.max(1, bytes / 1024);
    }

    private static int resolveCacheSizeKb(Context context) {
        int memClass = getMemoryClassMb(context);
        if (memClass <= 0) {
            return DEFAULT_CACHE_KB;
        }
        int sizeKb = memClass * 1024 / 6;
        int cap;
        if (memClass <= 64) cap = LOW_MEMORY_CACHE_KB;
        else if (memClass <= 128) cap = MEDIUM_CACHE_KB;
        else cap = DEFAULT_CACHE_KB;
        if (sizeKb > cap) {
            sizeKb = cap;
        }
        if (sizeKb < MIN_CACHE_KB) {
            sizeKb = MIN_CACHE_KB;
        }
        return sizeKb;
    }

    private static int getMemoryClassMb(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                return am.getMemoryClass();
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    private static int resolveMaxDownloadBytes(int maxSize) {
        if (maxSize <= 160) {
            return MAX_NETWORK_BYTES_AVATAR;
        }
        if (maxSize <= 640) {
            return MAX_NETWORK_BYTES_NORMAL;
        }
        if (maxSize <= 1200) {
            return MAX_NETWORK_BYTES_LARGE;
        }
        return MAX_NETWORK_BYTES_HUGE;
    }

    private static byte[] readToBytes(InputStream is, int maxBytes, long deadlineAt) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = is.read(buffer)) != -1) {
            if (deadlineAt > 0 && System.currentTimeMillis() > deadlineAt) {
                return null;
            }
            total += read;
            if (total > maxBytes) {
                return null;
            }
            bos.write(buffer, 0, read);
        }
        return bos.toByteArray();
    }

    private static boolean shouldUseFastFailoverTimeout(String url) {
        if (url == null || url.length() == 0) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.US);
        // 上传资源有 files → 60 → oc 三条线路。前两条只给 4 秒预算，避免首线路
        // 握手或旧服务器卡住时让图片空白十几秒；最后的 oc 主站仍使用正常超时。
        if (lower.startsWith("https://ocf.oss-cn-shanghai.aliyuncs.com/")
                || lower.startsWith("http://ocf.oss-cn-shanghai.aliyuncs.com/")) {
            return true;
        }
        if (isUploadPath(lower)) {
            return lower.startsWith("http://60.205.94.101:8080/")
                    || lower.startsWith("https://60.205.94.101:8080/");
        }
        if (!MediaUrlResolver.isMainOriginUrl(url)) {
            return false;
        }
        String fallback = MediaUrlResolver.resolveFallback(url);
        return fallback != null && fallback.length() > 0 && !fallback.equals(url)
                && MediaUrlResolver.isDataOriginUrl(fallback);
    }

    private static boolean isUploadPath(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.indexOf("/uploads/") >= 0 || lower.indexOf("/v1/uploads/") >= 0;
    }

    private static void maybeTrimDiskCache(Context context, String cacheDirName) {
        if (context == null || cacheDirName == null || cacheDirName.length() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCleanupAt < CACHE_CLEANUP_INTERVAL_MS) {
            return;
        }
        synchronized (CACHE_LOCK) {
            if (now - lastCleanupAt < CACHE_CLEANUP_INTERVAL_MS) {
                return;
            }
            lastCleanupAt = now;
        }

        File base = new File(context.getCacheDir(), cacheDirName);
        File[] files = base.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        long totalBytes = 0L;
        int validCount = 0;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name != null && name.endsWith(".tmp") && now - file.lastModified() > TMP_FILE_TTL_MS) {
                file.delete();
                continue;
            }
            if (isExpired(file)) {
                file.delete();
                continue;
            }
            totalBytes += Math.max(0L, file.length());
            validCount++;
        }
        if (validCount <= 0) {
            return;
        }

        long maxBytes = resolveDiskCacheLimitBytes(context);
        if (totalBytes <= maxBytes) {
            return;
        }

        FileSortCompat.sortByLastModifiedAsc(files);

        for (int i = 0; i < files.length && totalBytes > maxBytes; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            long len = Math.max(0L, file.length());
            if (file.delete()) {
                totalBytes -= len;
            }
        }
    }

    private static long resolveDiskCacheLimitBytes(Context context) {
        int memClass = getMemoryClassMb(context);
        if (memClass <= 0) {
            return DISK_CACHE_LIMIT_NORMAL_BYTES;
        }
        if (memClass <= 64) {
            return DISK_CACHE_LIMIT_LOW_BYTES;
        }
        if (memClass <= 192) {
            return DISK_CACHE_LIMIT_NORMAL_BYTES;
        }
        return DISK_CACHE_LIMIT_HIGH_BYTES;
    }

    private static boolean isExpired(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        long lastModified = file.lastModified();
        if (lastModified <= 0) {
            return false;
        }
        return System.currentTimeMillis() - lastModified > DISK_CACHE_TTL_MS;
    }

    private static void touch(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.setLastModified(System.currentTimeMillis());
        } catch (Exception e) {
        }
    }

    private static Object acquireFileLock(String key) {
        synchronized (FILE_LOCK_GUARD) {
            Object lock = FILE_LOCKS.get(key);
            Integer count = FILE_LOCK_COUNTS.get(key);
            if (lock == null) {
                lock = new Object();
                FILE_LOCKS.put(key, lock);
                FILE_LOCK_COUNTS.put(key, 1);
                return lock;
            }
            if (count == null) {
                count = 0;
            }
            FILE_LOCK_COUNTS.put(key, count + 1);
            return lock;
        }
    }

    private static void releaseFileLock(String key) {
        synchronized (FILE_LOCK_GUARD) {
            Integer count = FILE_LOCK_COUNTS.get(key);
            if (count == null || count <= 1) {
                FILE_LOCK_COUNTS.remove(key);
                FILE_LOCKS.remove(key);
                return;
            }
            FILE_LOCK_COUNTS.put(key, count - 1);
        }
    }
}
