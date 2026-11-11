package com.im.oldchat.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 动态下载源管理器：从服务端拉取下载 BaseURL 列表（首个为对象存储直链源，其余为 API 源）。
 * 服务端可随时更换（换套餐/换域名），客户端无需更新即可获得最新线路。
 * 缓存 10 分钟；拉取失败保留旧值；首次无缓存时使用内置默认源。
 */
public final class DownloadSourcesManager {
    public static final long CACHE_TTL_MS = 10L * 60L * 1000L;

    private static volatile String[] cachedSources;
    private static volatile long cachedAtMs;
    private static volatile boolean refreshInFlight;

    private DownloadSourcesManager() {
    }

    public static String[] getSources() {
        long now = System.currentTimeMillis();
        String[] cached = cachedSources;
        if (cached != null && cached.length > 0 && now - cachedAtMs < CACHE_TTL_MS) {
            return cached;
        }
        return defaultSources();
    }

    public static String[] getSourcesAndMaybeRefresh(Context context) {
        String[] cached = getSources();
        long now = System.currentTimeMillis();
        if (cachedSources == null || now - cachedAtMs >= CACHE_TTL_MS) {
            refresh(context);
        }
        return cached;
    }

    public static String getFilesSource() {
        String[] sources = getSources();
        return sources.length > 0 ? sources[0] : com.im.oldchat.api.HttpUtil.getBaseOrigin();
    }

    public static String getRainSource() {
        String[] sources = getSources();
        return sources.length > 1 ? sources[1] : com.im.oldchat.api.HttpUtil.getBaseOrigin();
    }

    /** 异步刷新（带防抖）；结果直接写缓存，无需回调。 */
    public static synchronized void refresh(final Context context) {
        if (refreshInFlight) {
            return;
        }
        refreshInFlight = true;
        final Context app = context == null ? null : context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String[] fetched = fetch(app);
                    if (fetched != null && fetched.length > 0) {
                        cachedSources = fetched;
                        cachedAtMs = System.currentTimeMillis();
                    }
                } catch (Exception ignored) {
                } finally {
                    refreshInFlight = false;
                }
            }
        }, "download-sources-refresh").start();
    }

    private static String[] fetch(Context context) {
        if (context == null) {
            return null;
        }
        String token = DownloadUtilStorageSupport.getAuthToken(context);
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(com.im.oldchat.api.HttpUtil.BASE_URL + "/download/sources");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            java.io.InputStream input = conn.getInputStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int len;
            while ((len = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, len);
            }
            input.close();
            JSONObject obj = new JSONObject(new String(buffer.toByteArray(), "UTF-8"));
            JSONArray array = obj.optJSONArray("sources");
            if (array == null || array.length() == 0) {
                return null;
            }
            ArrayList<String> list = new ArrayList<String>();
            for (int i = 0; i < array.length(); i++) {
                String raw = array.optString(i, "");
                if (raw != null && raw.trim().length() > 0) {
                    list.add(raw.trim());
                }
            }
            if (list.size() == 0) {
                return null;
            }
            return list.toArray(new String[list.size()]);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String[] defaultSources() {
        return new String[]{com.im.oldchat.api.HttpUtil.getBaseOrigin()};
    }

    /** 供 UI 线程在应用启动时预热调用。 */
    public static void warmUp(Context context) {
        if (context == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh(context);
            }
        }, 1500L);
    }
}
