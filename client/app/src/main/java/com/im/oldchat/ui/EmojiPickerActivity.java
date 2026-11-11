package com.im.oldchat.ui;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.view.ViewCompat;
import android.support.v4.content.ContextCompat;
import com.im.oldchat.BaseActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class EmojiPickerActivity extends BaseActivity {
    public static final String EXTRA_EMOJI_PATH = "emoji_path";
    public static final String EXTRA_EMOJI_IS_GIF = "emoji_is_gif";

    private static final int REQ_PICK_IMAGE = 4101;
    private static final String CATEGORY_ALL = "全部";
    private static final String NEW_CATEGORY_LABEL = "＋ 新建分类";
    private static final int MAX_CATEGORY_LEN = 10;
    private static final int SWIPE_DISTANCE_MIN = 100;
    private static final int SWIPE_VELOCITY_MIN = 180;
    private static final String PREF_EMOJI_UI = "emoji_picker_ui";
    private static final String KEY_LAST_CATEGORY = "last_category";

    private RecyclerView rvEmojis;
    private TextView btnManage;
    private View btnAdd;
    private TextView emptyView;
    private HorizontalScrollView svCategories;
    private LinearLayout layoutCategories;
    private EmojiAdapter adapter;
    private final List<EmojiStore.EmojiItem> allItems = new ArrayList<EmojiStore.EmojiItem>();
    private final List<EmojiStore.EmojiItem> items = new ArrayList<EmojiStore.EmojiItem>();
    private final List<String> categories = new ArrayList<String>();
    private boolean manageMode = false;
    private ItemTouchHelper touchHelper;
    private int emojiTargetPx;
    private String currentCategory = CATEGORY_ALL;
    private GestureDetector categoryGestureDetector;
    private boolean manageTipShown;
    private LinearLayout layoutBatchActions;
    private TextView tvBatchCount;
    private TextView btnBatchMove;
    private TextView btnBatchDelete;
    private TextView btnBatchCancel;
    private boolean multiSelectMode = false;
    private final LinkedHashSet<String> selectedEmojiKeys = new LinkedHashSet<String>();
    private String lastSavedCategory = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emoji_picker);

        rvEmojis = findViewByIdCompat(R.id.rvEmojis);
        btnManage = findViewByIdCompat(R.id.btnEmojiManage);
        btnAdd = findViewByIdCompat(R.id.btnEmojiAdd);
        emptyView = findViewByIdCompat(R.id.tvEmojiEmpty);
        svCategories = findViewByIdCompat(R.id.svEmojiCategories);
        layoutCategories = findViewByIdCompat(R.id.layoutEmojiCategories);
        layoutBatchActions = findViewByIdCompat(R.id.layoutEmojiBatchActions);
        tvBatchCount = findViewByIdCompat(R.id.tvEmojiBatchCount);
        btnBatchMove = findViewByIdCompat(R.id.btnEmojiBatchMove);
        btnBatchDelete = findViewByIdCompat(R.id.btnEmojiBatchDelete);
        btnBatchCancel = findViewByIdCompat(R.id.btnEmojiBatchCancel);
        View btnBack = (View) findViewByIdCompat(R.id.btnEmojiBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        rvEmojis.setLayoutManager(new com.im.oldchat.ui.widget.SafeGridLayoutManager(this, 4));
        adapter = new EmojiAdapter();
        rvEmojis.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new EmojiTouchCallback());
        touchHelper.attachToRecyclerView(rvEmojis);
        emojiTargetPx = dpToPx(72);

        categoryGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < SWIPE_DISTANCE_MIN || Math.abs(dx) < Math.abs(dy)
                        || Math.abs(velocityX) < SWIPE_VELOCITY_MIN) {
                    return false;
                }
                if (dx < 0) {
                    return switchCategoryByDelta(1);
                }
                return switchCategoryByDelta(-1);
            }
        });
        rvEmojis.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (categoryGestureDetector != null) {
                    categoryGestureDetector.onTouchEvent(event);
                }
                return false;
            }
        });

        if (btnAdd != null) {
            btnAdd.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickEmoji();
                }
            });
        }
        if (btnManage != null) {
            btnManage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleManageMode();
                }
            });
        }
        if (btnBatchMove != null) {
            btnBatchMove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!multiSelectMode || selectedEmojiKeys.isEmpty()) {
                        Toast.makeText(EmojiPickerActivity.this, "请先选择表情", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showBatchCategoryDialog();
                }
            });
        }
        if (btnBatchDelete != null) {
            btnBatchDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!multiSelectMode || selectedEmojiKeys.isEmpty()) {
                        Toast.makeText(EmojiPickerActivity.this, "请先选择表情", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmBatchDelete();
                }
            });
        }
        if (btnBatchCancel != null) {
            btnBatchCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exitMultiSelectMode();
                }
            });
        }

        restoreLastCategory();
        loadEmojis(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            EmojiImportHelper.importSelectedEmojis(this, data, new EmojiImportHelper.Callback() {
                @Override
                public void onComplete(int successCount, int failureCount) {
                    if (successCount > 0) {
                        loadEmojis(false);
                    }
                    String message = EmojiImportHelper.buildImportResultMessage(successCount, failureCount);
                    if (message != null) {
                        Toast.makeText(EmojiPickerActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }


    private void loadEmojis(boolean resetToAll) {
        allItems.clear();
        List<EmojiStore.EmojiItem> loaded = EmojiStore.load(this);
        if (loaded != null) {
            allItems.addAll(loaded);
        }
        rebuildCategoryList();
        if (resetToAll || !categories.contains(currentCategory)) {
            currentCategory = CATEGORY_ALL;
        }
        pruneSelectedKeys();
        if (selectedEmojiKeys.isEmpty()) {
            multiSelectMode = false;
        }
        applyCurrentCategory(true);
        updateBatchActionBar();
    }

    private void restoreLastCategory() {
        SharedPreferences prefs = getSharedPreferences(PREF_EMOJI_UI, MODE_PRIVATE);
        String saved = prefs.getString(KEY_LAST_CATEGORY, CATEGORY_ALL);
        if (saved == null || saved.trim().length() == 0) {
            saved = CATEGORY_ALL;
        }
        currentCategory = saved;
        lastSavedCategory = saved;
    }

    private void persistCurrentCategory() {
        String out = currentCategory;
        if (out == null || out.trim().length() == 0) {
            out = CATEGORY_ALL;
        }
        if (out.equals(lastSavedCategory)) {
            return;
        }
        getSharedPreferences(PREF_EMOJI_UI, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CATEGORY, out)
                .apply();
        lastSavedCategory = out;
    }

    private void rebuildCategoryList() {
        categories.clear();
        categories.add(CATEGORY_ALL);
        LinkedHashSet<String> catSet = new LinkedHashSet<String>();
        for (EmojiStore.EmojiItem item : allItems) {
            if (item == null) {
                continue;
            }
            String category = EmojiStore.normalizeCategoryName(item.category);
            item.category = category;
            catSet.add(category);
        }
        categories.addAll(catSet);
        renderCategoryTabs();
    }

    private void renderCategoryTabs() {
        if (layoutCategories == null) {
            return;
        }
        layoutCategories.removeAllViews();
        for (int i = 0; i < categories.size(); i++) {
            final String category = categories.get(i);
            TextView tab = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dpToPx(8);
            tab.setLayoutParams(lp);
            tab.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            tab.setBackgroundResource(R.drawable.bg_search_filter_chip);
            tab.setTextSize(13f);
            tab.setSingleLine(true);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setMaxEms(8);
            tab.setText(category);
            tab.setSelected(category.equals(currentCategory));
            updateTabStyle(tab, category.equals(currentCategory));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!category.equals(currentCategory)) {
                        currentCategory = category;
                        applyCurrentCategory(true);
                    }
                }
            });
            layoutCategories.addView(tab);
        }
    }

    private void updateTabStyle(TextView tab, boolean selected) {
        if (tab == null) {
            return;
        }
        int color = selected
                ? ContextCompat.getColor(this, R.color.color_on_primary)
                : ContextCompat.getColor(this, R.color.color_text_secondary);
        tab.setTextColor(color);
        tab.setSelected(selected);
    }

    private void applyCurrentCategory(boolean scrollTop) {
        items.clear();
        if (isAllCategory()) {
            items.addAll(allItems);
        } else {
            for (EmojiStore.EmojiItem item : allItems) {
                if (item == null) {
                    continue;
                }
                String category = EmojiStore.normalizeCategoryName(item.category);
                if (currentCategory.equals(category)) {
                    items.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateCategorySelectionUi();
        updateEmptyState();
        if (scrollTop && !items.isEmpty()) {
            rvEmojis.scrollToPosition(0);
        }
        persistCurrentCategory();
    }

    private void updateCategorySelectionUi() {
        if (layoutCategories == null) {
            return;
        }
        int count = layoutCategories.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = layoutCategories.getChildAt(i);
            if (!(child instanceof TextView)) {
                continue;
            }
            TextView tab = (TextView) child;
            String text = tab.getText() == null ? "" : tab.getText().toString();
            boolean selected = currentCategory.equals(text);
            updateTabStyle(tab, selected);
            if (selected && svCategories != null) {
                final View target = tab;
                svCategories.post(new Runnable() {
                    @Override
                    public void run() {
                        int sx = target.getLeft() - dpToPx(16);
                        if (sx < 0) {
                            sx = 0;
                        }
                        svCategories.smoothScrollTo(sx, 0);
                    }
                });
            }
        }
    }

    private void updateEmptyState() {
        if (emptyView == null) {
            return;
        }
        if (items.isEmpty()) {
            if (isAllCategory()) {
                emptyView.setText("暂无表情，点击添加");
            } else {
                emptyView.setText("该分类暂无表情，管理中可移动表情到此");
            }
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);
    }

    private void toggleManageMode() {
        manageMode = !manageMode;
        if (!manageMode) {
            exitMultiSelectMode();
        }
        if (btnManage != null) {
            btnManage.setText(manageMode ? "完成" : "管理");
        }
        if (manageMode && !manageTipShown) {
            manageTipShown = true;
            Toast.makeText(this, "管理模式：点击表情可分类/删除；在“全部”分类长按表情可拖动排序", Toast.LENGTH_SHORT).show();
        }
        updateBatchActionBar();
        adapter.notifyDataSetChanged();
    }

    private void updateBatchActionBar() {
        if (layoutBatchActions == null) {
            return;
        }
        boolean show = manageMode && multiSelectMode;
        layoutBatchActions.setVisibility(show ? View.VISIBLE : View.GONE);
        int count = selectedEmojiKeys.size();
        if (tvBatchCount != null) {
            tvBatchCount.setText("已选 " + count + " 项");
        }
        boolean enableActions = count > 0;
        if (btnBatchMove != null) {
            btnBatchMove.setEnabled(enableActions);
            ViewCompat.setAlpha(btnBatchMove, enableActions ? 1f : 0.5f);
        }
        if (btnBatchDelete != null) {
            btnBatchDelete.setEnabled(enableActions);
            ViewCompat.setAlpha(btnBatchDelete, enableActions ? 1f : 0.5f);
        }
    }

    private void enterMultiSelectMode(EmojiStore.EmojiItem initialItem) {
        if (!manageMode) {
            return;
        }
        multiSelectMode = true;
        selectedEmojiKeys.clear();
        if (initialItem != null) {
            String key = getEmojiKey(initialItem);
            if (key.length() > 0) {
                selectedEmojiKeys.add(key);
            }
        }
        updateBatchActionBar();
        adapter.notifyDataSetChanged();
    }

    private void exitMultiSelectMode() {
        boolean changed = multiSelectMode || !selectedEmojiKeys.isEmpty();
        multiSelectMode = false;
        selectedEmojiKeys.clear();
        if (changed) {
            updateBatchActionBar();
            adapter.notifyDataSetChanged();
        }
    }

    private void toggleItemSelected(EmojiStore.EmojiItem item) {
        if (item == null) {
            return;
        }
        String key = getEmojiKey(item);
        if (key.length() == 0) {
            return;
        }
        if (selectedEmojiKeys.contains(key)) {
            selectedEmojiKeys.remove(key);
        } else {
            selectedEmojiKeys.add(key);
        }
        if (selectedEmojiKeys.isEmpty()) {
            multiSelectMode = false;
        }
        updateBatchActionBar();
        adapter.notifyDataSetChanged();
    }

    private boolean isItemSelected(EmojiStore.EmojiItem item) {
        if (item == null) {
            return false;
        }
        String key = getEmojiKey(item);
        return key.length() > 0 && selectedEmojiKeys.contains(key);
    }

    private String getEmojiKey(EmojiStore.EmojiItem item) {
        if (item == null) {
            return "";
        }
        if (item.id != null && item.id.length() > 0) {
            return "id:" + item.id;
        }
        if (item.path != null && item.path.length() > 0) {
            return "path:" + item.path;
        }
        return "";
    }

    private void pruneSelectedKeys() {
        if (selectedEmojiKeys.isEmpty()) {
            return;
        }
        LinkedHashSet<String> exists = new LinkedHashSet<String>();
        for (int i = 0; i < allItems.size(); i++) {
            EmojiStore.EmojiItem item = allItems.get(i);
            String key = getEmojiKey(item);
            if (key.length() > 0) {
                exists.add(key);
            }
        }
        selectedEmojiKeys.retainAll(exists);
    }
    private void pickEmoji() {
        if (!EmojiImportHelper.startPickImages(this, REQ_PICK_IMAGE, "选择表情")) {
            Toast.makeText(this, "无法选择图片", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendEmoji(EmojiStore.EmojiItem item) {
        if (item == null || item.path == null || item.path.isEmpty()) {
            return;
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_EMOJI_PATH, item.path);
        data.putExtra(EXTRA_EMOJI_IS_GIF, item.isGif);
        setResult(RESULT_OK, data);
        finish();
    }

    private void deleteEmoji(EmojiStore.EmojiItem item) {
        if (item == null) {
            return;
        }
        boolean removed = EmojiStore.deleteEmoji(this, item);
        if (!removed) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
            return;
        }
        for (int i = allItems.size() - 1; i >= 0; i--) {
            EmojiStore.EmojiItem one = allItems.get(i);
            if (one == null) {
                allItems.remove(i);
                continue;
            }
            if (item.id != null && item.id.equals(one.id)) {
                allItems.remove(i);
                break;
            }
            if (item.path != null && item.path.equals(one.path)) {
                allItems.remove(i);
                break;
            }
        }
        EmojiStore.save(this, allItems);
        rebuildCategoryList();
        if (!categories.contains(currentCategory)) {
            currentCategory = CATEGORY_ALL;
        }
        applyCurrentCategory(false);
    }

    private void showManageMenu(final EmojiStore.EmojiItem item) {
        if (item == null) {
            return;
        }
        final CharSequence[] actions = new CharSequence[]{"移动到分类", "删除表情", "进入多选"};
        new AlertDialog.Builder(this)
                .setTitle("管理表情")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showCategoryDialog(item);
                        } else if (which == 1) {
                            deleteEmoji(item);
                        } else {
                            enterMultiSelectMode(item);
                        }
                    }
                })
                .show();
    }

    private void showBatchCategoryDialog() {
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

        final CharSequence[] arr = options.toArray(new CharSequence[options.size()]);
        new AlertDialog.Builder(this)
                .setTitle("批量移动到分类")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String selected = options.get(which);
                        if (NEW_CATEGORY_LABEL.equals(selected)) {
                            promptCreateCategoryForSelection();
                            return;
                        }
                        applyCategoryToSelection(selected);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptCreateCategoryForSelection() {
        final EditText input = new EditText(this);
        input.setHint("输入分类名（最多10字）");
        input.setSingleLine();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CATEGORY_LEN)});
        new AlertDialog.Builder(this)
                .setTitle("新建分类")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText() == null ? "" : input.getText().toString();
                        String category = raw == null ? "" : raw.trim();
                        if (category.length() == 0) {
                            Toast.makeText(EmojiPickerActivity.this, "分类名不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (CATEGORY_ALL.equals(category)) {
                            Toast.makeText(EmojiPickerActivity.this, "“全部”为系统分类，不可作为自定义分类", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        applyCategoryToSelection(EmojiStore.normalizeCategoryName(category));
                    }
                })
                .show();
    }

    private void applyCategoryToSelection(String category) {
        if (selectedEmojiKeys.isEmpty()) {
            Toast.makeText(this, "请先选择表情", Toast.LENGTH_SHORT).show();
            return;
        }
        String out = EmojiStore.normalizeCategoryName(category);
        int changed = 0;
        for (int i = 0; i < allItems.size(); i++) {
            EmojiStore.EmojiItem item = allItems.get(i);
            if (!isItemSelected(item)) {
                continue;
            }
            item.category = out;
            changed++;
        }
        if (changed <= 0) {
            Toast.makeText(this, "未选择有效表情", Toast.LENGTH_SHORT).show();
            return;
        }
        EmojiStore.save(this, allItems);
        rebuildCategoryList();
        if (!categories.contains(currentCategory)) {
            currentCategory = CATEGORY_ALL;
        }
        exitMultiSelectMode();
        applyCurrentCategory(false);
        Toast.makeText(this, "已移动 " + changed + " 项到“" + out + "”", Toast.LENGTH_SHORT).show();
    }

    private void confirmBatchDelete() {
        final int count = selectedEmojiKeys.size();
        if (count <= 0) {
            Toast.makeText(this, "请先选择表情", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定删除已选的 " + count + " 个表情吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        batchDeleteSelected();
                    }
                })
                .show();
    }

    private void batchDeleteSelected() {
        if (selectedEmojiKeys.isEmpty()) {
            return;
        }
        int removed = 0;
        for (int i = allItems.size() - 1; i >= 0; i--) {
            EmojiStore.EmojiItem item = allItems.get(i);
            if (!isItemSelected(item)) {
                continue;
            }
            if (item != null && item.path != null && item.path.length() > 0) {
                File file = new File(item.path);
                if (file.exists()) {
                    file.delete();
                }
            }
            allItems.remove(i);
            removed++;
        }
        EmojiStore.save(this, allItems);
        rebuildCategoryList();
        if (!categories.contains(currentCategory)) {
            currentCategory = CATEGORY_ALL;
        }
        exitMultiSelectMode();
        applyCurrentCategory(false);
        Toast.makeText(this, "已删除 " + removed + " 个表情", Toast.LENGTH_SHORT).show();
    }

    private void showCategoryDialog(final EmojiStore.EmojiItem item) {
        if (item == null) {
            return;
        }
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

        int checked = -1;
        String current = EmojiStore.normalizeCategoryName(item.category);
        for (int i = 0; i < options.size(); i++) {
            if (current.equals(options.get(i))) {
                checked = i;
                break;
            }
        }

        final CharSequence[] arr = options.toArray(new CharSequence[options.size()]);
        new AlertDialog.Builder(this)
                .setTitle("选择分类")
                .setSingleChoiceItems(arr, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String selected = options.get(which);
                        dialog.dismiss();
                        if (NEW_CATEGORY_LABEL.equals(selected)) {
                            promptCreateCategory(item);
                            return;
                        }
                        applyCategoryToItem(item, selected);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptCreateCategory(final EmojiStore.EmojiItem item) {
        final EditText input = new EditText(this);
        input.setHint("输入分类名（最多10字）");
        input.setSingleLine();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CATEGORY_LEN)});
        new AlertDialog.Builder(this)
                .setTitle("新建分类")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText() == null ? "" : input.getText().toString();
                        String category = raw == null ? "" : raw.trim();
                        if (category.length() == 0) {
                            Toast.makeText(EmojiPickerActivity.this, "分类名不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (CATEGORY_ALL.equals(category)) {
                            Toast.makeText(EmojiPickerActivity.this, "“全部”为系统分类，不可作为自定义分类", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        category = EmojiStore.normalizeCategoryName(category);
                        if (item != null) {
                            applyCategoryToItem(item, category);
                        } else {
                            Toast.makeText(EmojiPickerActivity.this, "请先选择一个表情再分类", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void applyCategoryToItem(EmojiStore.EmojiItem item, String category) {
        if (item == null) {
            return;
        }
        String out = EmojiStore.normalizeCategoryName(category);
        item.category = out;
        EmojiStore.save(this, allItems);
        rebuildCategoryList();
        if (!categories.contains(currentCategory)) {
            currentCategory = CATEGORY_ALL;
        }
        applyCurrentCategory(false);
        Toast.makeText(this, "已移动到“" + out + "”", Toast.LENGTH_SHORT).show();
    }

    private boolean switchCategoryByDelta(int delta) {
        if (categories.isEmpty()) {
            return false;
        }
        int index = categories.indexOf(currentCategory);
        if (index < 0) {
            index = 0;
        }
        int next = index + delta;
        if (next < 0 || next >= categories.size()) {
            return false;
        }
        currentCategory = categories.get(next);
        applyCurrentCategory(true);
        Toast.makeText(this, "分类：" + currentCategory, Toast.LENGTH_SHORT).show();
        return true;
    }

    private boolean isAllCategory() {
        return CATEGORY_ALL.equals(currentCategory);
    }

    private class EmojiAdapter extends RecyclerView.Adapter<EmojiViewHolder> {
        @Override
        public EmojiViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emoji, parent, false);
            return new EmojiViewHolder(v);
        }

        @Override
        public void onBindViewHolder(EmojiViewHolder holder, int position) {
            final EmojiStore.EmojiItem item = items.get(position);
            final EmojiViewHolder dragHolder = holder;
            holder.bind(item);
            boolean selected = multiSelectMode && isItemSelected(item);
            holder.selectMark.setVisibility(selected ? View.VISIBLE : View.GONE);
            holder.delete.setVisibility(manageMode && !multiSelectMode ? View.VISIBLE : View.GONE);
            holder.delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteEmoji(item);
                }
            });
            holder.delete.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!manageMode || multiSelectMode) {
                        return false;
                    }
                    if (!isAllCategory()) {
                        Toast.makeText(EmojiPickerActivity.this, "请切换到“全部”分类后再拖动排序", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    if (touchHelper != null) {
                        touchHelper.startDrag(dragHolder);
                    }
                    return true;
                }
            });
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (manageMode) {
                        if (multiSelectMode) {
                            toggleItemSelected(item);
                        } else {
                            showManageMenu(item);
                        }
                        return;
                    }
                    sendEmoji(item);
                }
            });
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!manageMode) {
                        return false;
                    }
                    if (isAllCategory() && !multiSelectMode) {
                        if (touchHelper != null) {
                            touchHelper.startDrag(dragHolder);
                            return true;
                        }
                    }
                    if (!multiSelectMode) {
                        enterMultiSelectMode(item);
                    } else {
                        toggleItemSelected(item);
                    }
                    return true;
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private class EmojiViewHolder extends RecyclerView.ViewHolder {
        private final ImageView image;
        private final ImageView delete;
        private final TextView selectMark;

        EmojiViewHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.ivEmoji);
            delete = itemView.findViewById(R.id.btnDeleteEmoji);
            selectMark = itemView.findViewById(R.id.tvEmojiSelected);
        }

        void bind(EmojiStore.EmojiItem item) {
            if (image == null || item == null) {
                return;
            }
            loadEmojiBitmap(image, item.path);
        }
    }

    private void loadEmojiBitmap(ImageView target, String path) {
        EmojiBitmapLoader.load(this, target, path, emojiTargetPx);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private class EmojiTouchCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            if (!manageMode || multiSelectMode) {
                return 0;
            }
            if (!isAllCategory()) {
                return 0;
            }
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
            return makeMovementFlags(dragFlags, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                              RecyclerView.ViewHolder target) {
            if (!isAllCategory()) {
                Toast.makeText(EmojiPickerActivity.this, "请切换到“全部”分类后再拖动排序", Toast.LENGTH_SHORT).show();
                return false;
            }
            int from = viewHolder.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false;
            }
            Collections.swap(items, from, to);
            Collections.swap(allItems, from, to);
            adapter.notifyItemMoved(from, to);
            EmojiStore.save(EmojiPickerActivity.this, allItems);
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }
    }
}
