package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class ChatInlineEmojiPanelController {
    static final int REQ_PICK_INLINE_EMOJI = 4201;

    static final int TAB_TOOLS = 0;
    static final int TAB_EMOJI = 1;
    static final int TAB_FILE = 2;

    private static final String CATEGORY_ALL = "全部";
    private static final String TAG_TOOLS = "action_tools_container";
    private static final String TAG_PANEL = "action_emoji_panel_container";
    private static final String TAG_FILE_PANEL = "action_file_panel_container";
    private static final String TAG_CATEGORIES = "action_emoji_categories";
    private static final String TAG_GRID = "action_emoji_grid";
    private static final String TAG = "InlineEmojiPanel";

    interface EmojiSendCallback {
        void onEmojiSend(String path, boolean isGif);
    }

    interface FileActionsListener {
        void onLosslessImage();
        void onLosslessVideo();
        void onSendDoc();
    }

    private final Activity activity;
    private final EmojiSendCallback sendCallback;
    private final FileActionsListener fileActionsListener;
    private final View toolsContainer;
    private final View emojiPanelContainer;
    private final View filePanelContainer;
    private final LinearLayout categoriesLayout;
    private final GridView emojiGrid;
    private final List<EmojiStore.EmojiItem> allItems = new ArrayList<EmojiStore.EmojiItem>();
    private final List<EmojiStore.EmojiItem> filteredItems = new ArrayList<EmojiStore.EmojiItem>();
    private final List<String> categories = new ArrayList<String>();
    private final ChatInlineEmojiGridAdapter gridAdapter;
    private String currentCategory = CATEGORY_ALL;
    private boolean dataLoaded = false;
    private boolean loading = false;
    private boolean reloadPending = false;
    private boolean pendingResetCategory = false;

    private final View tabActionTools;
    private final android.widget.ImageView ivTabToolsIcon;
    private final TextView tvTabToolsLabel;
    private final View tabActionEmoji;
    private final android.widget.ImageView ivTabEmojiIcon;
    private final TextView tvTabEmojiLabel;
    private final View tabActionFile;
    private final android.widget.ImageView ivTabFileIcon;
    private final TextView tvTabFileLabel;

    private final View btnFileLosslessImage;
    private final View btnFileLosslessVideo;
    private final View btnFileSendDoc;

    ChatInlineEmojiPanelController(final Activity activity, final View actionPanelRoot,
                                   EmojiSendCallback sendCallback,
                                   final FileActionsListener fileActionsListener) {
        this.activity = activity;
        this.sendCallback = sendCallback;
        this.fileActionsListener = fileActionsListener;
        this.toolsContainer = findByTag(actionPanelRoot, TAG_TOOLS);
        this.emojiPanelContainer = findByTag(actionPanelRoot, TAG_PANEL);
        this.filePanelContainer = findByTag(actionPanelRoot, TAG_FILE_PANEL);
        this.categoriesLayout = asLinearLayout(findByTag(actionPanelRoot, TAG_CATEGORIES));
        this.emojiGrid = asGridView(findByTag(actionPanelRoot, TAG_GRID));
        this.tabActionTools = findByTag(actionPanelRoot, "tab_action_tools");
        this.ivTabToolsIcon = actionPanelRoot != null ? (android.widget.ImageView) actionPanelRoot.findViewById(R.id.ivTabToolsIcon) : null;
        this.tvTabToolsLabel = actionPanelRoot != null ? (TextView) actionPanelRoot.findViewById(R.id.tvTabToolsLabel) : null;
        this.tabActionEmoji = findByTag(actionPanelRoot, "tab_action_emoji");
        this.ivTabEmojiIcon = actionPanelRoot != null ? (android.widget.ImageView) actionPanelRoot.findViewById(R.id.ivTabEmojiIcon) : null;
        this.tvTabEmojiLabel = actionPanelRoot != null ? (TextView) actionPanelRoot.findViewById(R.id.tvTabEmojiLabel) : null;
        this.tabActionFile = findByTag(actionPanelRoot, "tab_action_file");
        this.ivTabFileIcon = actionPanelRoot != null ? (android.widget.ImageView) actionPanelRoot.findViewById(R.id.ivTabFileIcon) : null;
        this.tvTabFileLabel = actionPanelRoot != null ? (TextView) actionPanelRoot.findViewById(R.id.tvTabFileLabel) : null;

        this.btnFileLosslessImage = actionPanelRoot != null ? actionPanelRoot.findViewById(R.id.btnFileLosslessImage) : null;
        this.btnFileLosslessVideo = actionPanelRoot != null ? actionPanelRoot.findViewById(R.id.btnFileLosslessVideo) : null;
        this.btnFileSendDoc = actionPanelRoot != null ? actionPanelRoot.findViewById(R.id.btnFileSendDoc) : null;

        if (this.btnFileLosslessImage != null) {
            this.btnFileLosslessImage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fileActionsListener != null) {
                        fileActionsListener.onLosslessImage();
                    }
                }
            });
        }
        if (this.btnFileLosslessVideo != null) {
            this.btnFileLosslessVideo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fileActionsListener != null) {
                        fileActionsListener.onLosslessVideo();
                    }
                }
            });
        }
        if (this.btnFileSendDoc != null) {
            this.btnFileSendDoc.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fileActionsListener != null) {
                        fileActionsListener.onSendDoc();
                    }
                }
            });
        }

        if (this.tabActionTools != null) {
            this.tabActionTools.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showToolsPanel();
                }
            });
        }
        if (this.tabActionEmoji != null) {
            this.tabActionEmoji.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEmojiPanel();
                }
            });
        }
        if (this.tabActionFile != null) {
            this.tabActionFile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFilePanel();
                }
            });
        }

        this.gridAdapter = new ChatInlineEmojiGridAdapter(activity, filteredItems, new ChatInlineEmojiGridAdapter.Listener() {
            @Override
            public void onAddClicked() {
                pickEmoji();
            }
            @Override
            public void onEmojiClicked(EmojiStore.EmojiItem item) {
                if (item != null && item.path != null && item.path.length() > 0 && sendCallback != null) {
                    sendCallback.onEmojiSend(item.path, item.isGif);
                }
            }
            @Override
            public void onEmojiLongClicked(EmojiStore.EmojiItem item) {
                if (item != null) {
                    ChatInlineEmojiManageHelper.showManageMenu(activity, item, categories, allItems,
                            new Runnable() {
                                @Override
                                public void run() {
                                    requestReload(false);
                                }
                            });
                }
            }
        });
        bindIfReady();
        updateBottomTabState(TAB_TOOLS);
    }

    private void updateBottomTabState(int activeTab) {
        if (activity == null) return;
        int primaryColor = com.im.oldchat.util.ThemeColorUtil.getPrimaryColor(activity);
        int secondaryColor = 0xFF888888;
        try {
            secondaryColor = activity.getResources().getColor(R.color.colorTextSecondary);
        } catch (Throwable ignored) {}

        boolean isTools = (activeTab == TAB_TOOLS);
        boolean isEmoji = (activeTab == TAB_EMOJI);
        boolean isFile = (activeTab == TAB_FILE);

        if (ivTabToolsIcon != null) {
            ivTabToolsIcon.setColorFilter(isTools ? primaryColor : secondaryColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        if (tvTabToolsLabel != null) {
            tvTabToolsLabel.setTextColor(isTools ? primaryColor : secondaryColor);
        }
        if (ivTabEmojiIcon != null) {
            ivTabEmojiIcon.setColorFilter(isEmoji ? primaryColor : secondaryColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        if (tvTabEmojiLabel != null) {
            tvTabEmojiLabel.setTextColor(isEmoji ? primaryColor : secondaryColor);
        }
        if (ivTabFileIcon != null) {
            ivTabFileIcon.setColorFilter(isFile ? primaryColor : secondaryColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        if (tvTabFileLabel != null) {
            tvTabFileLabel.setTextColor(isFile ? primaryColor : secondaryColor);
        }
    }

    void showEmojiPanel() {
        if (!isReady()) {
            return;
        }
        if (toolsContainer != null) toolsContainer.setVisibility(View.GONE);
        if (filePanelContainer != null) filePanelContainer.setVisibility(View.GONE);
        if (emojiPanelContainer != null) emojiPanelContainer.setVisibility(View.VISIBLE);
        updateBottomTabState(TAB_EMOJI);
        requestReload(false);
    }

    void showToolsPanel() {
        if (!isReady()) {
            return;
        }
        if (emojiPanelContainer != null) emojiPanelContainer.setVisibility(View.GONE);
        if (filePanelContainer != null) filePanelContainer.setVisibility(View.GONE);
        if (toolsContainer != null) toolsContainer.setVisibility(View.VISIBLE);
        updateBottomTabState(TAB_TOOLS);
    }

    void showFilePanel() {
        if (!isReady()) {
            return;
        }
        if (toolsContainer != null) toolsContainer.setVisibility(View.GONE);
        if (emojiPanelContainer != null) emojiPanelContainer.setVisibility(View.GONE);
        if (filePanelContainer != null) filePanelContainer.setVisibility(View.VISIBLE);
        updateBottomTabState(TAB_FILE);
    }

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_INLINE_EMOJI) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            return true;
        }
        EmojiImportHelper.importSelectedEmojis(activity, data, new EmojiImportHelper.Callback() {
            @Override
            public void onComplete(int successCount, int failureCount) {
                if (successCount > 0) {
                    requestReload(false);
                }
                String message = EmojiImportHelper.buildImportResultMessage(successCount, failureCount);
                if (message != null) {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
        return true;
    }

    private void bindIfReady() {
        if (!isReady()) {
            return;
        }
        emojiGrid.setAdapter(gridAdapter);
        requestReload(true);
    }

    private boolean isReady() {
        return toolsContainer != null && emojiPanelContainer != null && categoriesLayout != null && emojiGrid != null;
    }

    private void pickEmoji() {
        if (!EmojiImportHelper.startPickImages(activity, REQ_PICK_INLINE_EMOJI, "选择表情")) {
            Toast.makeText(activity, "无法选择图片", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestReload(boolean resetCategory) {
        if (!isReady()) {
            return;
        }
        if (resetCategory) {
            pendingResetCategory = true;
        }
        if (loading) {
            reloadPending = true;
            return;
        }
        // 每次打开都强制重载：表情广场/外部保存的表情才能及时出现，
        // 不再因 dataLoaded 缓存而跳过（否则新保存的表情看不到）。
        startAsyncReload();
    }

    private void startAsyncReload() {
        loading = true;
        final boolean resetCategory = pendingResetCategory;
        pendingResetCategory = false;
        ChatInlineEmojiPanelDataLoader.load(activity, new ChatInlineEmojiPanelDataLoader.Callback() {
            @Override
            public void onLoaded(List<EmojiStore.EmojiItem> loaded) {
                applyLoadedData(loaded, resetCategory);
            }
            @Override
            public void onError(Throwable error) {
                Log.e(TAG, "load emoji data failed", error);
                loading = false;
                Toast.makeText(activity, "表情面板加载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyLoadedData(List<EmojiStore.EmojiItem> loaded, boolean resetCategory) {
        loading = false;
        dataLoaded = true;
        allItems.clear();
        if (loaded != null) allItems.addAll(loaded);
        rebuildCategories();
        if (resetCategory || !categories.contains(currentCategory)) {
            currentCategory = CATEGORY_ALL;
        }
        renderCategories();
        applyCurrentCategory();
        if (reloadPending || pendingResetCategory) {
            reloadPending = false;
            startAsyncReload();
        }
    }

    private void rebuildCategories() {
        categories.clear();
        categories.add(CATEGORY_ALL);
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        for (int i = 0; i < allItems.size(); i++) {
            EmojiStore.EmojiItem item = allItems.get(i);
            if (item == null) {
                continue;
            }
            String category = EmojiStore.normalizeCategoryName(item.category);
            item.category = category;
            set.add(category);
        }
        categories.addAll(set);
    }

    private void renderCategories() {
        categoriesLayout.removeAllViews();
        for (int i = 0; i < categories.size(); i++) {
            final String category = categories.get(i);
            TextView tab = new TextView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dpToPx(8);
            tab.setLayoutParams(lp);
            tab.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
            tab.setTextSize(12f);
            tab.setText(category);
            boolean selected = category.equals(currentCategory);
            tab.setBackgroundResource(selected ? R.drawable.flat_button_accent_bg : R.drawable.flat_button_bg);
            tab.setTextColor(resolveCategoryTabTextColor(selected));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        if (!category.equals(currentCategory)) {
                            currentCategory = category;
                            renderCategories();
                            applyCurrentCategory();
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "switch category failed", t);
                        Toast.makeText(activity, "切换分类失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            categoriesLayout.addView(tab);
        }
    }

    private void applyCurrentCategory() {
        filteredItems.clear();
        for (int i = 0; i < allItems.size(); i++) {
            EmojiStore.EmojiItem item = allItems.get(i);
            if (item == null || item.path == null || item.path.length() == 0) {
                continue;
            }
            if (CATEGORY_ALL.equals(currentCategory)
                    || currentCategory.equals(EmojiStore.normalizeCategoryName(item.category))) {
                filteredItems.add(item);
            }
        }
        gridAdapter.notifyDataSetChanged();
    }

    private int resolveCategoryTabTextColor(boolean selected) {
        int fallback = selected ? 0xFFFFFFFF : 0xFF9AA4B2;
        try {
            return activity.getResources().getColor(selected
                    ? R.color.color_on_primary
                    : R.color.color_text_secondary);
        } catch (Throwable t) {
            Log.e(TAG, "resolve tab text color failed", t);
            return fallback;
        }
    }

    private int dpToPx(int dp) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.max(1, (int) (dp * density + 0.5f));
    }

    private static LinearLayout asLinearLayout(View view) {
        return view instanceof LinearLayout ? (LinearLayout) view : null;
    }

    private static GridView asGridView(View view) {
        return view instanceof GridView ? (GridView) view : null;
    }

    private static View findByTag(View root, String tag) {
        if (root == null || tag == null) {
            return null;
        }
        Object t = root.getTag();
        if (tag.equals(t)) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup vg = (ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View found = findByTag(vg.getChildAt(i), tag);
            if (found != null) return found;
        }
        return null;
    }
}
