package com.im.oldchat.ui;

import com.im.oldchat.api.HttpUtil;

import org.json.JSONObject;

import java.net.URLEncoder;

final class DailyCheckInWallActionHelper {
    interface SimpleCallback {
        void onSuccess(String response);
        void onFailed(int code, String error, boolean suppressed);
    }

    interface UploadCallback {
        void onUploaded(String imageUrl, String thumbUrl);
        void onFailed(int code, String error, boolean suppressed);
    }

    private DailyCheckInWallActionHelper() {
    }

    static void loadWall(String token, final SimpleCallback callback) {
        requestGet("/me/checkin/wall", token, callback);
    }

    static void requestComments(String token, String postId, final SimpleCallback callback) {
        requestGet("/me/checkin/wall/comments?post_id=" + encode(postId) + "&limit=50", token, callback);
    }

    static void requestLikes(String token, String postId, final SimpleCallback callback) {
        requestGet("/me/checkin/wall/likes?post_id=" + encode(postId) + "&limit=50", token, callback);
    }

    static void requestCheckIn(String token, final SimpleCallback callback) {
        requestPost("/me/checkin", new JSONObject(), token, callback);
    }

    static void toggleLike(String token, String postId, boolean like, final SimpleCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("post_id", postId == null ? "" : postId);
            requestPost(like ? "/me/checkin/wall/like" : "/me/checkin/wall/unlike", json, token, callback);
        } catch (Exception e) {
            callback.onFailed(-1, "build_error", false);
        }
    }

    static void createComment(String token, String postId, String body, final SimpleCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("post_id", postId == null ? "" : postId);
            json.put("body", body == null ? "" : body);
            requestPost("/me/checkin/wall/comment", json, token, callback);
        } catch (Exception e) {
            callback.onFailed(-1, "build_error", false);
        }
    }

    static void uploadImage(String token, DailyCheckInWallSupport.PickedImage pickedImage,
                            final UploadCallback callback) {
        HttpUtil.postMultipartWithThumb("/media", pickedImage.imageBytes, pickedImage.imageName,
                "image/jpeg", pickedImage.thumbBytes, "thumb_" + pickedImage.imageName,
                "image/jpeg", token, new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        try {
                            JSONObject obj = new JSONObject(response);
                            callback.onUploaded(obj.optString("url", ""), obj.optString("thumb_url", ""));
                        } catch (Exception e) {
                            callback.onFailed(-1, "parse_error", false);
                        }
                    }

                    @Override
                    public void onError(int code, String error) {
                        callback.onFailed(code, error, HttpUtil.shouldSuppressAuthToast(code, error));
                    }
                });
    }

    static void createPost(String token, String text, String imageUrl, String thumbUrl,
                           final SimpleCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("content_text", text == null ? "" : text);
            json.put("image_url", imageUrl == null ? "" : imageUrl);
            json.put("thumb_url", thumbUrl == null ? "" : thumbUrl);
            requestPost("/me/checkin/wall", json, token, callback);
        } catch (Exception e) {
            callback.onFailed(-1, "build_error", false);
        }
    }

    private static void requestGet(String path, String token, final SimpleCallback callback) {
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                callback.onSuccess(response);
            }

            @Override
            public void onError(int code, String error) {
                callback.onFailed(code, error, HttpUtil.shouldSuppressAuthToast(code, error));
            }
        });
    }

    private static void requestPost(String path, JSONObject json, String token, final SimpleCallback callback) {
        HttpUtil.post(path, json, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                callback.onSuccess(response);
            }

            @Override
            public void onError(int code, String error) {
                callback.onFailed(code, error, HttpUtil.shouldSuppressAuthToast(code, error));
            }
        });
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }
}
