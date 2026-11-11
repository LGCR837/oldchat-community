package com.im.oldchat.ui;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.im.oldchat.models.DailyCheckInWallComment;
import com.im.oldchat.models.DailyCheckInWallMessage;
import com.im.oldchat.util.ImageCompressUtil;

import org.json.JSONObject;

final class DailyCheckInWallSupport {
    private static final int MAX_IMAGE = 2560;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_THUMB_BYTES = 300 * 1024;

    static final class PickedImage {
        final byte[] imageBytes;
        final byte[] thumbBytes;
        final String imageName;
        final Bitmap preview;

        PickedImage(byte[] imageBytes, byte[] thumbBytes, String imageName, Bitmap preview) {
            this.imageBytes = imageBytes;
            this.thumbBytes = thumbBytes;
            this.imageName = imageName;
            this.preview = preview;
        }
    }

    private DailyCheckInWallSupport() {
    }

    static PickedImage loadPickedImage(Activity activity, Uri uri) {
        if (activity == null || uri == null) {
            return null;
        }
        try {
            byte[] imageBytes = ImageCompressUtil.compressToBytes(activity.getContentResolver(), uri,
                    MAX_IMAGE, MAX_IMAGE_BYTES);
            if (imageBytes == null || imageBytes.length == 0) {
                return null;
            }
            byte[] thumbBytes = ImageCompressUtil.compressToBytes(activity.getContentResolver(), uri,
                    320, MAX_THUMB_BYTES);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            byte[] sourceForPreview = (thumbBytes != null && thumbBytes.length > 0) ? thumbBytes : imageBytes;
            Bitmap preview = BitmapFactory.decodeByteArray(sourceForPreview, 0, sourceForPreview.length, opts);
            if (preview == null) {
                return null;
            }
            return new PickedImage(imageBytes, thumbBytes, queryFileName(activity, uri), preview);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static DailyCheckInWallMessage parseMessage(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        DailyCheckInWallMessage item = new DailyCheckInWallMessage();
        item.id = obj.optString("id", "");
        item.checkinDate = obj.optString("checkin_date", "");
        item.messageType = obj.optString("message_type", "text");
        item.contentText = obj.optString("content_text", "");
        item.imageUrl = obj.optString("image_url", "");
        item.thumbUrl = obj.optString("thumb_url", "");
        item.createdAt = obj.optString("created_at", "");
        JSONObject user = obj.optJSONObject("user");
        if (user != null) {
            item.userId = user.optString("id", "");
            item.userUid = user.optString("uid", "");
            item.username = user.optString("username", "");
        item.displayName = user.optString("display_name", "");
        item.avatarUrl = user.optString("avatar_url", "");
    }
        item.likeCount = obj.optInt("like_count", 0);
        item.commentCount = obj.optInt("comment_count", 0);
        item.likedByMe = obj.optBoolean("liked_by_me", false);
        return item;
    }

    static DailyCheckInWallComment parseComment(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        DailyCheckInWallComment item = new DailyCheckInWallComment();
        item.id = obj.optString("id", "");
        item.postId = obj.optString("post_id", "");
        item.body = obj.optString("body", "");
        item.createdAt = parseUnixSeconds(obj.opt("created_at"));
        JSONObject user = obj.optJSONObject("user");
        if (user != null) {
            item.fromUid = user.optString("uid", "");
            item.fromName = user.optString("display_name", "");
            item.fromTitle = user.optString("user_title", "");
            item.fromAvatar = user.optString("avatar_url", "");
        }
        return item;
    }

    static void bindMyPost(View card, TextView content, DailyCheckInWallMessage item) {
        if (card == null || content == null) {
            return;
        }
        if (item == null) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        content.setText(item.hasImage() ? item.getDisplayName() + " 的图片留言已发布"
                : (item.contentText == null ? "" : item.contentText));
    }

    static String parseCheckInToast(String response) {
        try {
            JSONObject obj = new JSONObject(response);
            if (obj.optBoolean("already_checked", false)) {
                return "今天已签到";
            }
            int repReward = obj.optInt("reputation_reward", 10);
            int coinReward = obj.optInt("coin_reward", 0);
            if (coinReward > 0 && repReward > 0) {
                return "签到成功 +" + coinReward + "旧币 +" + repReward + "信誉";
            } else if (repReward > 0) {
                return "签到成功 +" + repReward + "信誉";
            } else if (coinReward > 0) {
                return "签到成功 +" + coinReward + "旧币";
            } else {
                return "签到成功 (信誉分已达上限)";
            }
        } catch (Exception e) {
            return "签到成功";
        }
    }

    static String resolvePostError(int code, String error) {
        if (code == 409 || contains(error, "already_posted")) {
            return "今天已经留言过了";
        }
        if (code == 403 || contains(error, "checkin_required")) {
            return "请先签到再留言";
        }
        if (code == 400 || contains(error, "invalid_post")) {
            return "文字和图片只能二选一";
        }
        return "发布失败";
    }

    static String resolveCommentError(int code, String error) {
        if (code == 400 || contains(error, "invalid_comment")) {
            return "评论内容不能为空或太长";
        }
        if (code == 404 || contains(error, "not_found")) {
            return "这条留言不存在了";
        }
        return "评论失败";
    }

    static String buildStatLine(DailyCheckInWallMessage item) {
        if (item == null) {
            return "赞 0 · 评论 0";
        }
        return (item.likedByMe ? "已赞 " : "赞 ") + item.likeCount + " · 评论 " + item.commentCount;
    }

    static String buildMessagePreview(DailyCheckInWallMessage item) {
        if (item == null) {
            return "今天还没有随机留言";
        }
        if (item.hasImage()) {
            String text = item.contentText == null ? "" : item.contentText.trim();
            return TextUtils.isEmpty(text) ? "TA 发布了一张今日图片" : text;
        }
        return item.contentText == null ? "" : item.contentText;
    }

    static CharSequence[] parseLikeDisplayNames(String response) {
        try {
            JSONObject obj = new JSONObject(response);
            org.json.JSONArray arr = obj.optJSONArray("likes");
            if (arr == null || arr.length() == 0) {
                return new CharSequence[]{"还没有人点赞"};
            }
            CharSequence[] items = new CharSequence[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject user = arr.optJSONObject(i);
                String name = user == null ? "" : user.optString("display_name", "");
                if (TextUtils.isEmpty(name) && user != null) {
                    name = user.optString("username", "");
                }
                if (TextUtils.isEmpty(name) && user != null) {
                    name = user.optString("uid", "");
                }
                items[i] = TextUtils.isEmpty(name) ? "匿名用户" : name;
            }
            return items;
        } catch (Exception e) {
            return new CharSequence[]{"点赞列表解析失败"};
        }
    }

    private static long parseUnixSeconds(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean contains(String text, String key) {
        return text != null && text.contains(key);
    }

    private static String queryFileName(Activity activity, Uri uri) {
        Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null && name.length() > 0) {
                            return name;
                        }
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return "checkin.jpg";
    }
}
