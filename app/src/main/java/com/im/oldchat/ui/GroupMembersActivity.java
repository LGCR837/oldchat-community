package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.TextUtils;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupMember;

import java.util.ArrayList;
import java.util.List;

public class GroupMembersActivity extends BaseActivity {
    private SwipeRefreshLayout swipeRefresh;
    private ListView lvMembers;
    private TextView tvGroupMeta;
    private TextView tvMemberCount;
    private TextView tvEmpty;

    private String token;
    private String groupId;
    private String groupName;
    private int myRole;

    private final GroupManageApi manageApi = new GroupManageApi();
    private final List<GroupMember> members = new ArrayList<GroupMember>();
    private final GroupMembersLoader membersLoader = new GroupMembersLoader();
    private GroupMemberAdapter adapter;
    private int totalMemberCount;
    private boolean hasMoreMembers;
    private boolean membersLoading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_members);

        View btnBack = findViewByIdCompat(R.id.btnBack);
        swipeRefresh = findViewByIdCompat(R.id.swipeRefresh);
        lvMembers = findViewByIdCompat(R.id.lvMembers);
        tvGroupMeta = findViewByIdCompat(R.id.tvGroupMeta);
        tvMemberCount = findViewByIdCompat(R.id.tvMemberCount);
        tvEmpty = findViewByIdCompat(R.id.tvEmpty);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        groupId = getIntent().getStringExtra("group_id");
        groupName = getIntent().getStringExtra("group_name");
        myRole = getIntent().getIntExtra("group_role", 0);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        adapter = new GroupMemberAdapter(this, members, myRole, new GroupMemberAdapter.ActionListener() {
            @Override
            public void onKick(GroupMember member) {
                kickMember(member);
            }

            @Override
            public void onToggleAdmin(GroupMember member, boolean makeAdmin) {
                setAdmin(member, makeAdmin);
            }
        });
        if (lvMembers != null) {
            lvMembers.setAdapter(adapter);
            lvMembers.setFastScrollEnabled(true);
            lvMembers.setScrollingCacheEnabled(false);
            lvMembers.setAnimationCacheEnabled(false);
            lvMembers.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    updateSwipeRefreshEnabled();
                    if (!membersLoading && hasMoreMembers && visibleItemCount > 0
                            && firstVisibleItem + visibleItemCount >= totalItemCount - 8) {
                        loadMoreMembers();
                    }
                }
            });
        }

        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
                @Override
                public void onRefresh() {
                    loadMembers();
                }
            });
        }

        updateHeader();
        updateSwipeRefreshEnabled();
        loadGroupMeta();
        loadMembers();
    }

    private void updateHeader() {
        if (tvGroupMeta != null) {
            String meta;
            if (!TextUtils.isEmpty(groupName)) {
                meta = TextUtils.isEmpty(groupId) ? groupName : (groupName + "（" + groupId + "）");
            } else {
                meta = TextUtils.isEmpty(groupId) ? "群信息" : ("群号：" + groupId);
            }
            tvGroupMeta.setText(meta);
        }
        if (tvMemberCount != null) {
            int count = totalMemberCount > 0 ? totalMemberCount : members.size();
            tvMemberCount.setText("成员 " + count + " 人");
        }
    }

    private void loadGroupMeta() {
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(groupId)) {
            return;
        }
        manageApi.loadGroupInfo(this, token, groupId, new GroupManageApi.GroupInfoCallback() {
            @Override
            public void onLoaded(final Group g) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (g == null) {
                            return;
                        }
                        if (!TextUtils.isEmpty(g.name)) {
                            groupName = g.name;
                        }
                        myRole = g.role;
                        if (adapter != null) {
                            adapter.setMyRole(myRole);
                        }
                        updateHeader();
                    }
                });
            }
        });
    }

    private void loadMembers() {
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(groupId)) {
            Toast.makeText(this, "群ID无效", Toast.LENGTH_SHORT).show();
            return;
        }
        if (swipeRefresh != null) {
            swipeRefresh.setRefreshing(true);
            swipeRefresh.setEnabled(true);
        }
        membersLoader.cancel();
        members.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        totalMemberCount = 0;
        hasMoreMembers = false;
        loadMembersPage(0, true);
    }

    private void loadMoreMembers() {
        if (membersLoading || !hasMoreMembers) {
            return;
        }
        loadMembersPage(members.size(), false);
    }

    private void loadMembersPage(final int offset, final boolean refreshing) {
        membersLoading = true;
        membersLoader.loadPage(token, groupId, offset, GroupMembersLoader.DEFAULT_PAGE_SIZE, new GroupMembersLoader.Callback() {
            @Override
            public void onLoaded(List<GroupMember> list, int total, boolean hasMore) {
                membersLoading = false;
                if (swipeRefresh != null) {
                    swipeRefresh.setRefreshing(false);
                }
                if (offset == 0 || refreshing) {
                    members.clear();
                }
                if (list != null && !list.isEmpty()) {
                    members.addAll(list);
                }
                totalMemberCount = total;
                hasMoreMembers = hasMore;
                if (adapter != null) {
                    adapter.setMembers(members);
                }
                updateHeader();
                updateEmptyState();
                updateSwipeRefreshEnabled();
            }

            @Override
            public void onParseFailed() {
                membersLoading = false;
                if (swipeRefresh != null) {
                    swipeRefresh.setRefreshing(false);
                }
                Toast.makeText(GroupMembersActivity.this, "加载成员失败", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onHttpError(int code, String error) {
                membersLoading = false;
                if (swipeRefresh != null) {
                    swipeRefresh.setRefreshing(false);
                }
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(GroupMembersActivity.this, "加载成员失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        membersLoader.cancel();
        super.onDestroy();
    }

    private void updateEmptyState() {
        boolean empty = members.isEmpty();
        if (tvEmpty != null) {
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (lvMembers != null) {
            lvMembers.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isMemberListAtTop() {
        if (lvMembers == null) {
            return true;
        }
        if (lvMembers.getChildCount() == 0) {
            return true;
        }
        if (lvMembers.getFirstVisiblePosition() > 0) {
            return false;
        }
        View first = lvMembers.getChildAt(0);
        if (first == null) {
            return true;
        }
        return first.getTop() >= lvMembers.getListPaddingTop();
    }

    private void updateSwipeRefreshEnabled() {
        if (swipeRefresh == null) {
            return;
        }
        boolean keepEnabled = swipeRefresh.isRefreshing();
        swipeRefresh.setEnabled(keepEnabled || isMemberListAtTop());
    }

    private void kickMember(GroupMember member) {
        if (member == null || TextUtils.isEmpty(groupId) || TextUtils.isEmpty(token)) {
            return;
        }
        manageApi.kickMember(this, token, groupId, member, new Runnable() {
            @Override
            public void run() {
                loadMembers();
            }
        });
    }

    private void setAdmin(GroupMember member, boolean makeAdmin) {
        if (member == null || TextUtils.isEmpty(groupId) || TextUtils.isEmpty(token)) {
            return;
        }
        manageApi.setAdmin(this, token, groupId, member, makeAdmin, new Runnable() {
            @Override
            public void run() {
                loadMembers();
            }
        });
    }
}
