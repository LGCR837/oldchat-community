package com.im.oldchat.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewCompat;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.LyricLine;
import com.im.oldchat.service.MusicPlaybackService;
import com.im.oldchat.ui.widget.CircleImageView;
import com.im.oldchat.ui.widget.CoverAmbientMotionView;
import com.im.oldchat.ui.widget.LyricCascadeView;
import com.im.oldchat.ui.widget.OnLyricAnimationEndListener;
import com.im.oldchat.util.CoverColorExtractor;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.LyricParser;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.SettingsPrefs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

public class MusicPlayerActivity extends BaseActivity {
    private static final float COVER_SCALE_SMALL = 56f / 280f; // 小封面/大封面尺寸比（歌词页左上角缩略封面）
    private static final int LYRICS_CACHE_LIMIT = 18;
    private static final LinkedHashMap<String, List<LyricLine>> LYRICS_CACHE =
            new LinkedHashMap<String, List<LyricLine>>(24, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<LyricLine>> eldest) {
                    return size() > LYRICS_CACHE_LIMIT;
                }
            };

    private ImageView ivCover;
    private ImageView ivBack;
    private TextView tvSongName;
    private TextView tvOwner;
    private TextView tvCurrent;
    private TextView tvDuration;
    private ImageView ivPlayPause;
    private TextView ivRepeatOne;
    private ImageView ivComments;
    private TextView tvStatus;
    private SeekBar seekBar;
    private TextView btnDownload;
    private TextView btnLike;
    private TextView tvStats;
    private TextView tvSwipeHint;
    private TextView tvLyricTitle;
    private TextView tvLyricArtist;
    private View llCoverInfo;
    private View llLyricHeader;
    private CoverAmbientMotionView viewCoverAmbient;
    private android.view.View rootLayout;
    private LyricCascadeView mLyricView;

    private boolean isSeeking;
    private boolean isCoverPage = true;
    private int lyricTransitionVersion;

    private String songName = "";
    private String songUrl = "";
    private String coverUrl = "";
    private String lyricsUrl = "";
    private String ownerName = "";
    private String ownerUid = "";
    private String ownerTitle = "";
    private String ownerAvatar = "";
    private String itemId = "";
    private String token = "";
    private ImageView ivMore;
    private int likeCount;
    private int commentCount;
    private boolean likedByMe;
    private boolean likeRequesting;
    private boolean openNowPlayingOnly;

    private boolean isPreparing;
    private boolean isPlaying;
    private boolean repeatOne;
    private int durationMs;
    private int positionMs;

    private AsyncTask<String, Void, String> lyricTask;
    private int lyricTaskToken;
    private String lastResolvedLyricsUrl = "";
    private boolean shareDetailResolving;
    private String shareDetailAttemptedKey = "";
    private int[] ambientColors = new int[]{
            Color.parseColor("#5B6E8A"),
            Color.parseColor("#4D8BFF"),
            Color.parseColor("#8A5B6E")
    };
    private boolean ambientApplied;

    // 左滑/右滑切换封面与歌词页手势
    private float mTouchStartX = 0f;
    private float mTouchStartY = 0f;
    private boolean mIsHorizontalScroll = false;
    private boolean mIgnoreSwipeGesture = false;

    private final BroadcastReceiver playbackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (!MusicPlaybackService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            applyPlaybackState(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_player);

        bindViews();
        fitCoverToScreen();
        initLyricView();
        loadAuthToken();
        readIntentData(getIntent());
        bindHeaderInfo();
        bindActions();

        if (openNowPlayingOnly) {
            requestPlaybackState();
        } else {
            startOrAttachPlayback();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        readIntentData(intent);
        bindHeaderInfo();
        if (openNowPlayingOnly) {
            requestPlaybackState();
        } else {
            startOrAttachPlayback();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAuthToken();
        updateSocialButtons();
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(playbackReceiver,
                new IntentFilter(MusicPlaybackService.ACTION_STATE_CHANGED));
        requestPlaybackState();
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelLyricsTask();
        super.onDestroy();
    }

    private void bindViews() {
        rootLayout = findViewByIdCompat(R.id.rlMusicPlayerRoot);
        ivBack = findViewByIdCompat(R.id.btnMusicPlayerBack);
        ivCover = findViewByIdCompat(R.id.ivMusicPlayerCover);
        tvSongName = findViewByIdCompat(R.id.tvMusicPlayerSongName);
        tvOwner = findViewByIdCompat(R.id.tvMusicPlayerOwner);
        tvCurrent = findViewByIdCompat(R.id.tvMusicPlayerCurrent);
        tvDuration = findViewByIdCompat(R.id.tvMusicPlayerDuration);
        ivPlayPause = findViewByIdCompat(R.id.btnMusicPlayerPlayPause);
        ivRepeatOne = findViewByIdCompat(R.id.btnMusicPlayerRepeatOne);
        ivMore = findViewByIdCompat(R.id.btnMusicPlayerMore);
        ivComments = findViewByIdCompat(R.id.btnMusicPlayerComments);
        tvStatus = findViewByIdCompat(R.id.tvMusicPlayerStatus);
        seekBar = findViewByIdCompat(R.id.sbMusicPlayerProgress);
        btnDownload = findViewByIdCompat(R.id.btnMusicPlayerDownload);
        btnLike = findViewByIdCompat(R.id.btnMusicPlayerLike);
        tvStats = findViewByIdCompat(R.id.tvMusicPlayerStats);
        tvSwipeHint = findViewByIdCompat(R.id.tvMusicPlayerSwipeHint);
        tvLyricTitle = findViewByIdCompat(R.id.tvMusicPlayerLyricTitle);
        tvLyricArtist = findViewByIdCompat(R.id.tvMusicPlayerLyricArtist);
        llCoverInfo = findViewByIdCompat(R.id.llMusicPlayerCoverInfo);
        llLyricHeader = findViewByIdCompat(R.id.llMusicPlayerLyricHeader);
        viewCoverAmbient = (CoverAmbientMotionView) findViewByIdCompat(R.id.view_cover_ambient);
        mLyricView = (LyricCascadeView) findViewByIdCompat(R.id.view_music_player_lyric);
        applyControlIconTints();
    }

    /** 小屏/高 DPI 下自适应封面尺寸：保证封面不超出屏幕，歌曲标题信息始终可见。 */
    private void fitCoverToScreen() {
        if (ivCover == null) {
            return;
        }
        android.view.ViewGroup.LayoutParams lp = ivCover.getLayoutParams();
        if (lp == null) {
            return;
        }
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (metrics == null || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return;
        }
        // 竖向预留：顶栏 56dp + 底部控制栏约 161dp + 标题区最小高度约 91dp + 封面/标题区外边距 38dp，取整 350dp
        int reserved = dpToPx(350);
        int maxByHeight = metrics.heightPixels - reserved;
        // 横向预留：左右各 20dp
        int maxByWidth = metrics.widthPixels - dpToPx(40);
        int coverSize = Math.min(dpToPx(280), Math.min(maxByHeight, maxByWidth));
        if (coverSize < dpToPx(120)) {
            coverSize = dpToPx(120);
        }
        lp.width = coverSize;
        lp.height = coverSize;
        ivCover.setLayoutParams(lp);
    }

    /** 应用播放页背景设置：0=浅色，1=深色，2=动态（跟随封面取色）。 */
    private void applyPlayerBackground() {
        int bgStyle = SettingsPrefs.getMusicBgStyle(this);
        boolean dynamicBg = SettingsPrefs.isMusicDynamicBgEnabled(this);
        int baseColor = 0xFFF4F1EE; // 浅色
        boolean darkMode = false;
        if (bgStyle == 1) {
            baseColor = android.graphics.Color.parseColor("#1C1A17");
            darkMode = true;
        } else if (bgStyle == 2) {
            // 动态：保留动态背景，底色用浅色
            baseColor = 0xFFF4F1EE;
        }
        if (rootLayout != null) {
            rootLayout.setBackgroundColor(baseColor);
        }
        if (viewCoverAmbient != null) {
            viewCoverAmbient.setDarkMode(darkMode);
            boolean showAmbient = dynamicBg;
            viewCoverAmbient.setVisibility(showAmbient ? View.VISIBLE : View.GONE);
        }
        // 文本颜色跟随"背景颜色设置"（bgStyle），而不是深色模式总开关：
        // 深色背景 -> 亮色文字；浅色/动态背景 -> 深色文字
        applyPlayerTextColors(darkMode);
    }

    /** 根据播放器背景（darkMode）设置歌曲标题/演唱者/时间等文本颜色。 */
    private void applyPlayerTextColors(boolean darkMode) {
        int primary = darkMode ? 0xFFEDEAE6 : 0xFF181513;
        int secondary = darkMode ? 0xFFA9A09A : 0xFF6E645D;
        int[] primaryIds = {
                R.id.tvMusicPlayerSongName,
                R.id.tvMusicPlayerLyricTitle
        };
        int[] secondaryIds = {
                R.id.tvMusicPlayerOwner,
                R.id.tvMusicPlayerLyricArtist,
                R.id.tvMusicPlayerCurrent,
                R.id.tvMusicPlayerDuration,
                R.id.tvMusicPlayerStats,
                R.id.tvMusicPlayerSwipeHint,
                R.id.tvMusicPlayerStatus
        };
        for (int id : primaryIds) {
            TextView tv = (TextView) findViewByIdCompat(id);
            if (tv != null) {
                tv.setTextColor(primary);
            }
        }
        for (int id : secondaryIds) {
            TextView tv = (TextView) findViewByIdCompat(id);
            if (tv != null) {
                tv.setTextColor(secondary);
            }
        }
    }

    private void initLyricView() {
        if (mLyricView == null) {
            return;
        }
        // 应用音乐播放器设置（歌词弹性/颜色模式/背景颜色/动态背景）
        mLyricView.setBounceEnabled(SettingsPrefs.isLyricBounceEnabled(this));
        int lyricColorMode = SettingsPrefs.getLyricColorMode(this);
        mLyricView.setLyricColorMode(lyricColorMode);
        applyPlayerBackground();
        mLyricView.setPreviewEnabled(false);
        mLyricView.setOnLyricSeekListener(new LyricCascadeView.OnLyricSeekListener() {
            @Override
            public void onSeekTo(long timeMs) {
                Intent intent = new Intent(MusicPlayerActivity.this, MusicPlaybackService.class);
                intent.setAction(MusicPlaybackService.ACTION_SEEK);
                intent.putExtra(MusicPlaybackService.EXTRA_POSITION_MS, (int) timeMs);
                startMusicService(intent);
            }
        });
        mLyricView.setOnEmptyClickListener(new LyricCascadeView.OnEmptyClickListener() {
            @Override
            public void onEmptyClick() {
                if (lyricsUrl == null || lyricsUrl.length() == 0) {
                    Toast.makeText(MusicPlayerActivity.this,
                            "\u8BE5\u6B4C\u66F2\u6682\u65E0\u6B4C\u8BCD", Toast.LENGTH_SHORT).show();
                } else {
                    loadLyrics();
                }
            }
        });
    }

    private void readIntentData(Intent intent) {
        if (intent == null) {
            return;
        }
        openNowPlayingOnly = intent.getBooleanExtra(MusicPlaybackService.EXTRA_OPEN_NOW_PLAYING, false);
        songName = value(intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_NAME));
        if (songName.length() == 0) {
            songName = value(intent.getStringExtra("song_name"));
        }
        songUrl = value(intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_URL));
        if (songUrl.length() == 0) {
            songUrl = value(intent.getStringExtra("song_url"));
        }
        coverUrl = value(intent.getStringExtra(MusicPlaybackService.EXTRA_COVER_URL));
        if (coverUrl.length() == 0) {
            coverUrl = value(intent.getStringExtra("cover_url"));
        }
        lyricsUrl = value(intent.getStringExtra(MusicPlaybackService.EXTRA_LYRICS_URL));
        if (lyricsUrl.length() == 0) {
            lyricsUrl = value(intent.getStringExtra("lyrics_url"));
        }
        ownerUid = value(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_UID));
        if (ownerUid.length() == 0) {
            ownerUid = value(intent.getStringExtra("owner_uid"));
        }
        ownerName = value(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_NAME));
        if (ownerName.length() == 0) {
            ownerName = value(intent.getStringExtra("owner_name"));
        }
        ownerTitle = value(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_TITLE));
        if (ownerTitle.length() == 0) {
            ownerTitle = value(intent.getStringExtra("owner_title"));
        }
        ownerAvatar = value(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_AVATAR));
        if (ownerAvatar.length() == 0) {
            ownerAvatar = value(intent.getStringExtra("owner_avatar"));
        }
        if (intent.hasExtra(MusicPlaybackService.EXTRA_ITEM_ID)) {
            itemId = value(intent.getStringExtra(MusicPlaybackService.EXTRA_ITEM_ID));
        } else if (intent.hasExtra("item_id")) {
            itemId = value(intent.getStringExtra("item_id"));
        }
        if (intent.hasExtra(MusicPlaybackService.EXTRA_ITEM_LIKES)) {
            likeCount = Math.max(0, intent.getIntExtra(MusicPlaybackService.EXTRA_ITEM_LIKES, likeCount));
        } else if (intent.hasExtra("item_likes")) {
            likeCount = Math.max(0, intent.getIntExtra("item_likes", likeCount));
        }
        if (intent.hasExtra(MusicPlaybackService.EXTRA_ITEM_COMMENTS)) {
            commentCount = Math.max(0, intent.getIntExtra(MusicPlaybackService.EXTRA_ITEM_COMMENTS, commentCount));
        } else if (intent.hasExtra("item_comments")) {
            commentCount = Math.max(0, intent.getIntExtra("item_comments", commentCount));
        }
        if (intent.hasExtra(MusicPlaybackService.EXTRA_ITEM_LIKED)) {
            likedByMe = intent.getBooleanExtra(MusicPlaybackService.EXTRA_ITEM_LIKED, likedByMe);
        } else if (intent.hasExtra("item_liked")) {
            likedByMe = intent.getBooleanExtra("item_liked", likedByMe);
        }
    }

    private void bindHeaderInfo() {
        View btnBack = findViewByIdCompat(R.id.btnMusicPlayerBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            if (btnBack instanceof ImageView) {
                applyNeutralIconTint((ImageView) btnBack);
            }
        }

        String displayName = songName;
        if (displayName.length() == 0) {
            displayName = "\u672A\u547D\u540D\u6B4C\u66F2";
        }
        tvSongName.setText(displayName);

        String owner = ownerName;
        if (owner.length() == 0) {
            owner = ownerUid;
        }
        if (owner.length() == 0) {
            owner = "\u533F\u540D\u7528\u6237";
        }
        if (!TextUtils.isEmpty(ownerTitle)) {
            tvOwner.setText(owner + " \u00B7 " + ownerTitle);
        } else {
            tvOwner.setText(owner);
        }
        if (tvLyricTitle != null) {
            tvLyricTitle.setText(displayName);
        }
        if (tvLyricArtist != null) {
            tvLyricArtist.setText(owner);
        }
        if (tvStats != null) {
            tvStats.setText("\u8BC4\u8BBA " + Math.max(0, commentCount)
                    + "   \u559C\u6B22 " + Math.max(0, likeCount));
        }

        String resolvedCoverUrl = coverUrl.length() > 0 ? coverUrl : ownerAvatar;
        if (resolvedCoverUrl.length() > 0) {
            ImageLoader.load(ivCover, resolvedCoverUrl, new ImageLoader.ImageLoadListener() {
                @Override
                public void onComplete(String url) {
                    applyAmbientFromCoverDrawable();
                }
            });
        } else {
            ivCover.setImageResource(R.drawable.ic_avatar_placeholder);
            if (!ambientApplied) {
                applyAmbientColors(ambientColors);
            }
        }

        tvCurrent.setText(formatDuration(0));
        tvDuration.setText(formatDuration(0));
        tvStatus.setText("\u51C6\u5907\u64AD\u653E");
        if (ivPlayPause != null) {
            ivPlayPause.setImageResource(android.R.drawable.ic_media_play);
        }
        updateRepeatUi();
        updateSocialButtons();
        loadLyrics();
        resolveMissingShareDetail();
    }

    private void bindActions() {
        if (ivPlayPause != null) {
            ivPlayPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sendPlaybackAction(MusicPlaybackService.ACTION_TOGGLE_PLAY);
                }
            });
        }
        if (ivRepeatOne != null) {
            ivRepeatOne.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sendPlaybackAction(MusicPlaybackService.ACTION_TOGGLE_REPEAT_ONE);
                }
            });
        }
        if (ivMore != null) {
            ivMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMoreMenu();
                }
            });
        }
        if (btnDownload != null) {
            btnDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    downloadCurrentSong();
                }
            });
        }
        if (btnLike != null) {
            btnLike.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleLike();
                }
            });
        }
        if (ivComments != null) {
            ivComments.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openComments();
                }
            });
        }

        if (seekBar == null) {
            return;
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrent.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
                Intent intent = new Intent(MusicPlayerActivity.this, MusicPlaybackService.class);
                intent.setAction(MusicPlaybackService.ACTION_SEEK);
                intent.putExtra(MusicPlaybackService.EXTRA_POSITION_MS, seekBar.getProgress());
                startMusicService(intent);
            }
        });
    }

    private void showMoreMenu() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String myUid = prefs.getString("uid", "");
        if (myUid.length() == 0) {
            myUid = prefs.getString("user_uid", "");
        }
        boolean isOwn = (ownerUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(ownerUid.trim()));
        final java.util.ArrayList<String> optList = new java.util.ArrayList<String>();
        optList.add("添加到歌单");
        optList.add("分享到群聊/单聊");
        if (!isOwn) {
            optList.add("举报此歌曲");
        }
        final CharSequence[] options = optList.toArray(new CharSequence[optList.size()]);
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(songName == null || songName.length() == 0 ? "更多操作" : songName)
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which < 0 || which >= optList.size()) return;
                        String selected = optList.get(which);
                        if ("添加到歌单".equals(selected)) {
                            openAddToPlaylist();
                        } else if ("分享到群聊/单聊".equals(selected)) {
                            openShare();
                        } else if ("举报此歌曲".equals(selected)) {
                            showReportMusicDialog();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showReportMusicDialog() {
        if (ownerUid == null || ownerUid.trim().length() == 0) {
            if (token == null || token.length() == 0) {
                token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
            }
            if ((itemId != null && itemId.length() > 0) || (songUrl != null && songUrl.length() > 0)) {
                Toast.makeText(this, "正在获取发布者信息...", Toast.LENGTH_SHORT).show();
                if (itemId != null && itemId.length() > 0) {
                    MusicShareDetailResolver.resolveById(token, itemId, new MusicShareDetailResolver.Callback() {
                        @Override
                        public void onResolved(MusicShareDetailResolver.Detail detail) {
                            applyResolvedDetail(detail);
                            if (ownerUid != null && ownerUid.trim().length() > 0) {
                                showReportMusicDialog();
                            } else {
                                Toast.makeText(MusicPlayerActivity.this, "无法举报：缺少发布者信息", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onUnavailable() {
                            fallbackReportResolve();
                        }
                    });
                    return;
                } else {
                    fallbackReportResolve();
                    return;
                }
            }
            Toast.makeText(this, "无法举报：缺少发布者信息", Toast.LENGTH_SHORT).show();
            return;
        }

        final String targetUid = ownerUid.trim();
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String myUid = prefs.getString("uid", "");
        if (myUid.length() == 0) {
            myUid = prefs.getString("user_uid", "");
        }
        if (myUid.length() > 0 && myUid.equalsIgnoreCase(targetUid)) {
            Toast.makeText(this, "不能举报自己发布的歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("如：侵权、违规内容、恶意音频等");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(180)});
        int pad = (int) (getResources().getDisplayMetrics().density * 10);
        input.setPadding(pad, pad, pad, pad);
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle("举报此歌曲")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("提交", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String reason = input.getText() == null ? "" : input.getText().toString().trim();
                        StringBuilder detail = new StringBuilder();
                        detail.append("[音乐举报]");
                        if (itemId != null && itemId.length() > 0) {
                            detail.append(" 歌曲ID: ").append(itemId);
                        }
                        if (songName != null && songName.length() > 0) {
                            detail.append(" 歌名: ").append(songName);
                        }
                        if (ownerName != null && ownerName.length() > 0) {
                            detail.append(" 上传者: ").append(ownerName);
                        }
                        if (songUrl != null && songUrl.length() > 0) {
                            detail.append("\n媒体: ").append(songUrl);
                        }
                        if (coverUrl != null && coverUrl.length() > 0) {
                            detail.append("\n封面: ").append(coverUrl);
                        }
                        String full = reason;
                        if (full.length() > 0) {
                            full += "\n";
                        }
                        full += detail.toString();
                        submitMusicReport(targetUid, full);
                    }
                })
                .show();
    }

    private void fallbackReportResolve() {
        MusicShareDetailResolver.resolve(token, songName, songUrl, new MusicShareDetailResolver.Callback() {
            @Override
            public void onResolved(MusicShareDetailResolver.Detail detail) {
                applyResolvedDetail(detail);
                if (ownerUid != null && ownerUid.trim().length() > 0) {
                    showReportMusicDialog();
                } else {
                    Toast.makeText(MusicPlayerActivity.this, "无法举报：缺少发布者信息", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onUnavailable() {
                Toast.makeText(MusicPlayerActivity.this, "无法举报：缺少发布者信息", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void submitMusicReport(String targetUid, String reason) {
        if (targetUid == null || targetUid.length() == 0) {
            Toast.makeText(this, "举报失败：缺少目标用户", Toast.LENGTH_SHORT).show();
            return;
        }
        if (token == null || token.length() == 0) {
            token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("target_uid", targetUid);
            json.put("reason", reason == null ? "" : reason);
            HttpUtil.post("/reports/user", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(MusicPlayerActivity.this, "举报已提交，已进入公开法庭", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicPlayerActivity.this, "举报失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "举报失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAddToPlaylist() {
        if (songUrl == null || songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        com.im.oldchat.models.MusicItem item = new com.im.oldchat.models.MusicItem();
        item.id = itemId;
        item.name = songName;
        item.songUrl = songUrl;
        item.coverUrl = coverUrl;
        item.ownerUid = ownerUid;
        item.ownerName = ownerName;
        try {
            new AddToPlaylistDialog(MusicPlayerActivity.this, item).show();
        } catch (Exception e) {
            Toast.makeText(MusicPlayerActivity.this, "打开歌单失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void openShare() {
        if (songUrl == null || songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        MusicShareActionHelper.ShareItem share = new MusicShareActionHelper.ShareItem();
        share.id = itemId;
        share.name = songName;
        share.songUrl = songUrl;
        share.coverUrl = coverUrl;
        share.lyricsUrl = lyricsUrl;
        share.ownerUid = ownerUid;
        share.ownerName = ownerName;
        share.durationMs = durationMs;
        MusicShareActionHelper.showShareDialog(this, token, share);
    }

    private void downloadCurrentSong() {
        if (songUrl == null || songUrl.length() == 0) {
            Toast.makeText(this, "\u6B4C\u66F2\u94FE\u63A5\u4E0D\u53EF\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolvedSongUrl = MediaUrlResolver.resolve(songUrl);
        if (resolvedSongUrl == null || resolvedSongUrl.length() == 0) {
            Toast.makeText(this, "\u6B4C\u66F2\u94FE\u63A5\u4E0D\u53EF\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "\u5F00\u59CB\u4E0B\u8F7D\u5230\u672C\u5730", Toast.LENGTH_SHORT).show();
        DownloadUtil.saveUrlToDownloadsAsync(this,
                resolvedSongUrl,
                "oldchat_music_",
                ".mp3",
                new DownloadUtil.Callback() {
                    @Override
                    public void onResult(boolean success, String message, java.io.File file) {
                        Toast.makeText(MusicPlayerActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void openComments() {
        if (itemId == null || itemId.length() == 0) {
            Toast.makeText(this, "\u5F53\u524D\u6B4C\u66F2\u6682\u65E0\u8BC4\u8BBA\u5165\u53E3", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, MusicCommentsActivity.class);
        intent.putExtra("item_id", itemId);
        intent.putExtra("owner_uid", ownerUid == null ? "" : ownerUid);
        startActivity(intent);
    }

    private void toggleLike() {
        if (likeRequesting) {
            return;
        }
        if (itemId == null || itemId.length() == 0) {
            Toast.makeText(this, "\u5F53\u524D\u6B4C\u66F2\u6682\u65E0\u70B9\u8D5E\u5165\u53E3", Toast.LENGTH_SHORT).show();
            return;
        }
        if (token == null || token.length() == 0) {
            loadAuthToken();
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "\u8BF7\u5148\u767B\u5F55", Toast.LENGTH_SHORT).show();
            return;
        }
        likeRequesting = true;
        updateSocialButtons();
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", itemId);
            String path = likedByMe ? "/music/plaza/unlike" : "/music/plaza/like";
            HttpUtil.post(path, json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        likedByMe = obj.optBoolean("liked", likedByMe);
                        likeCount = Math.max(0, obj.optInt("likes", likeCount));
                    } catch (Exception e) {
                        likedByMe = !likedByMe;
                        if (likedByMe) {
                            likeCount = likeCount + 1;
                        } else if (likeCount > 0) {
                            likeCount = likeCount - 1;
                        }
                    }
                    notifyPlaybackItemMetaChanged();
                    likeRequesting = false;
                    updateSocialButtons();
                }

                @Override
                public void onError(int code, String error) {
                    likeRequesting = false;
                    updateSocialButtons();
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicPlayerActivity.this, "\u70B9\u8D5E\u5931\u8D25", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            likeRequesting = false;
            updateSocialButtons();
            Toast.makeText(this, "\u70B9\u8D5E\u5931\u8D25", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadAuthToken() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");
    }

    private void updateSocialButtons() {
        boolean available = itemId != null && itemId.length() > 0;
        if (btnLike != null) {
            btnLike.setText(likedByMe ? "\u2665" : "\u2661");
            btnLike.setEnabled(!likeRequesting);
            btnLike.setBackgroundColor(Color.TRANSPARENT);
            btnLike.setTextColor(getResources().getColor(likedByMe
                    ? R.color.color_on_primary
                    : R.color.music_player_text_primary));
            btnLike.setContentDescription((likedByMe ? "\u5DF2\u8D5E " : "\u70B9\u8D5E ") + Math.max(0, likeCount));
            ViewCompat.setAlpha(btnLike, likeRequesting ? 0.45f : (available ? 1.0f : 0.82f));
        }
        if (ivComments != null) {
            ivComments.setEnabled(true);
            ivComments.setContentDescription("\u8BC4\u8BBA " + Math.max(0, commentCount));
            ViewCompat.setAlpha(ivComments, available ? 1.0f : 0.82f);
        }
        if (tvStats != null) {
            tvStats.setText("\u8BC4\u8BBA " + Math.max(0, commentCount)
                    + "   \u559C\u6B22 " + Math.max(0, likeCount));
            tvStats.setVisibility(available ? View.VISIBLE : View.GONE);
            tvStats.setTextColor(getResources().getColor(available
                    ? R.color.music_player_text_secondary
                    : R.color.music_player_text_muted));
        }
    }
    private void notifyPlaybackItemMetaChanged() {
        if (itemId == null || itemId.length() == 0) {
            return;
        }
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_UPDATE_ITEM_META);
        intent.putExtra(MusicPlaybackService.EXTRA_ITEM_ID, itemId);
        intent.putExtra(MusicPlaybackService.EXTRA_ITEM_LIKES, Math.max(0, likeCount));
        intent.putExtra(MusicPlaybackService.EXTRA_ITEM_COMMENTS, Math.max(0, commentCount));
        intent.putExtra(MusicPlaybackService.EXTRA_ITEM_LIKED, likedByMe);
        startMusicService(intent);
    }
    private void startOrAttachPlayback() {
        String resolvedSongUrl = MediaUrlResolver.resolve(songUrl);
        if (resolvedSongUrl.length() == 0) {
            Toast.makeText(this, "\u6B4C\u66F2\u94FE\u63A5\u4E0D\u53EF\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent playIntent = new Intent(this, MusicPlaybackService.class);
        playIntent.setAction(MusicPlaybackService.ACTION_PLAY_SONG);
        playIntent.putExtra(MusicPlaybackService.EXTRA_SONG_NAME, songName);
        playIntent.putExtra(MusicPlaybackService.EXTRA_SONG_URL, resolvedSongUrl);
        playIntent.putExtra(MusicPlaybackService.EXTRA_COVER_URL, coverUrl);
        playIntent.putExtra(MusicPlaybackService.EXTRA_OWNER_UID, ownerUid);
        playIntent.putExtra(MusicPlaybackService.EXTRA_OWNER_NAME, ownerName);
        playIntent.putExtra(MusicPlaybackService.EXTRA_OWNER_TITLE, ownerTitle);
        playIntent.putExtra(MusicPlaybackService.EXTRA_OWNER_AVATAR, ownerAvatar);
        playIntent.putExtra(MusicPlaybackService.EXTRA_LYRICS_URL, lyricsUrl);
        playIntent.putExtra(MusicPlaybackService.EXTRA_ITEM_ID, itemId);
        playIntent.putExtra(MusicPlaybackService.EXTRA_ITEM_LIKES, likeCount);
        playIntent.putExtra(MusicPlaybackService.EXTRA_ITEM_COMMENTS, commentCount);
        playIntent.putExtra(MusicPlaybackService.EXTRA_ITEM_LIKED, likedByMe);
        startMusicService(playIntent);
    }

    private void requestPlaybackState() {
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_REQUEST_STATE);
        startMusicService(intent);
    }

    private void sendPlaybackAction(String action) {
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(action);
        startMusicService(intent);
    }

    private void startMusicService(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = value(intent.getAction());
        boolean requireForegroundStart = MusicPlaybackService.ACTION_PLAY_SONG.equals(action);
        try {
            if (Build.VERSION.SDK_INT >= 26 && requireForegroundStart) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            try {
                startService(intent);
            } catch (Exception ignored) {
            }
        }
    }

    private void applyPlaybackState(Intent stateIntent) {
        String newSongUrl = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_SONG_URL));
        String newLyricsUrl = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_LYRICS_URL));
        if (newSongUrl.length() > 0 && !TextUtils.equals(newSongUrl, songUrl)) {
            songUrl = newSongUrl;
            songName = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_SONG_NAME));
            coverUrl = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_COVER_URL));
            // 广播未携带歌词（newLyricsUrl 为空）时保留已有歌词，避免"歌词时有时无"
            if (newLyricsUrl.length() > 0) {
                lyricsUrl = newLyricsUrl;
            }
            ownerUid = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_UID));
            ownerName = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_NAME));
            ownerTitle = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_TITLE));
            ownerAvatar = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_AVATAR));
            String incomingItemId = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_ITEM_ID));
            if (incomingItemId.length() == 0) {
                itemId = "";
                likeCount = 0;
                commentCount = 0;
                likedByMe = false;
            } else {
                itemId = incomingItemId;
            }
            bindHeaderInfo();
            loadLyrics();
        } else if (newLyricsUrl.length() > 0 && !TextUtils.equals(newLyricsUrl, lyricsUrl)) {
            lyricsUrl = newLyricsUrl;
            loadLyrics();
        }

        isPreparing = stateIntent.getBooleanExtra(MusicPlaybackService.EXTRA_IS_PREPARING, false);
        isPlaying = stateIntent.getBooleanExtra(MusicPlaybackService.EXTRA_IS_PLAYING, false);
        repeatOne = stateIntent.getBooleanExtra(MusicPlaybackService.EXTRA_REPEAT_ONE, false);
        durationMs = Math.max(0, stateIntent.getIntExtra(MusicPlaybackService.EXTRA_DURATION_MS, 0));
        positionMs = Math.max(0, stateIntent.getIntExtra(MusicPlaybackService.EXTRA_POSITION_MS, 0));
        String stateItemId = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_ITEM_ID));
        if (stateItemId.length() > 0 && !TextUtils.equals(stateItemId, itemId)) {
            itemId = stateItemId;
            likeCount = Math.max(0, stateIntent.getIntExtra(MusicPlaybackService.EXTRA_ITEM_LIKES, likeCount));
            commentCount = Math.max(0, stateIntent.getIntExtra(MusicPlaybackService.EXTRA_ITEM_COMMENTS, commentCount));
            likedByMe = stateIntent.getBooleanExtra(MusicPlaybackService.EXTRA_ITEM_LIKED, likedByMe);
        }
        String error = value(stateIntent.getStringExtra(MusicPlaybackService.EXTRA_ERROR));
        updateRepeatUi();
        updateSocialButtons();

        if (!isSeeking) {
            seekBar.setMax(Math.max(durationMs, 1));
            seekBar.setProgress(Math.min(positionMs, Math.max(durationMs, 0)));
        }
        tvCurrent.setText(formatDuration(positionMs));
        tvDuration.setText(formatDuration(durationMs));
        updateLyricPlayback(positionMs, isPlaying);

        if (isPreparing) {
            tvStatus.setText("\u6B63\u5728\u52A0\u8F7D\uFF08\u652F\u6301\u540E\u53F0\u64AD\u653E\uFF09");
            if (ivPlayPause != null) {
                ivPlayPause.setImageResource(android.R.drawable.ic_popup_sync);
                ivPlayPause.setEnabled(false);
            }
            return;
        }

        if (ivPlayPause != null) {
            ivPlayPause.setEnabled(true);
        }
        if (error.length() > 0) {
            tvStatus.setText(error);
            if (ivPlayPause != null) {
                ivPlayPause.setImageResource(android.R.drawable.ic_media_play);
            }
            return;
        }
        if (isPlaying) {
            tvStatus.setText("\u6B63\u5728\u64AD\u653E\uFF08\u540E\u53F0\u6301\u7EED\uFF09");
            if (ivPlayPause != null) {
                ivPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            }
            return;
        }
        if (durationMs > 0 && positionMs >= durationMs - 400) {
            tvStatus.setText("\u64AD\u653E\u5B8C\u6210");
            if (ivPlayPause != null) {
                ivPlayPause.setImageResource(android.R.drawable.ic_media_play);
            }
            return;
        }
        if (durationMs > 0 || positionMs > 0) {
            tvStatus.setText("\u5DF2\u6682\u505C\uFF08\u53EF\u5728\u901A\u77E5\u680F\u7EE7\u7EED\uFF09");
        } else {
            tvStatus.setText("\u51C6\u5907\u64AD\u653E");
        }
        if (ivPlayPause != null) {
            ivPlayPause.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void updateRepeatUi() {
        if (ivRepeatOne == null) {
            return;
        }
        ViewCompat.setAlpha(ivRepeatOne, repeatOne ? 1.0f : 0.72f);
    }

    private void applyControlIconTints() {
        applyNeutralIconTint(ivBack);
        applyPrimaryIconTint(ivPlayPause);
        applyNeutralIconTint(ivComments);
        applyNeutralTextTint(ivRepeatOne);
        // 为新的圆形播放按钮设置合适的padding，使图标居中美观
        if (ivPlayPause != null) {
            ivPlayPause.setPadding(22, 22, 22, 22);
        }
    }

    private void applyPrimaryIconTint(ImageView view) {
        if (view == null) {
            return;
        }
        view.setColorFilter(getResources().getColor(R.color.color_on_primary));
    }

    private void applyNeutralIconTint(ImageView view) {
        if (view == null) {
            return;
        }
        view.setColorFilter(getResources().getColor(R.color.music_player_text_primary));
    }

    private void applyNeutralTextTint(TextView view) {
        if (view == null) {
            return;
        }
        view.setTextColor(getResources().getColor(R.color.music_player_text_primary));
    }

    /**
     * 兜底：入口未传歌词 URL 时，按 item_id 查服务端详情接口补齐 lyrics_url。
     * 服务端 GET /music/plaza/detail?item_id=xxx 返回完整字段（含 lyrics_url）。
     */
    private void fetchLyricsUrlFromDetail() {
        if (mLyricView == null || itemId == null || itemId.length() == 0) {
            return;
        }
        if (token == null || token.length() == 0) {
            loadAuthToken();
        }
        final String detailToken = token;
        if (detailToken == null || detailToken.length() == 0) {
            mLyricView.showMessage("\u6682\u65E0\u6B4C\u8BCD");
            return;
        }
        final String targetItemId = itemId;
        mLyricView.showMessage("\u6B4C\u8BCD\u52A0\u8F7D\u4E2D...");
        HttpUtil.get("/music/plaza/detail?item_id=" + urlEncode(targetItemId), detailToken, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                if (lyricsUrl != null && lyricsUrl.length() > 0) {
                    return; // 已有歌词，不覆盖
                }
                try {
                    JSONObject obj = new JSONObject(response);
                    String detailLyrics = obj.optString("lyrics_url", "");
                    if (detailLyrics.length() > 0) {
                        lyricsUrl = detailLyrics;
                        loadLyrics();
                    } else {
                        mLyricView.showMessage("\u6682\u65E0\u6B4C\u8BCD");
                    }
                } catch (Exception e) {
                    mLyricView.showMessage("\u6682\u65E0\u6B4C\u8BCD");
                }
            }

            @Override
            public void onError(int code, String error) {
                mLyricView.showMessage("\u6B4C\u8BCD\u52A0\u8F7D\u5931\u8D25");
            }
        });
    }

    /** 兼容历史记录与聊天音乐分享：未传 ownerUid/item_id/封面/歌词时，自动查询详情补齐。 */
    private void resolveMissingShareDetail() {
        if (shareDetailResolving) {
            return;
        }
        if (token == null || token.length() == 0) {
            loadAuthToken();
        }
        if (token == null || token.length() == 0) {
            return;
        }
        boolean missing = (coverUrl == null || coverUrl.length() == 0)
                || (lyricsUrl == null || lyricsUrl.length() == 0)
                || (ownerUid == null || ownerUid.length() == 0)
                || (itemId == null || itemId.length() == 0);
        if (!missing) {
            return;
        }
        shareDetailResolving = true;
        if (itemId != null && itemId.length() > 0) {
            MusicShareDetailResolver.resolveById(token, itemId, new MusicShareDetailResolver.Callback() {
                @Override
                public void onResolved(MusicShareDetailResolver.Detail detail) {
                    shareDetailResolving = false;
                    applyResolvedDetail(detail);
                }

                @Override
                public void onUnavailable() {
                    fallbackResolveBySongUrl();
                }
            });
        } else {
            fallbackResolveBySongUrl();
        }
    }

    private void fallbackResolveBySongUrl() {
        if (songUrl == null || songUrl.length() == 0) {
            shareDetailResolving = false;
            return;
        }
        final String targetKey = MediaUrlResolver.cacheIdentity(songUrl);
        if (targetKey.equals(shareDetailAttemptedKey)) {
            shareDetailResolving = false;
            return;
        }
        shareDetailAttemptedKey = targetKey;
        MusicShareDetailResolver.resolve(token, songName, songUrl, new MusicShareDetailResolver.Callback() {
            @Override
            public void onResolved(MusicShareDetailResolver.Detail detail) {
                shareDetailResolving = false;
                applyResolvedDetail(detail);
            }

            @Override
            public void onUnavailable() {
                shareDetailResolving = false;
            }
        });
    }

    private void applyResolvedDetail(MusicShareDetailResolver.Detail detail) {
        if (detail == null) {
            return;
        }
        boolean coverChanged = false;
        boolean lyricsChanged = false;
        boolean infoChanged = false;

        if ((itemId == null || itemId.length() == 0) && detail.itemId != null && detail.itemId.length() > 0) {
            itemId = detail.itemId;
            infoChanged = true;
        }
        if ((ownerUid == null || ownerUid.length() == 0) && detail.ownerUid != null && detail.ownerUid.length() > 0) {
            ownerUid = detail.ownerUid;
            infoChanged = true;
        }
        if ((ownerName == null || ownerName.length() == 0) && detail.ownerName != null && detail.ownerName.length() > 0) {
            ownerName = detail.ownerName;
            infoChanged = true;
        }
        if ((ownerTitle == null || ownerTitle.length() == 0) && detail.ownerTitle != null && detail.ownerTitle.length() > 0) {
            ownerTitle = detail.ownerTitle;
            infoChanged = true;
        }
        if ((ownerAvatar == null || ownerAvatar.length() == 0) && detail.ownerAvatar != null && detail.ownerAvatar.length() > 0) {
            ownerAvatar = detail.ownerAvatar;
            infoChanged = true;
        }
        if ((songName == null || songName.length() == 0 || "音乐分享".equals(songName)) && detail.name != null && detail.name.length() > 0) {
            songName = detail.name;
            infoChanged = true;
        }
        if ((coverUrl == null || coverUrl.length() == 0) && detail.coverUrl != null && detail.coverUrl.length() > 0) {
            coverUrl = detail.coverUrl;
            coverChanged = true;
        }
        if ((lyricsUrl == null || lyricsUrl.length() == 0) && detail.lyricsUrl != null && detail.lyricsUrl.length() > 0) {
            lyricsUrl = detail.lyricsUrl;
            lyricsChanged = true;
        }
        if (detail.likes > 0 || detail.comments > 0) {
            likeCount = Math.max(likeCount, detail.likes);
            commentCount = Math.max(commentCount, detail.comments);
            likedByMe = detail.liked;
            infoChanged = true;
        }

        if (infoChanged) {
            updateSocialButtons();
            String owner = ownerName;
            if (owner == null || owner.length() == 0) {
                owner = ownerUid;
            }
            if (owner == null || owner.length() == 0) {
                owner = "匿名用户";
            }
            if (tvOwner != null) {
                if (!TextUtils.isEmpty(ownerTitle)) {
                    tvOwner.setText(owner + " · " + ownerTitle);
                } else {
                    tvOwner.setText(owner);
                }
            }
            if (tvSongName != null && songName != null && songName.length() > 0) {
                tvSongName.setText(songName);
            }
            if (tvLyricTitle != null && songName != null && songName.length() > 0) {
                tvLyricTitle.setText(songName);
            }
            if (tvLyricArtist != null) {
                tvLyricArtist.setText(owner);
            }
        }
        if (coverChanged) {
            String resolvedCoverUrl = coverUrl.length() > 0 ? coverUrl : ownerAvatar;
            if (resolvedCoverUrl.length() > 0) {
                ImageLoader.load(ivCover, resolvedCoverUrl, new ImageLoader.ImageLoadListener() {
                    @Override
                    public void onComplete(String url) {
                        applyAmbientFromCoverDrawable();
                    }
                });
            }
        }
        if (lyricsChanged || (lyricsUrl == null || lyricsUrl.length() == 0)) {
            loadLyrics();
        }
    }

    private String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private void loadLyrics() {
        if (mLyricView == null) {
            return;
        }
        if ((lyricsUrl == null || lyricsUrl.length() == 0) && itemId != null && itemId.length() > 0) {
            fetchLyricsUrlFromDetail();
            return;
        }
        final String[] lyricCandidates = MediaUrlResolver.resolveCandidates(lyricsUrl);
        final String lyricsCacheKey = lyricsUrl == null ? "" : lyricsUrl.trim();
        lastResolvedLyricsUrl = lyricsCacheKey;
        if (lyricCandidates == null || lyricCandidates.length == 0) {
            lyricTaskToken++;
            cancelLyricsTask();
            mLyricView.showMessage("\u6682\u65E0\u6B4C\u8BCD");
            return;
        }

        List<LyricLine> cached = getCachedLyrics(lyricsCacheKey);
        if (cached != null) {
            lyricTaskToken++;
            cancelLyricsTask();
            renderLyrics(cached);
            return;
        }

        mLyricView.showMessage("\u6B4C\u8BCD\u52A0\u8F7D\u4E2D...");
        lyricTaskToken++;
        final int token = lyricTaskToken;
        cancelLyricsTask();
        lyricTask = new AsyncTask<String, Void, String>() {
            @Override
            protected String doInBackground(String... params) {
                if (params == null || params.length == 0) {
                    return null;
                }
                for (int i = 0; i < params.length; i++) {
                    if (isCancelled()) {
                        return null;
                    }
                    String content = downloadText(params[i]);
                    if (content != null && content.length() > 0) {
                        return content;
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(String content) {
                if (token != lyricTaskToken) {
                    return;
                }
                lyricTask = null;
                if (content == null || content.length() == 0) {
                    mLyricView.showMessage("\u6B4C\u8BCD\u52A0\u8F7D\u5931\u8D25");
                    return;
                }
                List<LyricLine> parsed = LyricParser.parse(content);
                cacheLyrics(lastResolvedLyricsUrl, parsed);
                renderLyrics(parsed);
            }
        };
        lyricTask.execute(lyricCandidates);
    }

    private void cancelLyricsTask() {
        if (lyricTask == null) {
            return;
        }
        try {
            lyricTask.cancel(true);
        } catch (Exception e) {
        }
        lyricTask = null;
    }

    private List<LyricLine> getCachedLyrics(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        synchronized (LYRICS_CACHE) {
            List<LyricLine> cached = LYRICS_CACHE.get(url);
            if (cached == null) {
                return null;
            }
            return new ArrayList<LyricLine>(cached);
        }
    }

    private void cacheLyrics(String url, List<LyricLine> lines) {
        if (TextUtils.isEmpty(url) || lines == null) {
            return;
        }
        synchronized (LYRICS_CACHE) {
            LYRICS_CACHE.put(url, new ArrayList<LyricLine>(lines));
        }
    }

    private String downloadText(String url) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        ByteArrayOutputStream bos = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            inputStream = connection.getInputStream();
            bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            String text = new String(bos.toByteArray(), "UTF-8");
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return text;
        } catch (Exception e) {
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e) {
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (Exception e) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void renderLyrics(List<LyricLine> lines) {
        if (mLyricView == null) {
            return;
        }
        if (lines == null || lines.isEmpty()) {
            mLyricView.showMessage("\u6682\u65E0\u6B4C\u8BCD");
            return;
        }
        mLyricView.setLyrics(lines);
        mLyricView.playIntroAnimation();
        updateLyricPlayback(positionMs, isPlaying);
    }

    private void updateLyricPlayback(int posMs, boolean playing) {
        if (mLyricView == null) {
            return;
        }
        mLyricView.setCurrentTime(Math.max(0, posMs));
        mLyricView.setPlaybackRunning(playing);
    }

    private String formatDuration(int durationMs) {
        if (durationMs <= 0) {
            return "00:00";
        }
        int totalSeconds = durationMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private String value(String raw) {
        return raw == null ? "" : raw;
    }

    // ============ 封面/歌词页切换与手势 ============

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (isSeeking) {
            return super.dispatchTouchEvent(event);
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mTouchStartX = event.getX();
                mTouchStartY = event.getY();
                mIsHorizontalScroll = false;
                mIgnoreSwipeGesture = isTouchInsideSeekBar(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mIgnoreSwipeGesture) {
                    break;
                }
                float moveDeltaX = event.getX() - mTouchStartX;
                float moveDeltaY = event.getY() - mTouchStartY;
                if (!mIsHorizontalScroll) {
                    if (Math.abs(moveDeltaX) > Math.abs(moveDeltaY) && Math.abs(moveDeltaX) > 10) {
                        mIsHorizontalScroll = true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mIgnoreSwipeGesture) {
                    mIgnoreSwipeGesture = false;
                    break;
                }
                float deltaX = event.getX() - mTouchStartX;
                float deltaY = event.getY() - mTouchStartY;
                if (Math.abs(deltaX) > Math.abs(deltaY)) {
                    if (isCoverPage) {
                        if (deltaX < -100f) {
                            showLyricPage();
                            return true;
                        } else if (deltaX > 100f) {
                            finish();
                            return true;
                        }
                    } else if (deltaX > 100f) {
                        showCoverPage();
                        return true;
                    }
                }
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isTouchInsideSeekBar(MotionEvent event) {
        if (seekBar == null || seekBar.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        seekBar.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= location[0]
                && rawX <= location[0] + seekBar.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + seekBar.getHeight();
    }

    private void showCoverPage() {
        if (isCoverPage) {
            return;
        }
        isCoverPage = true;
        final int transitionVersion = ++lyricTransitionVersion;

        if (Build.VERSION.SDK_INT < 11) {
            applyCoverPageImmediate();
            return;
        }

        if (mLyricView != null) {
            mLyricView.setPreviewEnabled(false);
            mLyricView.playExitAnimation(new OnLyricAnimationEndListener() {
                @Override
                public void onAnimationEnd() {
                    if (transitionVersion == lyricTransitionVersion && isCoverPage && mLyricView != null) {
                        mLyricView.setVisibility(View.INVISIBLE);
                    }
                }
            });
        }

        // 封面从左上的小封面放大回到中心
        ViewCompat.animate(ivCover)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        if (tvLyricTitle != null) {
            ViewCompat.animate(tvLyricTitle).alpha(0f).setDuration(180).start();
        }
        if (tvLyricArtist != null) {
            ViewCompat.animate(tvLyricArtist).alpha(0f).setDuration(180).setStartDelay(30).start();
        }
        if (llLyricHeader != null) {
            ViewCompat.animate(llLyricHeader)
                    .alpha(0f)
                    .setDuration(180)
                    .setStartDelay(80)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (transitionVersion == lyricTransitionVersion && isCoverPage && llLyricHeader != null) {
                                llLyricHeader.setVisibility(View.INVISIBLE);
                            }
                        }
                    })
                    .start();
        }
        if (llCoverInfo != null) {
            ViewCompat.animate(llCoverInfo)
                    .alpha(1f)
                    .setDuration(220)
                    .setStartDelay(150)
                    .start();
        }
        if (tvSwipeHint != null) {
            tvSwipeHint.setVisibility(View.VISIBLE);
        }
    }

    private void showLyricPage() {
        if (!isCoverPage) {
            return;
        }
        isCoverPage = false;
        final int transitionVersion = ++lyricTransitionVersion;

        if (Build.VERSION.SDK_INT < 11) {
            applyLyricPageImmediate();
            return;
        }

        if (mLyricView != null) {
            mLyricView.setPreviewEnabled(false);
        }

        if (llLyricHeader != null) {
            llLyricHeader.setVisibility(View.VISIBLE);
            llLyricHeader.setAlpha(0f);
        }
        if (tvLyricTitle != null) {
            tvLyricTitle.setAlpha(0f);
        }
        if (tvLyricArtist != null) {
            tvLyricArtist.setAlpha(0f);
        }
        if (mLyricView != null) {
            mLyricView.setVisibility(View.VISIBLE);
            mLyricView.setAlpha(0f);
            mLyricView.resetToCurrentPosition();
        }

        // 封面缩小移动到歌词页标题栏左侧
        llLyricHeader.post(new Runnable() {
            @Override
            public void run() {
                if (transitionVersion != lyricTransitionVersion || isCoverPage) {
                    return;
                }
                int[] coverLoc = new int[2];
                ivCover.getLocationOnScreen(coverLoc);
                int[] headerLoc = new int[2];
                llLyricHeader.getLocationOnScreen(headerLoc);
                float density = getResources().getDisplayMetrics().density;
                float smallSize = ivCover.getWidth() * COVER_SCALE_SMALL;
                // 参考 emusic：小封面左缘 16dp，宽 56dp → 右缘 72dp = 标题栏左边距，标题在其右侧不重叠
                float targetLeft = 16f * density;
                float targetCenterX = targetLeft + smallSize / 2f;
                float headerHeight = llLyricHeader.getHeight();
                float targetCenterY = headerLoc[1] + headerHeight / 2f;
                float coverCenterX = coverLoc[0] + ivCover.getWidth() / 2f;
                float coverCenterY = coverLoc[1] + ivCover.getHeight() / 2f;
                float translationX = targetCenterX - coverCenterX;
                float translationY = targetCenterY - coverCenterY;

                ViewCompat.animate(ivCover)
                        .scaleX(COVER_SCALE_SMALL)
                        .scaleY(COVER_SCALE_SMALL)
                        .translationX(translationX)
                        .translationY(translationY)
                        .setDuration(350)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
        });

        if (llCoverInfo != null) {
            ViewCompat.animate(llCoverInfo)
                    .alpha(0f)
                    .setDuration(180)
                    .start();
        }
        if (tvSwipeHint != null) {
            tvSwipeHint.setVisibility(View.GONE);
        }
        if (llLyricHeader != null) {
            ViewCompat.animate(llLyricHeader)
                    .alpha(1f)
                    .setDuration(180)
                    .setStartDelay(140)
                    .start();
        }
        if (tvLyricTitle != null) {
            ViewCompat.animate(tvLyricTitle)
                    .alpha(1f)
                    .setDuration(180)
                    .setStartDelay(180)
                    .start();
        }
        if (tvLyricArtist != null) {
            ViewCompat.animate(tvLyricArtist)
                    .alpha(1f)
                    .setDuration(180)
                    .setStartDelay(210)
                    .start();
        }

        if (mLyricView != null) {
            mLyricView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (transitionVersion == lyricTransitionVersion && !isCoverPage) {
                        mLyricView.setAlpha(1f);
                        mLyricView.playIntroAnimation();
                        mLyricView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                // 歌词拖动预览已按用户要求关闭，仅保留自动高亮滚动
                            }
                        }, 420L);
                    }
                }
            }, 190L);
        }
    }

    // API<11：无属性动画，直接切换页面状态
    private void applyCoverPageImmediate() {
        if (mLyricView != null) {
            mLyricView.setPreviewEnabled(false);
            mLyricView.setVisibility(View.INVISIBLE);
        }
        if (llLyricHeader != null) {
            llLyricHeader.setVisibility(View.GONE);
        }
        if (llCoverInfo != null) {
            llCoverInfo.setVisibility(View.VISIBLE);
            android.support.v4.view.ViewCompat.setAlpha(llCoverInfo, 1f);
        }
        if (tvLyricTitle != null) {
            android.support.v4.view.ViewCompat.setAlpha(tvLyricTitle, 1f);
        }
        if (tvLyricArtist != null) {
            android.support.v4.view.ViewCompat.setAlpha(tvLyricArtist, 1f);
        }
        if (tvSwipeHint != null) {
            tvSwipeHint.setVisibility(View.VISIBLE);
        }
    }

    private void applyLyricPageImmediate() {
        if (mLyricView != null) {
            mLyricView.setPreviewEnabled(false);
            mLyricView.setVisibility(View.VISIBLE);
            android.support.v4.view.ViewCompat.setAlpha(mLyricView, 1f);
            mLyricView.resetToCurrentPosition();
            mLyricView.playIntroAnimation();
        }
        if (llLyricHeader != null) {
            llLyricHeader.setVisibility(View.VISIBLE);
            android.support.v4.view.ViewCompat.setAlpha(llLyricHeader, 1f);
        }
        if (tvLyricTitle != null) {
            android.support.v4.view.ViewCompat.setAlpha(tvLyricTitle, 1f);
        }
        if (tvLyricArtist != null) {
            android.support.v4.view.ViewCompat.setAlpha(tvLyricArtist, 1f);
        }
        if (llCoverInfo != null) {
            llCoverInfo.setVisibility(View.INVISIBLE);
            android.support.v4.view.ViewCompat.setAlpha(llCoverInfo, 0f);
        }
        if (tvSwipeHint != null) {
            tvSwipeHint.setVisibility(View.GONE);
        }
    }

    // ============ 封面氛围背景取色 ============

    private void applyAmbientFromCoverDrawable() {
        if (ambientApplied) {
            return;
        }
        try {
            android.graphics.drawable.Drawable drawable = ivCover.getDrawable();
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                if (bitmap != null) {
                    int[] colors = CoverColorExtractor.extractAccentColors(bitmap, 3);
                    if (colors != null && colors.length >= 2) {
                        applyAmbientColors(colors);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        applyAmbientColors(ambientColors);
    }

    private void applyAmbientColors(int[] colors) {
        if (colors == null || colors.length == 0) {
            return;
        }
        ambientApplied = true;
        int[] target = new int[3];
        for (int i = 0; i < target.length; i++) {
            // 保留封面原始调色板；明暗色阶和全屏混合由 CoverAmbientMotionView 统一生成。
            target[i] = colors[i % colors.length];
        }
        ambientColors = target;
        if (viewCoverAmbient != null) {
            viewCoverAmbient.setAccentColors(target);
        }
    }
}
