package com.im.oldchat.ui;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Moment;
import com.im.oldchat.ui.fragments.MomentParser;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
abstract class UserSpaceActivitySupport0 extends BaseActivity implements MomentAdapter.OnMomentActionListener {
    protected static final String AUTH_PREFS = "auth";
    protected static final String PROFILE_CACHE_PREFS = "user_space_profile_cache";
    protected static final String PROFILE_CACHE_KEY_PREFIX = "profile_";

    protected ListView lvMoments;
    protected ImageView ivCover;
    protected ImageView ivAvatar;
    protected TextView tvName;
    protected TextView tvTitleBadge;
    protected TextView tvUid;
    protected TextView tvSignature;
    protected TextView tvMomentCount;
    protected TextView tvBanCount;
    protected View buttonRow;
    protected View btnEdit;
    protected View btnPost;
    protected View actionRow;
    protected View btnPrimary;
    protected View btnReport;
    protected MomentAdapter adapter;
    protected final List<Moment> moments = new ArrayList<Moment>();
    protected String token;
    protected String profileUid;
    protected String profileNcuid;
    protected String myUid;
    protected String myNcuid;
    protected boolean isSelf;
    protected boolean isFriend;
    protected String profileName;
    protected String profileAvatar;
    protected boolean hasLoadedOnCreate;
    protected boolean hasCachedProfile;
    protected boolean profileNotFoundRetried;
    // 个人主页动态分页状态
    protected long momentsBeforeCursor;
    protected boolean momentsHasMore = true;
    protected boolean momentsLoading;
    protected View momentsFooter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_space);

        lvMoments = findViewByIdCompat(R.id.lvMoments);
        View header = LayoutInflater.from(this).inflate(R.layout.header_user_space, lvMoments, false);
        ivCover = header.findViewById(R.id.ivSpaceCover);
        ivAvatar = header.findViewById(R.id.ivSpaceAvatar);
        tvName = header.findViewById(R.id.tvSpaceName);
        tvTitleBadge = header.findViewById(R.id.tvSpaceTitleBadge);
        tvUid = header.findViewById(R.id.tvSpaceUid);
        tvSignature = header.findViewById(R.id.tvSpaceSignature);
        tvMomentCount = header.findViewById(R.id.tvSpaceMomentCount);
        tvBanCount = header.findViewById(R.id.tvSpaceBanCount);
        buttonRow = header.findViewById(R.id.space_button_row);
        btnEdit = header.findViewById(R.id.btnSpaceEdit);
        btnPost = header.findViewById(R.id.btnSpacePost);
        actionRow = header.findViewById(R.id.space_action_row);
        btnPrimary = header.findViewById(R.id.btnSpacePrimary);
        btnReport = header.findViewById(R.id.btnSpaceReport);
        View btnBack = header.findViewById(R.id.btnSpaceBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
        }
        lvMoments.addHeaderView(header);
        String initialAvatar = getIntent().getStringExtra("friend_avatar");
        if (initialAvatar != null && !initialAvatar.isEmpty()) {
            com.im.oldchat.util.ImageLoader.loadAvatarCacheFirstTryOnce(ivAvatar, initialAvatar);
        }

        com.im.oldchat.util.ChatHeaderEnterAnimHelper.runEnterAnimation(this, ivAvatar, tvName, btnBack, lvMoments);

        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        myUid = prefs.getString("my_uid", "");
        myNcuid = normalizeUid(prefs.getString("my_ncuid", ""));

        profileNcuid = normalizeUid(getIntent().getStringExtra("ncuid"));
        profileUid = normalizeUid(getIntent().getStringExtra("uid"));
        if ((profileUid == null || profileUid.isEmpty())
                && (profileNcuid == null || profileNcuid.isEmpty())) {
            profileUid = myUid;
            profileNcuid = myNcuid;
        }
        if (profileUid == null) {
            profileUid = "";
        }
        isSelf = profileNcuid.length() > 0 && myNcuid.length() > 0
                ? profileNcuid.equalsIgnoreCase(myNcuid)
                : profileUid.equalsIgnoreCase(myUid);
        isFriend = UserSpaceActionHelper.isFriend(this, profileUid, profileNcuid);

        if (!isSelf) {
            if (buttonRow != null) {
                buttonRow.setVisibility(View.GONE);
            }
            if (btnEdit != null) {
                btnEdit.setVisibility(View.GONE);
            }
            if (btnPost != null) {
                btnPost.setVisibility(View.GONE);
            }
            if (actionRow != null) {
                actionRow.setVisibility(View.VISIBLE);
            }
        }

        if (btnEdit != null) {
            btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(UserSpaceActivitySupport0.this, ProfileSpaceEditActivity.class));
                }
            });
        }
        if (btnPost != null) {
            btnPost.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(UserSpaceActivitySupport0.this, MomentComposeActivity.class));
                }
            });
        }
        if (!isSelf) {
            UserSpaceActionHelper.bindActions((UserSpaceActivity) this, btnPrimary, btnReport, isFriend,
                    profileUid, profileNcuid, profileName, profileAvatar, token);
        }

        adapter = new MomentAdapter(this, moments, this);
        lvMoments.setAdapter(adapter);
        setupMomentPagination();
    }

    protected void initListViewLongClick() {
        lvMoments.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View v, int position, long id) {
                int index = position - lvMoments.getHeaderViewsCount();
                if (index < 0 || index >= moments.size()) {
                    return false;
                }
                Moment moment = moments.get(index);
                MomentsDeleteHelper.showMomentOptions(UserSpaceActivitySupport0.this, token, myUid, myNcuid, moment, index, moments, adapter);
                return true;
            }
        });

        loadProfile();
        loadMoments();
        hasLoadedOnCreate = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isSelf) {
            isFriend = UserSpaceActionHelper.isFriend(this, profileUid, profileNcuid);
            UserSpaceActionHelper.bindActions((UserSpaceActivity) this, btnPrimary, btnReport, isFriend,
                    profileUid, profileNcuid, profileName, profileAvatar, token);
        }
        if (hasLoadedOnCreate) {
            hasLoadedOnCreate = false;
            return;
        }
        loadProfile();
        loadMoments();
    }

    void markFriendConfirmed() {
        isFriend = true;
        UserSpaceActionHelper.bindActions((UserSpaceActivity) this, btnPrimary, btnReport, true,
                profileUid, profileNcuid, profileName, profileAvatar, token);
    }

    protected void loadProfile() {
        profileNotFoundRetried = false;
        loadCachedProfile();
        requestProfile(false);
    }

    protected void requestProfile(final boolean retried) {
        final long started = com.im.oldchat.util.ScreenPerfTracer.now();
        String path;
        if (isSelf) {
            path = "/me";
        } else if (profileNcuid != null && profileNcuid.length() > 0) {
            path = "/users/profile?ncuid=" + Uri.encode(profileNcuid);
        } else {
            // 仅兼容尚未迁移的旧页面入口；群聊入口只传 NCUID。
            path = "/users/profile?uid=" + Uri.encode(profileUid == null ? "" : profileUid);
        }
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                com.im.oldchat.util.ScreenPerfTracer.trace(UserSpaceActivitySupport0.this, "UserSpaceActivity", "profile_http", started,
                        "uid=" + profileUid + " retried=" + retried + " result=ok");
                persistProfileCache(response);
                if (!applyProfilePayload(response)) {
                    Toast.makeText(UserSpaceActivitySupport0.this, "加载资料失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                com.im.oldchat.util.ScreenPerfTracer.trace(UserSpaceActivitySupport0.this, "UserSpaceActivity", "profile_http", started,
                        "uid=" + profileUid + " retried=" + retried + " result=error code=" + code);
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                if (shouldRetryNotFoundProfile(code, error, retried)) {
                    profileNotFoundRetried = true;
                    retryProfileRequest();
                    return;
                }
                if (shouldRetryProfileRequest(code, error, retried)) {
                    retryProfileRequest();
                    return;
                }
                if (isUserProfileNotFound(code, error)) {
                    if (hasCachedProfile) {
                        Toast.makeText(UserSpaceActivitySupport0.this, "网络波动，已显示缓存资料", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(UserSpaceActivitySupport0.this, "用户不存在或已注销", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (hasCachedProfile) {
                    Toast.makeText(UserSpaceActivitySupport0.this, "网络波动，已显示缓存资料", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(UserSpaceActivitySupport0.this, "加载资料失败，请检查网络", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    protected void loadMoments() {
        momentsBeforeCursor = 0L;
        momentsHasMore = true;
        momentsLoading = false;
        requestMoments(false);
    }

    /** 初始化分页：滚动到底部自动加载更多。 */
    protected void setupMomentPagination() {
        if (lvMoments == null) {
            return;
        }
        lvMoments.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (totalItemCount == 0 || firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                    loadMoreMomentsIfNeeded();
                }
            }
        });
    }

    protected void loadMoreMomentsIfNeeded() {
        if (momentsLoading || !momentsHasMore || moments.size() == 0) {
            return;
        }
        if (momentsFooter == null && lvMoments != null) {
            momentsFooter = new android.widget.ProgressBar(this);
            momentsFooter.setPadding(0, 16, 0, 16);
            lvMoments.addFooterView(momentsFooter, null, false);
        }
        requestMoments(false);
    }

    protected void updateMomentsFooter() {
        if (momentsFooter == null && lvMoments != null) {
            momentsFooter = new android.widget.ProgressBar(this);
            momentsFooter.setPadding(0, 16, 0, 16);
            lvMoments.addFooterView(momentsFooter, null, false);
        }
        if (momentsFooter == null) {
            return;
        }
        if (momentsHasMore) {
            momentsFooter.setVisibility(android.view.View.VISIBLE);
        } else {
            momentsFooter.setVisibility(android.view.View.GONE);
        }
    }

    protected void requestMoments(final boolean retried) {
        if (momentsLoading) {
            return;
        }
        momentsLoading = true;
        final long started = com.im.oldchat.util.ScreenPerfTracer.now();
        final boolean isFirstPage = momentsBeforeCursor == 0L;
        String path;
        String identity = "";
        if (profileNcuid != null && profileNcuid.length() > 0) {
            identity = "ncuid=" + Uri.encode(profileNcuid);
        } else {
            identity = "uid=" + Uri.encode(profileUid == null ? "" : profileUid);
        }
        StringBuilder sb = new StringBuilder("/moments/user?").append(identity).append("&limit=20");
        if (momentsBeforeCursor > 0L) {
            sb.append("&before=").append(momentsBeforeCursor);
        }
        path = sb.toString();
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                com.im.oldchat.util.ScreenPerfTracer.trace(UserSpaceActivitySupport0.this, "UserSpaceActivity", "moments_http", started,
                        "uid=" + profileUid + " retried=" + retried + " result=ok");
                try {
                    JSONObject obj = new JSONObject(response);
                    List<Moment> incoming = MomentParser.parse(obj.getJSONArray("moments"));
                    if (isFirstPage) {
                        moments.clear();
                    }
                    int appendedBase = moments.size();
                    moments.addAll(incoming);
                    // 满页 = 可能还有更多；不足一页 = 到底了
                    momentsHasMore = incoming.size() >= 20;
                    if (incoming.size() > 0) {
                        momentsBeforeCursor = moments.get(moments.size() - 1).created_at;
                    }
                    if (tvMomentCount != null) {
                        tvMomentCount.setText(moments.size() + " 条动态");
                    }
                    adapter.notifyDataSetChanged();
                    updateMomentsFooter();
                } catch (Exception e) {
                    if (isFirstPage) {
                        Toast.makeText(UserSpaceActivitySupport0.this, "加载动态失败", Toast.LENGTH_SHORT).show();
                    }
                } finally {
                    momentsLoading = false;
                }
            }

            @Override
            public void onError(int code, String error) {
                com.im.oldchat.util.ScreenPerfTracer.trace(UserSpaceActivitySupport0.this, "UserSpaceActivity", "moments_http", started,
                        "uid=" + profileUid + " retried=" + retried + " result=error code=" + code);
                momentsLoading = false;
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                if (shouldRetryProfileRequest(code, error, retried)) {
                    retryMomentsRequest();
                    return;
                }
                if (isUserProfileNotFound(code, error)) {
                    if (moments.isEmpty()) {
                        Toast.makeText(UserSpaceActivitySupport0.this, "该用户暂无动态", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (!moments.isEmpty()) {
                    Toast.makeText(UserSpaceActivitySupport0.this, "网络波动，已保留已加载动态", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(UserSpaceActivitySupport0.this, "加载动态失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    protected String normalizeUid(String rawUid) {
        if (rawUid == null) {
            return "";
        }
        String uid = rawUid.trim();
        while (uid.startsWith("@")) {
            uid = uid.substring(1).trim();
        }
        return uid;
    }
    protected abstract boolean canDeleteMoment(Moment moment);
    protected abstract void confirmDeleteMoment(Moment moment, int index);
    protected abstract void retryProfileRequest();
    protected abstract void retryMomentsRequest();
    protected abstract boolean shouldRetryProfileRequest(int code, String error, boolean retried);
    protected abstract boolean shouldRetryNotFoundProfile(int code, String error, boolean retried);
    protected abstract boolean isUserProfileNotFound(int code, String error);
    protected abstract void loadCachedProfile();
    protected abstract void persistProfileCache(String payload);
    protected abstract boolean applyProfilePayload(String payload);
}
