package com.im.oldchat.ui;

import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.VideoUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

class ChatMediaHelperVideoSupport {
    protected static final int REQ_PICK_IMAGE = 2001;
    protected static final int REQ_RECORD_AUDIO = 2002;
    protected static final int REQ_STORAGE = 2003;
    protected static final int REQ_PICK_VIDEO = 2004;
    protected static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    protected static final int MAX_IMAGE_UPLOAD_BYTES = 15 * 1024 * 1024;
    protected static final long MAX_VIDEO_BYTES = 50L * 1024L * 1024L;
    protected static final long MAX_VIDEO_THUMB_BYTES = 200 * 1024;
    protected static final int MAX_VIDEO_THUMB_PX = 360;
    protected static final int PICK_NONE = 0;
    protected static final int PICK_IMAGE = 1;
    protected static final int PICK_VIDEO = 2;
    protected static final long MAX_RECORD_MS = 60000;

    protected final Activity activity;
    protected final String token;
    protected final EditText etInput;
    protected final Button btnSend;
    protected final View btnToggleVoice;
    protected final View btnPlus;
    protected final View btnHoldToTalk;
    protected View actionPanel;
    protected View btnActionImage;
    protected View btnActionVideo;
    protected final ChatMediaHelper.MediaCallback callback;
    protected final ChatMediaHelper.SendStateListener sendStateListener;
    protected ChatMediaHelper.ImageUploadListener imageUploadListener;
    protected final View videoProgressContainer;
    protected final ProgressBar videoProgressBar;
    protected final TextView videoProgressText;
    protected final ExecutorService videoExecutor = Executors.newSingleThreadExecutor();
    protected final AtomicBoolean videoProcessing = new AtomicBoolean(false);

    protected boolean isVoiceMode = false;
    protected boolean actionsVisible = false;
    protected android.media.MediaRecorder recorder;
    protected File recordingFile;
    protected long recordStartMs = 0;
    protected final android.os.Handler recordHandler = new android.os.Handler();
    protected Runnable recordTimeout;
    protected boolean recordLimitReached = false;
    protected int pendingPick = PICK_NONE;

    ChatMediaHelperVideoSupport(Activity activity, String token, EditText etInput, Button btnSend,
                                View btnToggleVoice, View btnPlus, View btnHoldToTalk, View actionPanel,
                                View btnActionImage, View btnActionVideo, View videoProgressContainer,
                                ProgressBar videoProgressBar, TextView videoProgressText,
                                ChatMediaHelper.MediaCallback callback,
                                ChatMediaHelper.SendStateListener sendStateListener) {
        this.activity = activity;
        this.token = token;
        this.etInput = etInput;
        this.btnSend = btnSend;
        this.btnToggleVoice = btnToggleVoice;
        this.btnPlus = btnPlus;
        this.btnHoldToTalk = btnHoldToTalk;
        this.actionPanel = actionPanel;
        this.btnActionImage = btnActionImage;
        this.btnActionVideo = btnActionVideo;
        this.videoProgressContainer = videoProgressContainer;
        this.videoProgressBar = videoProgressBar;
        this.videoProgressText = videoProgressText;
        this.callback = callback;
        this.sendStateListener = sendStateListener;
    }

    protected void stopRecording(boolean send) {
    }

    protected void uploadVideo(final Uri uri) {
        if (uri == null) {
            return;
        }
        if (videoProcessing.getAndSet(true)) {
            Toast.makeText(activity, "视频处理中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        notifySending(true);
        showVideoProgress("视频处理中...", true, 0);
        final String mime = resolveVideoMime(uri);
        if (mime == null || !isSupportedVideoMime(mime)) {
            hideVideoProgress();
            videoProcessing.set(false);
            notifySending(false);
            Toast.makeText(activity, "仅支持MP4/3GP视频", Toast.LENGTH_SHORT).show();
            return;
        }
        videoExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File videoFile = null;
                File thumbFile = null;
                int durationMs = 0;
                boolean ready = false;
                try {
                    videoFile = VideoUtils.prepareVideo(activity, uri, MAX_VIDEO_BYTES, 720, mime);
                    if (videoFile == null || !videoFile.exists()) {
                        postToast("读取视频失败");
                        return;
                    }
                    if (videoFile.length() > MAX_VIDEO_BYTES) {
                        postToast("视频太大，请压缩后再发(需<50MB)");
                        return;
                    }
                    long duration = VideoUtils.getDurationMs(activity, videoFile);
                    durationMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0, duration));
                    thumbFile = VideoUtils.buildVideoThumbnail(activity, videoFile,
                            MAX_VIDEO_THUMB_PX, 85, MAX_VIDEO_THUMB_BYTES);
                    ready = true;
                } finally {
                    if (!ready) {
                        cleanupVideoFiles(videoFile, thumbFile);
                        finishVideoTask();
                    }
                }
                if (!ready) {
                    return;
                }
                showVideoProgress("上传中 0%", false, 0);
                uploadVideoInternal(videoFile, thumbFile, durationMs, mime);
            }
        });
    }

    private void uploadVideoInternal(final File videoFile, final File thumbFile, final int durationMs,
                                     final String mime) {
        byte[] thumbData = null;
        String thumbName = null;
        String thumbType = null;
        if (thumbFile != null && thumbFile.exists()) {
            try {
                thumbData = readAllBytes(thumbFile);
                thumbName = thumbFile.getName();
                thumbType = "image/jpeg";
            } catch (Exception e) {
                thumbData = null;
                thumbName = null;
                thumbType = null;
            }
        }
        final byte[] thumbPayload = thumbData;
        final String thumbPayloadName = thumbName;
        final String thumbPayloadType = thumbType;
        final String contentType = mime != null && mime.startsWith("video/") ? mime : "video/mp4";
        HttpUtil.StreamProvider provider = new HttpUtil.StreamProvider() {
            @Override
            public InputStream open() throws Exception {
                return new java.io.FileInputStream(videoFile);
            }

            @Override
            public long length() {
                return videoFile.length();
            }
        };
        HttpUtil.postMultipartStreamWithThumb("/media", provider, videoFile.getName(), contentType,
                thumbPayload, thumbPayloadName, thumbPayloadType, token,
                new HttpUtil.ProgressCallback() {
                    @Override
                    public void onProgress(final long written, final long total) {
                        int percent = total > 0 ? (int) ((written * 100) / total) : 0;
                        showVideoProgress("上传中 " + percent + "%", false, percent);
                    }
                },
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        try {
                            hideVideoProgress();
                            JSONObject obj = new JSONObject(response);
                            String url = obj.optString("url");
                            String thumbUrl = obj.optString("thumb_url");
                            if (callback != null) {
                                callback.onMediaReady("video", url, thumbUrl, durationMs);
                            }
                        } catch (Exception e) {
                            Toast.makeText(activity, "发送视频失败", Toast.LENGTH_SHORT).show();
                        }
                        cleanupVideoFiles(videoFile, thumbFile);
                        finishVideoTask();
                    }

                    @Override
                    public void onError(int code, String error) {
                        hideVideoProgress();
                        cleanupVideoFiles(videoFile, thumbFile);
                        finishVideoTask();
                        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                            return;
                        }
                        Toast.makeText(activity, "发送视频失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void finishVideoTask() {
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideVideoProgress();
                    notifySending(false);
                    videoProcessing.set(false);
                }
            });
        } else {
            hideVideoProgress();
            notifySending(false);
            videoProcessing.set(false);
        }
    }

    private void showVideoProgress(final String text, final boolean indeterminate, final int percent) {
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (videoProgressBar != null) {
                    videoProgressBar.setVisibility(View.VISIBLE);
                    videoProgressBar.setIndeterminate(indeterminate);
                    if (!indeterminate) {
                        videoProgressBar.setProgress(Math.max(0, Math.min(percent, 100)));
                    }
                }
                if (videoProgressContainer != null) {
                    videoProgressContainer.setVisibility(View.VISIBLE);
                }
                if (videoProgressText != null) {
                    videoProgressText.setVisibility(View.VISIBLE);
                    videoProgressText.setText(text == null ? "" : text);
                }
            }
        });
    }

    private void hideVideoProgress() {
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (videoProgressBar != null) {
                    videoProgressBar.setVisibility(View.GONE);
                }
                if (videoProgressContainer != null) {
                    videoProgressContainer.setVisibility(View.GONE);
                }
                if (videoProgressText != null) {
                    videoProgressText.setVisibility(View.GONE);
                }
            }
        });
    }

    private void cleanupVideoFiles(File videoFile, File thumbFile) {
        if (videoFile != null && videoFile.exists()) {
            videoFile.delete();
        }
        if (thumbFile != null && thumbFile.exists()) {
            thumbFile.delete();
        }
    }

    private void postToast(final String message) {
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected String resolveVideoMime(Uri uri) {
        if (activity == null || uri == null) {
            return null;
        }
        String mime = activity.getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("video/")) {
            return mime;
        }
        String path = uri.getPath();
        if (path != null) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".3gp") || lower.endsWith(".3gpp")) {
                return "video/3gpp";
            }
            if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
                return "video/mp4";
            }
        }
        return null;
    }

    protected boolean isSupportedVideoMime(String mime) {
        if (mime == null) {
            return false;
        }
        String lower = mime.toLowerCase();
        return lower.contains("mp4") || lower.contains("3gpp") || lower.contains("3gp");
    }

    protected byte[] readAllBytes(File file) throws Exception {
        InputStream is = new java.io.FileInputStream(file);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        is.close();
        return baos.toByteArray();
    }

    protected void notifySending(boolean sending) {
        if (sendStateListener != null) {
            sendStateListener.onSendState(sending);
        }
    }
}
