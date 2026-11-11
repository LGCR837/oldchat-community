package com.im.oldchat.ui;

import android.annotation.TargetApi;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;

/** 隔离 API 11 的文字选择菜单，避免 Android 2.3 类校验加载 ActionMode。 */
@TargetApi(11)
final class ChatInputMenuApi11 {
    private ChatInputMenuApi11() { }

    static void bindSelection(EditText editText) {
        editText.setCustomSelectionActionModeCallback(buildCallback(editText));
    }

    static ActionMode.Callback buildCallback(final EditText editText) {
        return new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                ensureItem(menu, android.R.id.cut, "剪切");
                ensureItem(menu, android.R.id.copy, "复制");
                ensureItem(menu, android.R.id.paste, "粘贴");
                ensureItem(menu, android.R.id.selectAll, "全选");
                menu.add(0, ChatInputMenuHelper.MENU_ID_NEWLINE, 0, "换行");
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item != null && item.getItemId() == ChatInputMenuHelper.MENU_ID_NEWLINE) {
                    ChatInputMenuHelper.insertNewLine(editText);
                    mode.finish();
                    return true;
                }
                return item != null && editText.onTextContextMenuItem(item.getItemId());
            }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        };
    }

    private static void ensureItem(Menu menu, int id, String title) {
        if (menu == null) return;
        MenuItem item = menu.findItem(id);
        if (item == null) item = menu.add(0, id, 0, title);
        item.setVisible(true);
        item.setEnabled(true);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    }
}
