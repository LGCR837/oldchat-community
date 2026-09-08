package com.im.oldchat.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Looper;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AlertDialog;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.service.MusicPlaybackService;
import com.im.oldchat.ui.widget.CoverFlowView;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.VideoMetadataApi10;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Iterator;

public class MusicPlazaActivity extends BaseActivity {
    private static final int REQ_PICK_SONG = 4301;
    private static final int REQ_PICK_COVER = 4302;
    private static final int REQ_PICK_LYRICS = 4303;
    private static final int REQ_PICK_MEDIA_PERMISSION = 4303;
    private static final int REQ_PICK_EDIT_COVER = 4304;
    private static final int PICK_KIND_NONE = 0;
    private static final int PICK_KIND_SONG = 1;
    private static final int PICK_KIND_COVER = 2;
    private static final int PAGE_SIZE = 50;
    private static final long MAX_SONG_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_COVER_BYTES = 1L * 1024L * 1024L;
    private static final long MAX_LYRICS_BYTES = 1L * 1024L * 1024L;
    private static final String PREF_MUSIC_LOCAL = "music_plaza_local";
    private static final String KEY_RECENT_PLAY = "recent_play";
    private static final int MAX_RECENT_PLAY = 30;

    private EditText etKeyword;
    private TextView btnSearch;
    private TextView btnNowPlaying;
    private TextView btnDownloads;
    private TextView btnUpload;
    private TextView btnSettings;
    private View layoutFeatured;
    private ImageView ivFeaturedCover;
    private TextView tvFeaturedTitle;
    private TextView tvFeaturedSub;
    private TextView btnFeaturedPlay;
    private CoverFlowView coverflow;
    private TextView tvCoverflowEmpty;
    private android.widget.LinearLayout layoutPlaylists;
    private TextView tvPlaylistsEmpty;
    private TextView btnCreatePlaylist;

    private String token;
    private String myUid;
    private final List<MusicItem> items = new ArrayList<MusicItem>();
    private final List<MusicItem> recommendItems = new ArrayList<MusicItem>();
    private final List<MusicItem> rankingItems = new ArrayList<MusicItem>();
    private int currentOffset = 0;
    private int totalCount = 0;
    private boolean hasMore = false;
    private boolean showMineOnly = false;

    private Uri pendingSongUri;
    private Uri pendingCoverUri;
    private Uri pendingLyricsUri;
    private String pendingName;
    private int pendingPickKind = PICK_KIND_NONE;
    private int pendingCoverPickRequestCode = REQ_PICK_COVER;
    private AlertDialog uploadDialog;
    private TextView uploadSongTextView;
    private TextView uploadCoverTextView;
    private TextView uploadLyricsTextView;
    private AlertDialog uploadProgressDialog;
    private ProgressBar uploadProgressBar;
    private TextView uploadProgressTextView;
    private boolean uploadInProgress = false;
    private AlertDialog editDialog;
    private Uri pendingEditCoverUri;
    private TextView editCoverTextView;

    private MediaPlayer mediaPlayer;
    private String currentPlayUrl;
    private String currentPlayingSongName = "";
    private String currentPlayingSongUrl = "";
    private String currentPlayingCoverUrl = "";
    private String currentPlayingLyricsUrl = "";
    private String currentPlayingOwnerUid = "";
    private String currentPlayingOwnerName = "";
    private String currentPlayingOwnerTitle = "";
    private String currentPlayingOwnerAvatar = "";
    private String currentPlayingItemId = "";
    private int currentPlayingLikes;
    private int currentPlayingComments;
    private boolean currentPlayingLiked;
    private boolean currentPlayingActive = false;
    private boolean homeSectionDirty = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable homeSectionRefreshTask;

    private final Object durationProbeLock = new Object();
    private final HashMap<String, Integer> durationProbeCache = new HashMap<String, Integer>();
    private final HashSet<String> durationProbeRunning = new HashSet<String>();

    private final HashSet<String> cacheDownloadingUrls = new HashSet<String>();
    private final BroadcastReceiver musicCacheReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleMusicCacheResult(intent);
        }
    };
    private final BroadcastReceiver playbackStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handlePlaybackState(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_plaza);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        myUid = prefs.getString("uid", "");
        if (myUid == null || myUid.length() == 0) {
            myUid = prefs.getString("user_uid", "");
        }

        etKeyword = findViewByIdCompat(R.id.etMusicPlazaKeyword);
        btnSearch = findViewByIdCompat(R.id.btnMusicPlazaSearch);
        btnNowPlaying = findViewByIdCompat(R.id.btnMusicPlazaNowPlaying);
        btnDownloads = findViewByIdCompat(R.id.btnMusicPlazaDownloads);
        btnUpload = findViewByIdCompat(R.id.btnMusicPlazaUpload);
        btnSettings = findViewByIdCompat(R.id.btnMusicPlazaSettings);
        layoutFeatured = findViewByIdCompat(R.id.layoutMusicPlazaHero);
        ivFeaturedCover = findViewByIdCompat(R.id.ivMusicPlazaFeaturedCover);
        tvFeaturedTitle = findViewByIdCompat(R.id.tvMusicPlazaFeaturedTitle);
        tvFeaturedSub = findViewByIdCompat(R.id.tvMusicPlazaFeaturedSub);
        btnFeaturedPlay = findViewByIdCompat(R.id.btnMusicPlazaFeaturedPlay);
        coverflow = (CoverFlowView) findViewByIdCompat(R.id.coverflowMusicPlaza);
        tvCoverflowEmpty = findViewByIdCompat(R.id.tvMusicPlazaCoverflowEmpty);
        layoutPlaylists = findViewByIdCompat(R.id.layoutMusicPlazaPlaylists);
        tvPlaylistsEmpty = findViewByIdCompat(R.id.tvMusicPlazaPlaylistsEmpty);
        btnCreatePlaylist = findViewByIdCompat(R.id.btnMusicPlazaCreatePlaylist);

        View btnBack = findViewByIdCompat(R.id.btnMusicPlazaBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        if (coverflow != null) {
            coverflow.setOnItemClickListener(new CoverFlowView.OnItemClickListener() {
                @Override
                public void onItemClick(int position, CoverFlowView.Item item) {
                    Object tag = item != null ? item.mTag : null;
                    if (tag instanceof MusicItem) {
                        playItem((MusicItem) tag);
                    }
                }
            });
        }

        // 左右切换按钮：点击切一张，长按连续切换（自实现长按检测，避免 ScrollView 干扰）
        final View btnPrev = findViewByIdCompat(R.id.btnCoverflowPrev);
        final View btnNext = findViewByIdCompat(R.id.btnCoverflowNext);
        if (btnPrev != null) {
            btnPrev.setOnTouchListener(buildCoverflowRepeatTouchListener(-1));
        }
        if (btnNext != null) {
            btnNext.setOnTouchListener(buildCoverflowRepeatTouchListener(1));
        }

        View entryRecommend = findViewByIdCompat(R.id.btnMusicPlazaEntryRecommend);
        if (entryRecommend != null) {
            entryRecommend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openCategory(MusicCategoryActivity.CATEGORY_RECOMMEND);
                }
            });
        }
        View entryRanking = findViewByIdCompat(R.id.btnMusicPlazaEntryRanking);
        if (entryRanking != null) {
            entryRanking.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openCategory(MusicCategoryActivity.CATEGORY_RANKING);
                }
            });
        }
        View entryAll = findViewByIdCompat(R.id.btnMusicPlazaEntryAll);
        if (entryAll != null) {
            entryAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openCategory(MusicCategoryActivity.CATEGORY_LATEST);
                }
            });
        }
        if (btnCreatePlaylist != null) {
            btnCreatePlaylist.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showNewPlaylistDialog();
                }
            });
        }

        if (btnSearch != null) {
            btnSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSearchPage();
                }
            });
        }
        if (btnUpload != null) {
            btnUpload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMineMenu();
                }
            });
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPlayerSettingsDialog();
                }
            });
        }
        if (btnNowPlaying != null) {
            btnNowPlaying.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openNowPlayingPage();
                }
            });
        }
        if (btnDownloads != null) {
            btnDownloads.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MusicPlazaActivity.this, MusicDownloadsActivity.class));
                }
            });
        }
        if (etKeyword != null) {
            // 音乐广场搜索框变为搜索页入口：点击跳转独立搜索页
            etKeyword.setFocusable(false);
            etKeyword.setClickable(true);
            etKeyword.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSearchPage();
                }
            });
            etKeyword.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        openSearchPage();
                    }
                    return true;
                }
            });
        }

        if (btnFeaturedPlay != null) {
            btnFeaturedPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (items.isEmpty()) {
                        return;
                    }
                    playItem(items.get(0));
                }
            });
        }

        updateNowPlayingButton();
        loadItems();
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(musicCacheReceiver,
                new IntentFilter(MusicPlaybackService.ACTION_CACHE_RESULT));
        manager.registerReceiver(playbackStateReceiver,
                new IntentFilter(MusicPlaybackService.ACTION_STATE_CHANGED));
        requestPlaybackState();
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        try {
            manager.unregisterReceiver(musicCacheReceiver);
        } catch (Exception ignore) {
        }
        try {
            manager.unregisterReceiver(playbackStateReceiver);
        } catch (Exception ignore) {
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (homeSectionDirty) {
            homeSectionDirty = false;
            updateHomeSections();
        }
    }

    @Override
    protected void onDestroy() {
        if (homeSectionRefreshTask != null) {
            mainHandler.removeCallbacks(homeSectionRefreshTask);
        }
        dismissUploadProgress();
        stopPlay();
        super.onDestroy();
    }

    private void showMineMenu() {
        final String[] actions = new String[]{"最近听过", "我的发布", "上传音乐", "同步歌单"};
        new AlertDialog.Builder(this)
                .setTitle("我的")
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            showRecentPlayedDialog();
                            return;
                        }
                        if (which == 1) {
                            Intent intent = new Intent(MusicPlazaActivity.this, MusicManageActivity.class);
                            startActivity(intent);
                            return;
                        }
                        if (which == 2) {
                            showUploadDialog();
                            return;
                        }
                        if (which == 3) {
                            showPlaylistSyncDialog();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 音乐播放器设置：歌词动效弹性 / 音乐动态背景 / 歌词颜色模式。 */
    private void showPlayerSettingsDialog() {
        final android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        final android.support.v7.widget.SwitchCompat switchBounce = buildSettingSwitch(
                "歌词动效弹性", "歌词滚动时的弹簧缩放/回弹效果");
        switchBounce.setChecked(SettingsPrefs.isLyricBounceEnabled(this));
        container.addView(switchBounce);

        final android.support.v7.widget.SwitchCompat switchDynamicBg = buildSettingSwitch(
                "音乐动态背景", "播放页随封面取色的氛围动态背景");
        switchDynamicBg.setChecked(SettingsPrefs.isMusicDynamicBgEnabled(this));
        container.addView(switchDynamicBg);

        final TextView tvBgStyle = new TextView(this);
        tvBgStyle.setTextColor(0xFF4A423B);
        tvBgStyle.setTextSize(15);
        tvBgStyle.setText("背景颜色");
        container.addView(tvBgStyle);

        final android.widget.RadioGroup bgGroup = new android.widget.RadioGroup(this);
        bgGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        final android.widget.RadioButton rbBgLight = new android.widget.RadioButton(this);
        rbBgLight.setId(R.id.radioMusicBgLight);
        rbBgLight.setText("浅色（米白）");
        final android.widget.RadioButton rbBgDark = new android.widget.RadioButton(this);
        rbBgDark.setId(R.id.radioMusicBgDark);
        rbBgDark.setText("深色（炭黑）");
        final android.widget.RadioButton rbBgDynamic = new android.widget.RadioButton(this);
        rbBgDynamic.setId(R.id.radioMusicBgDynamic);
        rbBgDynamic.setText("动态（封面取色·莫奈）");
        int bgStyle = SettingsPrefs.getMusicBgStyle(this);
        if (bgStyle == 1) {
            rbBgDark.setChecked(true);
        } else if (bgStyle == 2) {
            rbBgDynamic.setChecked(true);
        } else {
            rbBgLight.setChecked(true);
        }
        bgGroup.addView(rbBgLight);
        bgGroup.addView(rbBgDark);
        bgGroup.addView(rbBgDynamic);
        container.addView(bgGroup);

        final TextView tvColorMode = new TextView(this);
        tvColorMode.setTextColor(0xFF4A423B);
        tvColorMode.setTextSize(15);
        tvColorMode.setText("歌词颜色");
        container.addView(tvColorMode);

        final android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(this);
        radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        final android.widget.RadioButton rbDark = new android.widget.RadioButton(this);
        rbDark.setId(R.id.radioMusicTextDark);
        rbDark.setText("暗色模式（歌词保持明亮）");
        final android.widget.RadioButton rbTwoTone = new android.widget.RadioButton(this);
        rbTwoTone.setId(R.id.radioMusicTextTwoTone);
        rbTwoTone.setText("两色模式（歌词暗色系）");
        int colorMode = SettingsPrefs.getLyricColorMode(this);
        if (colorMode == 1) {
            rbTwoTone.setChecked(true);
        } else {
            rbDark.setChecked(true);
        }
        radioGroup.addView(rbDark);
        radioGroup.addView(rbTwoTone);
        container.addView(radioGroup);

        new AlertDialog.Builder(this)
                .setTitle("播放器设置")
                .setView(container)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        SettingsPrefs.setLyricBounceEnabled(MusicPlazaActivity.this, switchBounce.isChecked());
                        SettingsPrefs.setMusicDynamicBgEnabled(MusicPlazaActivity.this, switchDynamicBg.isChecked());
                        SettingsPrefs.setLyricColorMode(MusicPlazaActivity.this,
                                rbTwoTone.isChecked() ? 1 : 0);
                        int bgStyle = rbBgDynamic.isChecked() ? 2 : (rbBgDark.isChecked() ? 1 : 0);
                        SettingsPrefs.setMusicBgStyle(MusicPlazaActivity.this, bgStyle);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.support.v7.widget.SwitchCompat buildSettingSwitch(String title, String summary) {
        final android.support.v7.widget.SwitchCompat switchCompat = new android.support.v7.widget.SwitchCompat(this);
        switchCompat.setText(title);
        switchCompat.setTextSize(15);
        switchCompat.setTextColor(0xFF4A423B);
        if (summary != null && summary.length() > 0) {
            switchCompat.setHint(summary);
        }
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        switchCompat.setPadding(0, pad, 0, pad);
        return switchCompat;
    }
    private void showPlaylistSyncDialog() {
        new AlertDialog.Builder(this)
                .setTitle("同步歌单")
                .setItems(new String[]{"☁ 上传歌单到服务器", "↓ 从服务器下载歌单"}, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            confirmSync(true);
                        } else if (which == 1) {
                            confirmSync(false);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmSync(final boolean isUpload) {
        new AlertDialog.Builder(this)
                .setTitle(isUpload ? "上传歌单到服务器" : "从服务器下载歌单")
                .setMessage(isUpload
                        ? "将用本地的全部歌单覆盖云端歌单，确定继续？"
                        : "将用云端的全部歌单覆盖本地歌单，确定继续？")
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        startSync(isUpload);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 开始同步并显示进度条（上传/下载字节进度）。 */
    private void startSync(final boolean isUpload) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle(isUpload ? "上传歌单到服务器" : "从服务器下载歌单");
        pd.setMessage("请稍候…");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setIndeterminate(false);
        pd.setCancelable(false);
        pd.show();

        com.im.oldchat.api.HttpProgressUtil.ProgressListener progress =
                new com.im.oldchat.api.HttpProgressUtil.ProgressListener() {
                    @Override
                    public void onProgress(int percent) {
                        if (pd == null || !pd.isShowing()) {
                            return;
                        }
                        if (percent < 0) {
                            pd.setIndeterminate(true);
                        } else {
                            pd.setIndeterminate(false);
                            pd.setProgress(percent);
                        }
                    }
                };
        final PlaylistSyncHelper.Callback done = new PlaylistSyncHelper.Callback() {
            @Override
            public void onDone(boolean success, String message) {
                if (pd != null && pd.isShowing()) {
                    pd.dismiss();
                }
            }
        };
        if (isUpload) {
            com.im.oldchat.ui.PlaylistSyncHelper.push(this, progress, done);
        } else {
            com.im.oldchat.ui.PlaylistSyncHelper.pull(this, progress, done);
        }
    }

    private void showRecentPlayedDialog() {
        final List<String> entries = getRecentPlayEntries();
        if (entries.isEmpty()) {
            Toast.makeText(this, "暂无最近听过", Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] labels = new CharSequence[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = decodeRecentLabel(entries.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("最近听过")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which < 0 || which >= entries.size()) {
                            return;
                        }
                        String raw = entries.get(which);
                        MusicItem item = parseRecentEntry(raw);
                        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
                            return;
                        }
                        playItem(item);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void updateFeaturedCard() {
        if (layoutFeatured == null) {
            return;
        }
        if (items.isEmpty()) {
            layoutFeatured.setVisibility(View.GONE);
            return;
        }
        final MusicItem featured = items.get(0);
        if (featured == null) {
            layoutFeatured.setVisibility(View.GONE);
            return;
        }
        layoutFeatured.setVisibility(View.VISIBLE);
        if (tvFeaturedTitle != null) {
            String title = featured.name == null || featured.name.length() == 0 ? "精选歌曲" : featured.name;
            tvFeaturedTitle.setText(title);
        }
        if (tvFeaturedSub != null) {
            String owner = featured.ownerName;
            if (owner == null || owner.length() == 0) {
                owner = featured.ownerUid;
            }
            if (owner == null || owner.length() == 0) {
                owner = "来自音乐广场";
            } else {
                owner = "推荐上传者 · " + owner;
            }
            tvFeaturedSub.setText(owner);
        }
        if (ivFeaturedCover != null) {
            if (featured.coverUrl != null && featured.coverUrl.length() > 0) {
                ImageLoader.load(ivFeaturedCover, featured.coverUrl);
            } else if (featured.ownerAvatar != null && featured.ownerAvatar.length() > 0) {
                ImageLoader.load(ivFeaturedCover, featured.ownerAvatar);
            } else {
                ivFeaturedCover.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        }
    }

    private void rebuildRecommendItems() {
        recommendItems.clear();
        if (items.isEmpty()) {
            return;
        }
        ArrayList<MusicItem> pool = new ArrayList<MusicItem>(items);
        Collections.shuffle(pool);
        int max = Math.min(pool.size(), 12);
        for (int i = 0; i < max; i++) {
            recommendItems.add(pool.get(i));
        }
    }

    private void updateHomeSections() {
        renderCoverflow();
        renderPlaylists();
    }

    private void renderCoverflow() {
        if (coverflow == null) {
            return;
        }
        List<MusicItem> source = recommendItems.isEmpty() ? items : recommendItems;
        List<CoverFlowView.Item> coverItems = new ArrayList<CoverFlowView.Item>();
        int max = Math.min(source.size(), 12);
        for (int i = 0; i < max; i++) {
            MusicItem item = source.get(i);
            if (item == null) {
                continue;
            }
            String title = item.name == null ? "未命名歌曲" : item.name;
            String sub = item.ownerName == null ? "" : item.ownerName;
            String cover = (item.coverUrl == null || item.coverUrl.length() == 0)
                    ? (item.ownerAvatar == null ? "" : item.ownerAvatar)
                    : item.coverUrl;
            // 封面是相对路径（如 /uploads/xxx），需补全为绝对 URL 才能下载
            if (cover != null && cover.length() > 0
                    && !(cover.startsWith("http://") || cover.startsWith("https://"))) {
                cover = MediaUrlResolver.resolve(cover);
            }
            coverItems.add(new CoverFlowView.Item(
                    item.id == null ? String.valueOf(i) : item.id,
                    title,
                    sub,
                    cover,
                    item));
        }
        if (coverItems.isEmpty()) {
            coverflow.setVisibility(View.GONE);
            if (tvCoverflowEmpty != null) {
                tvCoverflowEmpty.setVisibility(View.VISIBLE);
            }
            return;
        }
        coverflow.setVisibility(View.VISIBLE);
        if (tvCoverflowEmpty != null) {
            tvCoverflowEmpty.setVisibility(View.GONE);
        }
        coverflow.setItems(coverItems);
    }

    private void scheduleHomeSectionsRefresh() {
        if (isFinishing()) {
            return;
        }
        homeSectionDirty = true;
        if (homeSectionRefreshTask == null) {
            homeSectionRefreshTask = new Runnable() {
                @Override
                public void run() {
                    if (isFinishing()) {
                        return;
                    }
                    homeSectionDirty = false;
                    updateHomeSections();
                }
            };
        }
        mainHandler.removeCallbacks(homeSectionRefreshTask);
        mainHandler.postDelayed(homeSectionRefreshTask, 220);
    }

    private void renderPlaylists() {
        if (layoutPlaylists == null) {
            return;
        }
        layoutPlaylists.removeAllViews();
        List<com.im.oldchat.models.MusicPlaylist> playlists = com.im.oldchat.data.MusicPlaylistStore.getInstance(this).getPlaylists();
        if (playlists.isEmpty()) {
            if (tvPlaylistsEmpty != null) {
                tvPlaylistsEmpty.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (tvPlaylistsEmpty != null) {
            tvPlaylistsEmpty.setVisibility(View.GONE);
        }
        for (int i = 0; i < playlists.size(); i++) {
            final com.im.oldchat.models.MusicPlaylist pl = playlists.get(i);
            View card = buildPlaylistCard(pl);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    dpToPx(132),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.rightMargin = dpToPx(10);
            layoutPlaylists.addView(card, lp);
        }
    }

    private View buildPlaylistCard(final com.im.oldchat.models.MusicPlaylist pl) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_music_plaza_card_selector);
        card.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MusicPlazaActivity.this, PlaylistDetailActivity.class);
                intent.putExtra("playlist_id", pl.getId());
                startActivity(intent);
            }
        });

        ImageView cover = new ImageView(this);
        int coverSize = dpToPx(116);
        android.widget.LinearLayout.LayoutParams coverLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                coverSize
        );
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundResource(R.drawable.bg_music_plaza_cover_frame);
        cover.setPadding(1, 1, 1, 1);
        String coverUrl = pl.getCover();
        java.util.List<com.im.oldchat.models.MusicItem> songs = pl.getSongs();
        if ((coverUrl == null || coverUrl.length() == 0) && songs != null && !songs.isEmpty()) {
            coverUrl = songs.get(0).coverUrl;
        }
        if (coverUrl != null && coverUrl.length() > 0) {
            ImageLoader.load(cover, coverUrl);
        } else {
            cover.setImageResource(R.drawable.ic_avatar_placeholder);
        }
        card.addView(cover, coverLp);

        TextView name = new TextView(this);
        name.setTextColor(getResources().getColor(R.color.music_plaza_text_primary));
        name.setTextSize(13f);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dpToPx(6), 0, 0);
        name.setText(pl.getName() == null || pl.getName().length() == 0 ? "未命名歌单" : pl.getName());
        card.addView(name);

        TextView count = new TextView(this);
        count.setTextColor(getResources().getColor(R.color.music_plaza_text_secondary));
        count.setTextSize(11f);
        count.setPadding(0, dpToPx(2), 0, 0);
        count.setText(pl.getSongCount() + "首");
        card.addView(count);
        return card;
    }

    private void showNewPlaylistDialog() {
        final android.widget.EditText etName = new android.widget.EditText(this);
        etName.setHint("请输入歌单名称");
        etName.setTextColor(getResources().getColor(R.color.music_plaza_text_primary));
        etName.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("新建歌单")
                .setView(etName)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String name = etName.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(MusicPlazaActivity.this, "歌单名称不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        com.im.oldchat.models.MusicPlaylist pl = new com.im.oldchat.models.MusicPlaylist(
                                "pl_" + System.currentTimeMillis(), name);
                        com.im.oldchat.data.MusicPlaylistStore.getInstance(MusicPlazaActivity.this).addPlaylist(pl);
                        scheduleHomeSectionsRefresh();
                        Toast.makeText(MusicPlazaActivity.this, "歌单已创建", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openCategory(String category) {
        Intent intent = new Intent(this, MusicCategoryActivity.class);
        intent.putExtra(MusicCategoryActivity.EXTRA_CATEGORY, category);
        startActivity(intent);
    }

    private void openSearchPage() {
        String keyword = etKeyword == null || etKeyword.getText() == null
                ? "" : etKeyword.getText().toString().trim();
        Intent intent = new Intent(this, MusicSearchActivity.class);
        if (keyword.length() > 0) {
            intent.putExtra("keyword", keyword);
        }
        startActivity(intent);
    }

    private final Runnable coverflowRepeatTask = new Runnable() {
        @Override
        public void run() {
            if (coverflow != null) {
                if (coverflowRepeatDirection > 0) {
                    coverflow.moveToNext();
                } else {
                    coverflow.moveToPrevious();
                }
            }
            if (coverflowRepeatDirection != 0) {
                mainHandler.postDelayed(this, 320);
            }
        }
    };
    private int coverflowRepeatDirection = 0;

    /**
     * 构建 CoverFlow 切换按钮的触摸监听：按下 500ms 内抬起 = 单击切一张；
     * 按住超过 500ms = 进入连续切换模式，松手停止。
     * 返回 true 消费事件，避免 ScrollView 抢走触摸导致长按失败。
     */
    private android.view.View.OnTouchListener buildCoverflowRepeatTouchListener(final int direction) {
        return new android.view.View.OnTouchListener() {
            private boolean longPressed = false;
            private boolean repeating = false;
            private float downX = 0f;
            private float downY = 0f;
            private final Runnable startRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    if (coverflow != null) {
                        longPressed = true;
                        repeating = true;
                        coverflowRepeatDirection = direction;
                        mainHandler.removeCallbacks(coverflowRepeatTask);
                        mainHandler.postDelayed(coverflowRepeatTask, 60);
                    }
                }
            };

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        longPressed = false;
                        repeating = false;
                        downX = event.getX();
                        downY = event.getY();
                        v.postDelayed(startRepeatRunnable, 500);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!longPressed && (Math.abs(event.getX() - downX) > 12
                                || Math.abs(event.getY() - downY) > 12)) {
                            v.removeCallbacks(startRepeatRunnable);
                            return false;
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(startRepeatRunnable);
                        if (repeating) {
                            repeating = false;
                            coverflowRepeatDirection = 0;
                            mainHandler.removeCallbacks(coverflowRepeatTask);
                            return true;
                        }
                        if (!longPressed && event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            if (coverflow != null) {
                                if (direction > 0) {
                                    coverflow.moveToNext();
                                } else {
                                    coverflow.moveToPrevious();
                                }
                            }
                        }
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

    private void loadItems() {
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        final String q = etKeyword == null || etKeyword.getText() == null
                ? "" : etKeyword.getText().toString().trim();
        StringBuilder path = new StringBuilder("/music/plaza?limit=").append(PAGE_SIZE)
                .append("&offset=").append(currentOffset)
                .append("&sort=latest");
        if (showMineOnly) {
            path.append("&mine=1");
        }
        if (q.length() > 0) {
            path.append("&q=").append(urlEncode(q));
        }
        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("items");
                    totalCount = obj.optInt("total", 0);
                    hasMore = obj.optBoolean("has_more", false);
                    items.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject one = arr.optJSONObject(i);
                            if (one == null) {
                                continue;
                            }
                            MusicItem item = new MusicItem();
                            item.id = one.optString("id", "");
                            item.name = one.optString("name", "");
                            item.songUrl = one.optString("song_url", "");
                            item.coverUrl = one.optString("cover_url", "");
                            item.lyricsUrl = one.optString("lyrics_url", "");
                            item.sizeBytes = one.optLong("size_bytes", 0);
                            item.durationMs = parseDurationMsFromJson(one);
                            item.ownerUid = one.optString("owner_uid", "");
                            item.ownerName = one.optString("owner_name", "");
                            item.ownerTitle = one.optString("owner_title", "");
                            item.ownerAvatar = one.optString("owner_avatar", "");
                            item.likes = one.optInt("likes", 0);
                            item.comments = one.optInt("comments", 0);
                            item.playCount = one.optInt("play_count", 0);
                            item.liked = one.optBoolean("liked", false);
                            item.canDelete = one.optBoolean("can_delete", false);
                            if (item.id.length() == 0 || item.songUrl.length() == 0) {
                                continue;
                            }
                            items.add(item);
                        }
                    }
                    rebuildRecommendItems();
                    updateFeaturedCard();
                    updateHomeSections();
                    loadRankingItems();
                } catch (Exception e) {
                    Toast.makeText(MusicPlazaActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(MusicPlazaActivity.this, "加载失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadRankingItems() {
        if (token == null || token.length() == 0) {
            return;
        }
        HttpUtil.get("/music/plaza/ranking?limit=10", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("items");
                    rankingItems.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject one = arr.optJSONObject(i);
                            if (one == null) {
                                continue;
                            }
                            MusicItem item = new MusicItem();
                            item.id = one.optString("id", "");
                            item.name = one.optString("name", "");
                            item.songUrl = one.optString("song_url", "");
                            item.coverUrl = one.optString("cover_url", "");
                            item.lyricsUrl = one.optString("lyrics_url", "");
                            item.sizeBytes = one.optLong("size_bytes", 0);
                            item.durationMs = parseDurationMsFromJson(one);
                            item.ownerUid = one.optString("owner_uid", "");
                            item.ownerName = one.optString("owner_name", "");
                            item.ownerTitle = one.optString("owner_title", "");
                            item.ownerAvatar = one.optString("owner_avatar", "");
                            item.likes = one.optInt("likes", 0);
                            item.comments = one.optInt("comments", 0);
                            item.playCount = one.optInt("play_count", 0);
                            item.liked = one.optBoolean("liked", false);
                            item.canDelete = one.optBoolean("can_delete", false);
                            if (item.id.length() == 0 || item.songUrl.length() == 0) {
                                continue;
                            }
                            rankingItems.add(item);
                        }
                    }
                    updateHomeSections();
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void onError(int code, String error) {
                // ignore
            }
        });
    }

    private void showUploadDialog() {
        final EditText nameInput = new EditText(this);
        nameInput.setHint("请输入歌曲名称");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        final TextView songText = new TextView(this);
        final TextView coverText = new TextView(this);
        final TextView lyricsText = new TextView(this);
        uploadSongTextView = songText;
        uploadCoverTextView = coverText;
        uploadLyricsTextView = lyricsText;
        refreshUploadSelectionLabels();
        songText.setPadding(0, dpToPx(6), 0, dpToPx(4));
        coverText.setPadding(0, dpToPx(2), 0, dpToPx(4));
        lyricsText.setPadding(0, dpToPx(2), 0, dpToPx(4));

        final TextView chooseSong = buildFlatTextButton("选择歌曲文件");
        final TextView chooseCover = buildFlatTextButton("选择封面文件");
        final TextView chooseLyrics = buildFlatTextButton("选择歌词文件");

        pendingSongUri = null;
        pendingCoverUri = null;
        pendingLyricsUri = null;
        pendingName = null;

        final android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), 0);
        root.addView(nameInput);
        root.addView(songText);
        root.addView(chooseSong);
        root.addView(coverText);
        root.addView(chooseCover);
        root.addView(lyricsText);
        root.addView(chooseLyrics);

        chooseSong.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                tryPickSongFile();
            }
        });
        chooseCover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                tryPickCoverFile();
            }
        });
        chooseLyrics.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                tryPickLyricsFile();
            }
        });
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("上传音乐")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始上传", null)
                .create();
        uploadDialog = dialog;
        dialog.show();
        refreshUploadSelectionLabels();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialogInterface) {
                if (uploadDialog == dialog) {
                    uploadDialog = null;
                    uploadSongTextView = null;
                    uploadCoverTextView = null;
                    uploadLyricsTextView = null;
                }
            }
        });

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                    if (name.length() == 0) {
                        Toast.makeText(MusicPlazaActivity.this, "请填写歌曲名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (pendingSongUri == null) {
                        Toast.makeText(MusicPlazaActivity.this, "请选择歌曲文件", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingName = name;
                    dialog.dismiss();
                    uploadSongPackage();
                }
            });
        }

    }

    private void refreshUploadSelectionLabels() {
        if (uploadSongTextView != null) {
            uploadSongTextView.setText("歌曲文件：" + safeFileName(pendingSongUri, "未选择（≤20MB）"));
        }
        if (uploadCoverTextView != null) {
            uploadCoverTextView.setText("封面文件：" + safeFileName(pendingCoverUri, "未选择（可选，≤1MB）"));
        }
        if (uploadLyricsTextView != null) {
            uploadLyricsTextView.setText("歌词文件：" + safeFileName(pendingLyricsUri, "未选择（可选，.lrc/.ttml/.xml）"));
        }
    }

    /** 显示上传状态；上传过程不可取消，避免服务端已接收部分数据时产生重复上传。 */
    private void showUploadProgress(String fileName, int progress, String status) {
        if (isFinishing()) {
            return;
        }
        if (uploadProgressDialog == null) {
            android.widget.LinearLayout root = new android.widget.LinearLayout(this);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setPadding(dpToPx(22), dpToPx(12), dpToPx(22), dpToPx(4));

            uploadProgressTextView = new TextView(this);
            uploadProgressTextView.setPadding(0, 0, 0, dpToPx(10));
            root.addView(uploadProgressTextView);

            uploadProgressBar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            uploadProgressBar.setMax(100);
            root.addView(uploadProgressBar, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            uploadProgressDialog = new AlertDialog.Builder(this)
                    .setTitle("上传音乐")
                    .setView(root)
                    .setCancelable(false)
                    .create();
            uploadProgressDialog.setCanceledOnTouchOutside(false);
            uploadProgressDialog.show();
        }
        if (uploadProgressBar != null) {
            uploadProgressBar.setProgress(Math.max(0, Math.min(100, progress)));
        }
        if (uploadProgressTextView != null) {
            String safeName = fileName == null ? "音乐文件" : fileName;
            uploadProgressTextView.setText(status + "\n" + safeName + "  " + progress + "%");
        }
    }

    private void dismissUploadProgress() {
        if (uploadProgressDialog != null) {
            try {
                uploadProgressDialog.dismiss();
            } catch (Exception e) {
                // Activity 已销毁时无需额外处理。
            }
        }
        uploadProgressDialog = null;
        uploadProgressBar = null;
        uploadProgressTextView = null;
    }

    private void refreshEditSelectionLabels() {
        if (editCoverTextView == null) {
            return;
        }
        if (pendingEditCoverUri != null) {
            editCoverTextView.setText("新封面: " + safeFileName(pendingEditCoverUri, "cover"));
        } else {
            editCoverTextView.setText("新封面: 不修改");
        }
    }

    private void showEditDialog(final MusicItem item) {
        if (item == null || !item.canDelete || item.id == null || item.id.length() == 0) {
            return;
        }
        pendingEditCoverUri = null;
        final EditText nameInput = new EditText(this);
        nameInput.setHint("请输入歌曲名称");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        if (item.name != null) {
            nameInput.setText(item.name);
            nameInput.setSelection(item.name.length());
        }

        final TextView chooseCover = buildFlatTextButton("选择新封面");
        final TextView coverTip = new TextView(this);
        editCoverTextView = coverTip;
        coverTip.setPadding(0, dpToPx(6), 0, dpToPx(4));
        refreshEditSelectionLabels();

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), 0);
        root.addView(nameInput);
        root.addView(coverTip);
        root.addView(chooseCover);

        chooseCover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryPickEditCoverFile();
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑歌曲信息")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        editDialog = dialog;
        dialog.show();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialogInterface) {
                if (editDialog == dialog) {
                    editDialog = null;
                    editCoverTextView = null;
                    pendingEditCoverUri = null;
                }
            }
        });

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                    if (name.length() == 0) {
                        Toast.makeText(MusicPlazaActivity.this, "请填写歌曲名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    submitMusicItemEdit(item, name, pendingEditCoverUri);
                }
            });
        }
    }

    private void submitMusicItemEdit(final MusicItem item, final String newName, final Uri newCoverUri) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        byte[] coverBytes = null;
        String coverName = "cover.jpg";
        String coverType = "image/jpeg";
        if (newCoverUri != null) {
            if (!hasPickPermission(PICK_KIND_COVER)) {
                Toast.makeText(this, "未授予媒体读取权限，请重新选择", Toast.LENGTH_SHORT).show();
                return;
            }
            long coverSize = resolveContentLength(newCoverUri);
            if (coverSize > MAX_COVER_BYTES) {
                Toast.makeText(this, "封面不能超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
            coverName = queryDisplayName(newCoverUri);
            if (coverName == null || coverName.length() == 0) {
                coverName = "cover.jpg";
            }
            coverType = resolveImageMime(newCoverUri, coverName);
            coverBytes = readUriBytesSafe(newCoverUri, MAX_COVER_BYTES);
            if (coverBytes == null || coverBytes.length == 0) {
                Toast.makeText(this, "封面读取失败或超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        final byte[] finalCoverBytes = coverBytes;
        final String finalCoverName = coverName;
        final String finalCoverType = coverType;
        Toast.makeText(this, "正在保存修改...", Toast.LENGTH_SHORT).show();
        HttpUtil.postMultipartWithThumb(
                "/music/plaza/update?item_id=" + urlEncode(item.id) + "&name=" + urlEncode(newName),
                new byte[0],
                "meta.txt",
                "text/plain",
                finalCoverBytes,
                finalCoverName,
                finalCoverType,
                token,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        String resolvedName = newName;
                        String resolvedCover = item.coverUrl;
                        try {
                            JSONObject obj = new JSONObject(response);
                            String serverName = obj.optString("name", "");
                            if (serverName != null && serverName.trim().length() > 0) {
                                resolvedName = serverName.trim();
                            }
                            String serverCover = obj.optString("cover_url", "");
                            if (serverCover != null && serverCover.trim().length() > 0) {
                                resolvedCover = serverCover.trim();
                            }
                        } catch (Exception e) {
                            // ignore and fallback to local values
                        }
                        applyEditedSongToLists(item.id, resolvedName, resolvedCover);
                        Toast.makeText(MusicPlazaActivity.this, "修改成功", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(int code, String error) {
                        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                            return;
                        }
                        if (code == 400 && error != null && error.contains("invalid_name")) {
                            Toast.makeText(MusicPlazaActivity.this, "歌曲名称不合法", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 400 && error != null && error.contains("invalid_cover")) {
                            Toast.makeText(MusicPlazaActivity.this, "封面格式不支持", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 413 && error != null && error.contains("cover_too_large")) {
                            Toast.makeText(MusicPlazaActivity.this, "封面不能超过1MB", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 404 && error != null && error.contains("not_found")) {
                            Toast.makeText(MusicPlazaActivity.this, "歌曲不存在，列表将刷新", Toast.LENGTH_SHORT).show();
                            loadItems();
                            return;
                        }
                        if (code == 403) {
                            Toast.makeText(MusicPlazaActivity.this, "只能编辑自己上传的歌曲", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(MusicPlazaActivity.this, "修改失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void applyEditedSongToLists(String itemId, String name, String coverUrl) {
        if (itemId == null || itemId.length() == 0) {
            return;
        }
        boolean changed = false;
        changed = applyEditedSongToOneList(items, itemId, name, coverUrl) || changed;
        changed = applyEditedSongToOneList(recommendItems, itemId, name, coverUrl) || changed;
        changed = applyEditedSongToOneList(rankingItems, itemId, name, coverUrl) || changed;
        if (changed) {
            updateFeaturedCard();
            scheduleHomeSectionsRefresh();
        }
    }

    private boolean applyEditedSongToOneList(List<MusicItem> list, String itemId, String name, String coverUrl) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            MusicItem one = list.get(i);
            if (one == null || one.id == null || !one.id.equals(itemId)) {
                continue;
            }
            if (name != null && name.trim().length() > 0 && !name.equals(one.name)) {
                one.name = name;
                changed = true;
            }
            if (coverUrl != null && coverUrl.trim().length() > 0 && !coverUrl.equals(one.coverUrl)) {
                one.coverUrl = coverUrl;
                changed = true;
            }
        }
        return changed;
    }
    private TextView buildFlatTextButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.color_text_primary));
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        tv.setBackgroundResource(R.drawable.flat_button_bg);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dpToPx(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void pickSongFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "选择歌曲文件"), REQ_PICK_SONG);
    }

    private void pickCoverFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "选择封面图片"), REQ_PICK_COVER);
    }

    private void pickLyricsFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 不设置 EXTRA_MIME_TYPES：.lrc 在很多文件管理器中被识别为
        // application/octet-stream 或未知类型，MIME 过滤会把 .lrc 置灰无法选中。
        // 放宽为所有文件可选，提交时按扩展名 (.lrc/.ttml/.xml) 校验。
        startActivityForResult(Intent.createChooser(intent, "选择歌词文件(.lrc/.ttml/.xml)"), REQ_PICK_LYRICS);
    }

    private void pickEditCoverFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "选择新的封面图片"), REQ_PICK_EDIT_COVER);
    }

    private void tryPickSongFile() {
        if (ensurePickPermission(PICK_KIND_SONG)) {
            pickSongFile();
        }
    }

    private void tryPickCoverFile() {
        pendingCoverPickRequestCode = REQ_PICK_COVER;
        if (ensurePickPermission(PICK_KIND_COVER)) {
            pickCoverFile();
        }
    }

    private void tryPickLyricsFile() {
        if (ensurePickPermission(PICK_KIND_SONG)) {
            pickLyricsFile();
        }
    }

    private void tryPickEditCoverFile() {
        pendingCoverPickRequestCode = REQ_PICK_EDIT_COVER;
        if (ensurePickPermission(PICK_KIND_COVER)) {
            pickEditCoverFile();
        }
    }

    private boolean ensurePickPermission(int pickKind) {
        if (hasPickPermission(pickKind)) {
            return true;
        }
        String permission = resolveReadPermissionForPick(pickKind);
        if (permission == null || permission.length() == 0) {
            return true;
        }
        pendingPickKind = pickKind;
        ActivityCompat.requestPermissions(this, new String[]{permission}, REQ_PICK_MEDIA_PERMISSION);
        return false;
    }

    private boolean hasPickPermission(int pickKind) {
        String permission = resolveReadPermissionForPick(pickKind);
        if (permission == null || permission.length() == 0) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String resolveReadPermissionForPick(int pickKind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (pickKind == PICK_KIND_COVER) {
                return Manifest.permission.READ_MEDIA_IMAGES;
            }
            return Manifest.permission.READ_MEDIA_AUDIO;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PICK_MEDIA_PERMISSION) {
            return;
        }
        int kind = pendingPickKind;
        pendingPickKind = PICK_KIND_NONE;
        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, "未授予媒体读取权限，无法选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        if (kind == PICK_KIND_COVER) {
            if (pendingCoverPickRequestCode == REQ_PICK_EDIT_COVER) {
                pickEditCoverFile();
            } else {
                pickCoverFile();
            }
        } else if (kind == PICK_KIND_SONG) {
            pickSongFile();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (requestCode == REQ_PICK_SONG) {
            pendingSongUri = uri;
            refreshUploadSelectionLabels();
            Toast.makeText(this, "已选择歌曲: " + safeFileName(uri, "song"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode == REQ_PICK_COVER) {
            pendingCoverUri = uri;
            refreshUploadSelectionLabels();
            Toast.makeText(this, "已选择封面: " + safeFileName(uri, "cover"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode == REQ_PICK_LYRICS) {
            pendingLyricsUri = uri;
            refreshUploadSelectionLabels();
            Toast.makeText(this, "已选择歌词: " + safeFileName(uri, "lyrics"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode == REQ_PICK_EDIT_COVER) {
            pendingEditCoverUri = uri;
            refreshEditSelectionLabels();
            Toast.makeText(this, "已选择新封面: " + safeFileName(uri, "cover"), Toast.LENGTH_SHORT).show();
            return;
        }
    }

    private void uploadSongPackage() {
        if (uploadInProgress) {
            Toast.makeText(this, "音乐正在上传，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingSongUri == null || pendingName == null || pendingName.length() == 0) {
            return;
        }
        final Uri songUri = pendingSongUri;
        final Uri coverUri = pendingCoverUri;
        final String name = pendingName;

        String songName = queryDisplayName(songUri);
        if (songName == null || songName.length() == 0) {
            songName = "song.mp3";
        }
        final String songFileName = songName;
        final String songMime = resolveSongMime(songUri, songFileName);
        long songSize = resolveContentLength(songUri);
        // 部分 ContentProvider 不返回 SIZE，旧逻辑会把未知大小的文件直接上传，
        // 最终在服务端达到请求体上限后才失败。上传前最多读取 20MB+1 字节确认大小。
        if (songSize < 0) {
            songSize = measureUriLengthAtMost(songUri, MAX_SONG_BYTES + 1L);
        }
        if (songSize > MAX_SONG_BYTES) {
            Toast.makeText(this, "歌曲不能超过20MB", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasPickPermission(PICK_KIND_SONG)) {
            Toast.makeText(this, "未授予媒体读取权限，请先在系统设置中开启后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canReadUri(songUri)) {
            Toast.makeText(this, "歌曲文件不可读取，请重新选择本地文件", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] coverBytes = null;
        String coverName = "cover.jpg";
        String coverType = "image/jpeg";
        if (coverUri != null) {
            long coverSize = resolveContentLength(coverUri);
            if (coverSize > MAX_COVER_BYTES) {
                Toast.makeText(this, "封面不能超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
            coverName = queryDisplayName(coverUri);
            if (coverName == null || coverName.length() == 0) {
                coverName = "cover.jpg";
            }
            coverType = resolveImageMime(coverUri, coverName);
            coverBytes = readUriBytesSafe(coverUri, MAX_COVER_BYTES);
            if (coverBytes == null || coverBytes.length == 0) {
                Toast.makeText(this, "封面读取失败或超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        byte[] lyricsBytes = null;
        String lyricsName = "lyrics.lrc";
        String lyricsType = "text/plain";
        final Uri lyricsUri = pendingLyricsUri;
        if (lyricsUri != null) {
            String rawName = queryDisplayName(lyricsUri);
            String lower = rawName == null ? "" : rawName.toLowerCase(java.util.Locale.US);
            if (!lower.endsWith(".lrc") && !lower.endsWith(".ttml") && !lower.endsWith(".xml")) {
                Toast.makeText(this, "歌词仅支持 .lrc/.ttml/.xml", Toast.LENGTH_SHORT).show();
                return;
            }
            long lyricsSize = resolveContentLength(lyricsUri);
            if (lyricsSize > MAX_LYRICS_BYTES) {
                Toast.makeText(this, "歌词不能超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
            lyricsName = (rawName == null || rawName.length() == 0) ? "lyrics.lrc" : rawName;
            lyricsType = "text/plain";
            lyricsBytes = readUriBytesSafe(lyricsUri, MAX_LYRICS_BYTES);
            if (lyricsBytes == null || lyricsBytes.length == 0) {
                Toast.makeText(this, "歌词读取失败或超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        final byte[] finalCoverBytes = coverBytes;
        final String finalCoverName = coverName;
        final String finalCoverType = coverType;
        final byte[] finalLyricsBytes = lyricsBytes;
        final String finalLyricsName = lyricsName;
        final String finalLyricsType = lyricsType;
        final long uploadSongSize = songSize;
        long localDuration = 0L;
        if (Build.VERSION.SDK_INT >= 10) {
            localDuration = VideoMetadataApi10.readDurationMs(this, songUri);
        }
        final int uploadDurationMs = localDuration > 0L && localDuration <= 24L * 60L * 60L * 1000L
                ? (int) localDuration : 0;
        final int[] lastProgress = new int[]{-1};

        uploadInProgress = true;
        showUploadProgress(songFileName, 0, "正在连接服务器…");
        HttpUtil.StreamProvider provider = new HttpUtil.StreamProvider() {
            @Override
            public InputStream open() throws Exception {
                return openInputStreamCompat(songUri);
            }

            @Override
            public long length() {
                return uploadSongSize;
            }
        };

        HttpUtil.postMultipartStreamWithLyrics(
                "/music/plaza/upload?name=" + urlEncode(name)
                        + "&duration_ms=" + uploadDurationMs,
                provider,
                songFileName,
                songMime,
                finalCoverBytes,
                finalCoverName,
                finalCoverType,
                finalLyricsBytes,
                finalLyricsName,
                finalLyricsType,
                token,
                new HttpUtil.ProgressCallback() {
                    @Override
                    public void onProgress(long written, long total) {
                        if (total <= 0) {
                            return;
                        }
                        // 进度只统计歌曲文件本体：文件写完即 100%，但此时封面/歌词
                        // 尚未发送、服务器也还没返回结果，直接显示 100% 会看起来卡死。
                        // 封顶 95%，剩余 5% 留给收尾与服务器处理，成功/失败回调再关闭对话框。
                        final int raw = (int) Math.min(100L, (written * 100L) / total);
                        final int percent = Math.min(95, raw);
                        synchronized (lastProgress) {
                            if (percent == lastProgress[0]) {
                                return;
                            }
                            lastProgress[0] = percent;
                        }
                        final String status = percent >= 95 ? "正在等待服务器处理…" : "正在上传音乐…";
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                showUploadProgress(songFileName, percent, status);
                            }
                        });
                    }
                },
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        uploadInProgress = false;
                        dismissUploadProgress();
                        String itemId = "";
                        try {
                            JSONObject obj = new JSONObject(response);
                            itemId = obj.optString("id", "");
                        } catch (Exception e) {
                            itemId = "";
                        }
                        Toast.makeText(MusicPlazaActivity.this, "上传成功", Toast.LENGTH_SHORT).show();
                        currentOffset = 0;
                        loadItems();
                        pendingSongUri = null;
                        pendingCoverUri = null;
                        pendingLyricsUri = null;
                        pendingName = null;
                    }

                    @Override
                    public void onError(int code, String error) {
                        uploadInProgress = false;
                        dismissUploadProgress();
                        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                            return;
                        }
                        if (code == 409 || (error != null && error.contains("duplicate_song"))) {
                            Toast.makeText(MusicPlazaActivity.this, "你已上传过同一首歌", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 413 && error != null && error.contains("cover_too_large")) {
                            Toast.makeText(MusicPlazaActivity.this, "封面不能超过1MB", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 413 || (error != null && error.contains("song_too_large"))) {
                            Toast.makeText(MusicPlazaActivity.this, "歌曲不能超过20MB", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 400 && error != null && error.contains("invalid_song")) {
                            Toast.makeText(MusicPlazaActivity.this, "歌曲格式不支持", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 400 && error != null && error.contains("invalid_cover")) {
                            Toast.makeText(MusicPlazaActivity.this, "封面格式不支持", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == -1) {
                            String lower = error == null ? "" : error.toLowerCase(Locale.US);
                            if (lower.contains("cannot_open_stream") || lower.contains("open failed") || lower.contains("enoent")) {
                                Toast.makeText(MusicPlazaActivity.this, "上传失败：文件读取异常，请重新选择歌曲", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (lower.contains("timeout") || lower.contains("timed out")) {
                                Toast.makeText(MusicPlazaActivity.this, "上传超时，请检查网络后重试", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            Toast.makeText(MusicPlazaActivity.this, "上传失败：网络异常，请稍后重试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(MusicPlazaActivity.this, "上传失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void showItemActions(final MusicItem item) {
        if (item == null) {
            return;
        }
        final java.util.ArrayList<String> actions = new java.util.ArrayList<String>();
        actions.add("播放");
        actions.add("下载到本地");
        actions.add("缓存到本地");
        actions.add(item.liked ? "取消点赞" : "点赞");
        actions.add("评论");
        actions.add("分享到聊天");
        actions.add("加入歌单");
        boolean isOwn = (item.canDelete || (myUid != null && myUid.length() > 0 && item.ownerUid != null && myUid.equalsIgnoreCase(item.ownerUid.trim())));
        if (!isOwn) {
            actions.add("举报");
        }
        if (item.canDelete || isOwn) {
            actions.add("编辑信息");
            actions.add("删除歌曲");
        }
        final CharSequence[] array = actions.toArray(new CharSequence[actions.size()]);
        new AlertDialog.Builder(this)
                .setTitle(item.name == null || item.name.length() == 0 ? "歌曲操作" : item.name)
                .setItems(array, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which < 0 || which >= actions.size()) {
                            return;
                        }
                        String action = actions.get(which);
                        if ("播放".equals(action)) {
                            playItem(item);
                            return;
                        }
                        if ("下载到本地".equals(action)) {
                            downloadMusicItem(item);
                            return;
                        }
                        if ("缓存到本地".equals(action)) {
                            cacheMusicItem(item);
                            return;
                        }
                        if ("点赞".equals(action) || "取消点赞".equals(action)) {
                            toggleLike(item);
                            return;
                        }
                        if ("评论".equals(action)) {
                            openComments(item);
                            return;
                        }
                        if ("分享到聊天".equals(action)) {
                            shareMusicItem(item);
                            return;
                        }
                        if ("加入歌单".equals(action)) {
                            new AddToPlaylistDialog(MusicPlazaActivity.this, toPublicItem(item)).show();
                            return;
                        }
                        if ("举报".equals(action)) {
                            showMusicReportDialog(item);
                            return;
                        }
                        if ("编辑信息".equals(action)) {
                            showEditDialog(item);
                            return;
                        }
                        if ("删除歌曲".equals(action)) {
                            deleteMusicItem(item);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private com.im.oldchat.models.MusicItem toPublicItem(MusicItem item) {
        com.im.oldchat.models.MusicItem out = new com.im.oldchat.models.MusicItem();
        out.id = item.id;
        out.name = item.name;
        out.songUrl = item.songUrl;
        out.coverUrl = item.coverUrl;
        out.sizeBytes = item.sizeBytes;
        out.durationMs = item.durationMs;
        out.ownerUid = item.ownerUid;
        out.ownerName = item.ownerName;
        out.ownerTitle = item.ownerTitle;
        out.ownerAvatar = item.ownerAvatar;
        out.likes = item.likes;
        out.comments = item.comments;
        out.playCount = item.playCount;
        out.liked = item.liked;
        out.canDelete = item.canDelete;
        return out;
    }

    private void showMusicReportDialog(final MusicItem item) {
        if (item == null) {
            return;
        }
        final String targetUid = item.ownerUid == null ? "" : item.ownerUid.trim();
        if (targetUid.length() == 0) {
            Toast.makeText(this, "无法举报：缺少发布者信息", Toast.LENGTH_SHORT).show();
            return;
        }
        if (myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(targetUid)) {
            Toast.makeText(this, "不能举报自己发布的歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("如：侵权、违规内容、恶意音频等");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(180)});
        int pad = dpToPx(10);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("举报歌曲")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("提交", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String reason = input.getText() == null ? "" : input.getText().toString().trim();
                        StringBuilder detail = new StringBuilder();
                        detail.append("[音乐举报]");
                        if (item.id != null && item.id.length() > 0) {
                            detail.append(" 歌曲ID: ").append(item.id);
                        }
                        if (item.name != null && item.name.length() > 0) {
                            detail.append(" 歌名: ").append(item.name);
                        }
                        if (item.ownerName != null && item.ownerName.length() > 0) {
                            detail.append(" 上传者: ").append(item.ownerName);
                        }
                        if (item.songUrl != null && item.songUrl.length() > 0) {
                            detail.append("\n媒体: ").append(item.songUrl);
                        }
                        if (item.coverUrl != null && item.coverUrl.length() > 0) {
                            detail.append("\n封面: ").append(item.coverUrl);
                        }
                        String full = reason;
                        if (full.length() > 0) {
                            full += "\n";
                        }
                        full += detail.toString();
                        submitUserReport(targetUid, full);
                    }
                })
                .show();
    }

    private void submitUserReport(String targetUid, String reason) {
        if (targetUid == null || targetUid.length() == 0) {
            Toast.makeText(this, "举报失败：缺少目标用户", Toast.LENGTH_SHORT).show();
            return;
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
                    Toast.makeText(MusicPlazaActivity.this, "举报已提交，已进入公开法庭", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicPlazaActivity.this, "举报失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "举报失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadMusicItem(final MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolvedUrl = MediaUrlResolver.resolve(item.songUrl);
        if (resolvedUrl == null || resolvedUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "开始下载到本地", Toast.LENGTH_SHORT).show();
        DownloadUtil.saveUrlToDownloadsAsync(this,
                resolvedUrl,
                "oldchat_music_",
                ".mp3",
                new DownloadUtil.Callback() {
                    @Override
                    public void onResult(boolean success, String message, java.io.File file) {
                        Toast.makeText(MusicPlazaActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void cacheMusicItem(final MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        String resolvedUrl = MediaUrlResolver.resolve(item.songUrl);
        if (resolvedUrl == null || resolvedUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        synchronized (cacheDownloadingUrls) {
            if (cacheDownloadingUrls.contains(resolvedUrl)) {
                Toast.makeText(this, "该歌曲正在缓存中", Toast.LENGTH_SHORT).show();
                return;
            }
            cacheDownloadingUrls.add(resolvedUrl);
        }
        Toast.makeText(this, "已开始缓存，完成后会提示", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_CACHE_SONG);
        intent.putExtra(MusicPlaybackService.EXTRA_SONG_URL, resolvedUrl);
        startMusicService(intent);
    }

    private void startMusicService(Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            startService(intent);
        } catch (Exception e) {
            synchronized (cacheDownloadingUrls) {
                cacheDownloadingUrls.remove(MediaUrlResolver.resolve(intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_URL)));
            }
            Toast.makeText(this, "启动下载失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleMusicCacheResult(Intent intent) {
        if (intent == null) {
            return;
        }
        if (!MusicPlaybackService.ACTION_CACHE_RESULT.equals(intent.getAction())) {
            return;
        }
        String url = intent.getStringExtra(MusicPlaybackService.EXTRA_CACHE_URL);
        if (url == null) {
            url = "";
        }
        synchronized (cacheDownloadingUrls) {
            cacheDownloadingUrls.remove(url);
        }
        boolean ok = intent.getBooleanExtra(MusicPlaybackService.EXTRA_CACHE_OK, false);
        long size = intent.getLongExtra(MusicPlaybackService.EXTRA_CACHE_SIZE, 0L);
        String error = intent.getStringExtra(MusicPlaybackService.EXTRA_CACHE_ERROR);
        if (ok) {
            Toast.makeText(this, "已缓存到本地（" + formatSize(size) + "）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (error != null && error.length() > 0) {
            Toast.makeText(this, "缓存失败：" + error, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "缓存失败", Toast.LENGTH_SHORT).show();
    }

    private void toggleLike(final MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            String path = item.liked ? "/music/plaza/unlike" : "/music/plaza/like";
            HttpUtil.post(path, json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        item.liked = obj.optBoolean("liked", item.liked);
                        item.likes = obj.optInt("likes", item.likes);
                        if (item.id != null && item.id.equals(currentPlayingItemId)) {
                            currentPlayingLiked = item.liked;
                            currentPlayingLikes = Math.max(0, item.likes);
                        }
                    } catch (Exception e) {
                    }
                    updateFeaturedCard();
                    scheduleHomeSectionsRefresh();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicPlazaActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void openComments(MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        Intent intent = new Intent(this, MusicCommentsActivity.class);
        intent.putExtra("item_id", item.id);
        intent.putExtra("owner_uid", item.ownerUid == null ? "" : item.ownerUid);
        startActivity(intent);
    }

    private void shareMusicItem(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        MusicShareActionHelper.ShareItem share = new MusicShareActionHelper.ShareItem();
        share.id = item.id;
        share.name = item.name;
        share.songUrl = item.songUrl;
        share.coverUrl = item.coverUrl;
        share.lyricsUrl = item.lyricsUrl;
        if ((share.coverUrl == null || share.coverUrl.length() == 0)
                && item.ownerAvatar != null && item.ownerAvatar.length() > 0) {
            share.coverUrl = item.ownerAvatar;
        }
        share.ownerUid = item.ownerUid;
        share.ownerName = item.ownerName;
        share.durationMs = item.durationMs;
        MusicShareActionHelper.showShareDialog(this, token, share);
    }

    private void deleteMusicItem(final MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0 || !item.canDelete) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除歌曲")
                .setMessage("确定删除这首歌曲吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("item_id", item.id);
                            HttpUtil.post("/music/plaza/delete", json, token, new HttpUtil.Callback() {
                                @Override
                                public void onSuccess(String response) {
                                    items.remove(item);
                                    rebuildRecommendItems();
                                    updateFeaturedCard();
                                    updateHomeSections();
                                    Toast.makeText(MusicPlazaActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(int code, String error) {
                                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                                        return;
                                    }
                                    Toast.makeText(MusicPlazaActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            Toast.makeText(MusicPlazaActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestPlaybackState() {
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_REQUEST_STATE);
        try {
            startService(intent);
        } catch (Exception ignore) {
        }
    }

    private void handlePlaybackState(Intent intent) {
        if (intent == null) {
            return;
        }
        if (!MusicPlaybackService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }
        currentPlayingSongName = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_NAME));
        currentPlayingSongUrl = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_URL));
        currentPlayingCoverUrl = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_COVER_URL));
        currentPlayingLyricsUrl = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_LYRICS_URL));
        currentPlayingOwnerUid = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_UID));
        currentPlayingOwnerName = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_NAME));
        currentPlayingOwnerTitle = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_TITLE));
        currentPlayingOwnerAvatar = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_OWNER_AVATAR));
        currentPlayingItemId = safeString(intent.getStringExtra(MusicPlaybackService.EXTRA_ITEM_ID));
        currentPlayingLikes = Math.max(0, intent.getIntExtra(MusicPlaybackService.EXTRA_ITEM_LIKES, currentPlayingLikes));
        currentPlayingComments = Math.max(0, intent.getIntExtra(MusicPlaybackService.EXTRA_ITEM_COMMENTS, currentPlayingComments));
        currentPlayingLiked = intent.getBooleanExtra(MusicPlaybackService.EXTRA_ITEM_LIKED, currentPlayingLiked);
        currentPlayingActive = intent.getBooleanExtra(MusicPlaybackService.EXTRA_IS_PLAYING, false)
                || intent.getBooleanExtra(MusicPlaybackService.EXTRA_IS_PREPARING, false);
        updateNowPlayingButton();
    }

    private void updateNowPlayingButton() {
        if (btnNowPlaying == null) {
            return;
        }
        if (currentPlayingSongUrl.length() == 0) {
            btnNowPlaying.setText("正在播放");
            ViewCompat.setAlpha(btnNowPlaying, 0.85f);
            return;
        }
        ViewCompat.setAlpha(btnNowPlaying, 1.0f);
        btnNowPlaying.setText(currentPlayingActive ? "播放中" : "继续播放");
    }

    private void openNowPlayingPage() {
        if (currentPlayingSongUrl.length() == 0) {
            requestPlaybackState();
            Toast.makeText(this, "当前没有正在播放的歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("song_name", currentPlayingSongName);
        intent.putExtra("song_url", currentPlayingSongUrl);
        intent.putExtra("cover_url", currentPlayingCoverUrl);
        intent.putExtra("lyrics_url", currentPlayingLyricsUrl);
        intent.putExtra("owner_uid", currentPlayingOwnerUid);
        intent.putExtra("owner_name", currentPlayingOwnerName);
        intent.putExtra("owner_title", currentPlayingOwnerTitle);
        intent.putExtra("owner_avatar", currentPlayingOwnerAvatar);
        intent.putExtra("item_id", currentPlayingItemId);
        intent.putExtra("item_likes", currentPlayingLikes);
        intent.putExtra("item_comments", currentPlayingComments);
        intent.putExtra("item_liked", currentPlayingLiked);
        intent.putExtra(MusicPlaybackService.EXTRA_OPEN_NOW_PLAYING, true);
        startActivity(intent);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private void playItem(final MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        saveRecentPlay(item);
        reportPlay(item);
        currentPlayingSongName = item.name == null ? "" : item.name;
        currentPlayingSongUrl = item.songUrl == null ? "" : item.songUrl;
        currentPlayingCoverUrl = item.coverUrl == null ? "" : item.coverUrl;
        currentPlayingLyricsUrl = item.lyricsUrl == null ? "" : item.lyricsUrl;
        currentPlayingOwnerUid = item.ownerUid == null ? "" : item.ownerUid;
        currentPlayingOwnerName = item.ownerName == null ? "" : item.ownerName;
        currentPlayingOwnerTitle = item.ownerTitle == null ? "" : item.ownerTitle;
        currentPlayingOwnerAvatar = item.ownerAvatar == null ? "" : item.ownerAvatar;
        currentPlayingItemId = item.id == null ? "" : item.id;
        currentPlayingLikes = Math.max(0, item.likes);
        currentPlayingComments = Math.max(0, item.comments);
        currentPlayingLiked = item.liked;
        currentPlayingActive = true;
        updateNowPlayingButton();

        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("song_name", currentPlayingSongName);
        intent.putExtra("song_url", currentPlayingSongUrl);
        intent.putExtra("cover_url", currentPlayingCoverUrl);
        intent.putExtra("lyrics_url", currentPlayingLyricsUrl);
        intent.putExtra("owner_uid", currentPlayingOwnerUid);
        intent.putExtra("owner_name", currentPlayingOwnerName);
        intent.putExtra("owner_title", currentPlayingOwnerTitle);
        intent.putExtra("owner_avatar", currentPlayingOwnerAvatar);
        intent.putExtra("item_id", currentPlayingItemId);
        intent.putExtra("item_likes", currentPlayingLikes);
        intent.putExtra("item_comments", currentPlayingComments);
        intent.putExtra("item_liked", currentPlayingLiked);
        startActivity(intent);
    }

    private void reportPlay(final MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            HttpUtil.post("/music/plaza/play", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        int count = obj.optInt("play_count", item.playCount + 1);
                        updatePlayCountById(item.id, count);
                    } catch (Exception e) {
                        updatePlayCountById(item.id, item.playCount + 1);
                    }
                }

                @Override
                public void onError(int code, String error) {
                    // ignore
                }
            });
        } catch (Exception e) {
            // ignore
        }
    }

    private void updatePlayCountById(String itemId, int playCount) {
        if (itemId == null || itemId.length() == 0) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            MusicItem one = items.get(i);
            if (one != null && itemId.equals(one.id)) {
                one.playCount = playCount;
            }
        }
        for (int i = 0; i < rankingItems.size(); i++) {
            MusicItem one = rankingItems.get(i);
            if (one != null && itemId.equals(one.id)) {
                one.playCount = playCount;
            }
        }
        updateHomeSections();
    }

    private void saveRecentPlay(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            return;
        }
        String name = item.name == null ? "" : item.name;
        String owner = item.ownerName;
        if (owner == null || owner.length() == 0) {
            owner = item.ownerUid == null ? "" : item.ownerUid;
        }
        String ownerUid = item.ownerUid == null ? "" : item.ownerUid;
        String raw = encodeRecentPart(item.id) + "|"
                + encodeRecentPart(name) + "|"
                + encodeRecentPart(owner) + "|"
                + encodeRecentPart(item.songUrl) + "|"
                + encodeRecentPart(item.coverUrl) + "|"
                + encodeRecentPart(ownerUid) + "|"
                + encodeRecentPart(item.lyricsUrl) + "|"
                + encodeRecentPart(item.ownerAvatar);

        List<String> all = getRecentPlayEntries();
        List<String> next = new ArrayList<String>();
        next.add(raw);
        for (int i = 0; i < all.size(); i++) {
            String one = all.get(i);
            if (one == null || one.length() == 0 || one.equals(raw)) {
                continue;
            }
            next.add(one);
            if (next.size() >= MAX_RECENT_PLAY) {
                break;
            }
        }
        SharedPreferences prefs = getSharedPreferences(PREF_MUSIC_LOCAL, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_RECENT_PLAY, joinRecentEntries(next)).apply();
    }

    private List<String> getRecentPlayEntries() {
        SharedPreferences prefs = getSharedPreferences(PREF_MUSIC_LOCAL, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_RECENT_PLAY, "");
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        String[] lines = raw.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String one = lines[i] == null ? "" : lines[i].trim();
            if (one.length() == 0) {
                continue;
            }
            out.add(one);
            if (out.size() >= MAX_RECENT_PLAY) {
                break;
            }
        }
        return out;
    }

    private String joinRecentEntries(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            String one = entries.get(i);
            if (one == null || one.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(one);
        }
        return sb.toString();
    }

    private String encodeRecentPart(String value) {
        String safe = value == null ? "" : value;
        safe = safe.replace("\\", "\\\\");
        safe = safe.replace("|", "\\p");
        safe = safe.replace("\n", " ");
        return safe;
    }

    private String decodeRecentPart(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (esc) {
                if (c == 'p') {
                    sb.append('|');
                } else {
                    sb.append(c);
                }
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            sb.append(c);
        }
        if (esc) {
            sb.append('\\');
        }
        return sb.toString();
    }

    private List<String> splitRecentParts(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (esc) {
                sb.append('\\');
                sb.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '|') {
                out.add(decodeRecentPart(sb.toString()));
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        if (esc) {
            sb.append('\\');
        }
        out.add(decodeRecentPart(sb.toString()));
        return out;
    }

    private String decodeRecentLabel(String raw) {
        List<String> parts = splitRecentParts(raw);
        String name = parts.size() > 1 ? parts.get(1) : "";
        String owner = parts.size() > 2 ? parts.get(2) : "";
        if (name == null || name.length() == 0) {
            name = "未命名歌曲";
        }
        if (owner == null || owner.length() == 0) {
            owner = "匿名用户";
        }
        return name + " · " + owner;
    }

    private MusicItem parseRecentEntry(String raw) {
        List<String> parts = splitRecentParts(raw);
        if (parts.size() < 4) {
            return null;
        }
        MusicItem item = new MusicItem();
        item.id = parts.get(0);
        item.name = parts.get(1);
        item.ownerName = parts.get(2);
        item.songUrl = parts.get(3);
        item.coverUrl = parts.size() > 4 ? parts.get(4) : "";
        if (parts.size() > 5) {
            item.ownerUid = parts.get(5);
        }
        if (parts.size() > 6) {
            item.lyricsUrl = parts.get(6);
        }
        if (parts.size() > 7) {
            item.ownerAvatar = parts.get(7);
        }
        if ((item.ownerUid == null || item.ownerUid.length() == 0) && item.ownerName != null && item.ownerName.startsWith("USR-")) {
            item.ownerUid = item.ownerName;
        }
        return item;
    }

    private void stopPlay() {

        if (mediaPlayer != null) {
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
        currentPlayUrl = null;
    }

    private String safeFileName(Uri uri, String fallback) {
        String name = queryDisplayName(uri);
        if (name == null || name.length() == 0) {
            return fallback;
        }
        return name;
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) {
            return "";
        }
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            name = null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (name == null || name.length() == 0) {
            String path = uri.getLastPathSegment();
            if (path != null && path.length() > 0) {
                name = path;
            }
        }
        return name == null ? "" : name;
    }

    private InputStream openInputStreamCompat(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("uri_empty");
        }
        InputStream input = null;
        try {
            input = getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            input = null;
        }
        if (input != null) {
            return input;
        }
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            }
        } catch (Exception e) {
        }
        throw new IOException("cannot_open_stream");
    }

    private boolean canReadUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        InputStream is = null;
        try {
            is = openInputStreamCompat(uri);
            return is != null;
        } catch (Exception e) {
            return false;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
        }
    }

    private String resolveSongMime(Uri uri, String fileName) {
        String type = null;
        try {
            type = getContentResolver().getType(uri);
        } catch (Exception e) {
            type = null;
        }
        if (!TextUtils.isEmpty(type)) {
            return type;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        if (lower.endsWith(".amr")) {
            return "audio/amr";
        }
        if (lower.endsWith(".3gp") || lower.endsWith(".3gpp")) {
            return "audio/3gpp";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".wav") || lower.endsWith(".wave")) {
            return "audio/wav";
        }
        return "audio/mpeg";
    }

    private String resolveImageMime(Uri uri, String fileName) {
        String type = null;
        try {
            type = getContentResolver().getType(uri);
        } catch (Exception e) {
            type = null;
        }
        if (!TextUtils.isEmpty(type)) {
            return type;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return "image/jpeg";
    }

    private byte[] readUriBytesSafe(Uri uri, long maxBytes) {
        if (uri == null) {
            return null;
        }
        InputStream is = null;
        java.io.ByteArrayOutputStream bos = null;
        try {
            is = openInputStreamCompat(uri);
            if (is == null) {
                return null;
            }
            bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buf)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return null;
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (Exception e) {
                }
            }
        }
    }

    private long resolveContentLength(Uri uri) {
        if (uri == null) {
            return -1;
        }

        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    long size = cursor.getLong(idx);
                    if (size > 0) {
                        return size;
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                }
            }
        }

        android.content.res.AssetFileDescriptor afd = null;
        try {
            afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null && afd.getLength() > 0) {
                return afd.getLength();
            }
        } catch (Exception e) {
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception e) {
                }
            }
        }

        ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size > 0) {
                    return size;
                }
            }
        } catch (Exception e) {
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception e) {
                }
            }
        }

        return -1;
    }

    /**
     * 在媒体提供者未暴露文件大小时，读取至指定上限后停止。
     * 返回 -1 表示无法读取，返回上限值表示文件至少达到该大小。
     */
    private long measureUriLengthAtMost(Uri uri, long maxBytes) {
        if (uri == null || maxBytes <= 0) {
            return -1;
        }
        InputStream input = null;
        try {
            input = openInputStreamCompat(uri);
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total >= maxBytes) {
                    return total;
                }
            }
            return total;
        } catch (Exception e) {
            return -1;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception e) {
                }
            }
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

    private String formatSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            return "0B";
        }
        float size = sizeBytes;
        if (size < 1024) {
            return (int) size + "B";
        }
        size = size / 1024f;
        if (size < 1024) {
            return String.format(Locale.getDefault(), "%.1fKB", size);
        }
        size = size / 1024f;
        return String.format(Locale.getDefault(), "%.1fMB", size);
    }

    private String formatDuration(int durationMs) {
        if (durationMs <= 0) {
            return "--:--";
        }
        int totalSeconds = durationMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 99) {
            minutes = 99;
        }
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private int parseDurationMsFromJson(JSONObject obj) {
        if (obj == null) {
            return 0;
        }
        String[] numberKeys = new String[]{
                "duration_ms",
                "durationMs",
                "duration",
                "duration_sec",
                "durationSec",
                "seconds",
                "length"
        };
        for (int i = 0; i < numberKeys.length; i++) {
            String key = numberKeys[i];
            long raw = parseDurationNumber(obj.opt(key));
            int normalized = normalizeDurationToMs(raw, key);
            if (normalized > 0) {
                return normalized;
            }
        }

        String[] textKeys = new String[]{"duration_text", "duration", "length"};
        for (int i = 0; i < textKeys.length; i++) {
            int parsed = parseDurationTextToMs(obj.optString(textKeys[i], ""));
            if (parsed > 0) {
                return parsed;
            }
        }

        return findDurationRecursively(obj, 0);
    }

    private long parseDurationNumber(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return -1;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (!(value instanceof String)) {
            return -1;
        }
        String text = ((String) value).trim();
        if (text.length() == 0 || text.contains(":")) {
            return -1;
        }
        try {
            if (text.contains(".")) {
                return Math.round(Double.parseDouble(text));
            }
            return Long.parseLong(text);
        } catch (Exception e) {
            return -1;
        }
    }

    private int normalizeDurationToMs(long raw, String keyHint) {
        if (raw <= 0) {
            return 0;
        }
        String key = keyHint == null ? "" : keyHint.toLowerCase(Locale.US);
        if (key.contains("ms") || key.contains("milli")) {
            if (raw > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) raw;
        }
        if (key.contains("sec") || key.contains("second")) {
            long msSec = raw * 1000L;
            if (msSec > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) msSec;
        }
        long ms = raw;
        if (raw <= 10000L) {
            ms = raw * 1000L;
        }
        if (ms > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) ms;
    }

    private int findDurationRecursively(Object value, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 4) {
            return 0;
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = obj.opt(key);
                String lowerKey = key == null ? "" : key.toLowerCase(Locale.US);
                if (isDurationLikeKey(lowerKey)) {
                    long number = parseDurationNumber(child);
                    int normalized = normalizeDurationToMs(number, lowerKey);
                    if (normalized > 0) {
                        return normalized;
                    }
                    if (child instanceof String) {
                        int parsedText = parseDurationTextToMs((String) child);
                        if (parsedText > 0) {
                            return parsedText;
                        }
                    }
                }
                int nested = findDurationRecursively(child, depth + 1);
                if (nested > 0) {
                    return nested;
                }
            }
            return 0;
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                int nested = findDurationRecursively(arr.opt(i), depth + 1);
                if (nested > 0) {
                    return nested;
                }
            }
            return 0;
        }
        if (value instanceof String) {
            return parseDurationTextToMs((String) value);
        }
        return 0;
    }

    private boolean isDurationLikeKey(String lowerKey) {
        if (lowerKey == null || lowerKey.length() == 0) {
            return false;
        }
        return lowerKey.contains("duration")
                || lowerKey.contains("length")
                || lowerKey.contains("seconds")
                || lowerKey.contains("second")
                || lowerKey.contains("time");
    }

    private int probeDurationMs(String url) {
        if (url == null || url.length() == 0) {
            return 0;
        }
        if (Build.VERSION.SDK_INT < 10) {
            return 0;
        }
        String durationText = VideoMetadataApi10.readRemoteDurationText(url);
        long raw = parseDurationNumber(durationText);
        int parsed = normalizeDurationToMs(raw, "duration_ms");
        if (parsed > 0) {
            return parsed;
        }
        return parseDurationTextToMs(durationText);
    }

    private int parseDurationTextToMs(String text) {
        if (text == null) {
            return 0;
        }
        String raw = text.trim();
        if (raw.length() == 0) {
            return 0;
        }
        String[] parts = raw.split(":");
        if (parts.length < 2 || parts.length > 3) {
            return 0;
        }
        try {
            int seconds = 0;
            for (int i = 0; i < parts.length; i++) {
                String one = parts[i] == null ? "" : parts[i].trim();
                if (one.length() == 0) {
                    return 0;
                }
                int value;
                if (i == parts.length - 1 && one.contains(".")) {
                    value = (int) Double.parseDouble(one);
                } else {
                    value = Integer.parseInt(one);
                }
                if (value < 0) {
                    return 0;
                }
                seconds = seconds * 60 + value;
            }
            if (seconds <= 0) {
                return 0;
            }
            long ms = seconds * 1000L;
            if (ms > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) ms;
        } catch (Exception e) {
            return 0;
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private static class MusicItem {
        String id;
        String name;
        String songUrl;
        String coverUrl;
        String lyricsUrl;
        long sizeBytes;
        int durationMs;
        String ownerUid;
        String ownerName;
        String ownerTitle;
        String ownerAvatar;
        int likes;
        int comments;
        int playCount;
        boolean liked;
        boolean canDelete;
    }

}
