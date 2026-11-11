package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.MediaFileUtil;
import org.json.JSONObject;
import java.io.File;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;

public class EmojiSendHelper {
    private static final long MAX_EMOJI_BYTES = 3L * 1024L * 1024L;
    private static final String PREF_NAME = "emoji_upload_cache";
    private static final String KEY_PREFIX = "f_";

    public interface Callback {
        void onUploaded(String url);
        void onError(String message);
    }

    public static void send(Activity activity, File file, boolean isGif, String token, Callback callback) {
        if (file == null || !file.exists()) {
            if (callback != null) {
                callback.onError("表情文件不存在");
            }
            return;
        }
        if (file.length() > MAX_EMOJI_BYTES) {
            if (callback != null) {
                callback.onError("表情包不能超过3MB");
            }
            return;
        }

        Context app = activity == null ? null : activity.getApplicationContext();
        String key = buildFileKey(file, isGif);
        String cachedUrl = readUploadedUrl(app, key);
        if (cachedUrl != null && cachedUrl.length() > 0) {
            if (callback != null) {
                callback.onUploaded(cachedUrl);
            }
            return;
        }

        new EmojiReadTask(activity, file, isGif, token, callback).execute();
    }

    private static class EmojiReadTask extends AsyncTask<Void, Void, byte[]> {
        private final WeakReference<Activity> activityRef;
        private final File file;
        private final boolean isGif;
        private final String token;
        private final Callback callback;
        private String errorMessage;

        EmojiReadTask(Activity activity, File file, boolean isGif, String token, Callback callback) {
            this.activityRef = new WeakReference<>(activity);
            this.file = file;
            this.isGif = isGif;
            this.token = token;
            this.callback = callback;
        }

        @Override
        protected byte[] doInBackground(Void... voids) {
            try {
                if (file == null || !file.exists()) {
                    errorMessage = "表情文件不存在";
                    return null;
                }
                if (file.length() > MAX_EMOJI_BYTES) {
                    errorMessage = "表情包不能超过3MB";
                    return null;
                }
                return MediaFileUtil.readAllBytes(file);
            } catch (Exception e) {
                errorMessage = "发送表情失败";
                return null;
            }
        }

        @Override
        protected void onPostExecute(byte[] data) {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            if (data == null) {
                if (callback != null) {
                    callback.onError(errorMessage == null ? "发送表情失败" : errorMessage);
                }
                return;
            }
            String contentType = isGif ? "image/gif" : MediaFileUtil.resolveImageType(file.getName());
            HttpUtil.postMultipart("/media", data, file.getName(), contentType, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        String url = obj.optString("url");
                        if (url != null && url.length() > 0) {
                            String key = buildFileKey(file, isGif);
                            writeUploadedUrl(activity, key, url);
                        }
                        if (callback != null) {
                            callback.onUploaded(url);
                        }
                    } catch (Exception e) {
                        if (callback != null) {
                            callback.onError("发送表情失败");
                        }
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (callback != null) {
                        if (code == 413 || (error != null && error.contains("image_too_large"))) {
                            callback.onError("表情包不能超过3MB");
                            return;
                        }
                        callback.onError("发送表情失败: " + code);
                    }
                }
            });
        }

        private void writeUploadedUrl(Activity activity, String key, String url) {
            if (activity == null || key == null || key.length() == 0 || url == null || url.length() == 0) {
                return;
            }
            SharedPreferences prefs = activity.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_PREFIX + key, url).apply();
        }
    }

    private static String readUploadedUrl(Context context, String key) {
        if (context == null || key == null || key.length() == 0) {
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PREFIX + key, null);
    }

    private static String buildFileKey(File file, boolean isGif) {
        if (file == null || !file.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(file.getAbsolutePath());
        sb.append('|');
        sb.append(file.length());
        sb.append('|');
        sb.append(file.lastModified());
        sb.append('|');
        sb.append(isGif ? '1' : '0');
        return sha1(sb.toString());
    }

    private static String sha1(String text) {
        if (text == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
