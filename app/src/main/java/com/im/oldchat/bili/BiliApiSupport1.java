package com.im.oldchat.bili;

import android.os.AsyncTask;

import java.util.HashMap;

class BiliApiSupport1 extends BiliApiSupport0 {
    protected static BiliModels.CommentResult fetchComments(long aid, int page, String accessToken, String cookie) {
        java.util.Map<String, String> params = new HashMap<String, String>();
        params.put("oid", String.valueOf(aid));
        params.put("type", "1");
        params.put("pn", String.valueOf(Math.max(1, page)));
        params.put("ps", "20");
        params.put("sort", "0");
        params.put("mode", "3");
        params.put("nohot", "0");
        if (accessToken != null && accessToken.length() > 0) {
            params.put("access_key", accessToken);
        }
        Result result = BiliApi.get(API_BASE_URL + "x/v2/reply", params, cookie);
        if (!result.isOk()) {
            logError("Comments http=" + result.code + " body=" + clip(result.body), null);
            return null;
        }
        try {
            return GSON.fromJson(result.body, BiliModels.CommentResult.class);
        } catch (Exception e) {
            logError("Comments parse", e);
            return null;
        }
    }

    protected static BiliModels.CommentResult fetchCommentsWbi(long aid, int page, String cookie) {
        if (!ensureWbiKeys(cookie)) {
            return null;
        }
        java.util.Map<String, String> params = new HashMap<String, String>();
        params.put("oid", String.valueOf(aid));
        params.put("type", "1");
        params.put("pn", String.valueOf(Math.max(1, page)));
        params.put("ps", "20");
        params.put("mode", "3");
        BiliWbiSigner.SignResult sign = BiliWbiSigner.sign(params, WBI_IMG_KEY, WBI_SUB_KEY);
        if (sign == null || sign.wRid == null || sign.wRid.length() == 0) {
            return null;
        }
        params.put("w_rid", sign.wRid);
        params.put("wts", String.valueOf(sign.wts));
        Result result = BiliApi.get(API_BASE_URL + "x/v2/reply/wbi/main", params, cookie);
        if (!result.isOk()) {
            logError("CommentsWbi http=" + result.code + " body=" + clip(result.body), null);
            return null;
        }
        try {
            return GSON.fromJson(result.body, BiliModels.CommentResult.class);
        } catch (Exception e) {
            logError("CommentsWbi parse", e);
            return null;
        }
    }

    protected static boolean ensureWbiKeys(String cookie) {
        long now = System.currentTimeMillis();
        if (WBI_IMG_KEY != null && WBI_SUB_KEY != null && (now - WBI_FETCH_AT) < 24 * 60 * 60 * 1000L) {
            return true;
        }
        Result result = BiliApi.get(API_BASE_URL + "x/web-interface/nav", new HashMap<String, String>(), cookie);
        if (!result.isOk()) {
            logError("Nav http=" + result.code + " body=" + clip(result.body), null);
            return false;
        }
        try {
            BiliModels.NavResult response = GSON.fromJson(result.body, BiliModels.NavResult.class);
            if (response != null && response.code == 0 && response.data != null && response.data.wbiImg != null) {
                String imgUrl = response.data.wbiImg.imgUrl;
                String subUrl = response.data.wbiImg.subUrl;
                String imgKey = extractKey(imgUrl);
                String subKey = extractKey(subUrl);
                if (imgKey != null && imgKey.length() > 0 && subKey != null && subKey.length() > 0) {
                    WBI_IMG_KEY = imgKey;
                    WBI_SUB_KEY = subKey;
                    WBI_FETCH_AT = now;
                    return true;
                }
            }
        } catch (Exception e) {
            logError("Nav parse", e);
        }
        return false;
    }

    private static String extractKey(String url) {
        if (url == null) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        String tail = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = tail.indexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

    public static void requestPlayUrl(final String bvid, final long aid, final long cid, final int quality,
                                      final String cookie,
                                      final BiliApi.ApiCallback<BiliModels.PlayUrlResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.PlayUrlResult>() {
            private String error;

            @Override
            protected BiliModels.PlayUrlResult doInBackground(Void... voids) {
                if ((bvid == null || bvid.length() == 0) && (aid <= 0 || cid <= 0)) {
                    error = "invalid aid/cid";
                    return null;
                }
                BiliModels.PlayUrlResult response = fetchPlayUrl(bvid, aid, cid, quality, cookie, 1, "html5");
                if (isPlayable(response)) {
                    return response;
                }
                BiliModels.PlayUrlResult fallback = fetchPlayUrl(bvid, aid, cid, quality, cookie, 0, "android");
                if (isPlayable(fallback)) {
                    return fallback;
                }
                if (fallback != null) {
                    return fallback;
                }
                return response;
            }

            @Override
            protected void onPostExecute(BiliModels.PlayUrlResult response) {
                if (response != null) {
                    if (response.code != 0) {
                        logError("PlayUrl code=" + response.code + " msg=" + response.message
                                + " bvid=" + (bvid != null ? bvid : "") + " aid=" + aid + " cid=" + cid, null);
                    }
                    callback.onSuccess(response);
                    return;
                }
                logError("PlayUrl failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "获取播放地址失败");
            }
        });
    }

    public static void requestNav(final String cookie, final BiliApi.ApiCallback<BiliModels.NavResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.NavResult>() {
            private String error;

            @Override
            protected BiliModels.NavResult doInBackground(Void... voids) {
                Result result = BiliApi.get(API_BASE_URL + "x/web-interface/nav", new HashMap<String, String>(), cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("Nav http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.NavResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("Nav parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.NavResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("Nav failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "获取用户信息失败");
            }
        });
    }

    public static void requestHistory(final long max, final long viewAt, final int pageSize, final String cookie,
                                      final BiliApi.ApiCallback<BiliModels.HistoryResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.HistoryResult>() {
            private String error;

            @Override
            protected BiliModels.HistoryResult doInBackground(Void... voids) {
                java.util.Map<String, String> params = new HashMap<String, String>();
                params.put("ps", String.valueOf(pageSize > 0 ? pageSize : 20));
                params.put("max", String.valueOf(Math.max(0, max)));
                params.put("view_at", String.valueOf(Math.max(0, viewAt)));
                Result result = BiliApi.get(API_BASE_URL + "x/web-interface/history/cursor", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("History http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.HistoryResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("History parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.HistoryResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("History failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "获取历史失败");
            }
        });
    }

    public static void requestFavFolders(final long mid, final String cookie,
                                         final BiliApi.ApiCallback<BiliModels.FavFolderResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.FavFolderResult>() {
            private String error;

            @Override
            protected BiliModels.FavFolderResult doInBackground(Void... voids) {
                if (mid <= 0) {
                    error = "invalid mid";
                    return null;
                }
                java.util.Map<String, String> params = new HashMap<String, String>();
                params.put("up_mid", String.valueOf(mid));
                params.put("type", "2");
                Result result = BiliApi.get(API_BASE_URL + "x/v3/fav/folder/created/list-all", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("FavFolder http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.FavFolderResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("FavFolder parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.FavFolderResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("FavFolder failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "获取收藏夹失败");
            }
        });
    }

    public static void requestFavResources(final long mediaId, final int page, final int pageSize,
                                           final String cookie,
                                           final BiliApi.ApiCallback<BiliModels.FavResourceResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.FavResourceResult>() {
            private String error;

            @Override
            protected BiliModels.FavResourceResult doInBackground(Void... voids) {
                if (mediaId <= 0) {
                    error = "invalid media id";
                    return null;
                }
                java.util.Map<String, String> params = new HashMap<String, String>();
                params.put("media_id", String.valueOf(mediaId));
                params.put("pn", String.valueOf(Math.max(1, page)));
                params.put("ps", String.valueOf(pageSize > 0 ? pageSize : 20));
                Result result = BiliApi.get(API_BASE_URL + "x/v3/fav/resource/list", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("FavResource http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.FavResourceResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("FavResource parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.FavResourceResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("FavResource failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "获取收藏内容失败");
            }
        });
    }

    private static BiliModels.PlayUrlResult fetchPlayUrl(String bvid, long aid, long cid, int quality,
                                                         String cookie, int fnval, String platform) {
        if ((bvid == null || bvid.length() == 0) && aid > 0) {
            // B站新API：x/player/playurl 已不接受 aid 参数（返回 -400），必须用 bvid。
            // 先用 aid 请求视频详情解析出 bvid，再走正常播放链路。
            String resolvedBvid = resolveBvidByAid(aid, cookie);
            if (resolvedBvid != null && resolvedBvid.length() > 0) {
                bvid = resolvedBvid;
                logError("PlayUrl: resolved bvid=" + bvid + " from aid=" + aid + " (new API)", null);
            }
        }
        java.util.Map<String, String> params = new HashMap<String, String>();
        if (bvid != null && bvid.length() > 0) {
            params.put("bvid", bvid);
        } else {
            params.put("aid", String.valueOf(aid));
        }
        params.put("cid", String.valueOf(cid));
        params.put("qn", String.valueOf(quality));
        if (fnval > 0) {
            params.put("fnval", String.valueOf(fnval));
        }
        params.put("fnver", "0");
        params.put("fourk", "0");
        if (platform != null && platform.length() > 0) {
            params.put("platform", platform);
            params.put("mobi_app", platform);
        }
        params.put("otype", "json");
        Result result = BiliApi.get(API_BASE_URL + "x/player/playurl", params, cookie);
        if (!result.isOk()) {
            logError("PlayUrl http=" + result.code + " body=" + clip(result.body), null);
            return null;
        }
        try {
            return GSON.fromJson(result.body, BiliModels.PlayUrlResult.class);
        } catch (Exception e) {
            logError("PlayUrl parse", e);
            return null;
        }
    }

    private static boolean isPlayable(BiliModels.PlayUrlResult response) {
        if (response == null || response.code != 0 || response.data == null) {
            return false;
        }
        if (response.data.durl == null || response.data.durl.isEmpty()) {
            return false;
        }
        String url = response.data.durl.get(0) != null ? response.data.durl.get(0).url : null;
        return url != null && url.length() > 0;
    }

    private static String resolveBvidByAid(long aid, String cookie) {
        if (aid <= 0) {
            return null;
        }
        java.util.Map<String, String> params = new HashMap<String, String>();
        params.put("aid", String.valueOf(aid));
        Result result = BiliApi.get(API_BASE_URL + "x/web-interface/view", params, cookie);
        if (!result.isOk()) {
            logError("ResolveBvid http=" + result.code + " body=" + clip(result.body), null);
            return null;
        }
        try {
            BiliModels.VideoDetailResult response = GSON.fromJson(result.body, BiliModels.VideoDetailResult.class);
            if (response != null && response.code == 0 && response.data != null
                    && response.data.bvid != null && response.data.bvid.length() > 0) {
                return response.data.bvid;
            }
        } catch (Exception e) {
            logError("ResolveBvid parse", e);
        }
        return null;
    }
}
