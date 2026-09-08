package com.im.oldchat.bili;

import android.os.AsyncTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BiliApi extends BiliApiSupport1 {
    private BiliApi() {
    }

    public interface ApiCallback<T> {
        void onSuccess(T data);
        void onError(String error);
    }

    public static void requestQrAuthCode(final ApiCallback<BiliModels.QRAuthCodeResponse> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.QRAuthCodeResult>() {
            private String error;

            @Override
            protected BiliModels.QRAuthCodeResult doInBackground(Void... voids) {
                Map<String, String> params = new HashMap<String, String>();
                params.put("local_id", "0");
                params.put("ts", String.valueOf(System.currentTimeMillis() / 1000));
                params.put("sign", BiliSigner.sign(params));
                Result result = postForm(PASSPORT_BASE_URL + "x/passport-tv-login/qrcode/auth_code", params, "");
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("QR auth_code http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.QRAuthCodeResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("QR auth_code parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.QRAuthCodeResult response) {
                if (response != null && response.code == 0 && response.data != null) {
                    callback.onSuccess(response.data);
                    return;
                }
                String msg = error;
                if (msg == null && response != null && response.message != null) {
                    msg = response.message;
                }
                logError("QR auth_code failed: " + (msg != null ? msg : "unknown"), null);
                callback.onError(msg != null ? msg : "获取二维码失败");
            }
        });
    }

    public static void pollQrLoginStatus(final String authCode,
                                         final ApiCallback<BiliModels.QRPollResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.QRPollResult>() {
            private String error;

            @Override
            protected BiliModels.QRPollResult doInBackground(Void... voids) {
                Map<String, String> params = new HashMap<String, String>();
                params.put("auth_code", authCode != null ? authCode : "");
                params.put("local_id", "0");
                params.put("ts", String.valueOf(System.currentTimeMillis() / 1000));
                params.put("sign", BiliSigner.sign(params));
                Result result = postForm(PASSPORT_BASE_URL + "x/passport-tv-login/qrcode/poll", params, "");
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("QR poll http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.QRPollResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("QR poll parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.QRPollResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("QR poll failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "扫码状态查询失败");
            }
        });
    }

    public static void requestRecommendVideos(final String accessToken, final String cookie,
                                              final ApiCallback<List<BiliModels.RecommendItem>> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.RecommendResult>() {
            private String error;

            @Override
            protected BiliModels.RecommendResult doInBackground(Void... voids) {
                Map<String, String> params = new HashMap<String, String>();
                if (accessToken != null && accessToken.length() > 0) {
                    params.put("access_key", accessToken);
                    params.put("accessKey", accessToken);
                }
                params.put("mobi_app", "android");
                params.put("platform", "android");
                params.put("ts", String.valueOf(System.currentTimeMillis() / 1000));
                params.put("pull", "true");
                params.put("idx", String.valueOf(System.currentTimeMillis() / 1000));
                params.put("sign", BiliSigner.sign(params));

                Result result = BiliApi.get(APP_BASE_URL + "x/v2/feed/index", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("Recommend http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.RecommendResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("Recommend parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.RecommendResult response) {
                if (response != null && response.code == 0 && response.data != null) {
                    List<BiliModels.RecommendItem> items = response.data.items;
                    callback.onSuccess(items);
                    return;
                }
                String msg = error;
                if (msg == null && response != null && response.message != null) {
                    msg = response.message;
                }
                logError("Recommend failed: " + (msg != null ? msg : "unknown"), null);
                callback.onError(msg != null ? msg : "获取推荐失败");
            }
        });
    }

    public static void requestSearchVideos(final String keyword, final int page, final String cookie,
                                           final ApiCallback<BiliModels.SearchResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.SearchResult>() {
            private String error;

            @Override
            protected BiliModels.SearchResult doInBackground(Void... voids) {
                if (keyword == null || keyword.trim().length() == 0) {
                    error = "keyword empty";
                    return null;
                }
                Map<String, String> params = new HashMap<String, String>();
                params.put("keyword", keyword);
                params.put("search_type", "video");
                params.put("page", String.valueOf(Math.max(1, page)));
                params.put("page_size", "20");
                if (ensureWbiKeys(cookie)) {
                    BiliWbiSigner.SignResult sign = BiliWbiSigner.sign(params, WBI_IMG_KEY, WBI_SUB_KEY);
                    if (sign != null && sign.wRid != null && sign.wRid.length() > 0) {
                        params.put("w_rid", sign.wRid);
                        params.put("wts", String.valueOf(sign.wts));
                    }
                }
                Result result = BiliApi.get(API_BASE_URL + "x/web-interface/wbi/search/type", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("Search http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.SearchResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("Search parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.SearchResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("Search failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "搜索失败");
            }
        });
    }

    public static void requestVideoDetail(final String bvid, final long aid, final String cookie,
                                          final ApiCallback<BiliModels.VideoDetailResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.VideoDetailResult>() {
            private String error;

            @Override
            protected BiliModels.VideoDetailResult doInBackground(Void... voids) {
                Map<String, String> params = new HashMap<String, String>();
                if (bvid != null && bvid.length() > 0) {
                    params.put("bvid", bvid);
                } else if (aid > 0) {
                    params.put("aid", String.valueOf(aid));
                }
                Result result = BiliApi.get(API_BASE_URL + "x/web-interface/view", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("Detail http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.VideoDetailResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("Detail parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.VideoDetailResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("Detail failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "获取详情失败");
            }
        });
    }

    public static void requestComments(final long aid, final int page, final String accessToken, final String cookie,
                                       final ApiCallback<BiliModels.CommentResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.CommentResult>() {
            private String error;

            @Override
            protected BiliModels.CommentResult doInBackground(Void... voids) {
                if (aid <= 0) {
                    error = "invalid aid";
                    return null;
                }
                BiliModels.CommentResult wbi = fetchCommentsWbi(aid, page, cookie);
                if (wbi != null && wbi.code == 0) {
                    return wbi;
                }
                BiliModels.CommentResult response = fetchComments(aid, page, accessToken, cookie);
                if (response != null && response.code == 0) {
                    return response;
                }
                if (accessToken != null && accessToken.length() > 0) {
                    BiliModels.CommentResult retry = fetchComments(aid, page, "", cookie);
                    if (retry != null) {
                        return retry;
                    }
                }
                return response;
            }

            @Override
            protected void onPostExecute(BiliModels.CommentResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("Comments failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "获取评论失败");
            }
        });
    }

    public static void requestAddComment(final long aid, final String message, final long root, final long parent,
                                         final String accessToken, final String cookie,
                                         final ApiCallback<BiliModels.SimpleResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.SimpleResult>() {
            private String error;

            @Override
            protected BiliModels.SimpleResult doInBackground(Void... voids) {
                if (aid <= 0 || message == null || message.trim().length() == 0) {
                    error = "invalid input";
                    return null;
                }
                String csrf = extractCsrf(cookie);
                if (csrf == null || csrf.length() == 0) {
                    error = "csrf missing";
                    return null;
                }
                Map<String, String> params = new HashMap<String, String>();
                params.put("oid", String.valueOf(aid));
                params.put("type", "1");
                params.put("message", message);
                if (root > 0) {
                    params.put("root", String.valueOf(root));
                }
                if (parent > 0) {
                    params.put("parent", String.valueOf(parent));
                }
                if (accessToken != null && accessToken.length() > 0) {
                    params.put("access_key", accessToken);
                }
                params.put("csrf", csrf);
                params.put("csrf_token", csrf);
                Result result = postForm(API_BASE_URL + "x/v2/reply/add", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    logError("AddComment http=" + result.code + " body=" + clip(result.body), null);
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.SimpleResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    logError("AddComment parse", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.SimpleResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                logError("AddComment failed: " + (error != null ? error : "unknown"), null);
                callback.onError(error != null ? error : "发表评论失败");
            }
        });
    }
}
