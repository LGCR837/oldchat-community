package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.im.oldchat.util.ImageCacheUtil;
import com.im.oldchat.util.ImageCompressor;
import com.im.oldchat.util.MediaUrlResolver;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class EmojiStore {
    private static final String PREF_NAME = "emoji_store";
    private static final String KEY_LIST = "emoji_list";
    private static final int EMOJI_MAX_SIZE = 256;
    private static final int EMOJI_QUALITY = 85;
    private static final long EMOJI_MAX_BYTES = 3L * 1024L * 1024L;
    private static final String IMAGE_CACHE_DIR = "img_cache";
    public static final String DEFAULT_CATEGORY = "未分类";
    private static final int MAX_CATEGORY_LENGTH = 10;

    public static class EmojiItem {
        public String id;
        public String path;
        public boolean isGif;
        public String source_url;
        public String category;
    }

    public static List<EmojiItem> load(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_LIST, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<EmojiItem> list = new Gson().fromJson(json,
                    TypeToken.getParameterized(List.class, EmojiItem.class).getType());
            if (list == null) {
                return new ArrayList<EmojiItem>();
            }
            boolean changed = false;
            for (EmojiItem item : list) {
                if (item == null) {
                    continue;
                }
                String normalized = normalizeCategoryName(item.category);
                if (item.category == null || !normalized.equals(item.category)) {
                    item.category = normalized;
                    changed = true;
                }
            }
            if (changed) {
                save(context, list);
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean isValidEmojiFile(String path) {
        if (path == null || path.length() == 0) {
            return false;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif");
    }

    public static String normalizeEmojiExt(String path, boolean isGif) {
        if (isGif) {
            return ".gif";
        }
        if (path != null) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".png")) {
                return ".png";
            }
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return ".jpg";
            }
            if (lower.endsWith(".gif")) {
                return ".gif";
            }
        }
        return ".jpg";
    }

    public static String normalizeCategoryName(String category) {
        if (category == null) {
            return DEFAULT_CATEGORY;
        }
        String out = category.trim();
        if (out.length() == 0) {
            return DEFAULT_CATEGORY;
        }
        if (out.length() > MAX_CATEGORY_LENGTH) {
            out = out.substring(0, MAX_CATEGORY_LENGTH);
        }
        return out;
    }

    public static void save(Context context, List<EmojiItem> items) {
        if (context == null) {
            return;
        }
        String json = new Gson().toJson(items != null ? items : new ArrayList<>());
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIST, json)
                .apply();
    }

    public static EmojiItem addFromUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }
        String ext = resolveExtension(context, uri);
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        File dir = new File(context.getFilesDir(), "emojis");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String fileName = "emoji_" + System.currentTimeMillis() + ext;
        File outFile = new File(dir, fileName);
        boolean isGif = ".gif".equalsIgnoreCase(ext);
        boolean ok;
        if (isGif) {
            ok = copyToFile(context, uri, outFile, EMOJI_MAX_BYTES);
        } else {
            boolean usePng = ".png".equalsIgnoreCase(ext);
            ok = ImageCompressor.compressToTargetFile(context, uri, outFile, EMOJI_MAX_SIZE,
                    EMOJI_QUALITY, usePng, ImageCompressor.DEFAULT_MAX_BYTES);
            if (!ok) {
                ok = copyToFile(context, uri, outFile, EMOJI_MAX_BYTES);
            }
        }
        if (ok && outFile.length() > EMOJI_MAX_BYTES) {
            outFile.delete();
            ok = false;
        }
        if (!ok) {
            return null;
        }
        return createAndSave(context, outFile, isGif, null);
    }

    public interface SaveCallback {
        void onResult(boolean success, String message);
    }

    public static void saveFromUrlAsync(final Context context, final String url, final boolean isGif,
                                        final SaveCallback callback) {
        if (context == null || url == null || url.isEmpty()) {
            if (callback != null) {
                callback.onResult(false, "无效的表情地址");
            }
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String message = "保存失败";
                File temp = null;
                try {
                    String resolved = resolveUrl(url);
                    String sourceKey = normalizeSourceUrl(resolved);

                    List<EmojiItem> existed = load(context);
                    EmojiItem duplicated = findBySourceUrl(existed, sourceKey);
                    if (duplicated != null) {
                        if (duplicated.path != null && duplicated.path.length() > 0) {
                            File existedFile = new File(duplicated.path);
                            if (existedFile.exists()) {
                                postResult(callback, true, "该表情已在本地缓存");
                                return;
                            }
                        }
                        removeById(existed, duplicated.id);
                        save(context, existed);
                    }

                    File dir = new File(context.getFilesDir(), "emojis");
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    String ext = guessExtension(resolved, isGif);
                    File outFile = new File(dir, "emoji_" + System.currentTimeMillis() + ext);
                    temp = new File(context.getCacheDir(), "emoji_tmp_" + System.currentTimeMillis());
                    boolean copiedFromCache = copyFromImageCache(context, resolved, temp);
                    if (!copiedFromCache && !downloadToFile(resolved, temp, EMOJI_MAX_BYTES)) {
                        if (temp.exists() && temp.length() > EMOJI_MAX_BYTES) {
                            message = "表情包不能超过3MB";
                        } else {
                            message = "下载表情失败";
                        }
                        postResult(callback, false, message);
                        return;
                    }
                    if (temp.length() > EMOJI_MAX_BYTES) {
                        message = "表情包不能超过3MB";
                        postResult(callback, false, message);
                        return;
                    }
                    if (isGif) {
                        ok = moveFile(temp, outFile);
                    } else {
                        Uri src = Uri.fromFile(temp);
                        boolean usePng = ".png".equalsIgnoreCase(ext);
                        ok = ImageCompressor.compressToTargetFile(context, src, outFile, EMOJI_MAX_SIZE,
                                EMOJI_QUALITY, usePng, ImageCompressor.DEFAULT_MAX_BYTES);
                        if (!ok) {
                            ok = moveFile(temp, outFile);
                        }
                    }
                    if (ok && outFile.length() > EMOJI_MAX_BYTES) {
                        outFile.delete();
                        ok = false;
                    }
                    if (!ok) {
                        message = "保存表情失败";
                        postResult(callback, false, message);
                        return;
                    }
                    EmojiItem item = createAndSave(context, outFile, isGif, sourceKey);
                    if (item == null) {
                        message = "保存表情失败";
                        postResult(callback, false, message);
                        return;
                    }
                    postResult(callback, true, "已保存到表情包");
                } catch (Exception e) {
                    postResult(callback, false, "保存表情失败");
                } finally {
                    if (temp != null && temp.exists()) {
                        temp.delete();
                    }
                }
            }
        }).start();
    }

    public static boolean deleteEmoji(Context context, EmojiItem item) {
        if (context == null || item == null) {
            return false;
        }
        List<EmojiItem> list = load(context);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            EmojiItem candidate = list.get(i);
            if (candidate == null) {
                continue;
            }
            if (item.id != null && item.id.equals(candidate.id)) {
                list.remove(i);
                removed = true;
                break;
            }
            if (item.path != null && item.path.equals(candidate.path)) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            save(context, list);
        }
        if (item.path != null) {
            File file = new File(item.path);
            if (file.exists()) {
                file.delete();
            }
        }
        return removed;
    }

    private static String resolveExtension(Context context, Uri uri) {
        String mime = context.getContentResolver().getType(uri);
        String ext = null;
        if (mime != null) {
            ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        }
        if (ext == null || ext.isEmpty()) {
            String path = uri.getPath();
            if (path != null) {
                int idx = path.lastIndexOf('.');
                if (idx >= 0 && idx < path.length() - 1) {
                    ext = path.substring(idx + 1);
                }
            }
        }
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        ext = ext.toLowerCase();
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return ".jpg";
        }
        if ("png".equals(ext)) {
            return ".png";
        }
        if ("gif".equals(ext)) {
            return ".gif";
        }
        return null;
    }

    private static EmojiItem createAndSave(Context context, File file, boolean isGif, String sourceUrl) {
        if (context == null || file == null || !file.exists()) {
            return null;
        }
        EmojiItem item = new EmojiItem();
        item.id = String.valueOf(System.currentTimeMillis());
        item.path = file.getAbsolutePath();
        item.isGif = isGif;
        item.source_url = sourceUrl == null ? "" : sourceUrl;
        item.category = DEFAULT_CATEGORY;
        List<EmojiItem> list = load(context);
        list.add(item);
        save(context, list);
        return item;
    }

    private static EmojiItem findBySourceUrl(List<EmojiItem> list, String sourceUrl) {
        if (list == null || list.isEmpty() || sourceUrl == null || sourceUrl.length() == 0) {
            return null;
        }
        for (EmojiItem item : list) {
            if (item == null || item.source_url == null) {
                continue;
            }
            if (sourceUrl.equals(normalizeSourceUrl(item.source_url))) {
                return item;
            }
        }
        return null;
    }

    private static void removeById(List<EmojiItem> list, String id) {
        if (list == null || list.isEmpty() || id == null || id.length() == 0) {
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            EmojiItem item = list.get(i);
            if (item != null && id.equals(item.id)) {
                list.remove(i);
                return;
            }
        }
    }

    private static String normalizeSourceUrl(String url) {
        if (url == null) {
            return "";
        }
        String out = url.trim();
        if (out.length() == 0) {
            return "";
        }
        int queryIdx = out.indexOf('?');
        if (queryIdx >= 0) {
            out = out.substring(0, queryIdx);
        }
        int hashIdx = out.indexOf('#');
        if (hashIdx >= 0) {
            out = out.substring(0, hashIdx);
        }
        return out;
    }

    private static boolean copyFromImageCache(Context context, String url, File outFile) {
        if (context == null || url == null || url.length() == 0 || outFile == null) {
            return false;
        }
        File cacheFile = ImageCacheUtil.getCacheFile(context.getApplicationContext(), IMAGE_CACHE_DIR, url);
        if (cacheFile == null || !cacheFile.exists()) {
            return false;
        }
        return copyFile(cacheFile, outFile);
    }

    private static boolean copyToFile(Context context, Uri uri, File outFile, long maxBytes) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                return false;
            }
            os = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return false;
                }
                os.write(buffer, 0, len);
            }
            os.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (os != null) try { os.close(); } catch (Exception e) {}
        }
    }

    private static String resolveUrl(String url) {
        return MediaUrlResolver.resolve(url);
    }

    private static String guessExtension(String url, boolean isGif) {
        if (isGif) {
            return ".gif";
        }
        if (url != null) {
            int idx = url.lastIndexOf('.');
            if (idx >= 0 && idx < url.length() - 1) {
                String ext = url.substring(idx + 1).toLowerCase();
                if ("png".equals(ext)) {
                    return ".png";
                }
                if ("jpg".equals(ext) || "jpeg".equals(ext)) {
                    return ".jpg";
                }
            }
        }
        return ".jpg";
    }

    private static boolean downloadToFile(String url, File outFile, long maxBytes) {
        String[] candidates = MediaUrlResolver.resolveCandidates(url);
        if (candidates == null || candidates.length == 0) {
            return downloadToFileOne(url, outFile, maxBytes);
        }
        for (int i = 0; i < candidates.length; i++) {
            String one = candidates[i];
            if (one == null || one.length() == 0) {
                continue;
            }
            if (downloadToFileOne(one, outFile, maxBytes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean downloadToFileOne(String url, File outFile, long maxBytes) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.connect();
            if (conn.getResponseCode() != 200) {
                return false;
            }
            int contentLen = conn.getContentLength();
            if (maxBytes > 0 && contentLen > 0 && contentLen > maxBytes) {
                return false;
            }
            is = conn.getInputStream();
            os = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return false;
                }
                os.write(buffer, 0, len);
            }
            os.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (os != null) try { os.close(); } catch (Exception e) {}
        }
    }

    private static boolean moveFile(File from, File to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.renameTo(to)) {
            return true;
        }
        boolean ok = copyFile(from, to);
        if (ok) {
            from.delete();
        }
        return ok;
    }

    private static boolean copyFile(File from, File to) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            is = new java.io.FileInputStream(from);
            os = new FileOutputStream(to);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (os != null) try { os.close(); } catch (Exception e) {}
        }
    }

    private static void postResult(final SaveCallback callback, final boolean ok, final String message) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(ok, message);
            }
        });
    }
}
