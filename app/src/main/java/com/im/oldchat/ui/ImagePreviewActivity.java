package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.FileDownloadStorage;
import com.im.oldchat.util.ImageLoader;

import java.io.File;
import java.lang.ref.WeakReference;

public class ImagePreviewActivity extends BaseActivity {
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_ORIGINAL_URL = "original_url";
    private ImageView imageView;
    private View loadingView;
    private View errorView;
    private TextView btnOriginal;
    private String previewUrl;
    private String originalUrl;
    private int loadToken;
    private boolean destroyed;
    private boolean closeTracked = false;
    private boolean originalRequested;

    public static void start(Context context, String url) {
        start(context, url, null);
    }

    public static void start(Context context, String url, String originalUrl) {
        if (context == null || url == null || url.isEmpty()) {
            return;
        }
        ImagePreviewReturnTracker.markLaunchRequested();
        Intent intent = new Intent(context, ImagePreviewActivity.class);
        intent.putExtra(EXTRA_URL, url);
        if (originalUrl != null && originalUrl.length() > 0) {
            intent.putExtra(EXTRA_ORIGINAL_URL, originalUrl);
        }
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImagePreviewReturnTracker.markOpened();
        setContentView(R.layout.activity_image_preview);

        imageView = (ImageView) findViewByIdCompat(R.id.ivPreview);
        ImageView btnBack = (ImageView) findViewByIdCompat(R.id.btnPreviewBack);
        loadingView = findViewByIdCompat(R.id.pbPreviewLoading);
        errorView = findViewByIdCompat(R.id.tvPreviewError);
        btnOriginal = (TextView) findViewByIdCompat(R.id.btnPreviewOriginal);
        previewUrl = getIntent().getStringExtra(EXTRA_URL);
        originalUrl = getIntent().getStringExtra(EXTRA_ORIGINAL_URL);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (errorView != null) {
            errorView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 原图下载失败后点击重试应重新加载原图，而不是预览图。
                    if (originalRequested) {
                        loadOriginal();
                    } else {
                        loadPreview();
                    }
                }
            });
        }
        if (btnOriginal != null) {
            boolean hasOriginal = originalUrl != null && originalUrl.length() > 0
                    && !originalUrl.equals(previewUrl);
            btnOriginal.setVisibility(hasOriginal ? View.VISIBLE : View.GONE);
            if (hasOriginal) {
                btnOriginal.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadOriginal();
                    }
                });
            }
        }
        loadPreview();
    }

    private void loadPreview() {
        if (imageView == null || previewUrl == null || previewUrl.length() == 0) {
            return;
        }
        originalRequested = false;
        loadToken++;
        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (errorView != null) {
            errorView.setVisibility(View.GONE);
        }
        ImageLoader.loadLarge(imageView, previewUrl,
                new PreviewLoadListener(this, loadToken));
    }

    private void loadOriginal() {
        if (originalUrl == null || originalUrl.length() == 0 || btnOriginal == null) {
            return;
        }
        originalRequested = true;
        btnOriginal.setEnabled(false);
        btnOriginal.setText("正在下载原图...");
        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (errorView != null) {
            errorView.setVisibility(View.GONE);
        }
        final int token = ++loadToken;
        final Context context = getApplicationContext();
        final String tokenStr = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File target = new File(context.getCacheDir(), "original_preview_" + System.currentTimeMillis() + ".img");
                final FileDownloadStorage.Result result = FileDownloadStorage.downloadToFile(
                        originalUrl, target, tokenStr, null);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || token != loadToken) {
                            return;
                        }
                        if (btnOriginal != null) {
                            btnOriginal.setEnabled(true);
                            btnOriginal.setText(getString(R.string.image_preview_original));
                        }
                        if (loadingView != null) {
                            loadingView.setVisibility(View.GONE);
                        }
                        if (result.success && result.path != null && result.path.length() > 0) {
                            // 本地文件路径不能走 resolveCandidates（会被拼成 http），用本地解码加载。
                            ImageLoader.loadLocalFile(imageView, result.path, null);
                        } else {
                            if (errorView != null) {
                                errorView.setVisibility(View.VISIBLE);
                            }
                            Toast.makeText(ImagePreviewActivity.this, "原图加载失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private static class PreviewLoadListener implements ImageLoader.ImageLoadResultListener {
        private final WeakReference<ImagePreviewActivity> activityRef;
        private final int token;

        PreviewLoadListener(ImagePreviewActivity activity, int token) {
            this.activityRef = new WeakReference<ImagePreviewActivity>(activity);
            this.token = token;
        }

        @Override
        public void onComplete(String url) {
        }

        @Override
        public void onResult(String url, boolean success) {
            ImagePreviewActivity activity = activityRef.get();
            if (activity == null || activity.destroyed || token != activity.loadToken) {
                return;
            }
            if (activity.loadingView != null) {
                activity.loadingView.setVisibility(View.GONE);
            }
            if (!success && activity.errorView != null) {
                activity.errorView.setVisibility(View.VISIBLE);
            }
        }
    }
}
