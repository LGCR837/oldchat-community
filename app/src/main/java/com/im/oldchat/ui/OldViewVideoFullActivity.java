package com.im.oldchat.ui;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.FileProvider;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.MediaUrlResolver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class OldViewVideoFullActivity extends BaseActivity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_RESULT_POSITION = "result_position";
    public static final String EXTRA_COMPLETED = "completed";

    private VideoView vvPlayer;
    private View layoutLoading;
    private TextView tvLoadingHint;
    private View layoutTop;
    private View layoutControls;
    private ImageView btnPlayPause;
    private SeekBar seekBar;
    private TextView tvTime;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean seeking;
    private int startPosition = 0;
    private int lastPosition = 0;
    private boolean completed = false;
    private String sourceVideoUrl;
    private String[] videoCandidates;
    private int videoCandidateIndex;
    private String videoUrl;
    private boolean triedLocal = false;
    private boolean playingLocal = false;
    private boolean destroyed;
    private File cachedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_old_view_fullscreen);

        vvPlayer = (VideoView) findViewByIdCompat(R.id.vvOldViewFullPlayer);
        layoutLoading = (View) findViewByIdCompat(R.id.layoutOldViewFullLoading);
        tvLoadingHint = (TextView) findViewByIdCompat(R.id.tvOldViewFullHint);
        layoutTop = (View) findViewByIdCompat(R.id.layoutOldViewFullTop);
        layoutControls = (View) findViewByIdCompat(R.id.layoutOldViewFullControls);
        btnPlayPause = (ImageView) findViewByIdCompat(R.id.btnOldViewFullPlayPause);
        seekBar = (SeekBar) findViewByIdCompat(R.id.sbOldViewFullSeek);
        tvTime = (TextView) findViewByIdCompat(R.id.tvOldViewFullTime);

        TextView tvTitle = (TextView) findViewByIdCompat(R.id.tvOldViewFullTitle);
        View btnBack = (View) findViewByIdCompat(R.id.btnOldViewFullBack);
        if (btnBack instanceof ImageView) {
            ((ImageView) btnBack).setColorFilter(0xFFFFFFFF);
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finishWithResult();
                }
            });
        }

        Intent intent = getIntent();
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        startPosition = intent != null ? intent.getIntExtra(EXTRA_POSITION, 0) : 0;
        if (tvTitle != null) {
            tvTitle.setText(title != null ? title : "");
        }
        if (url == null || url.length() == 0) {
            Toast.makeText(this, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            finishWithResult();
            return;
        }
        sourceVideoUrl = url;
        videoCandidates = MediaUrlResolver.resolveCandidates(sourceVideoUrl);
        videoCandidateIndex = 0;
        videoUrl = firstCandidate(videoCandidates);
        if (videoUrl == null || videoUrl.length() == 0) {
            Toast.makeText(this, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            finishWithResult();
            return;
        }

        vvPlayer.setMediaController(null);
        bindPlayerListeners();
        bindModernControls();
        playUri(Uri.parse(videoUrl), "视频加载中...");
    }

    private void bindPlayerListeners() {
        if (vvPlayer == null) {
            return;
        }
        vvPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                showLoading(false, null);
                if (startPosition > 0) {
                    try {
                        vvPlayer.seekTo(startPosition);
                    } catch (Exception e) {
                    }
                }
                startPosition = 0;
                try {
                    vvPlayer.start();
                    updatePlayPauseIcon();
                    scheduleControlsHide();
                } catch (Exception e) {
                    showLoading(false, null);
                    openSystemPlayer();
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            vvPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        showLoading(true, "缓冲中...");
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                            || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        showLoading(false, null);
                    }
                    return false;
                }
            });
        }

        vvPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                completed = true;
                finishWithResult();
            }
        });

        vvPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                lastPosition = safeGetPosition();
                showLoading(false, null);
                if (!playingLocal && tryNextNetworkCandidate()) {
                    return true;
                }
                if (!triedLocal && !playingLocal && videoUrl != null && videoUrl.startsWith("http")) {
                    triedLocal = true;
                    tryCachePlayback();
                    return true;
                }
                openSystemPlayer();
                return true;
            }
        });
    }

    private void playUri(Uri uri, String hint) {
        if (vvPlayer == null || uri == null) {
            return;
        }
        showLoading(true, hint);
        try {
            vvPlayer.stopPlayback();
        } catch (Exception e) {
        }
        vvPlayer.setVideoURI(uri);
        vvPlayer.requestFocus();
    }

    private void bindModernControls() {
        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        if (vvPlayer.isPlaying()) {
                            vvPlayer.pause();
                        } else {
                            vvPlayer.start();
                        }
                        updatePlayPauseIcon();
                        showControls();
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    seeking = true;
                    uiHandler.removeCallbacks(hideControlsRunnable);
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    try {
                        int duration = vvPlayer.getDuration();
                        if (duration > 0) {
                            vvPlayer.seekTo((int) (duration * (bar.getProgress() / 1000f)));
                        }
                    } catch (Exception ignored) {
                    }
                    seeking = false;
                    scheduleControlsHide();
                }
            });
        }
        View root = (View) findViewByIdCompat(R.id.layoutOldViewFullRoot);
        if (root != null) {
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (layoutControls != null && layoutControls.getVisibility() == View.VISIBLE) {
                        hideControls();
                    } else {
                        showControls();
                    }
                }
            });
        }
        uiHandler.post(progressRunnable);
    }

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            try {
                int position = vvPlayer != null ? vvPlayer.getCurrentPosition() : 0;
                int duration = vvPlayer != null ? vvPlayer.getDuration() : 0;
                if (!seeking && seekBar != null && duration > 0) {
                    seekBar.setProgress((int) (position * 1000f / duration));
                }
                if (tvTime != null) {
                    tvTime.setText(formatTime(position) + " / " + formatTime(duration));
                }
                updatePlayPauseIcon();
            } catch (Exception ignored) {
            }
            uiHandler.postDelayed(this, 500L);
        }
    };

    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (vvPlayer != null && vvPlayer.isPlaying() && !seeking) {
                    hideControls();
                }
            } catch (Exception ignored) {
            }
        }
    };

    private void updatePlayPauseIcon() {
        if (btnPlayPause == null) {
            return;
        }
        boolean playing = false;
        try {
            playing = vvPlayer != null && vvPlayer.isPlaying();
        } catch (Exception ignored) {
        }
        btnPlayPause.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    private void showControls() {
        if (layoutTop != null) layoutTop.setVisibility(View.VISIBLE);
        if (layoutControls != null) layoutControls.setVisibility(View.VISIBLE);
        scheduleControlsHide();
    }

    private void hideControls() {
        if (layoutTop != null) layoutTop.setVisibility(View.GONE);
        if (layoutControls != null) layoutControls.setVisibility(View.GONE);
    }

    private void scheduleControlsHide() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        uiHandler.postDelayed(hideControlsRunnable, 2600L);
    }

    private String formatTime(int millis) {
        int total = Math.max(0, millis) / 1000;
        int minutes = total / 60;
        int seconds = total % 60;
        return (minutes < 10 ? "0" : "") + minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private void showLoading(boolean show, String hint) {
        if (layoutLoading != null) {
            layoutLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (show && tvLoadingHint != null && hint != null && hint.length() > 0) {
            tvLoadingHint.setText(hint);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (vvPlayer != null && vvPlayer.isPlaying()) {
                vvPlayer.pause();
            }
            lastPosition = safeGetPosition();
        } catch (Exception e) {
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacks(progressRunnable);
        uiHandler.removeCallbacks(hideControlsRunnable);
        if (vvPlayer != null) {
            try {
                vvPlayer.stopPlayback();
            } catch (Exception e) {
            }
        }
        if (cachedFile != null && cachedFile.exists()) {
            cachedFile.delete();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    private int safeGetPosition() {
        try {
            if (vvPlayer != null) {
                return Math.max(0, vvPlayer.getCurrentPosition());
            }
        } catch (Exception e) {
        }
        return 0;
    }

    private void finishWithResult() {
        if (lastPosition <= 0) {
            lastPosition = safeGetPosition();
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_POSITION, lastPosition);
        data.putExtra(EXTRA_COMPLETED, completed);
        setResult(RESULT_OK, data);
        finish();
    }

    private boolean tryNextNetworkCandidate() {
        if (videoCandidates == null) {
            return false;
        }
        while (++videoCandidateIndex < videoCandidates.length) {
            String next = videoCandidates[videoCandidateIndex];
            if (next == null || next.length() == 0 || next.equals(videoUrl)) {
                continue;
            }
            videoUrl = next;
            if (lastPosition > 0) {
                startPosition = lastPosition;
            }
            playUri(Uri.parse(videoUrl), "切换备用线路...");
            return true;
        }
        return false;
    }

    private String firstCandidate(String[] candidates) {
        if (candidates == null) {
            return null;
        }
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] != null && candidates[i].length() > 0) {
                return candidates[i];
            }
        }
        return null;
    }

    private void tryCachePlayback() {
        showLoading(true, "网络波动，尝试缓存播放...");
        final String[] candidates = videoCandidates;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File file = downloadCandidatesToCache(candidates);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || isFinishing()) {
                            if (file != null && file.exists()) {
                                file.delete();
                            }
                            return;
                        }
                        if (file == null || !file.exists()) {
                            showLoading(false, null);
                            openSystemPlayer();
                            return;
                        }
                        cachedFile = file;
                        playingLocal = true;
                        if (lastPosition > 0) {
                            startPosition = lastPosition;
                        }
                        playUri(Uri.fromFile(file), "正在加载本地缓存...");
                    }
                });
            }
        }).start();
    }

    private File downloadCandidatesToCache(String[] candidates) {
        if (candidates == null || candidates.length == 0) {
            return downloadToCache(videoUrl);
        }
        for (int i = 0; i < candidates.length; i++) {
            if (destroyed) {
                return null;
            }
            File file = downloadToCache(candidates[i]);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
        }
        return null;
    }

    private File downloadToCache(String url) {
        if (url == null || url.length() == 0) {
            return null;
        }
        File dir = new File(getCacheDir(), "video_play_cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String ext = guessExtension(url);
        File out = new File(dir, "video_" + System.currentTimeMillis() + ext);
        InputStream is = null;
        FileOutputStream os = null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() != 200) {
                return null;
            }
            is = conn.getInputStream();
            os = new FileOutputStream(out);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
            return out;
        } catch (Exception e) {
            if (out.exists()) {
                out.delete();
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (Exception e) {
                }
            }
        }
    }

    private String guessExtension(String url) {
        if (url != null) {
            int q = url.indexOf('?');
            if (q >= 0) {
                url = url.substring(0, q);
            }
            int h = url.indexOf('#');
            if (h >= 0) {
                url = url.substring(0, h);
            }
            int idx = url.lastIndexOf('.');
            if (idx >= 0 && idx < url.length() - 1) {
                String ext = url.substring(idx).toLowerCase();
                if (ext.length() <= 5) {
                    return ext;
                }
            }
        }
        return ".mp4";
    }

    private void openSystemPlayer() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri targetUri;
            if (playingLocal && cachedFile != null && cachedFile.exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    targetUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cachedFile);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    targetUri = Uri.fromFile(cachedFile);
                }
            } else if (videoUrl != null && videoUrl.length() > 0) {
                targetUri = Uri.parse(videoUrl);
            } else {
                Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show();
                finishWithResult();
                return;
            }
            intent.setDataAndType(targetUri, "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show();
        }
        finishWithResult();
    }
}
