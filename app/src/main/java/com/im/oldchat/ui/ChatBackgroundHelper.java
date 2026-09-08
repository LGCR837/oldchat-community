package com.im.oldchat.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.data.ChatBackgroundStore;
import java.io.File;

public class ChatBackgroundHelper {
    private final Activity activity;
    private final View backgroundView;
    private final String targetId;
    private final boolean isGroup;

    public ChatBackgroundHelper(Activity activity, View backgroundView, String targetId, boolean isGroup) {
        this.activity = activity;
        this.backgroundView = backgroundView;
        this.targetId = targetId;
        this.isGroup = isGroup;
    }

    public void showBackgroundDialog(final int requestCode) {
        if (activity == null || targetId == null) {
            return;
        }
        boolean hasBg = ChatBackgroundStore.hasBackground(activity, targetId, isGroup);
        String[] items;
        if (hasBg) {
            items = new String[]{
                    activity.getString(R.string.chat_background_set),
                    activity.getString(R.string.chat_background_clear)
            };
        } else {
            items = new String[]{activity.getString(R.string.chat_background_set)};
        }
        new android.support.v7.app.AlertDialog.Builder(activity, R.style.AppDialogTheme)
                .setTitle(R.string.chat_background_title)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            pickBackground(requestCode);
                        } else {
                            clearBackground();
                        }
                    }
                })
                .show();
    }

    public void pickBackground(int requestCode) {
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(Intent.createChooser(intent,
                    activity.getString(R.string.chat_background_set)), requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.error_pick_image, Toast.LENGTH_SHORT).show();
        }
    }

    public void handlePickResult(Uri uri) {
        if (activity == null || uri == null || targetId == null) {
            return;
        }
        boolean success = ChatBackgroundStore.saveBackground(activity, targetId, isGroup, uri);
        if (success) {
            ChatBackgroundLoader.clearCache();
            applyBackgroundImmediately();
            Toast.makeText(activity, R.string.chat_background_set_success, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, R.string.error_save_image, Toast.LENGTH_SHORT).show();
        }
    }

    public void clearBackground() {
        if (activity == null || targetId == null) {
            return;
        }
        ChatBackgroundStore.clearBackground(activity, targetId, isGroup);
        ChatBackgroundLoader.clearCache();
        applyBackgroundImmediately();
        Toast.makeText(activity, R.string.chat_background_clear_success, Toast.LENGTH_SHORT).show();
    }

    public void applyBackground() {
        applyBackgroundImmediately();
    }
    
    /**
     * 延迟加载背景图片
     * @param delayMs 延迟毫秒数
     */
    public void applyBackgroundDelayed(long delayMs) {
        applyBackgroundImmediately();
    }
    
    /**
     * 立即加载背景（用于设置后刷新）
     */
    public void applyBackgroundImmediately() {
        if (backgroundView == null || targetId == null || activity == null) {
            return;
        }
        ChatBackgroundLoader.loadBackground(activity, backgroundView, targetId, isGroup);
    }

    private void setViewBackground(View view, BitmapDrawable drawable) {
        if (view == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            view.setBackground(drawable);
        } else {
            view.setBackgroundDrawable(drawable);
        }
    }

    private int resolveBackgroundMaxSize() {
        int maxSize = 1024;
        try {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int screenMax = Math.max(dm.widthPixels, dm.heightPixels);
            int memClass = getMemoryClassMb();
            int cap = memClass > 0 && memClass <= 128 ? 720 : 1024;
            maxSize = Math.min(screenMax, cap);
            if (maxSize <= 0) {
                maxSize = cap;
            }
        } catch (Exception e) {
            return maxSize;
        }
        return maxSize;
    }

    private int getMemoryClassMb() {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
            if (am != null) {
                return am.getMemoryClass();
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }
}
