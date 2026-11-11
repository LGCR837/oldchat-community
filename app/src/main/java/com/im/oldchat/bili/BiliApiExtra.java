package com.im.oldchat.bili;

import android.os.AsyncTask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BiliApiExtra extends BiliApiSupport0 {
    private BiliApiExtra() {
    }

    public static void requestRelatedVideos(final long aid, final String cookie,
                                            final BiliApi.ApiCallback<List<BiliModels.RecommendItem>> callback) {
        executeTask(new AsyncTask<Void, Void, List<BiliModels.RecommendItem>>() {
            private String error;

            @Override
            protected List<BiliModels.RecommendItem> doInBackground(Void... voids) {
                if (aid <= 0) {
                    error = "invalid aid";
                    return new ArrayList<BiliModels.RecommendItem>();
                }
                Map<String, String> params = new HashMap<String, String>();
                params.put("aid", String.valueOf(aid));
                Result result = BiliApi.get(API_BASE_URL + "x/web-interface/archive/related", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    return null;
                }
                return parseRelatedItems(result.body);
            }

            @Override
            protected void onPostExecute(List<BiliModels.RecommendItem> items) {
                if (items != null) {
                    callback.onSuccess(items);
                    return;
                }
                callback.onError(error != null ? error : "获取相关推荐失败");
            }
        });
    }

    public static void requestLikeComment(final long aid, final long rpid, final boolean like,
                                          final String accessToken, final String cookie,
                                          final BiliApi.ApiCallback<BiliModels.SimpleResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.SimpleResult>() {
            private String error;

            @Override
            protected BiliModels.SimpleResult doInBackground(Void... voids) {
                if (aid <= 0 || rpid <= 0) {
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
                params.put("rpid", String.valueOf(rpid));
                params.put("action", like ? "1" : "0");
                if (accessToken != null && accessToken.length() > 0) {
                    params.put("access_key", accessToken);
                }
                params.put("csrf", csrf);
                params.put("csrf_token", csrf);
                Result result = BiliApi.postForm(API_BASE_URL + "x/v2/reply/action", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.SimpleResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.SimpleResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                callback.onError(error != null ? error : "评论点赞失败");
            }
        });
    }

    public static void requestCommentReplies(final long aid, final long root,
                                             final int page, final int pageSize,
                                             final String accessToken, final String cookie,
                                             final BiliApi.ApiCallback<BiliModels.CommentResult> callback) {
        executeTask(new AsyncTask<Void, Void, BiliModels.CommentResult>() {
            private String error;

            @Override
            protected BiliModels.CommentResult doInBackground(Void... voids) {
                if (aid <= 0 || root <= 0) {
                    error = "invalid input";
                    return null;
                }
                Map<String, String> params = new HashMap<String, String>();
                params.put("oid", String.valueOf(aid));
                params.put("type", "1");
                params.put("root", String.valueOf(root));
                params.put("pn", String.valueOf(Math.max(1, page)));
                params.put("ps", String.valueOf(pageSize > 0 ? pageSize : 20));
                if (accessToken != null && accessToken.length() > 0) {
                    params.put("access_key", accessToken);
                }
                Result result = BiliApi.get(API_BASE_URL + "x/v2/reply/reply", params, cookie);
                if (!result.isOk()) {
                    error = result.errorMessage();
                    return null;
                }
                try {
                    return GSON.fromJson(result.body, BiliModels.CommentResult.class);
                } catch (Exception e) {
                    error = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(BiliModels.CommentResult response) {
                if (response != null) {
                    callback.onSuccess(response);
                    return;
                }
                callback.onError(error != null ? error : "获取评论回复失败");
            }
        });
    }

    private static List<BiliModels.RecommendItem> parseRelatedItems(String body) {
        List<BiliModels.RecommendItem> result = new ArrayList<BiliModels.RecommendItem>();
        try {
            JSONObject root = new JSONObject(body != null ? body : "{}");
            int code = root.optInt("code", -1);
            if (code != 0) {
                return result;
            }
            JSONArray data = root.optJSONArray("data");
            if (data == null) {
                return result;
            }
            for (int i = 0; i < data.length(); i++) {
                JSONObject itemObj = data.optJSONObject(i);
                if (itemObj == null) {
                    continue;
                }
                BiliModels.RecommendItem item = new BiliModels.RecommendItem();
                item.cardType = "related";
                item.cardGoto = "av";
                item.gotoType = "av";
                item.title = itemObj.optString("title", "");
                item.cover = BiliApi.normalizeUrl(itemObj.optString("pic", ""));

                String bvid = itemObj.optString("bvid", "");
                long aid = itemObj.optLong("aid", 0L);
                if (bvid != null && bvid.length() > 0) {
                    item.param = bvid;
                } else if (aid > 0) {
                    item.param = String.valueOf(aid);
                }

                int durationSec = itemObj.optInt("duration", 0);
                item.duration = formatDurationText(durationSec);

                JSONObject owner = itemObj.optJSONObject("owner");
                BiliModels.RecommendArgs args = new BiliModels.RecommendArgs();
                if (owner != null) {
                    args.upName = owner.optString("name", "");
                }
                args.aid = aid;
                item.args = args;

                JSONObject stat = itemObj.optJSONObject("stat");
                if (stat != null) {
                    int view = stat.optInt("view", 0);
                    int danmaku = stat.optInt("danmaku", 0);
                    item.playCount = view > 0 ? String.valueOf(view) : "";
                    item.danmakuCount = danmaku > 0 ? String.valueOf(danmaku) : "";
                }
                result.add(item);
            }
        } catch (Exception e) {
            return result;
        }
        return result;
    }

    private static String formatDurationText(int durationSec) {
        if (durationSec <= 0) {
            return "";
        }
        int hours = durationSec / 3600;
        int minutes = (durationSec % 3600) / 60;
        int seconds = durationSec % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }
}
