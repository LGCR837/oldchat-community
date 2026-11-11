package com.im.oldchat.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;

import com.im.oldchat.R;
import com.im.oldchat.ui.MusicPlayerActivity;
import com.im.oldchat.util.FileSortCompat;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.NotificationChannelCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;

public class MusicPlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String ACTION_PLAY_SONG = "com.im.oldchat.action.music.PLAY_SONG";
    public static final String ACTION_CACHE_SONG = "com.im.oldchat.action.music.CACHE_SONG";
    public static final String ACTION_TOGGLE_PLAY = "com.im.oldchat.action.music.TOGGLE_PLAY";
    public static final String ACTION_PAUSE = "com.im.oldchat.action.music.PAUSE";
    public static final String ACTION_STOP = "com.im.oldchat.action.music.STOP";
    public static final String ACTION_SEEK = "com.im.oldchat.action.music.SEEK";
    public static final String ACTION_SEEK_RELATIVE = "com.im.oldchat.action.music.SEEK_RELATIVE";
    public static final String ACTION_TOGGLE_REPEAT_ONE = "com.im.oldchat.action.music.TOGGLE_REPEAT_ONE";
    public static final String ACTION_UPDATE_ITEM_META = "com.im.oldchat.action.music.UPDATE_ITEM_META";
    public static final String ACTION_REQUEST_STATE = "com.im.oldchat.action.music.REQUEST_STATE";

    public static final String ACTION_STATE_CHANGED = "com.im.oldchat.action.music.STATE_CHANGED";
    public static final String ACTION_CACHE_RESULT = "com.im.oldchat.action.music.CACHE_RESULT";

    public static final String EXTRA_SONG_NAME = "song_name";
    public static final String EXTRA_SONG_URL = "song_url";
    public static final String EXTRA_COVER_URL = "cover_url";
    public static final String EXTRA_LYRICS_URL = "lyrics_url";
    public static final String EXTRA_OWNER_UID = "owner_uid";
    public static final String EXTRA_OWNER_NAME = "owner_name";
    public static final String EXTRA_OWNER_TITLE = "owner_title";
    public static final String EXTRA_OWNER_AVATAR = "owner_avatar";
    public static final String EXTRA_ITEM_ID = "item_id";
    public static final String EXTRA_ITEM_LIKES = "item_likes";
    public static final String EXTRA_ITEM_COMMENTS = "item_comments";
    public static final String EXTRA_ITEM_LIKED = "item_liked";
    public static final String EXTRA_CACHE_URL = "cache_url";
    public static final String EXTRA_CACHE_OK = "cache_ok";
    public static final String EXTRA_CACHE_SIZE = "cache_size";
    public static final String EXTRA_CACHE_ERROR = "cache_error";

    public static final String EXTRA_IS_PREPARING = "is_preparing";
    public static final String EXTRA_IS_PLAYING = "is_playing";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_POSITION_MS = "position_ms";
    public static final String EXTRA_SEEK_DELTA_MS = "seek_delta_ms";
    public static final String EXTRA_REPEAT_ONE = "repeat_one";
    public static final String EXTRA_WAVE_LEVEL = "wave_level";
    public static final String EXTRA_WAVE_BINS = "wave_bins";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_OPEN_NOW_PLAYING = "open_now_playing";

    private static final String CHANNEL_ID = "oldchat_music_playback";
    private static final int NOTIFY_ID = 5201;
    private static final int PROGRESS_TICK_MS = 220;
    private static final int PREPARE_TIMEOUT_MS = 12000;
    private static final int WAVE_BIN_COUNT = 15;

    private static final String MUSIC_CACHE_DIR = "music_player_cache";
    private static final long MUSIC_CACHE_MAX_BYTES = 220L * 1024L * 1024L;
    private static final int MUSIC_CACHE_MAX_FILES = 120;

    private MediaPlayer mediaPlayer;
    private Visualizer visualizer;
    private boolean isPreparing;
    private int prepareToken;
    private int currentDurationMs;
    private int currentPositionMs;
    private boolean repeatOne;

    private String currentSongName = "";
    private String currentSongUrl = "";
    private String currentCoverUrl = "";
    private String currentLyricsUrl = "";
    private String currentOwnerUid = "";
    private String currentOwnerName = "";
    private String currentOwnerTitle = "";
    private String currentOwnerAvatar = "";
    private String currentItemId = "";
    private int currentItemLikes;
    private int currentItemComments;
    private boolean currentItemLiked;
    private volatile int currentWaveLevel;
    private final int[] currentWaveBins = new int[WAVE_BIN_COUNT];
    private final Object waveLock = new Object();
    private volatile long lastFftCaptureAtMs;

    private AudioManager audioManager;
    private boolean resumeOnFocusGain;
    private volatile boolean isTrimmingCache;
    private final Object cacheWarmLock = new Object();
    private final HashSet<String> warmingCacheKeys = new HashSet<String>();

    private LocalBroadcastManager localBroadcastManager;

    private final Handler progressHandler = new Handler();
    private Runnable prepareTimeoutRunnable;
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updateProgressFromPlayer();
            pushState("");
            if (isPlayingInternal()) {
                progressHandler.postDelayed(this, PROGRESS_TICK_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_REQUEST_STATE : intent.getAction();
        if (ACTION_PLAY_SONG.equals(action)) {
            handlePlaySong(intent);
        } else if (ACTION_CACHE_SONG.equals(action)) {
            handleCacheSong(intent);
        } else if (ACTION_TOGGLE_PLAY.equals(action)) {
            handleTogglePlay();
        } else if (ACTION_PAUSE.equals(action)) {
            pauseInternal();
        } else if (ACTION_STOP.equals(action)) {
            stopAndExit();
        } else if (ACTION_SEEK.equals(action)) {
            int to = intent == null ? 0 : intent.getIntExtra(EXTRA_POSITION_MS, 0);
            seekToInternal(to);
        } else if (ACTION_SEEK_RELATIVE.equals(action)) {
            int delta = intent == null ? 0 : intent.getIntExtra(EXTRA_SEEK_DELTA_MS, 0);
            seekByInternal(delta);
        } else if (ACTION_TOGGLE_REPEAT_ONE.equals(action)) {
            repeatOne = !repeatOne;
            pushState("");
            updateForegroundNotification();
        } else if (ACTION_UPDATE_ITEM_META.equals(action)) {
            handleUpdateItemMeta(intent);
        } else if (ACTION_REQUEST_STATE.equals(action)) {
            updateProgressFromPlayer();
            pushState("");
        }
        return START_STICKY;
    }

    private void handleCacheSong(Intent intent) {
        String songUrl = intent == null ? "" : strings(intent.getStringExtra(EXTRA_SONG_URL));
        String resolvedUrl = MediaUrlResolver.resolve(songUrl);
        if (resolvedUrl.length() == 0) {
            dispatchCacheResult(songUrl, false, 0L, "歌曲链接不可用");
            return;
        }
        warmMusicCacheAsync(resolvedUrl, true);
    }

    @Override
    public void onDestroy() {
        stopProgressTicker();
        releasePlayerInternal();
        abandonAudioFocusSafe();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocusGain = isPlayingInternal();
            pauseInternal();
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            pauseInternal();
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                startInternal();
            }
        }
    }

    private void handlePlaySong(Intent intent) {
        if (intent == null) {
            return;
        }
        String songUrl = strings(intent.getStringExtra(EXTRA_SONG_URL));
        String resolvedUrl = MediaUrlResolver.resolve(songUrl);
        if (resolvedUrl.length() == 0) {
            pushState("歌曲链接不可用");
            return;
        }

        String songName = strings(intent.getStringExtra(EXTRA_SONG_NAME));
        String coverUrl = strings(intent.getStringExtra(EXTRA_COVER_URL));
        String lyricsUrl = strings(intent.getStringExtra(EXTRA_LYRICS_URL));
        String ownerUid = strings(intent.getStringExtra(EXTRA_OWNER_UID));
        String ownerName = strings(intent.getStringExtra(EXTRA_OWNER_NAME));
        String ownerTitle = strings(intent.getStringExtra(EXTRA_OWNER_TITLE));
        String ownerAvatar = strings(intent.getStringExtra(EXTRA_OWNER_AVATAR));
        String itemId = strings(intent.getStringExtra(EXTRA_ITEM_ID));
        int itemLikes = Math.max(0, intent.getIntExtra(EXTRA_ITEM_LIKES, 0));
        int itemComments = Math.max(0, intent.getIntExtra(EXTRA_ITEM_COMMENTS, 0));
        boolean itemLiked = intent.getBooleanExtra(EXTRA_ITEM_LIKED, false);

        boolean sameSong = resolvedUrl.equals(currentSongUrl);
        if (sameSong && (mediaPlayer != null || isPreparing)) {
            currentSongName = songName;
            currentCoverUrl = coverUrl;
            currentLyricsUrl = lyricsUrl;
            currentOwnerUid = ownerUid;
            currentOwnerName = ownerName;
            currentOwnerTitle = ownerTitle;
            currentOwnerAvatar = ownerAvatar;
            currentItemId = itemId;
            currentItemLikes = itemLikes;
            currentItemComments = itemComments;
            currentItemLiked = itemLiked;
            if (!isPreparing && !isPlayingInternal()) {
                if (isPlaybackCompleted()) {
                    seekToInternal(0);
                }
                startInternal();
            }
            updateProgressFromPlayer();
            pushState("");
            updateForegroundNotification();
            return;
        }

        cancelPrepareTimeout();
        currentSongName = songName;
        currentSongUrl = resolvedUrl;
        currentCoverUrl = coverUrl;
        currentLyricsUrl = lyricsUrl;
        currentOwnerUid = ownerUid;
        currentOwnerName = ownerName;
        currentOwnerTitle = ownerTitle;
        currentOwnerAvatar = ownerAvatar;
        currentItemId = itemId;
        currentItemLikes = itemLikes;
        currentItemComments = itemComments;
        currentItemLiked = itemLiked;

        isPreparing = true;
        currentDurationMs = 0;
        currentPositionMs = 0;
        pushState("");
        updateForegroundNotification();

        final int token = ++prepareToken;
        File cachedFile = resolveMusicCacheFile(resolvedUrl);
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            try {
                cachedFile.setLastModified(System.currentTimeMillis());
            } catch (Exception e) {
            }
            prepareFromFile(cachedFile, resolvedUrl, token);
            return;
        }

        prepareFromUrl(resolvedUrl, token);
        if (shouldWarmCacheForPlayback(resolvedUrl)) {
            warmMusicCacheAsync(resolvedUrl);
        }
    }

    private void handleUpdateItemMeta(Intent intent) {
        if (intent == null) {
            return;
        }
        String itemId = strings(intent.getStringExtra(EXTRA_ITEM_ID));
        if (itemId.length() == 0) {
            return;
        }
        currentItemId = itemId;
        currentItemLikes = Math.max(0, intent.getIntExtra(EXTRA_ITEM_LIKES, currentItemLikes));
        currentItemComments = Math.max(0, intent.getIntExtra(EXTRA_ITEM_COMMENTS, currentItemComments));
        currentItemLiked = intent.getBooleanExtra(EXTRA_ITEM_LIKED, currentItemLiked);
        pushState("");
    }

    private void prepareFromUrl(final String url, final int token) {
        releasePlayerInternal();
        try {
            mediaPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                MusicPlaybackApi21.configure(mediaPlayer);
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (token != prepareToken || mp != mediaPlayer) {
                        return;
                    }
                    MediaUrlResolver.reportLineSuccess(url);
                    cancelPrepareTimeout();
                    attachVisualizerSafe(mp);
                    isPreparing = false;
                    try {
                        currentDurationMs = mp.getDuration();
                    } catch (Exception e) {
                        currentDurationMs = 0;
                    }
                    currentPositionMs = 0;
                    startInternal();
                    trimMusicCacheAsync();
                }
            });
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (token != prepareToken || mp != mediaPlayer) {
                        return;
                    }
                    if (repeatOne) {
                        seekToInternal(0);
                        startInternal();
                        return;
                    }
                    clearWaveData();
                    stopProgressTicker();
                    updateProgressFromPlayer();
                    pushState("");
                    updateForegroundNotification();
                }
            });
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (token != prepareToken || mp != mediaPlayer) {
                        return true;
                    }
                    MediaUrlResolver.reportLineFailure(url);
                    cancelPrepareTimeout();
                    if (tryFallbackPrepare(url, token)) {
                        return true;
                    }
                    isPreparing = false;
                    stopProgressTicker();
                    releasePlayerInternal();
                    pushState("播放失败");
                    updateForegroundNotification();
                    return true;
                }
            });
            mediaPlayer.prepareAsync();
            schedulePrepareTimeout(url, token);
        } catch (Exception e) {
            MediaUrlResolver.reportLineFailure(url);
            cancelPrepareTimeout();
            if (tryFallbackPrepare(url, token)) {
                return;
            }
            isPreparing = false;
            releasePlayerInternal();
            pushState("播放失败");
            updateForegroundNotification();
        }
    }

    private boolean tryFallbackPrepare(String currentUrl, int token) {
        if (token != prepareToken) {
            return false;
        }
        String fallbackUrl = MediaUrlResolver.resolveNextCandidate(currentUrl);
        if (fallbackUrl == null || fallbackUrl.length() == 0 || fallbackUrl.equals(currentUrl)) {
            return false;
        }
        currentSongUrl = fallbackUrl;
        // 每次回退都生成新代次，使已释放播放器的迟到回调立即失效。
        int fallbackToken = ++prepareToken;
        prepareFromUrl(fallbackUrl, fallbackToken);
        if (shouldWarmCacheForPlayback(fallbackUrl)) {
            warmMusicCacheAsync(fallbackUrl);
        }
        return true;
    }

    private void handleTogglePlay() {
        if (isPreparing) {
            pushState("");
            return;
        }
        if (mediaPlayer == null) {
            if (currentSongUrl != null && currentSongUrl.length() > 0) {
                Intent replay = new Intent(this, MusicPlaybackService.class);
                replay.setAction(ACTION_PLAY_SONG);
                replay.putExtra(EXTRA_SONG_NAME, currentSongName);
                replay.putExtra(EXTRA_SONG_URL, currentSongUrl);
                replay.putExtra(EXTRA_COVER_URL, currentCoverUrl);
                replay.putExtra(EXTRA_LYRICS_URL, currentLyricsUrl);
                replay.putExtra(EXTRA_OWNER_UID, currentOwnerUid);
                replay.putExtra(EXTRA_OWNER_NAME, currentOwnerName);
                replay.putExtra(EXTRA_OWNER_TITLE, currentOwnerTitle);
                replay.putExtra(EXTRA_OWNER_AVATAR, currentOwnerAvatar);
                replay.putExtra(EXTRA_ITEM_ID, currentItemId);
                replay.putExtra(EXTRA_ITEM_LIKES, currentItemLikes);
                replay.putExtra(EXTRA_ITEM_COMMENTS, currentItemComments);
                replay.putExtra(EXTRA_ITEM_LIKED, currentItemLiked);
                onStartCommand(replay, 0, 0);
            }
            return;
        }
        if (isPlayingInternal()) {
            pauseInternal();
        } else {
            startInternal();
        }
    }

    private void prepareFromFile(final File file, final String fallbackUrl, final int token) {
        releasePlayerInternal();
        try {
            mediaPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                MusicPlaybackApi21.configure(mediaPlayer);
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (token != prepareToken || mp != mediaPlayer) {
                        return;
                    }
                    cancelPrepareTimeout();
                    attachVisualizerSafe(mp);
                    isPreparing = false;
                    try {
                        currentDurationMs = mp.getDuration();
                    } catch (Exception e) {
                        currentDurationMs = 0;
                    }
                    currentPositionMs = 0;
                    startInternal();
                    trimMusicCacheAsync();
                }
            });
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (token != prepareToken || mp != mediaPlayer) {
                        return;
                    }
                    if (repeatOne) {
                        seekToInternal(0);
                        startInternal();
                        return;
                    }
                    clearWaveData();
                    stopProgressTicker();
                    updateProgressFromPlayer();
                    pushState("");
                    updateForegroundNotification();
                }
            });
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (token != prepareToken || mp != mediaPlayer) {
                        return true;
                    }
                    cancelPrepareTimeout();
                    stopProgressTicker();
                    releasePlayerInternal();
                    if (file != null) {
                        try {
                            file.delete();
                        } catch (Exception ignored) {
                        }
                    }
                    if (fallbackUrl != null && fallbackUrl.length() > 0 && token == prepareToken) {
                        int fallbackToken = ++prepareToken;
                        prepareFromUrl(fallbackUrl, fallbackToken);
                        if (shouldWarmCacheForPlayback(fallbackUrl)) {
                            warmMusicCacheAsync(fallbackUrl);
                        }
                        return true;
                    }
                    isPreparing = false;
                    pushState("播放失败");
                    updateForegroundNotification();
                    return true;
                }
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            cancelPrepareTimeout();
            releasePlayerInternal();
            if (file != null) {
                try {
                    file.delete();
                } catch (Exception ignored) {
                }
            }
            if (fallbackUrl != null && fallbackUrl.length() > 0 && token == prepareToken) {
                int fallbackToken = ++prepareToken;
                prepareFromUrl(fallbackUrl, fallbackToken);
                if (shouldWarmCacheForPlayback(fallbackUrl)) {
                    warmMusicCacheAsync(fallbackUrl);
                }
                return;
            }
            isPreparing = false;
            pushState("播放失败");
            updateForegroundNotification();
        }
    }

    private void startInternal() {
        if (mediaPlayer == null) {
            return;
        }
        if (!requestAudioFocusSafe()) {
            pushState("音频焦点被占用");
            updateForegroundNotification();
            return;
        }
        try {
            mediaPlayer.start();
            setVisualizerEnabledSafe(true);
            startProgressTicker();
            pushState("");
            updateForegroundNotification();
        } catch (Exception e) {
            pushState("播放失败");
            updateForegroundNotification();
        }
    }

    private void pauseInternal() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (Exception e) {
            }
        }
        stopProgressTicker();
        clearWaveData();
        setVisualizerEnabledSafe(false);
        updateProgressFromPlayer();
        pushState("");
        updateForegroundNotification();
        abandonAudioFocusSafe();
    }

    private void seekToInternal(int positionMs) {
        if (mediaPlayer == null) {
            return;
        }
        if (positionMs < 0) {
            positionMs = 0;
        }
        try {
            mediaPlayer.seekTo(positionMs);
        } catch (Exception e) {
        }
        updateProgressFromPlayer();
        pushState("");
    }

    private void seekByInternal(int deltaMs) {
        if (deltaMs == 0) {
            pushState("");
            return;
        }
        int now = currentPositionMs;
        if (mediaPlayer != null) {
            try {
                now = mediaPlayer.getCurrentPosition();
            } catch (Exception e) {
            }
        }
        int target = now + deltaMs;
        if (target < 0) {
            target = 0;
        }
        int duration = currentDurationMs;
        if (mediaPlayer != null) {
            try {
                duration = Math.max(duration, mediaPlayer.getDuration());
            } catch (Exception e) {
            }
        }
        if (duration > 0 && target > duration) {
            target = duration;
        }
        seekToInternal(target);
    }

    private void stopAndExit() {
        ++prepareToken;
        cancelPrepareTimeout();
        isPreparing = false;
        stopProgressTicker();
        clearWaveData();
        releasePlayerInternal();
        abandonAudioFocusSafe();
        stopForegroundCompat();
        pushState("");
        stopSelf();
    }

    private void releasePlayerInternal() {
        cancelPrepareTimeout();
        clearWaveData();
        releaseVisualizerInternal();
        if (mediaPlayer == null) {
            return;
        }
        try {
            mediaPlayer.stop();
        } catch (Exception e) {
        }
        try {
            mediaPlayer.release();
        } catch (Exception e) {
        }
        mediaPlayer = null;
    }

    private void updateProgressFromPlayer() {
        if (mediaPlayer == null) {
            currentPositionMs = 0;
            if (currentDurationMs < 0) {
                currentDurationMs = 0;
            }
            return;
        }
        try {
            currentPositionMs = Math.max(0, mediaPlayer.getCurrentPosition());
        } catch (Exception e) {
            currentPositionMs = 0;
        }
        try {
            currentDurationMs = Math.max(0, mediaPlayer.getDuration());
        } catch (Exception e) {
            if (currentDurationMs < 0) {
                currentDurationMs = 0;
            }
        }
    }

    private void startProgressTicker() {
        stopProgressTicker();
        progressHandler.post(progressTicker);
    }

    private void stopProgressTicker() {
        progressHandler.removeCallbacks(progressTicker);
    }

    private void schedulePrepareTimeout(final String url, final int token) {
        cancelPrepareTimeout();
        prepareTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (token != prepareToken || !isPreparing) {
                    return;
                }
                MediaUrlResolver.reportLineFailure(url);
                if (tryFallbackPrepare(url, token)) {
                    return;
                }
                isPreparing = false;
                stopProgressTicker();
                releasePlayerInternal();
                pushState("加载超时，请重试");
                updateForegroundNotification();
            }
        };
        progressHandler.postDelayed(prepareTimeoutRunnable, PREPARE_TIMEOUT_MS);
    }

    private void cancelPrepareTimeout() {
        if (prepareTimeoutRunnable == null) {
            return;
        }
        progressHandler.removeCallbacks(prepareTimeoutRunnable);
        prepareTimeoutRunnable = null;
    }

    private boolean isPlayingInternal() {
        if (mediaPlayer == null) {
            return false;
        }
        try {
            return mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPlaybackCompleted() {
        if (mediaPlayer == null) {
            return false;
        }
        int duration = currentDurationMs;
        int position = currentPositionMs;
        if (duration <= 0) {
            try {
                duration = Math.max(0, mediaPlayer.getDuration());
            } catch (Exception e) {
                duration = 0;
            }
        }
        if (position <= 0) {
            try {
                position = Math.max(0, mediaPlayer.getCurrentPosition());
            } catch (Exception e) {
                position = 0;
            }
        }
        return duration > 0 && position >= duration - 400;
    }

    private void pushState(String error) {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_SONG_NAME, currentSongName);
        intent.putExtra(EXTRA_SONG_URL, currentSongUrl);
        intent.putExtra(EXTRA_COVER_URL, currentCoverUrl);
        intent.putExtra(EXTRA_LYRICS_URL, currentLyricsUrl);
        intent.putExtra(EXTRA_OWNER_UID, currentOwnerUid);
        intent.putExtra(EXTRA_OWNER_NAME, currentOwnerName);
        intent.putExtra(EXTRA_OWNER_TITLE, currentOwnerTitle);
        intent.putExtra(EXTRA_OWNER_AVATAR, currentOwnerAvatar);
        intent.putExtra(EXTRA_ITEM_ID, currentItemId);
        intent.putExtra(EXTRA_ITEM_LIKES, Math.max(0, currentItemLikes));
        intent.putExtra(EXTRA_ITEM_COMMENTS, Math.max(0, currentItemComments));
        intent.putExtra(EXTRA_ITEM_LIKED, currentItemLiked);
        intent.putExtra(EXTRA_IS_PREPARING, isPreparing);
        intent.putExtra(EXTRA_IS_PLAYING, isPlayingInternal());
        intent.putExtra(EXTRA_DURATION_MS, Math.max(0, currentDurationMs));
        intent.putExtra(EXTRA_POSITION_MS, Math.max(0, currentPositionMs));
        intent.putExtra(EXTRA_REPEAT_ONE, repeatOne);
        intent.putExtra(EXTRA_WAVE_LEVEL, Math.max(0, Math.min(100, currentWaveLevel)));
        intent.putExtra(EXTRA_WAVE_BINS, copyWaveBins());
        intent.putExtra(EXTRA_ERROR, error == null ? "" : error);
        if (localBroadcastManager != null) {
            localBroadcastManager.sendBroadcast(intent);
        }
    }

    private int[] copyWaveBins() {
        int[] copy = new int[WAVE_BIN_COUNT];
        synchronized (waveLock) {
            System.arraycopy(currentWaveBins, 0, copy, 0, WAVE_BIN_COUNT);
        }
        return copy;
    }

    private void clearWaveData() {
        currentWaveLevel = 0;
        lastFftCaptureAtMs = 0L;
        synchronized (waveLock) {
            for (int i = 0; i < WAVE_BIN_COUNT; i++) {
                currentWaveBins[i] = 0;
            }
        }
    }

    private void attachVisualizerSafe(MediaPlayer player) {
        releaseVisualizerInternal();
        if (player == null) {
            return;
        }
        try {
            int sessionId = player.getAudioSessionId();
            if (sessionId <= 0) {
                return;
            }
            Visualizer created = new Visualizer(sessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            if (range != null && range.length >= 2) {
                int preferred = 512;
                int captureSize = Math.max(range[0], Math.min(range[1], preferred));
                created.setCaptureSize(captureSize);
            }
            int captureRate = Visualizer.getMaxCaptureRate() / 2;
            if (captureRate <= 0) {
                captureRate = 10000;
            }
            Visualizer.OnDataCaptureListener listener = new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    long now = System.currentTimeMillis();
                    if (now - lastFftCaptureAtMs > 350L) {
                        updateWaveFromWaveform(waveform);
                    }
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    lastFftCaptureAtMs = System.currentTimeMillis();
                    updateWaveFromFft(fft);
                }
            };
            int listenerResult = created.setDataCaptureListener(listener, captureRate, true, true);
            if (listenerResult != Visualizer.SUCCESS) {
                listenerResult = created.setDataCaptureListener(listener, captureRate, true, false);
            }
            if (listenerResult != Visualizer.SUCCESS) {
                listenerResult = created.setDataCaptureListener(listener, captureRate, false, true);
            }
            if (listenerResult != Visualizer.SUCCESS) {
                created.release();
                return;
            }
            created.setEnabled(isPlayingInternal());
            visualizer = created;
        } catch (Throwable ignored) {
            releaseVisualizerInternal();
        }
    }

    private void setVisualizerEnabledSafe(boolean enabled) {
        if (visualizer == null) {
            return;
        }
        try {
            visualizer.setEnabled(enabled);
        } catch (Exception ignored) {
        }
    }

    private void releaseVisualizerInternal() {
        if (visualizer == null) {
            return;
        }
        try {
            visualizer.setEnabled(false);
        } catch (Exception ignored) {
        }
        try {
            visualizer.release();
        } catch (Exception ignored) {
        }
        visualizer = null;
    }

    private void updateWaveFromFft(byte[] fft) {
        if (fft == null || fft.length < 4) {
            updateWaveSilence();
            return;
        }

        int complexCount = fft.length / 2;
        if (complexCount <= 1) {
            updateWaveSilence();
            return;
        }

        int spectrumCount = complexCount - 1;
        int usableBins = Math.min(spectrumCount, 96);
        if (usableBins <= 0) {
            updateWaveSilence();
            return;
        }

        int[] nextBins = new int[WAVE_BIN_COUNT];
        int total = 0;
        int peak = 0;
        for (int i = 0; i < WAVE_BIN_COUNT; i++) {
            int from = 1 + (i * usableBins) / WAVE_BIN_COUNT;
            int to = 1 + ((i + 1) * usableBins) / WAVE_BIN_COUNT;
            if (to <= from) {
                to = from + 1;
            }
            if (to > spectrumCount) {
                to = spectrumCount;
            }

            double avgMag = 0.0d;
            double maxMag = 0.0d;
            int count = 0;
            for (int k = from; k < to; k++) {
                int real = fft[2 * k];
                int imag = fft[2 * k + 1];
                double mag = Math.sqrt((double) real * (double) real + (double) imag * (double) imag);
                avgMag += mag;
                if (mag > maxMag) {
                    maxMag = mag;
                }
                count++;
            }

            if (count > 0) {
                avgMag = avgMag / count;
            }
            double combined = avgMag * 0.62d + maxMag * 0.38d;
            int normalized = (int) (combined * 100.0d / 86.0d);
            normalized = clampToPercent(normalized);
            if (normalized < 4) {
                normalized = 0;
            }

            nextBins[i] = normalized;
            total += normalized;
            if (normalized > peak) {
                peak = normalized;
            }
        }

        synchronized (waveLock) {
            for (int i = 0; i < WAVE_BIN_COUNT; i++) {
                int incoming = nextBins[i];
                int base = currentWaveBins[i];
                if (incoming == 0) {
                    currentWaveBins[i] = (base * 6) / 10;
                } else {
                    currentWaveBins[i] = (base * 5 + incoming * 5) / 10;
                }
            }

            int lowPeak = Math.max(currentWaveBins[0], Math.max(currentWaveBins[1], currentWaveBins[2]));
            int avg = total / WAVE_BIN_COUNT;
            int mixed = (avg * 4 + peak * 3 + lowPeak * 3) / 10;
            if (mixed < 3) {
                mixed = 0;
            }
            currentWaveLevel = (currentWaveLevel * 6 + mixed * 4) / 10;
        }
    }

    private void updateWaveFromWaveform(byte[] waveform) {
        if (waveform == null || waveform.length == 0) {
            updateWaveSilence();
            return;
        }

        int chunk = waveform.length / WAVE_BIN_COUNT;
        if (chunk <= 0) {
            chunk = 1;
        }
        int total = 0;
        int peak = 0;

        synchronized (waveLock) {
            for (int i = 0; i < WAVE_BIN_COUNT; i++) {
                int start = i * chunk;
                if (start >= waveform.length) {
                    start = waveform.length - 1;
                }
                int end = Math.min(waveform.length, start + chunk);
                int localPeak = 0;
                for (int j = start; j < end; j++) {
                    int value = (waveform[j] & 0xFF) - 128;
                    if (value < 0) {
                        value = -value;
                    }
                    if (value > localPeak) {
                        localPeak = value;
                    }
                }

                int normalized = clampToPercent(localPeak * 100 / 128);
                if (normalized < 4) {
                    normalized = 0;
                }

                if (normalized == 0) {
                    currentWaveBins[i] = (currentWaveBins[i] * 7) / 10;
                } else {
                    currentWaveBins[i] = (currentWaveBins[i] * 4 + normalized * 6) / 10;
                }
                total += currentWaveBins[i];
                if (currentWaveBins[i] > peak) {
                    peak = currentWaveBins[i];
                }
            }

            int avg = total / WAVE_BIN_COUNT;
            int mixed = (avg * 7 + peak * 3) / 10;
            if (mixed < 3) {
                mixed = 0;
            }
            currentWaveLevel = (currentWaveLevel * 6 + mixed * 4) / 10;
        }
    }

    private void updateWaveSilence() {
        synchronized (waveLock) {
            for (int i = 0; i < WAVE_BIN_COUNT; i++) {
                currentWaveBins[i] = (currentWaveBins[i] * 5) / 10;
            }
            currentWaveLevel = (currentWaveLevel * 5) / 10;
            if (currentWaveLevel < 2) {
                currentWaveLevel = 0;
            }
        }
    }

    private int clampToPercent(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 100) {
            return 100;
        }
        return value;
    }

    private void updateForegroundNotification() {
        Notification notification = buildPlaybackNotification();
        if (notification == null) {
            return;
        }
        try {
            startForeground(NOTIFY_ID, notification);
        } catch (Throwable e) {
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(true);
            return;
        }
        stopForeground(true);
    }

    private Notification buildPlaybackNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannelCompat.ensureChannel(manager, CHANNEL_ID, "音乐播放", NotificationChannelCompat.IMPORTANCE_LOW);
            return buildPlaybackNotification26();
        }
        return buildPlaybackNotificationCompat();
    }

    private Notification buildPlaybackNotificationCompat() {
        String title = currentSongName;
        if (title == null || title.length() == 0) {
            title = "旧聊音乐";
        }
        String text = resolveNotificationText();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(buildContentIntent());

        PendingIntent toggleIntent = buildServiceActionPendingIntent(ACTION_TOGGLE_PLAY, 1);
        PendingIntent repeatIntent = buildServiceActionPendingIntent(ACTION_TOGGLE_REPEAT_ONE, 2);
        PendingIntent stopIntent = buildServiceActionPendingIntent(ACTION_STOP, 3);
        if (toggleIntent != null) {
            int icon = isPlayingInternal() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
            String textAction = isPlayingInternal() ? "暂停" : "播放";
            builder.addAction(icon, textAction, toggleIntent);
        }
        if (repeatIntent != null) {
            String textAction = repeatOne ? "循环开" : "循环关";
            builder.addAction(android.R.drawable.ic_menu_rotate, textAction, repeatIntent);
        }
        if (stopIntent != null) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopIntent);
        }
        return builder.build();
    }

    private Notification buildPlaybackNotification26() {
        String title = currentSongName;
        if (title == null || title.length() == 0) {
            title = "旧聊音乐";
        }
        String text = resolveNotificationText();
        PendingIntent toggleIntent = buildServiceActionPendingIntent(ACTION_TOGGLE_PLAY, 1);
        PendingIntent repeatIntent = buildServiceActionPendingIntent(ACTION_TOGGLE_REPEAT_ONE, 2);
        PendingIntent stopIntent = buildServiceActionPendingIntent(ACTION_STOP, 3);
        return MusicPlaybackNotificationApi26.build(
                this, CHANNEL_ID, R.drawable.ic_msg_sent, title, text, buildContentIntent(),
                toggleIntent, isPlayingInternal() ? "暂停" : "播放",
                repeatIntent, repeatOne ? "循环开" : "循环关", stopIntent);
    }

    private String resolveNotificationText() {
        if (isPreparing) {
            return "歌曲加载中";
        }
        if (isPlayingInternal()) {
            return "正在后台播放";
        }
        if (mediaPlayer != null) {
            return "已暂停";
        }
        return "等待播放";
    }

    private PendingIntent buildContentIntent() {
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra(EXTRA_SONG_NAME, currentSongName);
        intent.putExtra(EXTRA_SONG_URL, currentSongUrl);
        intent.putExtra(EXTRA_COVER_URL, currentCoverUrl);
        intent.putExtra(EXTRA_LYRICS_URL, currentLyricsUrl);
        intent.putExtra(EXTRA_OWNER_UID, currentOwnerUid);
        intent.putExtra(EXTRA_OWNER_NAME, currentOwnerName);
        intent.putExtra(EXTRA_OWNER_TITLE, currentOwnerTitle);
        intent.putExtra(EXTRA_OWNER_AVATAR, currentOwnerAvatar);
        intent.putExtra(EXTRA_ITEM_ID, currentItemId);
        intent.putExtra(EXTRA_ITEM_LIKES, Math.max(0, currentItemLikes));
        intent.putExtra(EXTRA_ITEM_COMMENTS, Math.max(0, currentItemComments));
        intent.putExtra(EXTRA_ITEM_LIKED, currentItemLiked);
        intent.putExtra(EXTRA_OPEN_NOW_PLAYING, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 3001, intent, pendingFlags());
    }

    private PendingIntent buildServiceActionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent, pendingFlags());
    }

    private int pendingFlags() {
        final int FLAG_IMMUTABLE = 1 << 26;
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void startAsync(AsyncTask<Void, Void, File> task) {
        if (task == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 11) {
            MusicPlaybackAsyncApi11.execute(task);
        } else {
            task.execute((Void[]) null);
        }
    }

    private boolean requestAudioFocusSafe() {
        if (audioManager == null) {
            return true;
        }
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocusSafe() {
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocus(this);
        } catch (Exception e) {
        }
    }

    private String strings(String value) {
        return value == null ? "" : value;
    }

    private File ensureCachedMusicFile(String url) {
        if (url == null || url.length() == 0) {
            return null;
        }
        File cacheFile = resolveMusicCacheFile(url);
        if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
            try {
                cacheFile.setLastModified(System.currentTimeMillis());
            } catch (Exception e) {
            }
            return cacheFile;
        }

        String[] candidates = MediaUrlResolver.resolveCandidates(url);
        if (candidates == null || candidates.length == 0) {
            candidates = new String[]{url};
        }
        for (int i = 0; i < candidates.length; i++) {
            String one = candidates[i];
            if (one == null || one.length() == 0) {
                continue;
            }
            if (ensureCachedMusicFileOne(one, cacheFile)) {
                return cacheFile;
            }
        }
        return null;
    }

    private boolean ensureCachedMusicFileOne(String url, File cacheFile) {
        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream os = null;
        try {
            if (cacheFile == null) {
                return false;
            }
            File parent = cacheFile.getParentFile();
            if (parent == null) {
                return false;
            }
            if (!parent.exists() && !parent.mkdirs()) {
                return false;
            }
            // 保留未完成文件；网络恢复或线路切换后从已有字节继续下载。
            File part = new File(cacheFile.getAbsolutePath() + ".part");
            long downloaded = part.exists() ? Math.max(0L, part.length()) : 0L;
            if (downloaded > 80L * 1024L * 1024L) {
                part.delete();
                downloaded = 0L;
            }

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            if (downloaded > 0) {
                conn.setRequestProperty("Range", "bytes=" + downloaded + "-");
            }
            int code = conn.getResponseCode();
            if (code == 416 && downloaded > 0) {
                long remoteSize = parseRangeTotal(conn.getHeaderField("Content-Range"));
                if (remoteSize != downloaded) {
                    part.delete();
                    return false;
                }
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
                return part.renameTo(cacheFile);
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return false;
            }

            boolean append = code == HttpURLConnection.HTTP_PARTIAL && downloaded > 0;
            if (!append) {
                downloaded = 0L;
            }
            long responseBytes = parsePositiveLong(conn.getHeaderField("Content-Length"));
            long expectedTotal = responseBytes > 0 ? downloaded + responseBytes : -1L;
            if (expectedTotal > 80L * 1024L * 1024L) {
                part.delete();
                return false;
            }

            is = conn.getInputStream();
            os = new FileOutputStream(part, append);
            byte[] buffer = new byte[8192];
            int len;
            long total = downloaded;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
                total += len;
                if (total > 80L * 1024L * 1024L) {
                    part.delete();
                    return false;
                }
            }
            os.flush();
            if (total <= 0 || (expectedTotal > 0 && total != expectedTotal)) {
                return false;
            }
            if (cacheFile.exists() && !cacheFile.delete()) {
                return false;
            }
            if (!part.renameTo(cacheFile)) {
                return false;
            }
            try {
                cacheFile.setLastModified(System.currentTimeMillis());
            } catch (Exception e) {
            }
            return true;
        } catch (Exception e) {
            // 不删除 .part，下次可继续下载。
            return false;
        } finally {
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
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private long parseRangeTotal(String value) {
        if (value == null) {
            return -1L;
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? parsePositiveLong(value.substring(slash + 1)) : -1L;
    }

    private long parsePositiveLong(String value) {
        if (value == null || value.length() == 0) {
            return -1L;
        }
        try {
            long out = Long.parseLong(value.trim());
            return out > 0 ? out : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private File resolveMusicCacheFile(String url) {
        if (url == null || url.length() == 0) {
            return null;
        }
        File root = getFilesDir();
        if (root == null) {
            root = getCacheDir();
        }
        if (root == null) {
            return null;
        }
        File dir = new File(root, MUSIC_CACHE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String key = buildMusicCacheKey(url);
        String ext = guessMusicExt(url);
        return new File(dir, key + ext);
    }

    private String guessMusicExt(String url) {
        if (url == null) {
            return ".cache";
        }
        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        int h = lower.indexOf('#');
        if (h >= 0) {
            lower = lower.substring(0, h);
        }
        String[] exts = new String[]{".mp3", ".m4a", ".aac", ".amr", ".3gp", ".wav", ".ogg", ".flac"};
        for (int i = 0; i < exts.length; i++) {
            String ext = exts[i];
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        return ".cache";
    }

    private String buildMusicCacheKey(String url) {
        String identity = MediaUrlResolver.cacheIdentity(url);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] out = digest.digest(identity.getBytes("UTF-8"));
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

    private void warmMusicCacheAsync(final String url) {
        warmMusicCacheAsync(url, false);
    }

    private boolean shouldWarmCacheForPlayback(String url) {
        if (url == null || url.length() == 0) {
            return false;
        }
        File cacheFile = resolveMusicCacheFile(url);
        if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
            return false;
        }
        return isUnmeteredNetworkConnected();
    }

    private boolean isUnmeteredNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= 16) {
                try {
                    if (cm.isActiveNetworkMetered()) {
                        return false;
                    }
                } catch (Throwable ignored) {
                }
            }
            int type = info.getType();
            if (type == ConnectivityManager.TYPE_WIFI
                    || type == ConnectivityManager.TYPE_ETHERNET
                    || type == ConnectivityManager.TYPE_WIMAX) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void warmMusicCacheAsync(final String url, final boolean notifyResult) {
        if (url == null || url.length() == 0) {
            if (notifyResult) {
                dispatchCacheResult("", false, 0L, "歌曲链接不可用");
            }
            return;
        }
        final String warmingKey = MediaUrlResolver.cacheIdentity(url);
        synchronized (cacheWarmLock) {
            if (warmingCacheKeys.contains(warmingKey)) {
                if (notifyResult) {
                    dispatchCacheResult(url, false, 0L, "正在缓存中");
                }
                return;
            }
            warmingCacheKeys.add(warmingKey);
        }
        startAsync(new AsyncTask<Void, Void, File>() {
            @Override
            protected File doInBackground(Void... voids) {
                return ensureCachedMusicFile(url);
            }

            @Override
            protected void onPostExecute(File file) {
                synchronized (cacheWarmLock) {
                    warmingCacheKeys.remove(warmingKey);
                }
                if (file != null && file.exists() && file.length() > 0) {
                    trimMusicCacheAsync();
                    if (notifyResult) {
                        dispatchCacheResult(url, true, file.length(), "");
                    }
                    return;
                }
                if (notifyResult) {
                    dispatchCacheResult(url, false, 0L, "缓存失败");
                }
            }
        });
    }

    private void dispatchCacheResult(String url, boolean ok, long size, String error) {
        Intent result = new Intent(ACTION_CACHE_RESULT);
        result.putExtra(EXTRA_CACHE_URL, strings(url));
        result.putExtra(EXTRA_CACHE_OK, ok);
        result.putExtra(EXTRA_CACHE_SIZE, Math.max(0L, size));
        result.putExtra(EXTRA_CACHE_ERROR, strings(error));
        if (localBroadcastManager != null) {
            localBroadcastManager.sendBroadcast(result);
        }
    }

    private void trimMusicCacheAsync() {
        if (isTrimmingCache) {
            return;
        }
        isTrimmingCache = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    trimMusicCache();
                } finally {
                    isTrimmingCache = false;
                }
            }
        }, "music-cache-trim-svc").start();
    }

    private void trimMusicCache() {
        File root = getFilesDir();
        if (root == null) {
            root = getCacheDir();
        }
        if (root == null) {
            return;
        }
        File dir = new File(root, MUSIC_CACHE_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        FileSortCompat.sortByLastModifiedAsc(files);
        File activeCache = resolveMusicCacheFile(currentSongUrl);
        String activePath = activeCache == null ? "" : activeCache.getAbsolutePath();
        HashSet<String> warmingPaths = new HashSet<String>();
        synchronized (cacheWarmLock) {
            for (String warmingKey : warmingCacheKeys) {
                File warming = resolveMusicCacheFile(warmingKey);
                if (warming != null) {
                    warmingPaths.add(warming.getAbsolutePath() + ".part");
                }
            }
        }

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
        for (int i = 0; i < files.length && (total > MUSIC_CACHE_MAX_BYTES || count > MUSIC_CACHE_MAX_FILES); i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            String path = file.getAbsolutePath();
            if (path.equals(activePath) || warmingPaths.contains(path)) {
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
