package com.im.oldchat.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

import com.im.oldchat.R;

final class ChatBurnModeQuickActionHelper {
    private static final int[] BURN_SECONDS_OPTIONS = new int[]{5, 10, 20};

    interface ModeChangeCallback {
        void onModeChanged(boolean enabled);
    }

    private ChatBurnModeQuickActionHelper() {
    }

    static void showBurnModePicker(final Activity activity, final ModeChangeCallback callback) {
        if (activity == null) {
            notifyMode(callback, false);
            return;
        }
        final CharSequence[] labels = buildOptionLabels();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.chat_burn_mode_picker_title);
        builder.setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which < 0 || which >= BURN_SECONDS_OPTIONS.length) {
                    return;
                }
                int seconds = BURN_SECONDS_OPTIONS[which];
                applySeconds(activity, seconds);
                showToast(activity, seconds);
                notifyMode(callback, seconds > 0);
                dialog.dismiss();
            }
        });
        builder.setNegativeButton(R.string.chat_burn_mode_picker_close, null);
        builder.setNeutralButton(R.string.chat_burn_mode_turn_off, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                applySeconds(activity, 0);
                showToast(activity, 0);
                notifyMode(callback, false);
            }
        });
        builder.show();
    }

    static boolean isEnabled(Activity activity) {
        if (activity instanceof ChatActivitySupport) {
            ChatActivitySupport support = (ChatActivitySupport) activity;
            return support.messageSender != null && support.messageSender.isBurnModeEnabled();
        }
        if (activity instanceof GroupChatActivitySupport0) {
            GroupChatActivitySupport0 support = (GroupChatActivitySupport0) activity;
            return support.messageSender != null && support.messageSender.isBurnModeEnabled();
        }
        return false;
    }

    static void syncHint(Activity activity) {
        if (activity == null) {
            return;
        }
        if (activity instanceof ChatActivitySupport) {
            ChatActivitySupport support = (ChatActivitySupport) activity;
            int seconds = support.messageSender == null ? 0 : support.messageSender.getBurnAfterSeconds();
            applyHint(support.etInput, activity, seconds);
            return;
        }
        if (activity instanceof GroupChatActivitySupport0) {
            GroupChatActivitySupport0 support = (GroupChatActivitySupport0) activity;
            int seconds = support.messageSender == null ? 0 : support.messageSender.getBurnAfterSeconds();
            applyHint(support.etInput, activity, seconds);
        }
    }

    private static void applyHint(EditText input, Activity activity, int seconds) {
        if (input == null || activity == null) {
            return;
        }
        if (seconds > 0) {
            input.setHint(activity.getString(R.string.chat_input_hint_burn, seconds));
        } else {
            input.setHint(activity.getString(R.string.chat_input_hint));
        }
    }

    private static void showToast(Activity activity, int seconds) {
        if (activity == null) {
            return;
        }
        if (seconds > 0) {
            Toast.makeText(activity, activity.getString(R.string.chat_burn_mode_enabled, seconds), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, R.string.chat_burn_mode_disabled, Toast.LENGTH_SHORT).show();
    }

    private static int getCurrentSeconds(Activity activity) {
        if (activity instanceof ChatActivitySupport) {
            ChatActivitySupport support = (ChatActivitySupport) activity;
            if (support.messageSender != null) {
                return support.messageSender.getBurnAfterSeconds();
            }
        }
        if (activity instanceof GroupChatActivitySupport0) {
            GroupChatActivitySupport0 support = (GroupChatActivitySupport0) activity;
            if (support.messageSender != null) {
                return support.messageSender.getBurnAfterSeconds();
            }
        }
        return 0;
    }

    private static void applySeconds(Activity activity, int seconds) {
        if (activity instanceof ChatActivitySupport) {
            ChatActivitySupport support = (ChatActivitySupport) activity;
            if (support.messageSender != null) {
                support.messageSender.setBurnAfterSeconds(seconds);
                applyHint(support.etInput, activity, support.messageSender.getBurnAfterSeconds());
            }
            return;
        }
        if (activity instanceof GroupChatActivitySupport0) {
            GroupChatActivitySupport0 support = (GroupChatActivitySupport0) activity;
            if (support.messageSender != null) {
                support.messageSender.setBurnAfterSeconds(seconds);
                applyHint(support.etInput, activity, support.messageSender.getBurnAfterSeconds());
            }
        }
    }

    private static CharSequence[] buildOptionLabels() {
        CharSequence[] labels = new CharSequence[BURN_SECONDS_OPTIONS.length];
        for (int i = 0; i < BURN_SECONDS_OPTIONS.length; i++) {
            labels[i] = BURN_SECONDS_OPTIONS[i] + "秒";
        }
        return labels;
    }

    private static void notifyMode(ModeChangeCallback callback, boolean enabled) {
        if (callback != null) {
            callback.onModeChanged(enabled);
        }
    }
}
