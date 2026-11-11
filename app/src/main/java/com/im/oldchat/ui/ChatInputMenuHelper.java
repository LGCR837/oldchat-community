package com.im.oldchat.ui;

import android.os.Build;
import android.text.Editable;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import com.im.oldchat.util.SettingsPrefs;

final class ChatInputMenuHelper {
    static final int MENU_ID_NEWLINE = 9301;

    interface SendAction {
        void onSend();
    }

    private ChatInputMenuHelper() {
    }

    static void bind(final EditText editText, final SendAction sendAction) {
        if (editText == null) {
            return;
        }
        applyEnterSendImeMode(editText);
        editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null) {
                    return false;
                }
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    if (!isEnterSendEnabled(editText)) {
                        return false;
                    }
                    if (sendAction != null) {
                        sendAction.onSend();
                    }
                    return true;
                }
                return false;
            }
        });
        editText.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event != null && event.getAction() == KeyEvent.ACTION_UP) {
                    if (!isEnterSendEnabled(editText)) {
                        return false;
                    }
                    if (sendAction != null) {
                        sendAction.onSend();
                    }
                    return true;
                }
                return false;
            }
        });
        if (Build.VERSION.SDK_INT >= 11) {
            ChatInputMenuApi11.bindSelection(editText);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            ChatInputMenuApi23.bindInsertion(editText);
        }
    }

    private static boolean isEnterSendEnabled(EditText editText) {
        return editText != null && SettingsPrefs.isEnterSendEnabled(editText.getContext());
    }

    private static void applyEnterSendImeMode(EditText editText) {
        if (editText == null) {
            return;
        }
        int imeOptions = editText.getImeOptions();
        imeOptions &= ~EditorInfo.IME_MASK_ACTION;
        imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        if (isEnterSendEnabled(editText)) {
            imeOptions |= EditorInfo.IME_ACTION_SEND;
        } else {
            imeOptions |= EditorInfo.IME_ACTION_NONE;
            imeOptions |= EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        }
        editText.setImeOptions(imeOptions);
    }

    static void fillContextMenu(ContextMenu menu) {
        if (menu == null) {
            return;
        }
        addDefaultContextMenuItems(menu);
        menu.add(0, MENU_ID_NEWLINE, 0, "换行");
    }

    static boolean handleContextItem(EditText editText, MenuItem item) {
        if (item != null && item.getItemId() == MENU_ID_NEWLINE) {
            insertNewLine(editText);
            return true;
        }
        if (item != null) {
            int id = item.getItemId();
            if (id == android.R.id.cut || id == android.R.id.copy || id == android.R.id.paste || id == android.R.id.selectAll) {
                return editText != null && editText.onTextContextMenuItem(id);
            }
        }
        return false;
    }

    static void insertNewLine(EditText editText) {
        if (editText == null) {
            return;
        }
        Editable editable = editText.getText();
        if (editable == null) {
            return;
        }
        int start = editText.getSelectionStart();
        int end = editText.getSelectionEnd();
        if (start < 0) {
            start = editable.length();
        }
        if (end < 0) {
            end = editable.length();
        }
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        editable.replace(start, end, "\n");
        int pos = start + 1;
        if (pos > editable.length()) {
            pos = editable.length();
        }
        editText.setSelection(pos);
    }

    private static void addDefaultContextMenuItems(ContextMenu menu) {
        MenuItem item = menu.add(0, android.R.id.cut, 0, "剪切");
        item.setEnabled(true);
        item = menu.add(0, android.R.id.copy, 1, "复制");
        item.setEnabled(true);
        item = menu.add(0, android.R.id.paste, 2, "粘贴");
        item.setEnabled(true);
        item = menu.add(0, android.R.id.selectAll, 3, "全选");
        item.setEnabled(true);
    }
}
