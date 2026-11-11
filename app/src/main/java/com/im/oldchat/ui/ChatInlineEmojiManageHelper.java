package com.im.oldchat.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.support.v7.app.AlertDialog;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

final class ChatInlineEmojiManageHelper {
    private static final String CATEGORY_ALL = "全部";
    private static final String NEW_CATEGORY_LABEL = "＋ 新建分类";

    private ChatInlineEmojiManageHelper() {
    }

    static void showManageMenu(final Activity activity,
                               final EmojiStore.EmojiItem item,
                               final List<String> categories,
                               final List<EmojiStore.EmojiItem> allItems,
                               final Runnable reloadAction) {
        if (activity == null || item == null) {
            return;
        }
        final CharSequence[] actions = new CharSequence[]{"移动到分类", "删除表情", "进入多选"};
        new AlertDialog.Builder(activity)
                .setTitle("管理表情")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showCategoryDialog(activity, item, categories, allItems, reloadAction);
                        } else if (which == 1) {
                            if (EmojiStore.deleteEmoji(activity, item)) {
                                if (reloadAction != null) {
                                    reloadAction.run();
                                }
                            } else {
                                Toast.makeText(activity, "删除失败", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            activity.startActivity(new Intent(activity, EmojiPickerActivity.class));
                        }
                    }
                })
                .show();
    }

    private static void showCategoryDialog(final Activity activity,
                                           final EmojiStore.EmojiItem item,
                                           List<String> categories,
                                           final List<EmojiStore.EmojiItem> allItems,
                                           final Runnable reloadAction) {
        final ArrayList<String> options = new ArrayList<String>();
        for (int i = 0; i < categories.size(); i++) {
            String one = categories.get(i);
            if (!CATEGORY_ALL.equals(one)) {
                options.add(one);
            }
        }
        if (options.isEmpty()) {
            options.add(EmojiStore.DEFAULT_CATEGORY);
        }
        options.add(NEW_CATEGORY_LABEL);
        new AlertDialog.Builder(activity)
                .setTitle("移动到分类")
                .setItems(options.toArray(new CharSequence[options.size()]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String selected = options.get(which);
                        if (NEW_CATEGORY_LABEL.equals(selected)) {
                            promptCreateCategory(activity, item, allItems, reloadAction);
                            return;
                        }
                        item.category = EmojiStore.normalizeCategoryName(selected);
                        EmojiStore.save(activity, allItems);
                        if (reloadAction != null) {
                            reloadAction.run();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void promptCreateCategory(final Activity activity,
                                             final EmojiStore.EmojiItem item,
                                             final List<EmojiStore.EmojiItem> allItems,
                                             final Runnable reloadAction) {
        final EditText input = new EditText(activity);
        input.setHint("输入分类名（最多10字）");
        input.setSingleLine();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        new AlertDialog.Builder(activity)
                .setTitle("新建分类")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText() == null ? "" : input.getText().toString();
                        item.category = EmojiStore.normalizeCategoryName(raw);
                        EmojiStore.save(activity, allItems);
                        if (reloadAction != null) {
                            reloadAction.run();
                        }
                    }
                })
                .show();
    }
}
