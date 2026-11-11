package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.ResourceItem;

import org.json.JSONObject;

public final class FavoriteHelper {
    public interface Callback {
        void onDone(boolean success);
    }

    private static final String AUTH_PREFS = "auth";

    private FavoriteHelper() {
    }

    public static void addFavorite(final Context context,
                                   final String type,
                                   final String targetId,
                                   final String title,
                                   final String subtitle,
                                   final String mediaUrl,
                                   final String extra,
                                   final String successText) {
        if (context == null) {
            return;
        }
        String token = getToken(context);
        if (token == null || token.length() == 0) {
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        final String target = normalizeTargetId(targetId, mediaUrl);
        if (target.length() == 0) {
            Toast.makeText(context, "收藏失败：目标无效", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", type == null ? "" : type);
            json.put("target_id", target);
            json.put("title", title == null ? "" : title);
            json.put("subtitle", subtitle == null ? "" : subtitle);
            json.put("media_url", mediaUrl == null ? "" : mediaUrl);
            json.put("extra", extra == null ? "" : extra);
            HttpUtil.post("/favorites/add", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(context,
                            successText == null || successText.length() == 0 ? "已收藏" : successText,
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(context, "收藏失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(context, "收藏失败", Toast.LENGTH_SHORT).show();
        }
    }

    public static void removeFavorite(final Context context,
                                      final String type,
                                      final String targetId,
                                      final String mediaUrl,
                                      final Callback callback) {
        if (context == null) {
            if (callback != null) {
                callback.onDone(false);
            }
            return;
        }
        String token = getToken(context);
        if (token == null || token.length() == 0) {
            if (callback != null) {
                callback.onDone(false);
            }
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        final String target = normalizeTargetId(targetId, mediaUrl);
        if (target.length() == 0) {
            if (callback != null) {
                callback.onDone(false);
            }
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", type == null ? "" : type);
            json.put("target_id", target);
            HttpUtil.post("/favorites/remove", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (callback != null) {
                        callback.onDone(true);
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (callback != null) {
                        callback.onDone(false);
                    }
                }
            });
        } catch (Exception e) {
            if (callback != null) {
                callback.onDone(false);
            }
        }
    }

    public static void addChatMediaFavorite(Context context,
                                            String msgType,
                                            String messageId,
                                            String fromUid,
                                            String mediaUrl,
                                            String scene) {
        String type = toChatFavoriteType(msgType);
        if (type.length() == 0) {
            return;
        }
        String title = "聊天";
        if ("chat_image".equals(type)) {
            title = "聊天图片";
        } else if ("chat_voice".equals(type)) {
            title = "聊天语音";
        } else if ("chat_video".equals(type)) {
            title = "聊天视频";
        }
        String subtitle = fromUid == null || fromUid.length() == 0 ? "" : ("来自 " + fromUid);
        String extra = "";
        try {
            JSONObject obj = new JSONObject();
            obj.put("scene", scene == null ? "" : scene);
            obj.put("msg_type", msgType == null ? "" : msgType);
            extra = obj.toString();
        } catch (Exception e) {
            extra = "";
        }
        addFavorite(context, type, messageId, title, subtitle, mediaUrl, extra, "已加入收藏");
    }

    public static void addResourceFavorite(Context context, ResourceItem item) {
        if (item == null) {
            return;
        }
        String title = item.name == null || item.name.length() == 0 ? "资源文件" : item.name;
        String subtitle = item.uploader_name;
        if (subtitle == null || subtitle.length() == 0) {
            subtitle = item.uploader_uid;
        }
        addFavorite(context,
                "resource_file",
                item.id,
                title,
                subtitle == null ? "" : subtitle,
                item.url,
                "",
                "已收藏资源");
    }

    public static void addEmojiFavorite(Context context,
                                        String itemId,
                                        String name,
                                        String owner,
                                        String mediaUrl,
                                        String packageUrl) {
        String extra = "";
        try {
            JSONObject obj = new JSONObject();
            obj.put("package_url", packageUrl == null ? "" : packageUrl);
            extra = obj.toString();
        } catch (Exception e) {
            extra = "";
        }
        addFavorite(context,
                "emoji_pack",
                itemId,
                name == null || name.length() == 0 ? "表情包" : name,
                owner == null ? "" : owner,
                mediaUrl,
                extra,
                "已收藏表情包");
    }

    public static void addMusicFavorite(Context context,
                                        String itemId,
                                        String name,
                                        String owner,
                                        String songUrl,
                                        String coverUrl,
                                        String lyricsUrl) {
        String extra = "";
        try {
            JSONObject obj = new JSONObject();
            obj.put("song_url", songUrl == null ? "" : songUrl);
            obj.put("cover_url", coverUrl == null ? "" : coverUrl);
            obj.put("lyrics_url", lyricsUrl == null ? "" : lyricsUrl);
            extra = obj.toString();
        } catch (Exception e) {
            extra = "";
        }
        addFavorite(context,
                "music_song",
                itemId,
                name == null || name.length() == 0 ? "歌曲" : name,
                owner == null ? "" : owner,
                songUrl,
                extra,
                "已收藏歌曲");
    }

    public static String toChatFavoriteType(String msgType) {
        if (msgType == null) {
            return "";
        }
        String lower = msgType.toLowerCase();
        if ("image".equals(lower)) {
            return "chat_image";
        }
        if ("voice".equals(lower)) {
            return "chat_voice";
        }
        if ("video".equals(lower)) {
            return "chat_video";
        }
        return "";
    }

    public static String normalizeTargetId(String targetId, String mediaUrl) {
        if (targetId != null && targetId.trim().length() > 0) {
            return targetId.trim();
        }
        if (mediaUrl == null || mediaUrl.trim().length() == 0) {
            return "";
        }
        String url = mediaUrl.trim();
        return "url_" + Integer.toHexString(url.hashCode());
    }

    private static String getToken(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        return prefs.getString("access_token", "");
    }
}
