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
import com.im.oldchat.util.ImageCompressUtil;

import org.json.JSONObject;

import java.io.File;

class ChatMediaHelperSupport extends ChatMediaHelperVideoSupport {
    private static final int MAX_VOICE_DURATION_MS = 60000;

    ChatMediaHelperSupport(Activity activity, String token, EditText etInput, Button btnSend,
                           View btnToggleVoice, View btnPlus, View btnHoldToTalk, View actionPanel,
                           View btnActionImage, View btnActionVideo, View videoProgressContainer,
                           ProgressBar videoProgressBar, TextView videoProgressText,
                           ChatMediaHelper.MediaCallback callback,
                           ChatMediaHelper.SendStateListener sendStateListener) {
        super(activity, token, etInput, btnSend, btnToggleVoice, btnPlus, btnHoldToTalk, actionPanel,
                btnActionImage, btnActionVideo, videoProgressContainer, videoProgressBar,
                videoProgressText, callback, sendStateListener);
    }

    protected void scheduleRecordTimeout() {
        cancelRecordTimeout();
        recordTimeout = new Runnable() {
            @Override
            public void run() {
                if (recorder != null) {
                    recordLimitReached = true;
                    stopRecording(true);
                }
            }
        };
        recordHandler.postDelayed(recordTimeout, MAX_RECORD_MS);
    }

    protected void cancelRecordTimeout() {
        if (recordTimeout != null) {
            recordHandler.removeCallbacks(recordTimeout);
            recordTimeout = null;
        }
    }

    protected void uploadVoice(File file, long durationMs) {
        int safeDuration = (int) durationMs;
        if (safeDuration > MAX_VOICE_DURATION_MS) {
            safeDuration = MAX_VOICE_DURATION_MS;
        }
        if (safeDuration < 1) {
            safeDuration = 1;
        }
        final int durationFinal = safeDuration;
        try {
            notifySending(true);
            byte[] data = readAllBytes(file);
            final String uploadName = resolveVoiceUploadFileName(file);
            final String uploadType = resolveVoiceContentType(uploadName);
            HttpUtil.postMultipart("/media", data, uploadName, uploadType, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        String url = obj.optString("url");
                        if (callback != null) {
                            callback.onMediaReady("voice", url, "", durationFinal);
                        }
                    } catch (Exception e) {
                        Toast.makeText(activity, "发送语音失败", Toast.LENGTH_SHORT).show();
                    }
                    notifySending(false);
                }

                @Override
                public void onError(int code, String error) {
                    notifySending(false);
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    String lower = error == null ? "" : error.toLowerCase();
                    if (code == 403) {
                        if (lower.contains("video_disabled")) {
                            Toast.makeText(activity, "服务器禁用了视频/3GP上传，请联系管理员", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (lower.contains("user_banned")) {
                            Toast.makeText(activity, "账号已被封禁，无法发送语音", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    Toast.makeText(activity, "发送语音失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(activity, "发送语音失败", Toast.LENGTH_SHORT).show();
            notifySending(false);
        }
    }

    private String resolveVoiceUploadFileName(File file) {
        if (file != null) {
            String name = file.getName();
            if (name != null) {
                name = name.trim();
                if (name.length() > 0) {
                    return name;
                }
            }
        }
        return "voice.3gp";
    }

    protected String resolveVoiceContentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        if (lower.endsWith(".amr")) {
            return "audio/amr";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        return "audio/3gpp";
    }

    protected void uploadImage(Uri uri) {
        ChatImageUploadCoordinator.upload(activity, token, uri, callback, sendStateListener,
                imageUploadListener, MAX_IMAGE_BYTES, MAX_IMAGE_UPLOAD_BYTES);
    }
}
