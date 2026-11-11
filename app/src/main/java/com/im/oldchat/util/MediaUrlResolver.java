package com.im.oldchat.util;

import com.im.oldchat.api.HttpUtil;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

public class MediaUrlResolver {
    private static final String DATA_ORIGIN = "http://154.8.227.219:9090";
    private static final String OSS_ORIGIN = "https://ocf.oss-cn-shanghai.aliyuncs.com";
    private static final String MAIN_ORIGIN_60 = "http://60.205.94.101:8080";
    private static final String CURRENT_MAIN_ORIGIN = "http://oc.mcl0.dpdns.org";
    private static final long LINE_FAILURE_COOLDOWN_MS = 60L * 1000L;
    private static final HashMap<String, Long> FAILED_LINE_UNTIL = new HashMap<String, Long>();

    private MediaUrlResolver() {
    }

    public static String resolve(String url) {
        String[] candidates = resolveCandidates(url);
        return candidates.length > 0 ? candidates[0] : "";
    }

    public static String resolveFallback(String url) {
        String[] candidates = resolveCandidates(url);
        return candidates.length > 1 ? candidates[1] : "";
    }

    /**
     * 返回同一上传资源跨 OSS、IP 和域名线路共享的缓存标识。
     * 第三方地址保留完整 URL，避免不同服务器的同名路径发生冲突。
     */
    public static String cacheIdentity(String url) {
        if (url == null) {
            return "";
        }
        String raw = url.trim();
        if (raw.length() == 0) {
            return "";
        }
        if (!(raw.startsWith("http://") || raw.startsWith("https://"))) {
            return isUploadPath(raw) ? toAppUploadPath(raw) : raw;
        }
        try {
            URL parsed = new URL(raw);
            String path = parsed.getPath();
            if (path == null || path.length() == 0) {
                return raw;
            }
            if (hasOrigin(raw, OSS_ORIGIN)) {
                return toAppUploadPath("/oss" + path);
            }
            if (isKnownUploadOrigin(parsed) && isUploadPath(path)) {
                return toAppUploadPath(path);
            }
        } catch (Exception e) {
            return raw;
        }
        return raw;
    }

    public static String resolveNextCandidate(String currentUrl) {
        if (currentUrl == null) {
            return "";
        }
        String current = currentUrl.trim();
        if (current.length() == 0) {
            return "";
        }
        // 回退过程保持原始线路顺序，避免所有线路均冷却时在两个地址间循环。
        String[] candidates = resolveCandidatesRaw(current);
        for (int i = 0; i < candidates.length; i++) {
            if (current.equals(candidates[i])) {
                return i + 1 < candidates.length ? candidates[i + 1] : "";
            }
        }
        return candidates.length > 1 ? candidates[1] : "";
    }

    public static boolean isDataOriginUrl(String url) {
        return hasOrigin(url, DATA_ORIGIN);
    }

    public static boolean isMainOriginUrl(String url) {
        String mainOrigin = resolveMainOrigin();
        return mainOrigin.length() > 0 && hasOrigin(url, mainOrigin);
    }

    public static String[] resolveCandidates(String url) {
        return prioritizeHealthy(resolveCandidatesRaw(url));
    }

    private static String[] resolveCandidatesRaw(String url) {
        if (url == null) {
            return new String[0];
        }
        String raw = url.trim();
        if (raw.length() == 0) {
            return new String[0];
        }
        String mainOrigin = resolveMainOrigin();
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return resolveAbsoluteCandidates(raw);
        }
        if (raw.startsWith("/")) {
            if (isUploadPath(raw)) {
                return buildUploadCandidates(raw);
            }
            return dedupe(new String[]{mainOrigin + raw});
        }
        if (isUploadPath(raw)) {
            return buildUploadCandidates(raw);
        }
        return new String[]{raw};
    }

    /** 记录播放线路短期故障，后续歌曲优先绕过该线路。 */
    public static synchronized void reportLineFailure(String url) {
        String key = lineKey(url);
        if (key.length() > 0) {
            FAILED_LINE_UNTIL.put(key, Long.valueOf(System.currentTimeMillis() + LINE_FAILURE_COOLDOWN_MS));
        }
    }

    /** 成功播放后恢复该线路优先级。 */
    public static synchronized void reportLineSuccess(String url) {
        String key = lineKey(url);
        if (key.length() > 0) {
            FAILED_LINE_UNTIL.remove(key);
        }
    }

    private static String[] prioritizeHealthy(String[] candidates) {
        if (candidates == null || candidates.length < 2) {
            return candidates == null ? new String[0] : candidates;
        }
        ArrayList<String> healthy = new ArrayList<String>();
        ArrayList<String> cooling = new ArrayList<String>();
        long now = System.currentTimeMillis();
        synchronized (MediaUrlResolver.class) {
            for (int i = 0; i < candidates.length; i++) {
                String candidate = candidates[i];
                String key = lineKey(candidate);
                Long until = FAILED_LINE_UNTIL.get(key);
                if (until != null && until.longValue() > now) {
                    cooling.add(candidate);
                } else {
                    if (until != null) {
                        FAILED_LINE_UNTIL.remove(key);
                    }
                    healthy.add(candidate);
                }
            }
        }
        healthy.addAll(cooling);
        return healthy.toArray(new String[healthy.size()]);
    }

    private static String lineKey(String url) {
        if (url == null) {
            return "";
        }
        try {
            URL parsed = new URL(url.trim());
            return buildOrigin(parsed.getProtocol(), parsed.getHost(), parsed.getPort());
        } catch (Exception e) {
            return "";
        }
    }

    private static String[] resolveAbsoluteCandidates(String raw) {
        URL parsed;
        try {
            parsed = new URL(raw);
        } catch (Exception e) {
            return new String[]{raw};
        }
        String pathAndQuery = parsed.getFile();
        if (pathAndQuery == null || pathAndQuery.length() == 0) {
            pathAndQuery = "/";
        }
        // files 自定义域名使用阿里云 OSS 对象路径，顶层对象可能没有 media/ 等分类；
        // 即使只剩 /filename，也必须还能恢复 60 和 oc 两条回退线路。
        if (hasOrigin(raw, OSS_ORIGIN)) {
            return buildUploadCandidatesFromOssPath(pathAndQuery);
        }
        if (!isUploadPath(pathAndQuery)) {
            // 业务下载 URL（如 /v2/files/download/{id}）带 ?ref= 时，可生成对象直链候选。
            String ref = extractRefFromUrl(raw);
            if (ref.length() > 0) {
                ArrayList<String> dynamic = new ArrayList<String>();
                String rel = ref;
                while (rel.startsWith("/")) {
                    rel = rel.substring(1);
                }
                // 只有资源广场（filewearhouse）在雨云桶中有对象。但雨云桶当前为私有桶，
                // 直链会 403；优先 files 域名（Cloudflare）API 下载，避免白等一次 403。
                boolean isResourceHall = rel.startsWith("filewearhouse/");
                if (isResourceHall) {
                    // 保留原始 files API URL（走 CF），不再生成雨云私有桶直链候选。
                    return new String[]{raw};
                }
                dynamic.add(raw);
                return dynamic.toArray(new String[dynamic.size()]);
            }
            return new String[]{raw};
        }
        // 只改写 Oldchat 已知源站和阿里云 OSS。第三方或用户自定义服务器即使路径也叫
        // /uploads，也必须保留原始绝对 URL，不能被静默替换到官方服务器。
        return isKnownUploadOrigin(parsed) ? buildUploadCandidates(pathAndQuery) : new String[]{raw};
    }

    private static String[] buildUploadCandidates(String path) {
        String ossPath = extractUploadPath(path);
        String appPath = toAppUploadPath(path);
        return dedupe(concat(
                dynamicCandidates(ossPath, appPath),
                new String[]{
                    OSS_ORIGIN + ossPath,
                    MAIN_ORIGIN_60 + appPath,
                    CURRENT_MAIN_ORIGIN + appPath,
                    resolveMainOrigin() + appPath
                }));
    }

    /** 动态下载源候选：资源广场（filewearhouse）优先 files 域名（CF）API；雨云桶为私有桶，不生成直链。 */
    private static String[] dynamicCandidates(String ossPath, String appPath) {
        ArrayList<String> out = new ArrayList<String>();
        String rel = ossPath == null ? "" : ossPath.trim();
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        // 只有资源广场文件在雨云桶中有对象；头像/媒体/封面等未同步，不能插雨云直链。
        boolean isResourceHall = rel.startsWith("filewearhouse/")
                || (appPath != null && appPath.contains("/uploads/filewearhouse/"));
        if (!isResourceHall) {
            return out.toArray(new String[out.size()]);
        }
        // 雨云桶私有：不生成雨云直链候选（403 无意义），保留 files API（走 CF）原线路。
        try {
            String[] sources = DownloadSourcesManager.getSourcesAndMaybeRefresh(null);
            if (sources != null && sources.length > 0) {
                // 其余为 API 源：files 域名 + 原业务路径
                for (int i = 0; i < sources.length; i++) {
                    String api = sources[i] + appPath;
                    if (api.length() > 0 && !out.contains(api)) {
                        out.add(api);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out.toArray(new String[out.size()]);
    }

    /** 从带 ?ref= 的完整 URL 还原相对路径（对象存储直链需要）。 */
    static String extractRefFromUrl(String raw) {
        if (raw == null) {
            return "";
        }
        int q = raw.indexOf('?');
        if (q < 0) {
            return "";
        }
        String query = raw.substring(q + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "ref".equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return "";
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean isKnownUploadOrigin(URL parsed) {
        if (parsed == null) {
            return false;
        }
        String host = parsed.getHost();
        if (host == null) {
            return false;
        }
        String lowerHost = host.trim().toLowerCase(java.util.Locale.US);
        if (lowerHost.endsWith(".aliyuncs.com")) {
            return true;
        }
        String origin = buildOrigin(parsed.getProtocol(), parsed.getHost(), parsed.getPort());
        return sameOrigin(origin, OSS_ORIGIN)
                || sameOrigin(origin, MAIN_ORIGIN_60)
                || sameOrigin(origin, CURRENT_MAIN_ORIGIN)
                || sameOrigin(origin, DATA_ORIGIN);
    }

    private static String[] buildUploadCandidatesFromOssPath(String path) {
        String ossPath = path == null ? "/" : path.trim();
        if (!ossPath.startsWith("/")) {
            ossPath = "/" + ossPath;
        }
        String appPath = toAppUploadPath(ossPath);
        if (appPath.equals(ossPath)) {
            appPath = "/v1/uploads" + ossPath;
        }
        return dedupe(new String[]{
            OSS_ORIGIN + ossPath,
            MAIN_ORIGIN_60 + appPath,
            CURRENT_MAIN_ORIGIN + appPath,
            resolveMainOrigin() + appPath
        });
    }

    private static boolean hasOrigin(String url, String expectedOrigin) {
        if (url == null) {
            return false;
        }
        String raw = url.trim();
        if (raw.length() == 0 || !(raw.startsWith("http://") || raw.startsWith("https://"))) {
            return false;
        }
        try {
            URL parsed = new URL(raw);
            String currentOrigin = buildOrigin(parsed.getProtocol(), parsed.getHost(), parsed.getPort());
            return sameOrigin(currentOrigin, expectedOrigin);
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveMainOrigin() {
        String base = HttpUtil.BASE_URL;
        if (base == null) {
            return "";
        }
        String trimmed = base.trim();
        int idx = trimmed.indexOf("/v1");
        return idx > 0 ? normalizeOrigin(trimmed.substring(0, idx)) : normalizeOrigin(trimmed);
    }

    private static boolean isUploadPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim();
        return p.startsWith("/uploads/")
                || p.startsWith("/v1/uploads/")
                || p.startsWith("uploads/")
                || p.startsWith("v1/uploads/")
                || p.startsWith("/oss/")
                || p.startsWith("oss/")
                || p.startsWith("/media/")
                || p.startsWith("/music/")
                || p.startsWith("/lyrics/")
                || p.startsWith("media/")
                || p.startsWith("music/")
                || p.startsWith("lyrics/")
                || p.startsWith("/avatars/")
                || p.startsWith("avatars/")
                || p.startsWith("/covers/")
                || p.startsWith("covers/");
    }

    private static String extractUploadPath(String path) {
        if (path == null) {
            return "/";
        }
        String p = path.trim();
        if (p.startsWith("/oss/")) {
            return "/" + p.substring(5);
        }
        if (p.startsWith("oss/")) {
            return "/" + p.substring(4);
        }
        if (p.startsWith("/v1/uploads/")) {
            return "/" + p.substring(12);
        }
        if (p.startsWith("v1/uploads/")) {
            return "/" + p.substring(11);
        }
        if (p.startsWith("/uploads/")) {
            return "/" + p.substring(9);
        }
        if (p.startsWith("uploads/")) {
            return "/" + p.substring(8);
        }
        if (p.startsWith("/media/") || p.startsWith("/music/") || p.startsWith("/lyrics/")
                || p.startsWith("/avatars/") || p.startsWith("/covers/")) {
            return p;
        }
        if (p.startsWith("media/") || p.startsWith("music/") || p.startsWith("lyrics/")
                || p.startsWith("avatars/") || p.startsWith("covers/")) {
            return "/" + p;
        }
        return p;
    }

    private static String toAppUploadPath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.length() == 0) {
            return "";
        }
        if (p.startsWith("/oss/")) {
            return "/v1/uploads/" + p.substring(5);
        }
        if (p.startsWith("oss/")) {
            return "/v1/uploads/" + p.substring(4);
        }
        if (p.startsWith("/v1/uploads/")) {
            return p;
        }
        if (p.startsWith("v1/uploads/")) {
            return "/" + p;
        }
        if (p.startsWith("/uploads/")) {
            return "/v1" + p;
        }
        if (p.startsWith("uploads/")) {
            return "/v1/" + p;
        }
        if (p.startsWith("/media/") || p.startsWith("/music/") || p.startsWith("/lyrics/")
                || p.startsWith("/avatars/") || p.startsWith("/covers/")) {
            return "/v1/uploads" + p;
        }
        if (p.startsWith("media/") || p.startsWith("music/") || p.startsWith("lyrics/")
                || p.startsWith("avatars/") || p.startsWith("covers/")) {
            return "/v1/uploads/" + p;
        }
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String[] dedupe(String[] input) {
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < input.length; i++) {
            String item = input[i];
            if (item == null) {
                continue;
            }
            String normalized = item.trim();
            if (normalized.length() == 0) {
                continue;
            }
            boolean exists = false;
            for (int j = 0; j < out.size(); j++) {
                if (normalized.equals(out.get(j))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                out.add(normalized);
            }
        }
        return out.toArray(new String[out.size()]);
    }

    private static boolean sameOrigin(String a, String b) {
        return normalizeOrigin(a).equals(normalizeOrigin(b));
    }

    private static String normalizeOrigin(String origin) {
        if (origin == null) {
            return "";
        }
        String out = origin.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String buildOrigin(String protocol, String host, int port) {
        String p = protocol == null ? "http" : protocol;
        String h = host == null ? "" : host;
        return port <= 0 ? normalizeOrigin(p + "://" + h) : normalizeOrigin(p + "://" + h + ":" + port);
    }
}
