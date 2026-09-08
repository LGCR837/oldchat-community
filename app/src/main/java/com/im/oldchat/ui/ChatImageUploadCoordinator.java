package com.im.oldchat.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageCompressUtil;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

final class ChatImageUploadCoordinator {
    private ChatImageUploadCoordinator() {
    }

    static void upload(final Activity activity, final String token, final Uri uri,
                       final ChatMediaHelper.MediaCallback mediaCallback,
                       final ChatMediaHelper.SendStateListener sendStateListener,
                       final ChatMediaHelper.ImageUploadListener imageUploadListener,
                       final int maxImageBytes, final int maxImageUploadBytes) {
        if (activity == null || uri == null) {
            return;
        }
        final String localId = buildLocalId();
        if (imageUploadListener != null) {
            imageUploadListener.onQueued(localId, uri.toString());
        }
        notifySending(sendStateListener, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                File stagedFile = null;
                File convertedFile = null;
                try {
                    // Android 16 的系统选择器可能返回云端或临时 content URI。先只读取一次并落到
                    // 应用缓存，避免缩略图解码和正式上传多次打开 provider 导致权限或云下载失败。
                    int stagingLimit = Math.max(maxImageUploadBytes * 4, 40 * 1024 * 1024);
                    stagedFile = stageUriToCache(activity, uri, stagingLimit);
                    String sourceMime = resolveImageMime(activity.getContentResolver(), uri);
                    File uploadFile = stagedFile;
                    String uploadMime = normalizeDirectUploadMime(sourceMime);
                    if (uploadMime == null || stagedFile.length() > maxImageUploadBytes) {
                        byte[] converted = ImageCompressUtil.compressToBytes(
                                activity.getContentResolver(), Uri.fromFile(stagedFile), 4096,
                                maxImageUploadBytes - 64 * 1024);
                        if (converted == null || converted.length == 0 || converted.length > maxImageUploadBytes) {
                            throw new IllegalStateException("unsupported_image_format");
                        }
                        convertedFile = new File(activity.getCacheDir(), "chat_image_" + System.nanoTime() + ".jpg");
                        writeBytes(convertedFile, converted);
                        uploadFile = convertedFile;
                        uploadMime = "image/jpeg";
                    }
                    byte[] thumb = null;
                    try {
                        thumb = ImageCompressUtil.compressToBytes(
                                activity.getContentResolver(), Uri.fromFile(uploadFile), 1280, maxImageBytes);
                    } catch (Exception thumbError) {
                        Log.w("ChatImageUpload", "thumbnail decode failed mime=" + sourceMime, thumbError);
                    }
                    final File finalUploadFile = uploadFile;
                    final File finalStagedFile = stagedFile;
                    final File finalConvertedFile = convertedFile;
                    final String finalUploadMime = uploadMime;
                    final byte[] finalThumb = thumb;
                    HttpUtil.StreamProvider provider = new HttpUtil.StreamProvider() {
                        @Override
                        public InputStream open() throws Exception {
                            return new FileInputStream(finalUploadFile);
                        }

                        @Override
                        public long length() {
                            return finalUploadFile.length();
                        }
                    };
                    HttpUtil.postMultipartStreamWithThumb("/media", provider,
                            "image" + extensionForMime(finalUploadMime), finalUploadMime,
                            finalThumb, finalThumb == null ? null : "thumb.jpg",
                            finalThumb == null ? null : "image/jpeg", token,
                            new HttpUtil.ProgressCallback() {
                                private long lastUiAt;

                                @Override
                                public void onProgress(long written, long total) {
                                    if (imageUploadListener == null || total <= 0) {
                                        return;
                                    }
                                    // 限流：每 120ms 最多回调一次 UI，避免 8KB/次的高频
                                    // 进度导致 notifyItemChanged + 缓存入队风暴（大图 5MB=640 次）。
                                    long now = System.currentTimeMillis();
                                    if (now - lastUiAt < 120L) {
                                        return;
                                    }
                                    lastUiAt = now;
                                    int percent = (int) ((written * 100) / total);
                                    imageUploadListener.onProgress(localId, percent);
                                }
                            },
                            new HttpUtil.Callback() {
                                @Override
                                public void onSuccess(String response) {
                                    try {
                                        JSONObject obj = new JSONObject(response);
                                        String url = obj.optString("url");
                                        String thumbUrl = obj.optString("thumb_url");
                                        String originalUrl = obj.optString("original_url");
                                        if (imageUploadListener != null) {
                                            imageUploadListener.onUploaded(localId, url, thumbUrl, originalUrl);
                                        } else if (mediaCallback != null) {
                                            mediaCallback.onMediaReady("image", url, thumbUrl, 0);
                                        }
                                    } catch (Exception e) {
                                        if (imageUploadListener != null) {
                                            imageUploadListener.onFailed(localId, "发送图片失败");
                                        }
                                        Toast.makeText(activity, "发送图片失败", Toast.LENGTH_SHORT).show();
                                    }
                                    cleanup(finalStagedFile, finalConvertedFile);
                                    notifySending(sendStateListener, false);
                                }

                                @Override
                                public void onError(int code, String error) {
                                    cleanup(finalStagedFile, finalConvertedFile);
                                    notifySending(sendStateListener, false);
                                    if (imageUploadListener != null) {
                                        imageUploadListener.onFailed(localId, error);
                                    }
                                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                                        return;
                                    }
                                    if (code == 413 || (error != null && error.contains("image_too_large"))) {
                                        Toast.makeText(activity, "图片不能超过10MB", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    if (code == -1 || code == 0) {
                                        Toast.makeText(activity, "网络异常，请检查网络后重试", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    Toast.makeText(activity, "发送图片失败: " + code, Toast.LENGTH_SHORT).show();
                                }
                            });
                } catch (Exception e) {
                    Log.e("ChatImageUpload", "prepare failed uri=" + uri + " type="
                            + resolveImageMime(activity.getContentResolver(), uri), e);
                    cleanup(stagedFile, convertedFile);
                    String detail = e.getMessage();
                    String message = detail != null && detail.contains("image_too_large")
                            ? "图片不能超过10MB"
                            : (detail != null && detail.contains("unsupported_image_format")
                            ? "该图片格式无法读取，请转为 JPG 或 PNG 后重试" : "读取图片失败，请重新选择");
                    postFailure(activity, sendStateListener, imageUploadListener, localId, message);
                }
            }
        }).start();
    }

    private static File stageUriToCache(Activity activity, Uri uri, int maxBytes) throws Exception {
        File output = new File(activity.getCacheDir(), "chat_selected_" + System.nanoTime() + ".img");
        InputStream input = null;
        FileOutputStream stream = null;
        boolean complete = false;
        try {
            input = activity.getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new IllegalStateException("cannot_open_content_uri");
            }
            stream = new FileOutputStream(output);
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("image_too_large");
                }
                stream.write(buffer, 0, read);
            }
            stream.flush();
            if (total <= 0) {
                throw new IllegalStateException("empty_image");
            }
            complete = true;
            return output;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
            if (!complete && output.exists()) output.delete();
        }
    }

    private static String resolveImageMime(ContentResolver resolver, Uri uri) {
        String mime = null;
        try { mime = resolver.getType(uri); } catch (Exception ignored) { }
        return mime == null ? "" : mime.trim().toLowerCase();
    }

    private static String normalizeDirectUploadMime(String mime) {
        if ("image/jpeg".equals(mime) || "image/jpg".equals(mime)) return "image/jpeg";
        if ("image/png".equals(mime)) return "image/png";
        if ("image/gif".equals(mime)) return "image/gif";
        // WebP 在 Android 2.x 无法可靠显示；HEIC/HEIF/AVIF 也需要先转成 JPEG。
        return null;
    }

    private static String extensionForMime(String mime) {
        if ("image/png".equals(mime)) return ".png";
        if ("image/gif".equals(mime)) return ".gif";
        return ".jpg";
    }

    private static void writeBytes(File file, byte[] data) throws Exception {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(data);
            output.flush();
        } finally {
            output.close();
        }
    }

    private static void cleanup(File first, File second) {
        if (first != null && first.exists()) first.delete();
        if (second != null && second.exists() && !second.equals(first)) second.delete();
    }

    private static long querySize(Activity activity, Uri uri) {
        try {
            android.content.res.AssetFileDescriptor fd = activity.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (fd == null) {
                return 0;
            }
            try {
                return fd.getLength();
            } finally {
                fd.close();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static void postFailure(final Activity activity,
                                    final ChatMediaHelper.SendStateListener sendStateListener,
                                    final ChatMediaHelper.ImageUploadListener imageUploadListener,
                                    final String localId,
                                    final String message) {
        if (activity == null) {
            notifySending(sendStateListener, false);
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (imageUploadListener != null) {
                    imageUploadListener.onFailed(localId, message);
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                notifySending(sendStateListener, false);
            }
        });
    }

    private static void notifySending(ChatMediaHelper.SendStateListener sendStateListener, boolean sending) {
        if (sendStateListener != null) {
            sendStateListener.onSendState(sending);
        }
    }

    private static String buildLocalId() {
        return "local_image_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 1000));
    }
}
