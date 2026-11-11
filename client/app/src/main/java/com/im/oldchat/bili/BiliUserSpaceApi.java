package com.im.oldchat.bili;

import android.os.AsyncTask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BiliUserSpaceApi extends BiliApiSupport1 {
    public static class UpProfileData {
        public long mid;
        public String name;
        public String face;
        public String sign;
        public int level;
        public int following;
        public int follower;
        public int archiveCount;
    }

    public static class UpVideoPage {
        public final List<BiliModels.RecommendItem> items = new ArrayList<BiliModels.RecommendItem>();
        public int page;
        public int pageSize;
        public int total;
        public boolean hasMore;
    }

    private BiliUserSpaceApi() {
    }

    public static void requestUpProfile(final long mid, final String cookie,
                                        final BiliApi.ApiCallback<UpProfileData> callback) {
        executeTask(new AsyncTask<Void, Void, UpProfileData>() {
            private String error;

            @Override
            protected UpProfileData doInBackground(Void... voids) {
                if (mid <= 0) {
                    error = "invalid mid";
                    return null;
                }
                try {
                    UpProfileData profile = fetchBasicProfile(mid, cookie);
                    if (profile == null) {
                        error = "fetch profile failed";
                        return null;
                    }
                    fillRelationStat(profile, cookie);
                    return profile;
                } catch (Exception e) {
                    error = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(UpProfileData data) {
                if (data != null) {
                    callback.onSuccess(data);
                    return;
                }
                callback.onError(error != null ? error : "load profile failed");
            }
        });
    }

    public static void requestUpVideos(final long mid, final int page, final int pageSize, final String cookie,
                                       final BiliApi.ApiCallback<UpVideoPage> callback) {
        executeTask(new AsyncTask<Void, Void, UpVideoPage>() {
            private String error;

            @Override
            protected UpVideoPage doInBackground(Void... voids) {
                if (mid <= 0) {
                    error = "invalid mid";
                    return null;
                }
                try {
                    return fetchVideoPage(mid, page, pageSize, cookie);
                } catch (Exception e) {
                    error = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(UpVideoPage data) {
                if (data != null) {
                    callback.onSuccess(data);
                    return;
                }
                callback.onError(error != null ? error : "load videos failed");
            }
        });
    }

    private static UpProfileData fetchBasicProfile(long mid, String cookie) {
        Map<String, String> params = new HashMap<String, String>();
        params.put("mid", String.valueOf(mid));
        Result result = getSpaceResult("x/space/wbi/acc/info", "x/space/acc/info", params, cookie);
        if (result == null || !result.isOk()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(result.body != null ? result.body : "{}");
            if (root.optInt("code", -1) != 0) {
                return null;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return null;
            }
            UpProfileData profile = new UpProfileData();
            profile.mid = data.optLong("mid", mid);
            profile.name = data.optString("name", "");
            profile.face = normalizeUrl(data.optString("face", ""));
            profile.sign = data.optString("sign", "");
            profile.archiveCount = data.optInt("archive_count", 0);
            JSONObject levelInfo = data.optJSONObject("level_info");
            profile.level = levelInfo != null ? levelInfo.optInt("current_level", 0) : data.optInt("level", 0);
            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    private static void fillRelationStat(UpProfileData profile, String cookie) {
        if (profile == null || profile.mid <= 0) {
            return;
        }
        Map<String, String> params = new HashMap<String, String>();
        params.put("vmid", String.valueOf(profile.mid));
        Result result = BiliApi.get(API_BASE_URL + "x/relation/stat", params, cookie);
        if (!result.isOk()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(result.body != null ? result.body : "{}");
            if (root.optInt("code", -1) != 0) {
                return;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return;
            }
            profile.following = data.optInt("following", 0);
            profile.follower = data.optInt("follower", 0);
        } catch (Exception ignored) {
        }
    }

    private static UpVideoPage fetchVideoPage(long mid, int page, int pageSize, String cookie) {
        int pn = Math.max(1, page);
        int ps = pageSize > 0 ? pageSize : 20;
        Map<String, String> params = new HashMap<String, String>();
        params.put("mid", String.valueOf(mid));
        params.put("pn", String.valueOf(pn));
        params.put("ps", String.valueOf(ps));
        params.put("order", "pubdate");
        params.put("tid", "0");
        params.put("keyword", "");
        params.put("platform", "web");
        Result result = getSpaceResult("x/space/wbi/arc/search", "x/space/arc/search", params, cookie);
        if (result == null || !result.isOk()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(result.body != null ? result.body : "{}");
            if (root.optInt("code", -1) != 0) {
                return null;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return null;
            }
            UpVideoPage out = new UpVideoPage();
            out.page = pn;
            out.pageSize = ps;

            JSONObject pageObj = data.optJSONObject("page");
            if (pageObj != null) {
                out.total = pageObj.optInt("count", 0);
            }

            JSONObject listObj = data.optJSONObject("list");
            JSONArray vlist = listObj != null ? listObj.optJSONArray("vlist") : null;
            if (vlist != null) {
                for (int i = 0; i < vlist.length(); i++) {
                    JSONObject obj = vlist.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    BiliModels.RecommendItem item = toRecommendItem(mid, obj);
                    if (item != null) {
                        out.items.add(item);
                    }
                }
            }
            if (out.total <= 0) {
                out.total = out.items.size();
            }
            out.hasMore = out.page * out.pageSize < out.total;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static BiliModels.RecommendItem toRecommendItem(long mid, JSONObject obj) {
        String bvid = obj.optString("bvid", "");
        long aid = obj.optLong("aid", 0L);
        if ((bvid == null || bvid.length() == 0) && aid <= 0) {
            return null;
        }
        BiliModels.RecommendItem item = new BiliModels.RecommendItem();
        item.cardType = "up_archive";
        item.cardGoto = "av";
        item.gotoType = "av";
        item.title = obj.optString("title", "");
        item.cover = normalizeUrl(obj.optString("pic", ""));
        item.duration = normalizeDuration(obj.optString("length", ""));
        item.param = bvid != null && bvid.length() > 0 ? bvid : String.valueOf(aid);
        item.playCount = String.valueOf(obj.optString("play", ""));
        item.danmakuCount = String.valueOf(obj.optString("video_review", ""));

        BiliModels.RecommendArgs args = new BiliModels.RecommendArgs();
        args.aid = aid;
        args.mid = mid;
        args.upId = mid;
        args.upName = obj.optString("author", "");
        item.args = args;
        return item;
    }

    private static String normalizeDuration(String length) {
        if (length == null) {
            return "";
        }
        String value = length.trim();
        if (value.length() == 0) {
            return "";
        }
        return value;
    }

    private static Result getSpaceResult(String wbiPath, String fallbackPath,
                                         Map<String, String> params, String cookie) {
        Result result = null;
        if (ensureWbiKeys(cookie)) {
            Map<String, String> signed = new HashMap<String, String>(params);
            BiliWbiSigner.SignResult sign = BiliWbiSigner.sign(signed, WBI_IMG_KEY, WBI_SUB_KEY);
            if (sign != null && sign.wRid != null && sign.wRid.length() > 0) {
                signed.put("w_rid", sign.wRid);
                signed.put("wts", String.valueOf(sign.wts));
                result = BiliApi.get(API_BASE_URL + wbiPath, signed, cookie);
                if (result != null && result.isOk()) {
                    return result;
                }
            }
        }
        return BiliApi.get(API_BASE_URL + fallbackPath, params, cookie);
    }
}
