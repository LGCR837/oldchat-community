package com.im.oldchat.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.util.FileDownloadStorage;
import com.im.oldchat.util.ImageLoader;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

final class ChatImagePreviewDialog {
    private ChatImagePreviewDialog() {
    }

    static void show(Context context, String url) {
        show(context, url, null);
    }

    static void show(Context context, String url, final String originalUrl) {
        if (context == null || url == null || url.length() == 0) {
            return;
        }
        Activity activity = resolveActivity(context);
        if (activity == null || activity.isFinishing()) {
            ImagePreviewActivity.start(context, url, originalUrl);
            return;
        }
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.activity_image_preview);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        final ZoomImageView imageView = (ZoomImageView) dialog.findViewById(R.id.ivPreview);
        ImageView btnBack = (ImageView) dialog.findViewById(R.id.btnPreviewBack);
        final View loading = dialog.findViewById(R.id.pbPreviewLoading);
        final TextView btnOriginal = (TextView) dialog.findViewById(R.id.btnPreviewOriginal);
        final TextView errorView = (TextView) dialog.findViewById(R.id.tvPreviewError);

        final AtomicInteger loadToken = new AtomicInteger(0);
        final boolean[] originalRequested = new boolean[1];

        if (loading != null) {
            loading.setVisibility(View.VISIBLE);
        }

        // 加载预览图（缩略图/中图）
        final Runnable loadPreviewRunnable = new Runnable() {
            @Override
            public void run() {
                originalRequested[0] = false;
                final int token = loadToken.incrementAndGet();
                if (loading != null) loading.setVisibility(View.VISIBLE);
                if (errorView != null) errorView.setVisibility(View.GONE);
                ImageLoader.loadLarge(imageView, url, new ImageLoader.ImageLoadListener() {
                    @Override
                    public void onComplete(String u) {
                        if (token != loadToken.get()) {
                            return;
                        }
                        if (loading != null) loading.setVisibility(View.GONE);
                    }
                });
            }
        };

        // 下载并显示原图
        final Runnable loadOriginalRunnable = new Runnable() {
            @Override
            public void run() {
                if (originalUrl == null || originalUrl.length() == 0) {
                    return;
                }
                originalRequested[0] = true;
                final int token = loadToken.incrementAndGet();
                if (btnOriginal != null) {
                    btnOriginal.setEnabled(false);
                    btnOriginal.setText("正在下载原图...");
                }
                if (loading != null) loading.setVisibility(View.VISIBLE);
                if (errorView != null) errorView.setVisibility(View.GONE);
                final Context appContext = activity.getApplicationContext();
                final SharedPreferences prefs = activity.getSharedPreferences("auth", Context.MODE_PRIVATE);
                final String tokenStr = prefs.getString("access_token", "");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        File target = new File(appContext.getCacheDir(),
                                "original_preview_" + System.currentTimeMillis() + ".img");
                        final FileDownloadStorage.Result result = FileDownloadStorage.downloadToFile(
                                originalUrl, target, tokenStr, null);
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (token != loadToken.get()) {
                                    return;
                                }
                                if (btnOriginal != null) {
                                    btnOriginal.setEnabled(true);
                                    btnOriginal.setText(R.string.image_preview_original);
                                }
                                if (loading != null) loading.setVisibility(View.GONE);
                                if (result.success && result.path != null && result.path.length() > 0) {
                                    // 本地文件路径不能走 URL 解析，直接本地解码显示。
                                    ImageLoader.loadLocalFile(imageView, result.path, null);
                                    if (btnOriginal != null) {
                                        btnOriginal.setVisibility(View.GONE);
                                    }
                                } else {
                                    if (errorView != null) {
                                        errorView.setVisibility(View.VISIBLE);
                                    }
                                    Toast.makeText(activity, "原图加载失败", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        };

        if (btnOriginal != null) {
            // originalUrl 非空且与预览图不同 → 显示“加载原图”按钮。
            boolean hasOriginal = originalUrl != null && originalUrl.length() > 0
                    && !originalUrl.equals(url);
            btnOriginal.setVisibility(hasOriginal ? View.VISIBLE : View.GONE);
            if (hasOriginal) {
                btnOriginal.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadOriginalRunnable.run();
                    }
                });
            }
        }

        // 失败提示可点击：原图失败重试原图，预览失败重试预览。
        if (errorView != null) {
            errorView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (originalRequested[0]) {
                        loadOriginalRunnable.run();
                    } else {
                        loadPreviewRunnable.run();
                    }
                }
            });
        }

        if (imageView != null) {
            loadPreviewRunnable.run();
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }

        View root = dialog.findViewById(android.R.id.content);
        if (root != null) {
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }

        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialogInterface) {
                loadToken.incrementAndGet();
                if (imageView != null) {
                    imageView.setImageDrawable(null);
                    imageView.setTag(null);
                }
                if (loading != null) {
                    loading.setVisibility(View.GONE);
                }
            }
        });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
    }

    private static Activity resolveActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
