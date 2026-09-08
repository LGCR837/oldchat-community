package com.im.oldchat.ui;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.bili.BiliModels;
import com.im.oldchat.bili.BiliShareUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
abstract class OldViewVideoDetailSupport3 extends BaseActivity {
    protected static final String LOG_PREFIX = "【bilibili 测试报错！！！！！】";
    public static final String EXTRA_BVID = "bvid";
    public static final String EXTRA_AID = "aid";
    public static final String EXTRA_CID = "cid";
    public static final String EXTRA_PRELOAD_URL = "preload_url";
    public static final String EXTRA_COVER = "cover_url";
    public static final String EXTRA_TITLE = "title";
    protected static final int REQ_FULLSCREEN = 9101;
    protected ProgressBar pbLoading;
    protected ListView lvComments;
    protected TextView tvEmpty;
    protected View btnShare;
    protected EditText etComment;
    protected View btnSendComment;
    protected View headerView;
    protected View playerSpacerView;
    protected com.im.oldchat.ui.widget.OldViewPlayerView playerView;
    protected View layoutPlayer;
    protected ImageView ivCover;
    protected android.widget.VideoView vvPlayer;
    protected View btnPlay;
    protected View btnFullscreen;
    protected View btnRetry;
    protected View layoutPlayerLoading;
    protected ProgressBar pbPlayerLoading;
    protected TextView tvPlayerHint;
    protected View layoutOwner;
    protected ImageView ivOwnerAvatar;
    protected TextView tvOwnerName;
    protected TextView tvOwnerTag;
    protected TextView tvVideoTitle;
    protected TextView tvMeta;
    protected View layoutStats;
    protected TextView tvStatPlay;
    protected TextView tvStatDanmaku;
    protected TextView tvStatReply;
    protected TextView tvStatLike;
    protected TextView tvDesc;
    protected OldViewCommentAdapter commentAdapter;
    protected OldViewVideoAdapter relatedAdapter;
    protected View layoutCommentInput;
    protected View layoutPlayerControls;
    protected ImageView btnPlayPause;
    protected android.widget.SeekBar sbSeek;
    protected TextView tvTime;
    protected long currentAid = 0L;
    protected String currentBvid;
    protected long currentCid = 0L;
    protected int commentPage = 0;
    protected boolean isLoadingComments = false;
    protected boolean hasMoreComments = true;
    protected boolean isPlaying = false;
    protected boolean hasStarted = false;
    protected boolean pausedByScroll = false;
    protected int lastPlaybackPos = 0;
    protected boolean enteringFullscreen = false;
    protected String preloadUrl;
    protected String coverUrl;
    protected String currentTitle;
    protected String currentPlayUrl;
    protected long currentDuration = 0L;
    protected String currentCover;
    protected String token;
    protected int lastVideoWidth = 0;
    protected int lastVideoHeight = 0;
    protected boolean commentSending = false;
    protected void showPlayerLoading(boolean show, String hint) {
        if (layoutPlayerLoading != null) {
            layoutPlayerLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (pbPlayerLoading != null) {
            pbPlayerLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (tvPlayerHint != null && hint != null && hint.length() > 0) {
            tvPlayerHint.setText(hint);
        }
        if (show) {
            showRetryButton(false);
        }
    }
    protected boolean showRetryButton(boolean show) {
        if (btnRetry == null) {
            return false;
        }
        btnRetry.setVisibility(show ? View.VISIBLE : View.GONE);
        return true;
    }
    protected void setPlayerHint(String hint) {
        if (tvPlayerHint == null || hint == null || hint.length() == 0) {
            return;
        }
        tvPlayerHint.setText(hint);
    }
    protected void showLoading(boolean show) {
        if (pbLoading != null) {
            pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (show) {
            updateEmpty(false, null);
        }
    }
    protected void updateEmpty(boolean show, String text) {
        if (tvEmpty == null) {
            return;
        }
        if (text != null) {
            tvEmpty.setText(text);
        }
        tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }
    protected void hideKeyboard(View view) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && view != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Exception e) {
        }
    }
    protected void logError(String detail) {
        Log.e("OldViewDetail", LOG_PREFIX + " " + (detail != null ? detail : ""));
    }
    protected void logStep(String detail) {
        Log.d("OldViewDetail", LOG_PREFIX + " " + (detail != null ? detail : ""));
    }
    protected boolean isValidBvid(String bvid) {
        if (bvid == null) {
            return false;
        }
        String t = bvid.trim();
        if (t.length() < 3) {
            return false;
        }
        return t.startsWith("BV") || t.startsWith("bv");
    }
    protected long extractCid(BiliModels.VideoDetailData data) {
        if (data == null || data.pages == null || data.pages.isEmpty()) {
            return data != null ? data.cid : 0L;
        }
        BiliModels.VideoPage page = data.pages.get(0);
        if (page != null && page.cid > 0) {
            return page.cid;
        }
        return data != null ? data.cid : 0L;
    }
    protected boolean hasMore(BiliModels.CommentPage page, List<BiliModels.CommentReply> replies) {
        if (page == null) {
            return replies != null && replies.size() >= 20;
        }
        int total = page.count;
        int size = page.size > 0 ? page.size : 20;
        int current = page.num;
        int totalPages = (int) Math.ceil(total / (double) size);
        return current < totalPages;
    }
    protected String formatDuration(long duration) {
        if (duration <= 0) {
            return "";
        }
        long minutes = duration / 60;
        long seconds = duration % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
    protected String formatCount(int count) {
        if (count < 10000) {
            return String.valueOf(count);
        }
        return String.format(Locale.US, "%.1f万", count / 10000.0);
    }
    protected String buildMeta(BiliModels.VideoDetailData data) {
        StringBuilder sb = new StringBuilder();
        if (data.stat != null) {
            appendMeta(sb, formatCount(data.stat.view) + "次观看");
            if (data.stat.reply > 0) {
                appendMeta(sb, formatCount(data.stat.reply) + "条评论");
            }
        }
        String duration = formatDuration(data.duration);
        if (duration.length() > 0) {
            appendMeta(sb, duration);
        }
        return sb.toString();
    }
    protected void appendMeta(StringBuilder sb, String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(text);
    }
    protected String buildShareText() {
        String title = buildShareTitle();
        StringBuilder sb = new StringBuilder();
        sb.append("视频: ").append(title);
        String duration = formatDuration(currentDuration);
        if (duration.length() > 0) {
            sb.append("\n时长: ").append(duration);
        }
        sb.append("\n点击气泡观看");
        return sb.toString();
    }
    protected String buildShareTitle() {
        if (currentTitle != null && currentTitle.length() > 0) {
            return currentTitle;
        }
        if (tvVideoTitle != null && tvVideoTitle.getText() != null && tvVideoTitle.getText().length() > 0) {
            return tvVideoTitle.getText().toString();
        }
        return "B站视频";
    }
    protected String buildShareUrl() {
        String cover = currentCover;
        if (cover == null || cover.length() == 0) {
            cover = coverUrl;
        }
        return BiliShareUtil.buildShareUrl(currentBvid, currentAid, currentCid, buildShareTitle(), cover, currentDuration);
    }
    protected List<BiliModels.CommentReply> buildCommentList(BiliModels.CommentData data, int page) {
        List<BiliModels.CommentReply> result = new ArrayList<BiliModels.CommentReply>();
        if (data == null) {
            return result;
        }
        List<BiliModels.CommentReply> replies = data.replies != null ? data.replies : new ArrayList<BiliModels.CommentReply>();
        if (page == 1) {
            BiliModels.CommentReply top = null;
            if (data.top != null) {
                if (data.top.reply != null) {
                    top = data.top.reply;
                } else if (data.top.upper != null) {
                    top = data.top.upper;
                }
            }
            if (top == null && data.upper != null) {
                top = data.upper.top;
            }
            Set<Long> added = new HashSet<Long>();
            if (top != null) {
                top.topComment = true;
                appendUniqueComment(result, added, top);
            }
            List<BiliModels.CommentReply> hots = data.hots != null ? data.hots : new ArrayList<BiliModels.CommentReply>();
            for (int i = 0; i < hots.size(); i++) {
                BiliModels.CommentReply item = hots.get(i);
                if (item != null) {
                    item.hotComment = true;
                    appendUniqueComment(result, added, item);
                }
            }
            if (replies.isEmpty() && !hots.isEmpty()) {
                logStep("loadComments: use hot replies size=" + hots.size());
            }
            for (int i = 0; i < replies.size(); i++) {
                BiliModels.CommentReply item = replies.get(i);
                if (item != null) {
                    appendUniqueComment(result, added, item);
                }
            }
        } else {
            for (int i = 0; i < replies.size(); i++) {
                BiliModels.CommentReply item = replies.get(i);
                if (item != null) {
                    result.add(item);
                }
            }
        }
        return result;
    }
    private void appendUniqueComment(List<BiliModels.CommentReply> target, Set<Long> added, BiliModels.CommentReply item) {
        if (target == null || added == null || item == null) {
            return;
        }
        if (item.rpid > 0 && added.contains(item.rpid)) {
            return;
        }
        target.add(item);
        if (item.rpid > 0) {
            added.add(item.rpid);
        }
    }
    protected long bvidToAid(String bvid) {
        if (bvid == null || bvid.length() < 10) {
            return 0L;
        }
        final String table = "fZodR9XQDSUm21yCkr6zBqiveYah8bt4xsWpHnJE7jL5VG3guMTKNPAwcF";
        final int[] s = new int[]{11, 10, 3, 8, 4, 6};
        long r = 0L;
        try {
            for (int i = 0; i < 6; i++) {
                char c = bvid.charAt(s[i]);
                int idx = table.indexOf(c);
                if (idx < 0) {
                    return 0L;
                }
                r += idx * pow58(i);
            }
            long aid = (r - 8728348608L) ^ 177451812L;
            return aid > 0 ? aid : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
    protected long pow58(int p) {
        long result = 1L;
        for (int i = 0; i < p; i++) {
            result *= 58L;
        }
        return result;
    }
}
