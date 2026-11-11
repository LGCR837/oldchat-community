package com.im.oldchat.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;

import java.io.File;

class ChatMediaHelperVoiceSupport extends ChatMediaHelperVoiceOptionSupport {
    private static final long MIN_RECORD_SEND_MS = 800L;
    private static final String DEFAULT_RECORD_EXT = ".3gp";

    private static final class RecordProfile {
        final String extension;
        final int outputFormat;
        final int audioEncoder;

        RecordProfile(String extension, int outputFormat, int audioEncoder) {
            this.extension = extension;
            this.outputFormat = outputFormat;
            this.audioEncoder = audioEncoder;
        }
    }

    private static final RecordProfile[] RECORD_PROFILES = new RecordProfile[]{
            new RecordProfile(".m4a",
                    android.media.MediaRecorder.OutputFormat.MPEG_4,
                    android.media.MediaRecorder.AudioEncoder.AAC),
            new RecordProfile(".3gp",
                    android.media.MediaRecorder.OutputFormat.THREE_GPP,
                    android.media.MediaRecorder.AudioEncoder.AMR_NB)
    };

    private boolean pendingRecordAfterPermission = false;

    ChatMediaHelperVoiceSupport(Activity activity, String token, EditText etInput, Button btnSend,
                                View btnToggleVoice, View btnPlus, View btnHoldToTalk, View actionPanel,
                                View btnActionImage, View btnActionVideo, View videoProgressContainer,
                                ProgressBar videoProgressBar, TextView videoProgressText,
                                ChatMediaHelper.MediaCallback callback,
                                ChatMediaHelper.SendStateListener sendStateListener) {
        super(activity, token, etInput, btnSend, btnToggleVoice, btnPlus, btnHoldToTalk, actionPanel,
                btnActionImage, btnActionVideo, videoProgressContainer, videoProgressBar,
                videoProgressText, callback, sendStateListener);
    }

    protected boolean onHoldToTalkTouch(View touchView, MotionEvent event) {
        if (event == null) {
            return true;
        }
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            pendingRecordAfterPermission = true;
            recordOptionPending = false;
            downRawY = event.getRawY();
            if (ensureAudioPermission()) {
                setHoldToTalkPressed(true);
                startRecording();
            } else {
                setHoldToTalkPressed(false);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (recorder != null) {
                updateRecordOptionState(touchView, event);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pendingRecordAfterPermission = false;
            boolean hadRecorder = recorder != null;
            boolean canceledBySystem = action == MotionEvent.ACTION_CANCEL;
            if (canceledBySystem) {
                recordOptionPending = false;
            }
            setHoldToTalkPressed(false);
            stopRecording(!canceledBySystem);
            resetRecordGestureState();
            if (canceledBySystem && hadRecorder) {
                Toast.makeText(activity, R.string.chat_voice_send_canceled, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return true;
    }

    protected boolean handleRecordPermissionResult(int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingRecordAfterPermission) {
                setHoldToTalkPressed(true);
                startRecording();
            }
        } else {
            setHoldToTalkPressed(false);
            Toast.makeText(activity, R.string.call_permission_needed, Toast.LENGTH_SHORT).show();
        }
        pendingRecordAfterPermission = false;
        return true;
    }

    protected void onPauseVoice() {
        if (recorder != null) {
            stopRecording(false);
        }
        discardPendingVoiceOptionFile(false);
    }

    private boolean ensureAudioPermission() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        return false;
    }

    private void setHoldToTalkPressed(boolean pressed) {
        if (btnHoldToTalk == null) {
            return;
        }
        btnHoldToTalk.setPressed(pressed);
        ViewCompat.setAlpha(btnHoldToTalk, pressed ? 0.68f : 1.0f);
    }

    private void startRecording() {
        if (recorder != null) {
            return;
        }
        File dir = new File(activity.getCacheDir(), "voice");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        boolean started = false;
        for (int i = 0; i < RECORD_PROFILES.length; i++) {
            // AAC 编码器从 API 10 才可用；Android 2.3(API 9)直接使用 AMR_NB，
            // 避免部分厂商 MediaRecorder 在 prepare 阶段 native 崩溃。
            if (i == 0 && android.os.Build.VERSION.SDK_INT < 10) {
                continue;
            }
            if (tryStartRecordingWithProfile(dir, RECORD_PROFILES[i])) {
                started = true;
                break;
            }
        }
        if (!started && tryStartRecordingWithProfile(dir, new RecordProfile(DEFAULT_RECORD_EXT,
                android.media.MediaRecorder.OutputFormat.THREE_GPP,
                android.media.MediaRecorder.AudioEncoder.AMR_NB))) {
            started = true;
        }
        if (!started) {
            recorder = null;
            recordingFile = null;
            recordOptionPending = false;
            setHoldToTalkPressed(false);
            Toast.makeText(activity, R.string.chat_voice_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        recordStartMs = System.currentTimeMillis();
        recordLimitReached = false;
        scheduleRecordTimeout();
        if (btnHoldToTalk instanceof TextView) {
            ((TextView) btnHoldToTalk).setText(R.string.chat_hold_release);
        }
    }

    private boolean tryStartRecordingWithProfile(File dir, RecordProfile profile) {
        android.media.MediaRecorder candidate = null;
        File target = null;
        try {
            String ext = profile == null || profile.extension == null || profile.extension.length() == 0
                    ? DEFAULT_RECORD_EXT : profile.extension;
            target = new File(dir, "voice_" + System.currentTimeMillis() + ext);
            candidate = new android.media.MediaRecorder();
            candidate.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            candidate.setOutputFormat(profile.outputFormat);
            candidate.setAudioEncoder(profile.audioEncoder);
            candidate.setOutputFile(target.getAbsolutePath());
            candidate.prepare();
            candidate.start();
            recorder = candidate;
            recordingFile = target;
            return true;
        } catch (Throwable e) {
            if (candidate != null) {
                try {
                    candidate.release();
                } catch (Throwable ignored) {
                }
            }
            if (target != null && target.exists()) {
                target.delete();
            }
            return false;
        }
    }

    @Override
    protected void stopRecording(boolean send) {
        if (recorder == null) {
            setHoldToTalkPressed(false);
            recordOptionPending = false;
            if (btnHoldToTalk instanceof TextView) {
                ((TextView) btnHoldToTalk).setText(R.string.chat_hold_to_talk);
            }
            return;
        }
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        recorder.release();
        recorder = null;
        cancelRecordTimeout();
        setHoldToTalkPressed(false);
        if (btnHoldToTalk instanceof TextView) {
            ((TextView) btnHoldToTalk).setText(R.string.chat_hold_to_talk);
        }
        long duration = System.currentTimeMillis() - recordStartMs;
        if (duration > MAX_RECORD_MS) {
            duration = MAX_RECORD_MS;
        }
        boolean shouldOpenOption = send && recordOptionPending && !recordLimitReached;
        recordOptionPending = false;
        if (!send) {
            deleteRecordingFile();
            return;
        }
        if (duration < MIN_RECORD_SEND_MS) {
            deleteRecordingFile();
            Toast.makeText(activity, "Record too short", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recordingFile == null || !recordingFile.exists()) {
            return;
        }
        if (shouldOpenOption) {
            showVoiceOptionDialog(recordingFile);
            return;
        }
        uploadVoice(recordingFile, duration);
    }
}
