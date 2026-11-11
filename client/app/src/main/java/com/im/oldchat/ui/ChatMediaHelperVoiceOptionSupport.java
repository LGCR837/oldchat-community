package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.util.SiliconFlowAsrClient;

import java.io.File;

class ChatMediaHelperVoiceOptionSupport extends ChatMediaHelperSupport {
    private static final float VOICE_OPTION_DISTANCE_DP = 48f;
    private static final float VOICE_OPTION_MARGIN_DP = 16f;

    protected float downRawY = 0f;
    protected boolean recordOptionPending = false;
    private File pendingVoiceOptionFile;

    ChatMediaHelperVoiceOptionSupport(Activity activity, String token, EditText etInput, Button btnSend,
                                      View btnToggleVoice, View btnPlus, View btnHoldToTalk, View actionPanel,
                                      View btnActionImage, View btnActionVideo, View videoProgressContainer,
                                      ProgressBar videoProgressBar, TextView videoProgressText,
                                      ChatMediaHelper.MediaCallback callback,
                                      ChatMediaHelper.SendStateListener sendStateListener) {
        super(activity, token, etInput, btnSend, btnToggleVoice, btnPlus, btnHoldToTalk, actionPanel,
                btnActionImage, btnActionVideo, videoProgressContainer, videoProgressBar,
                videoProgressText, callback, sendStateListener);
    }

    protected void updateRecordOptionState(View touchView, MotionEvent event) {
        if (touchView == null || event == null) {
            return;
        }
        float optionDistancePx = dpToPx(VOICE_OPTION_DISTANCE_DP);
        float optionMarginPx = dpToPx(VOICE_OPTION_MARGIN_DP);
        float moveUp = downRawY - event.getRawY();
        float x = event.getX();
        float y = event.getY();
        boolean movedOut = x < -optionMarginPx
                || x > touchView.getWidth() + optionMarginPx
                || y < -optionMarginPx
                || y > touchView.getHeight() + optionMarginPx;
        boolean nextOptionPending = moveUp >= optionDistancePx || movedOut;
        if (nextOptionPending == recordOptionPending) {
            return;
        }
        recordOptionPending = nextOptionPending;
        if (btnHoldToTalk instanceof TextView) {
            ((TextView) btnHoldToTalk).setText(nextOptionPending
                    ? R.string.chat_hold_release_for_voice_option
                    : R.string.chat_hold_release);
        }
        if (btnHoldToTalk != null) {
            ViewCompat.setAlpha(btnHoldToTalk, nextOptionPending ? 0.52f : 0.68f);
        }
    }

    protected void resetRecordGestureState() {
        recordOptionPending = false;
        downRawY = 0f;
        if (btnHoldToTalk instanceof TextView) {
            ((TextView) btnHoldToTalk).setText(R.string.chat_hold_to_talk);
        }
    }

    protected void discardPendingVoiceOptionFile(boolean showToast) {
        if (pendingVoiceOptionFile != null && pendingVoiceOptionFile.exists()) {
            pendingVoiceOptionFile.delete();
        }
        pendingVoiceOptionFile = null;
        if (showToast) {
            Toast.makeText(activity, R.string.chat_voice_send_canceled, Toast.LENGTH_SHORT).show();
        }
    }

    protected void deleteRecordingFile() {
        if (recordingFile != null && recordingFile.exists()) {
            recordingFile.delete();
        }
    }

    protected void showVoiceOptionDialog(File voiceFile) {
        pendingVoiceOptionFile = voiceFile;
        if (activity == null || activity.isFinishing()) {
            discardPendingVoiceOptionFile(false);
            return;
        }
        CharSequence[] items = new CharSequence[]{
                activity.getString(R.string.chat_voice_option_transcribe),
                activity.getString(R.string.chat_voice_option_cancel)
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.chat_voice_option_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            transcribePendingVoiceOption();
                        } else {
                            discardPendingVoiceOptionFile(true);
                        }
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        discardPendingVoiceOptionFile(true);
                    }
                })
                .show();
    }

    private void transcribePendingVoiceOption() {
        final File voiceFile = pendingVoiceOptionFile;
        pendingVoiceOptionFile = null;
        if (voiceFile == null || !voiceFile.exists()) {
            Toast.makeText(activity, R.string.chat_voice_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final byte[] data;
        try {
            data = readAllBytes(voiceFile);
        } catch (Exception e) {
            voiceFile.delete();
            Toast.makeText(activity, R.string.chat_voice_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, R.string.chat_voice_transcribing, Toast.LENGTH_SHORT).show();
        notifySending(true);
        String contentType = resolveVoiceContentType(voiceFile.getName());
        SiliconFlowAsrClient.transcribe(token, data, voiceFile.getName(), contentType,
                new SiliconFlowAsrClient.Callback() {
                    @Override
                    public void onSuccess(String text) {
                        notifySending(false);
                        voiceFile.delete();
                        String safe = text == null ? "" : text.trim();
                        if (safe.length() == 0) {
                            Toast.makeText(activity, R.string.chat_voice_transcribe_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        placeTranscribedText(safe);
                        showTranscribedTextActionDialog();
                    }

                    @Override
                    public void onError(String message) {
                        notifySending(false);
                        voiceFile.delete();
                        showTranscribeError(message);
                    }
                });
    }

    private void placeTranscribedText(String text) {
        if (text == null) {
            return;
        }
        String content = text.trim();
        if (content.length() == 0) {
            return;
        }
        if (etInput != null) {
            etInput.requestFocus();
            etInput.setText(content);
            etInput.setSelection(content.length());
        }
    }

    private void showTranscribedTextActionDialog() {
        if (activity == null) {
            return;
        }
        if (activity.isFinishing()) {
            Toast.makeText(activity, R.string.chat_voice_transcribe_filled, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.chat_voice_transcribe_ready_title)
                .setMessage(R.string.chat_voice_transcribe_ready_message)
                .setNegativeButton(R.string.chat_voice_transcribe_edit,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                focusInputAndShowKeyboard();
                            }
                        });
        if (btnSend != null) {
            builder.setPositiveButton(R.string.chat_voice_transcribe_send_now,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            btnSend.performClick();
                        }
                    });
        }
        builder.show();
    }

    private void focusInputAndShowKeyboard() {
        if (activity == null || etInput == null) {
            return;
        }
        etInput.requestFocus();
        CharSequence content = etInput.getText();
        int end = content == null ? 0 : content.length();
        etInput.setSelection(end);
        Object service = activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (!(service instanceof InputMethodManager)) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) service;
        imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void showTranscribeError(String message) {
        String detail = message == null ? "" : message.trim();
        if (detail.length() == 0
                || "asr_failed".equalsIgnoreCase(detail)
                || "http_502".equalsIgnoreCase(detail)
                || "invalid_response".equalsIgnoreCase(detail)) {
            Toast.makeText(activity, R.string.chat_voice_transcribe_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (detail.length() > 32) {
            detail = detail.substring(0, 32) + "...";
        }
        Toast.makeText(activity, "转写失败: " + detail, Toast.LENGTH_SHORT).show();
    }

    private float dpToPx(float dp) {
        if (activity == null) {
            return dp;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        return dp * density;
    }
}
