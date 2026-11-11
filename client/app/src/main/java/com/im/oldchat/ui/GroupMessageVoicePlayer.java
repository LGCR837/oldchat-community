package com.im.oldchat.ui;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.FileSortCompat;
import com.im.oldchat.util.MediaUrlResolver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class GroupMessageVoicePlayer {
    private static final String TAG = "GroupMessageVoicePlayer";

    public interface PlaybackListener {
        void onPlaybackStateChanged();
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_ERROR = 4;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final long RETRY_DELAY_BASE_MS = 350L;
    private static final int MIN_PROGRESS_NOTIFY_STEP = 8;
    private static final long MIN_PROGRESS_NOTIFY_INTERVAL_MS = 160L;

    private static final String VOICE_CACHE_DIR = "voice_cache";
    private static final long VOICE_CACHE_MAX_BYTES = 120L * 1024L * 1024L;
    private static final int VOICE_CACHE_MAX_FILES = 600;

    private final Context appContext;
    private final PlaybackListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private String playingId;
    private long playingCreatedAt;
    private int state = STATE_IDLE;
    private int downloadProgress = 0;
    private int playToken = 0;
    private File localVoiceFile;
    private String lastErrorMessage;
    private int lastNotifiedProgress = -1;
    private long lastProgressNotifyAtMs = 0L;

    public GroupMessageVoicePlayer(Context context, PlaybackListener listener) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.listener = listener;
    }

    public void play(GroupMessage msg) {
        if (msg == null) {
            return;
        }
        String url = MediaUrlResolver.resolve(msg.media_url);
        if (url == null || url.length() == 0) {
            showToast("语音地址无效");
            return;
        }
        synchronized (this) {
            if (isTargetLocked(msg)
                    && (state == STATE_DOWNLOADING || state == STATE_PREPARING || state == STATE_PLAYING)) {
                stopLocked();
                notifyChanged();
                return;
            }
        }

        stop();
        final int token;
        final File cachedFile;
        synchronized (this) {
            setPlayingMessageLocked(msg);
            token = ++playToken;
            cachedFile = resolveCacheFile(url);
            if (isCacheFileReady(cachedFile)) {
                state = STATE_PREPARING;
                downloadProgress = 100;
            } else {
                state = STATE_DOWNLOADING;
                downloadProgress = 0;
            }
            lastErrorMessage = null;
        }
        notifyChanged();

        if (isCacheFileReady(cachedFile)) {
            touchCacheFile(cachedFile);
            final File local = cachedFile;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    prepareLocalFile(local, token);
                }
            });
            trimVoiceCacheAsync();
            return;
        }

        final String targetUrl = url;
        new Thread(new Runnable() {
            @Override
            public void run() {
                downloadAndPrepare(targetUrl, token);
            }
        }, "group-voice-player").start();
    }

    public void stop() {
        synchronized (this) {
            stopLocked();
        }
        notifyChanged();
    }

    public boolean isLoading(GroupMessage msg) {
        synchronized (this) {
            return isTargetLocked(msg) && (state == STATE_DOWNLOADING || state == STATE_PREPARING);
        }
    }

    public boolean isPlaying(GroupMessage msg) {
        synchronized (this) {
            return isTargetLocked(msg) && state == STATE_PLAYING;
        }
    }

    public String getDurationLabel(GroupMessage msg, int seconds) {
        synchronized (this) {
            if (!isTargetLocked(msg)) {
                return seconds + "\"";
            }
            if (state == STATE_DOWNLOADING) {
                if (downloadProgress > 0) {
                    return "加载" + downloadProgress + "%";
                }
                return "加载中...";
            }
            if (state == STATE_PREPARING) {
                return "准备中...";
            }
            if (state == STATE_ERROR) {
                return "加载失败";
            }
            return seconds + "\"";
        }
    }

    private void downloadAndPrepare(String url, int token) {
        File out;
        try {
            out = downloadToCacheWithRetry(url, token);
        } catch (Exception e) {
            Log.e(TAG, "downloadAndPrepare failed", e);
            setErrorMessage(token, "语音加载失败，请重试");
            out = null;
        }
        if (out == null) {
            failIfToken(token, "语音加载失败，请重试");
            return;
        }

        final File file = out;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                prepareLocalFile(file, token);
            }
        });
    }

    private File downloadToCacheWithRetry(String url, int token) throws Exception {
        File cacheFile = resolveCacheFile(url);
        if (isCacheFileReady(cacheFile)) {
            touchCacheFile(cacheFile);
            return cacheFile;
        }
        String[] candidates = MediaUrlResolver.resolveCandidates(url);
        if (candidates == null || candidates.length == 0) {
            candidates = new String[]{url};
        }
        for (int attempt = 0; attempt < MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            if (!isTokenActive(token)) {
                return null;
            }
            for (int i = 0; i < candidates.length; i++) {
                if (!isTokenActive(token)) {
                    return null;
                }
                String one = candidates[i];
                if (one == null || one.length() == 0) {
                    continue;
                }
                File file = downloadToCacheOnce(one, token, cacheFile);
                if (file != null) {
                    trimVoiceCacheAsync();
                    return file;
                }
            }
            if (!isTokenActive(token)) {
                return null;
            }
            if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_BASE_MS * (attempt + 1));
                } catch (InterruptedException ignored) {
                }
            }
        }
        return null;
    }

    private File downloadToCacheOnce(String url, int token, File cacheFile) throws Exception {
        if (cacheFile == null) {
            setErrorMessage(token, "缓存不可用");
            return null;
        }
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        File target = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setUseCaches(true);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("User-Agent", "OldChat-Android/1.0");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                if (code == HttpURLConnection.HTTP_UNAVAILABLE) {
                    setErrorMessage(token, "语音加载繁忙，请稍后重试");
                } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    setErrorMessage(token, "语音文件不存在");
                } else {
                    setErrorMessage(token, "语音加载失败(" + code + ")");
                }
                Log.w(TAG, "download voice failed, http=" + code + " url=" + url);
                return null;
            }

            int total = conn.getContentLength();
            in = conn.getInputStream();

            File parent = cacheFile.getParentFile();
            if (parent == null) {
                setErrorMessage(token, "缓存不可用");
                return null;
            }
            if (!parent.exists()) {
                parent.mkdirs();
            }
            target = new File(cacheFile.getAbsolutePath() + ".part." + System.currentTimeMillis() + "." + token);
            out = new FileOutputStream(target);

            byte[] buf = new byte[4096];
            long read = 0;
            int len;
            int lastPercent = -1;
            while ((len = in.read(buf)) != -1) {
                if (!isTokenActive(token)) {
                    return null;
                }
                out.write(buf, 0, len);
                read += len;
                if (total > 0) {
                    int percent = (int) ((read * 100L) / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        updateProgress(token, percent);
                    }
                }
            }
            out.flush();
            updateProgress(token, 100);

            if (target.length() <= 0) {
                setErrorMessage(token, "语音文件为空");
                Log.w(TAG, "downloaded empty voice file");
                return null;
            }
            if (!isTokenActive(token)) {
                return null;
            }
            if (isCacheFileReady(cacheFile)) {
                target.delete();
                touchCacheFile(cacheFile);
                return cacheFile;
            }
            if (!target.renameTo(cacheFile)) {
                if (isCacheFileReady(cacheFile)) {
                    target.delete();
                    touchCacheFile(cacheFile);
                    return cacheFile;
                }
                setErrorMessage(token, "缓存写入失败");
                return null;
            }
            touchCacheFile(cacheFile);
            return cacheFile;
        } catch (Exception e) {
            setErrorMessage(token, "网络异常，请重试");
            throw e;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
            if (target != null && target.exists()) {
                if (!isTokenActive(token) || target.getName().contains(".part.")) {
                    target.delete();
                }
            }
        }
    }

    private void prepareLocalFile(final File file, final int token) {
        if (file == null) {
            failIfToken(token, "语音加载失败，请重试");
            return;
        }
        synchronized (this) {
            if (!isTokenActive(token)) {
                return;
            }
            releasePlayerLocked();
            localVoiceFile = file;
            state = STATE_PREPARING;
        }
        notifyChanged();

        try {
            final MediaPlayer player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    synchronized (GroupMessageVoicePlayer.this) {
                        if (!isTokenActive(token) || mediaPlayer != player) {
                            try {
                                mp.release();
                            } catch (Exception e) {
                            }
                            return;
                        }
                        state = STATE_PLAYING;
                    }
                    try {
                        mp.start();
                    } catch (Exception e) {
                        Log.e(TAG, "start playback failed", e);
                        invalidateCacheFile(file);
                        setErrorMessage(token, "语音播放失败");
                        failIfToken(token, "语音播放失败");
                        return;
                    }
                    notifyChanged();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    clearIfToken(token);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                    invalidateCacheFile(file);
                    setErrorMessage(token, "语音播放失败");
                    failIfToken(token, "语音播放失败");
                    return true;
                }
            });
            synchronized (this) {
                if (!isTokenActive(token)) {
                    player.release();
                    return;
                }
                mediaPlayer = player;
            }
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "prepareLocalFile failed", e);
            invalidateCacheFile(file);
            setErrorMessage(token, "语音播放失败");
            failIfToken(token, "语音播放失败");
        }
    }

    private void failIfToken(int token, String defaultMessage) {
        String message = defaultMessage;
        synchronized (this) {
            if (!isTokenActive(token)) {
                return;
            }
            releasePlayerLocked();
            state = STATE_ERROR;
            downloadProgress = 0;
            if (lastErrorMessage != null && lastErrorMessage.length() > 0) {
                message = lastErrorMessage;
            }
        }
        notifyChanged();
        showToast(message);
    }

    private void clearIfToken(int token) {
        synchronized (this) {
            if (!isTokenActive(token)) {
                return;
            }
            stopLocked();
        }
        notifyChanged();
    }

    private void updateProgress(int token, int percent) {
        boolean shouldNotify = false;
        synchronized (this) {
            if (!isTokenActive(token)) {
                return;
            }
            if (percent < 0) {
                percent = 0;
            }
            if (percent > 100) {
                percent = 100;
            }
            if (downloadProgress == percent && state == STATE_DOWNLOADING) {
                return;
            }
            state = STATE_DOWNLOADING;
            downloadProgress = percent;
            long now = System.currentTimeMillis();
            shouldNotify = lastNotifiedProgress < 0
                    || percent <= 0
                    || percent >= 100
                    || Math.abs(percent - lastNotifiedProgress) >= MIN_PROGRESS_NOTIFY_STEP
                    || now - lastProgressNotifyAtMs >= MIN_PROGRESS_NOTIFY_INTERVAL_MS;
            if (shouldNotify) {
                lastNotifiedProgress = percent;
                lastProgressNotifyAtMs = now;
            }
        }
        if (shouldNotify) {
            notifyChanged();
        }
    }

    private void setErrorMessage(int token, String message) {
        if (message == null || message.length() == 0) {
            return;
        }
        synchronized (this) {
            if (!isTokenActive(token)) {
                return;
            }
            lastErrorMessage = message;
        }
    }

    private boolean isTokenActive(int token) {
        synchronized (this) {
            return token == playToken;
        }
    }

    private void stopLocked() {
        playToken++;
        releasePlayerLocked();
        playingId = null;
        playingCreatedAt = 0;
        state = STATE_IDLE;
        downloadProgress = 0;
        lastErrorMessage = null;
        lastNotifiedProgress = -1;
        lastProgressNotifyAtMs = 0L;
    }

    private void releasePlayerLocked() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception e) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        localVoiceFile = null;
    }

    private void setPlayingMessageLocked(GroupMessage msg) {
        playingId = msg.id;
        playingCreatedAt = msg.created_at;
    }

    private boolean isTargetLocked(GroupMessage msg) {
        if (msg == null) {
            return false;
        }
        if (playingId != null && msg.id != null) {
            return playingId.equals(msg.id);
        }
        return playingId == null && playingCreatedAt > 0 && msg.created_at == playingCreatedAt;
    }

    private void notifyChanged() {
        if (listener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onPlaybackStateChanged();
                }
            });
        }
    }

    private void showToast(final String message) {
        if (appContext == null || message == null || message.length() == 0) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private File resolveCacheFile(String url) {
        if (appContext == null || url == null || url.length() == 0) {
            return null;
        }
        File root = appContext.getFilesDir();
        if (root == null) {
            root = appContext.getCacheDir();
        }
        if (root == null) {
            return null;
        }
        File dir = new File(root, VOICE_CACHE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String key = buildCacheKey(url);
        String ext = guessVoiceExt(url);
        return new File(dir, key + ext);
    }

    private String guessVoiceExt(String url) {
        if (url == null) {
            return ".cache";
        }
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        int h = lower.indexOf('#');
        if (h >= 0) {
            lower = lower.substring(0, h);
        }
        String[] exts = new String[]{".3gp", ".amr", ".aac", ".mp3", ".m4a", ".wav", ".ogg"};
        for (int i = 0; i < exts.length; i++) {
            if (lower.endsWith(exts[i])) {
                return exts[i];
            }
        }
        return ".cache";
    }

    private String buildCacheKey(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] out = digest.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (int i = 0; i < out.length; i++) {
                int v = out[i] & 0xFF;
                if (v < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private boolean isCacheFileReady(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    private void touchCacheFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.setLastModified(System.currentTimeMillis());
        } catch (Exception ignore) {
        }
    }

    private void invalidateCacheFile(File file) {
        if (file == null) {
            return;
        }
        if (isVoiceCacheFile(file) && file.exists()) {
            try {
                file.delete();
            } catch (Exception ignore) {
            }
        }
    }

    private boolean isVoiceCacheFile(File file) {
        if (file == null) {
            return false;
        }
        File parent = file.getParentFile();
        return parent != null && VOICE_CACHE_DIR.equals(parent.getName());
    }

    private void trimVoiceCacheAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                trimVoiceCache();
            }
        }, "group-voice-cache-trim").start();
    }

    private void trimVoiceCache() {
        if (appContext == null) {
            return;
        }
        File root = appContext.getFilesDir();
        if (root == null) {
            root = appContext.getCacheDir();
        }
        if (root == null) {
            return;
        }
        File dir = new File(root, VOICE_CACHE_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        FileSortCompat.sortByLastModifiedAsc(files);

        long total = 0;
        int count = 0;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            total += file.length();
            count++;
        }
        for (int i = 0; i < files.length && (total > VOICE_CACHE_MAX_BYTES || count > VOICE_CACHE_MAX_FILES); i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            long len = file.length();
            if (file.delete()) {
                total -= len;
                count--;
            }
        }
    }
}
