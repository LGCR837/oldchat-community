package com.im.oldchat.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.models.RecentChatCache;
import com.im.oldchat.util.AvatarSyncManager;
import com.im.oldchat.util.CacheSizeUtil;
import com.im.oldchat.util.ImageLoader;

import java.io.File;

public class CacheSettingsActivity extends BaseActivity {
    private static final String VOICE_CACHE_DIR = "voice_cache";

    private TextView tvTotalSize;
    private TextView tvImageCacheSize;
    private TextView tvVoiceCacheSize;
    private TextView tvChatCacheSize;
    private TextView tvFriendCacheSize;
    private TextView tvGroupCacheSize;
    private TextView tvMomentCacheSize;
    private TextView tvRecentCacheSize;
    private TextView tvUserCacheSize;
    private TextView tvAvatarMetaCacheSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache_settings);

        tvTotalSize = findViewByIdCompat(R.id.tvCacheTotalSize);
        tvImageCacheSize = findViewByIdCompat(R.id.tvImageCacheSize);
        tvVoiceCacheSize = findViewByIdCompat(R.id.tvVoiceCacheSize);
        tvChatCacheSize = findViewByIdCompat(R.id.tvChatCacheSize);
        tvFriendCacheSize = findViewByIdCompat(R.id.tvFriendCacheSize);
        tvGroupCacheSize = findViewByIdCompat(R.id.tvGroupCacheSize);
        tvMomentCacheSize = findViewByIdCompat(R.id.tvMomentCacheSize);
        tvRecentCacheSize = findViewByIdCompat(R.id.tvRecentCacheSize);
        tvUserCacheSize = findViewByIdCompat(R.id.tvUserCacheSize);
        tvAvatarMetaCacheSize = findViewByIdCompat(R.id.tvAvatarMetaCacheSize);

        View btnBack = findViewByIdCompat(R.id.btnCacheBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        bindClearAction(R.id.btnClearImageCache, new Runnable() {
            @Override
            public void run() {
                ImageLoader.clearDiskCache(CacheSettingsActivity.this);
                toast("\u5DF2\u6E05\u7406\u56FE\u7247\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearVoiceCache, new Runnable() {
            @Override
            public void run() {
                clearVoiceCache();
                toast("\u5DF2\u6E05\u7406\u8BED\u97F3\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearChatCache, new Runnable() {
            @Override
            public void run() {
                CacheSizeUtil.clearSharedPrefs(CacheSettingsActivity.this, "message_history_cache");
                toast("\u5DF2\u6E05\u7406\u804A\u5929\u8BB0\u5F55\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearFriendCache, new Runnable() {
            @Override
            public void run() {
                CacheSizeUtil.clearSharedPrefs(CacheSettingsActivity.this, "friend_cache");
                toast("\u5DF2\u6E05\u7406\u597D\u53CB\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearGroupCache, new Runnable() {
            @Override
            public void run() {
                CacheSizeUtil.clearSharedPrefs(CacheSettingsActivity.this, "group_cache");
                toast("\u5DF2\u6E05\u7406\u7FA4\u5217\u8868\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearMomentCache, new Runnable() {
            @Override
            public void run() {
                CacheSizeUtil.clearSharedPrefs(CacheSettingsActivity.this, "moment_cache");
                toast("\u5DF2\u6E05\u7406\u52A8\u6001\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearRecentCache, new Runnable() {
            @Override
            public void run() {
                RecentChatCache.clearAll(CacheSettingsActivity.this);
                GroupRecentChatCache.clearAll(CacheSettingsActivity.this);
                toast("\u5DF2\u6E05\u7406\u6700\u8FD1\u4F1A\u8BDD\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearUserCache, new Runnable() {
            @Override
            public void run() {
                CacheSizeUtil.clearSharedPrefs(CacheSettingsActivity.this, "user_name_cache");
                toast("\u5DF2\u6E05\u7406\u7528\u6237\u540D\u7F13\u5B58");
            }
        });

        bindClearAction(R.id.btnClearAvatarMetaCache, new Runnable() {
            @Override
            public void run() {
                AvatarSyncManager.clearAll(CacheSettingsActivity.this);
                toast("\u5DF2\u6E05\u7406\u5934\u50CF\u6620\u5C04\u7F13\u5B58");
            }
        });

        updateSizes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSizes();
    }

    private void bindClearAction(int viewId, final Runnable action) {
        View target = findViewByIdCompat(viewId);
        if (target == null || action == null) {
            return;
        }
        target.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
                updateSizes();
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void updateSizes() {
        long imageSize = CacheSizeUtil.getDirSize(new File(getCacheDir(), "img_cache"));
        long voiceSize = getVoiceCacheSize();
        long chatSize = CacheSizeUtil.getSharedPrefsSize(this, "message_history_cache");
        long friendSize = CacheSizeUtil.getSharedPrefsSize(this, "friend_cache");
        long groupSize = CacheSizeUtil.getSharedPrefsSize(this, "group_cache");
        long momentSize = CacheSizeUtil.getSharedPrefsSize(this, "moment_cache");
        long recentSize = CacheSizeUtil.getSharedPrefsSize(this, "recent_chats")
                + CacheSizeUtil.getSharedPrefsSize(this, "recent_groups");
        long userSize = CacheSizeUtil.getSharedPrefsSize(this, "user_name_cache");
        long avatarMetaSize = CacheSizeUtil.getSharedPrefsSize(this, "avatar_cache");

        long total = imageSize + voiceSize + chatSize + friendSize + groupSize
                + momentSize + recentSize + userSize + avatarMetaSize;

        setSizeText(tvTotalSize, total);
        setSizeText(tvImageCacheSize, imageSize);
        setSizeText(tvVoiceCacheSize, voiceSize);
        setSizeText(tvChatCacheSize, chatSize);
        setSizeText(tvFriendCacheSize, friendSize);
        setSizeText(tvGroupCacheSize, groupSize);
        setSizeText(tvMomentCacheSize, momentSize);
        setSizeText(tvRecentCacheSize, recentSize);
        setSizeText(tvUserCacheSize, userSize);
        setSizeText(tvAvatarMetaCacheSize, avatarMetaSize);
    }

    private long getVoiceCacheSize() {
        File filesDirCache = getVoiceCacheDirInFiles();
        File cacheDirCache = getVoiceCacheDirInCache();
        long total = CacheSizeUtil.getDirSize(filesDirCache);
        if (cacheDirCache != null && (filesDirCache == null
                || !cacheDirCache.getAbsolutePath().equals(filesDirCache.getAbsolutePath()))) {
            total += CacheSizeUtil.getDirSize(cacheDirCache);
        }
        return total;
    }

    private void clearVoiceCache() {
        File filesDirCache = getVoiceCacheDirInFiles();
        File cacheDirCache = getVoiceCacheDirInCache();
        deleteRecursive(filesDirCache);
        if (cacheDirCache != null && (filesDirCache == null
                || !cacheDirCache.getAbsolutePath().equals(filesDirCache.getAbsolutePath()))) {
            deleteRecursive(cacheDirCache);
        }
    }

    private File getVoiceCacheDirInFiles() {
        File root = getFilesDir();
        if (root == null) {
            return null;
        }
        return new File(root, VOICE_CACHE_DIR);
    }

    private File getVoiceCacheDirInCache() {
        File root = getCacheDir();
        if (root == null) {
            return null;
        }
        return new File(root, VOICE_CACHE_DIR);
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursive(children[i]);
                }
            }
        }
        try {
            file.delete();
        } catch (Exception e) {
            // Ignore delete failures for robustness.
        }
    }

    private void setSizeText(TextView view, long bytes) {
        if (view != null) {
            view.setText(CacheSizeUtil.formatSize(bytes));
        }
    }
}
