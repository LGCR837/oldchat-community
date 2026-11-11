package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

final class ChatDraftHelper {
    private static final long SAVE_DELAY_MS = 420L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object PENDING_GUARD = new Object();
    private static final Map<String, Runnable> PENDING_SAVES = new HashMap<String, Runnable>();

    private ChatDraftHelper() {
    }

    static String restoreDraft(Context context, EditText editText, String key) {
        if (context == null || editText == null || key == null || key.length() == 0) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences("drafts", Context.MODE_PRIVATE);
        String draft = prefs.getString(key, "");
        if (draft == null || draft.length() == 0) {
            return "";
        }
        Editable current = editText.getText();
        if (current != null && current.length() > 0) {
            return current.toString();
        }
        editText.setText(draft);
        editText.setSelection(draft.length());
        return draft;
    }

    static String saveDraft(Context context, String key, String text, String lastDraft) {
        if (context == null || key == null || key.length() == 0) {
            return lastDraft == null ? "" : lastDraft;
        }
        String value = text == null ? "" : text;
        String old = lastDraft == null ? "" : lastDraft;
        if (value.equals(old)) {
            return old;
        }
        persistDraft(context, key, value, false);
        return value;
    }

    static String saveDraftSync(Context context, String key, String text, String lastDraft) {
        if (context == null || key == null || key.length() == 0) {
            return lastDraft == null ? "" : lastDraft;
        }
        String value = text == null ? "" : text;
        String old = lastDraft == null ? "" : lastDraft;
        if (value.equals(old)) {
            persistDraft(context, key, value, true);
            return value;
        }
        persistDraft(context, key, value, true);
        return value;
    }

    static String scheduleSaveDraft(final Context context, final String key, final String text,
                                    String lastDraft) {
        if (context == null || key == null || key.length() == 0) {
            return lastDraft == null ? "" : lastDraft;
        }
        final String value = text == null ? "" : text;
        String old = lastDraft == null ? "" : lastDraft;
        if (value.equals(old)) {
            return old;
        }
        cancelPending(key);
        final Context appContext = context.getApplicationContext();
        Runnable saveRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (PENDING_GUARD) {
                    PENDING_SAVES.remove(key);
                }
                saveDraft(appContext, key, value, null);
            }
        };
        synchronized (PENDING_GUARD) {
            PENDING_SAVES.put(key, saveRunnable);
        }
        MAIN_HANDLER.postDelayed(saveRunnable, SAVE_DELAY_MS);
        return value;
    }

    static void flushPending(String key) {
        if (key == null || key.length() == 0) {
            return;
        }
        Runnable pending;
        synchronized (PENDING_GUARD) {
            pending = PENDING_SAVES.remove(key);
        }
        if (pending != null) {
            MAIN_HANDLER.removeCallbacks(pending);
            pending.run();
        }
    }

    static String clearDraft(Context context, String key) {
        if (context == null || key == null || key.length() == 0) {
            return "";
        }
        cancelPending(key);
        SharedPreferences prefs = context.getSharedPreferences("drafts", Context.MODE_PRIVATE);
        prefs.edit().remove(key).commit();
        return "";
    }

    private static void cancelPending(String key) {
        Runnable pending;
        synchronized (PENDING_GUARD) {
            pending = PENDING_SAVES.remove(key);
        }
        if (pending != null) {
            MAIN_HANDLER.removeCallbacks(pending);
        }
    }

    static String saveDraftFromInput(Context context, EditText editText, String key, String lastDraft) {
        if (editText == null) {
            return lastDraft == null ? "" : lastDraft;
        }
        Editable editable = editText.getText();
        String text = editable == null ? "" : editable.toString();
        cancelPending(key);
        return saveDraftSync(context, key, text, lastDraft);
    }

    private static void persistDraft(Context context, String key, String value, boolean sync) {
        SharedPreferences prefs = context.getSharedPreferences("drafts", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (value == null || value.trim().length() == 0) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
        if (sync) {
            editor.commit();
        } else {
            editor.apply();
        }
    }
}
