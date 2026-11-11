package com.im.oldchat.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.data.ChatBackgroundStore;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * 聊天背景图片异步加载器 - 优化启动性能
 */
public final class ChatBackgroundLoader {
    private static final int MAX_SIZE_DEFAULT = 1024;
    private static final int MAX_SIZE_LOW_MEMORY = 720;
    
    private static final class BackgroundEntry {
        final BitmapDrawable drawable;
        final boolean tiled;
        BackgroundEntry(BitmapDrawable drawable, boolean tiled) {
            this.drawable = drawable;
            this.tiled = tiled;
        }
    }
    private static final android.support.v4.util.LruCache<String, BackgroundEntry> MEMORY_CACHE =
            new android.support.v4.util.LruCache<String, BackgroundEntry>(6);

    public static void clearCache() {
        try {
            MEMORY_CACHE.evictAll();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 异步加载背景，带延迟选项
     */
    public static void loadBackgroundDelayed(final Activity activity, final View backgroundView,
                                              final String targetId, final boolean isGroup,
                                              long delayMs) {
        if (backgroundView == null || targetId == null) {
            return;
        }
        loadBackground(activity, backgroundView, targetId, isGroup);
    }
    
    /**
     * 立即异步加载背景（优先内存命中同步直出）
     */
    public static void loadBackground(final Activity activity, final View backgroundView,
                                       final String targetId, final boolean isGroup) {
        if (backgroundView == null || targetId == null || activity == null) {
            return;
        }
        loadBackgroundInternal(activity, backgroundView, targetId, isGroup);
    }
    
    private static void loadBackgroundInternal(final Activity activity, final View backgroundView,
                                                final String targetId, final boolean isGroup) {
        boolean isNight = com.im.oldchat.util.SettingsPrefs.isDarkModeEnabled(activity)
                || com.im.oldchat.util.ThemeColorUtil.isMinimalDark(activity);
        String path = ChatBackgroundStore.getEffectiveBackgroundPath(activity, targetId, isGroup);
        final String cacheKey = (path != null && path.length() > 0 ? path : "default") + "_" + (isNight ? "night" : "day");

        BackgroundEntry cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null && cached.drawable != null) {
            setViewBackground(backgroundView, cached.drawable, cached.tiled);
            backgroundView.setVisibility(View.VISIBLE);
            return;
        }

        final WeakReference<Activity> activityRef = new WeakReference<Activity>(activity);
        final WeakReference<View> viewRef = new WeakReference<View>(backgroundView);
        
        com.im.oldchat.util.ThreadPoolManager.getInstance().executeIo(new Runnable() {
            @Override
            public void run() {
                Activity act = activityRef.get();
                if (act == null || act.isFinishing()) {
                    return;
                }
                
                final boolean customExists = hasCustomBackground(act, targetId, isGroup);
                final Bitmap bitmap = decodeBackgroundBitmap(act, targetId, isGroup);
                if (bitmap == null) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            View view = viewRef.get();
                            if (view != null) {
                                setViewBackground(view, null, false);
                                view.setVisibility(View.GONE);
                            }
                        }
                    });
                    return;
                }

                final BitmapDrawable drawable;
                final boolean tiled;
                if (customExists) {
                    drawable = new BitmapDrawable(act.getResources(), bitmap);
                    tiled = false;
                } else {
                    drawable = new BitmapDrawable(act.getResources(), bitmap);
                    drawable.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                    drawable.setAntiAlias(false);
                    tiled = true;
                }

                MEMORY_CACHE.put(cacheKey, new BackgroundEntry(drawable, tiled));
                
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Activity act2 = activityRef.get();
                        View view = viewRef.get();
                        if (act2 == null || view == null || act2.isFinishing()) {
                            return;
                        }
                        setViewBackground(view, drawable, tiled);
                        view.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }
    
    /** 是否存在用户自定义背景文件；false 时使用内置默认背景（平铺）。 */
    private static boolean hasCustomBackground(Activity activity, String targetId, boolean isGroup) {
        String path = ChatBackgroundStore.getEffectiveBackgroundPath(activity, targetId, isGroup);
        return path != null && path.length() > 0 && new File(path).exists();
    }

    private static Bitmap decodeBackgroundBitmap(Activity activity, String targetId, boolean isGroup) {
        String path = ChatBackgroundStore.getEffectiveBackgroundPath(activity, targetId, isGroup);
        try {
            int maxSize = resolveBackgroundMaxSize(activity);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            boolean customExists = path != null && path.length() > 0 && new File(path).exists();
            if (customExists) {
                BitmapFactory.decodeFile(path, opts);
            } else {
                // drawable-night-nodpi 下存在同名暗色资源，由当前 DayNight 配置自动选择。
                BitmapFactory.decodeResource(activity.getResources(), R.drawable.chat_default_background, opts);
            }

            int scale = 1;
            if (opts.outWidth > maxSize || opts.outHeight > maxSize) {
                scale = Math.max(opts.outWidth / maxSize, opts.outHeight / maxSize);
                if (scale < 1) scale = 1;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = scale;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inDither = true;
            return customExists
                    ? BitmapFactory.decodeFile(path, opts)
                    : BitmapFactory.decodeResource(activity.getResources(),
                            R.drawable.chat_default_background, opts);
        } catch (Throwable e) {
            return null;
        }
    }
    
    private static int resolveBackgroundMaxSize(Activity activity) {
        int maxSize = MAX_SIZE_DEFAULT;
        try {
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int screenMax = Math.max(dm.widthPixels, dm.heightPixels);
            int memClass = getMemoryClassMb(activity);
            int cap = memClass > 0 && memClass <= 128 ? MAX_SIZE_LOW_MEMORY : MAX_SIZE_DEFAULT;
            maxSize = Math.min(screenMax, cap);
            if (maxSize <= 0) {
                maxSize = cap;
            }
        } catch (Exception e) {
            return maxSize;
        }
        return maxSize;
    }
    
    private static int getMemoryClassMb(Activity activity) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) 
                    activity.getSystemService(Activity.ACTIVITY_SERVICE);
            if (am != null) {
                return am.getMemoryClass();
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }
    
    @SuppressWarnings("deprecation")
    private static void setViewBackground(View view, BitmapDrawable drawable, boolean tiled) {
        if (view == null) {
            return;
        }
        if (tiled) {
            // 平铺背景必须放在 background 层，Image 层的 scaleType 会干扰 TileMode。
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                view.setBackground(drawable);
            } else {
                view.setBackgroundDrawable(drawable);
            }
            return;
        }
        if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setImageDrawable(drawable);
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            view.setBackground(drawable);
        } else {
            view.setBackgroundDrawable(drawable);
        }
    }
}
