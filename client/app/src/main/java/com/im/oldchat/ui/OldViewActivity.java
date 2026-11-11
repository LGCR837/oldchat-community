package com.im.oldchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;

public class OldViewActivity extends OldViewActivitySupport1 {
    private android.support.v4.widget.DrawerLayout drawerLayout;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_old_view);
        bindViews();
        initList();
        initActions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        logStep("onResume: ensureLoginState");
        ensureLoginState();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
    }

    protected void bindViews() {
        ivQr = findViewByIdCompat(R.id.ivOldViewQr);
        tvStatus = findViewByIdCompat(R.id.tvOldViewStatus);
        tvLoginTitle = findViewByIdCompat(R.id.tvOldViewLoginTitle);
        panelLogin = findViewByIdCompat(R.id.panelOldViewLogin);
        btnMenu = findViewByIdCompat(R.id.btnOldViewMenu);
        btnSearch = findViewByIdCompat(R.id.btnOldViewSearch);
        btnRefresh = findViewByIdCompat(R.id.btnOldViewRefresh);
        pbLoading = findViewByIdCompat(R.id.pbOldViewLoading);
        lvVideos = findViewByIdCompat(R.id.lvOldViewVideos);
        tvEmpty = findViewByIdCompat(R.id.tvOldViewEmpty);
        tvOldViewSideStatus = findViewByIdCompat(R.id.tvOldViewSideStatus);
        if (lvVideos != null && tvEmpty != null) {
            lvVideos.setEmptyView(tvEmpty);
        }
    }

    protected void initList() {
        adapter = new OldViewVideoAdapter(this);
        adapter.setOnVideoActionListener(new OldViewVideoAdapter.OnVideoActionListener() {
            @Override
            public void onOpenUpProfile(BiliModels.RecommendItem item) {
                openUpProfile(item);
            }
        });
        if (lvVideos == null) {
            return;
        }
        lvVideos.setAdapter(adapter);
        lvVideos.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object obj = adapter != null ? adapter.getItem(position) : null;
                if (!(obj instanceof com.im.oldchat.bili.BiliModels.RecommendItem)) {
                    return;
                }
                com.im.oldchat.bili.BiliModels.RecommendItem item = (com.im.oldchat.bili.BiliModels.RecommendItem) obj;
                openDetail(item);
            }
        });
        lvVideos.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (!searchMode || !searchHasMore || searchLoading) {
                    return;
                }
                if (totalItemCount <= 0) {
                    return;
                }
                int last = firstVisibleItem + visibleItemCount;
                if (last >= totalItemCount - 2) {
                    loadSearch(searchPage + 1, false);
                }
            }
        });
    }

    protected void initActions() {
        drawerLayout = (android.support.v4.widget.DrawerLayout) findViewByIdCompat(R.id.drawerOldView);
        btnMenu = findViewByIdCompat(R.id.btnOldViewMenu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (drawerLayout != null) {
                        drawerLayout.openDrawer(android.support.v4.view.GravityCompat.START);
                    }
                }
            });
        }
        if (btnSearch instanceof ImageView) {
            ((ImageView) btnSearch).setColorFilter(getResources().getColor(R.color.color_text_primary));
        }
        if (btnSearch != null) {
            btnSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSearchDialog();
                }
            });
        }
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startLogin(true);
                }
            });
        }

        // 侧栏：返回发现页（关闭旧视）
        View sideBackDiscover = findViewByIdCompat(R.id.btnOldViewSideBackDiscover);
        if (sideBackDiscover != null) {
            sideBackDiscover.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        // 侧栏：推荐（回到首页）
        View sideHome = findViewByIdCompat(R.id.btnOldViewSideHome);
        if (sideHome != null) {
            sideHome.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDrawer();
                    clearSearch();
                }
            });
        }
        // 侧栏：搜索
        View sideSearch = findViewByIdCompat(R.id.btnOldViewSideSearch);
        if (sideSearch != null) {
            sideSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDrawer();
                    showSearchDialog();
                }
            });
        }
        // 侧栏：历史
        View sideHistory = findViewByIdCompat(R.id.btnOldViewSideHistory);
        if (sideHistory != null) {
            sideHistory.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDrawer();
                    startActivity(new Intent(OldViewActivity.this, OldViewHistoryActivity.class));
                }
            });
        }
        // 侧栏：收藏
        View sideFavorites = findViewByIdCompat(R.id.btnOldViewSideFavorites);
        if (sideFavorites != null) {
            sideFavorites.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDrawer();
                    startActivity(new Intent(OldViewActivity.this, OldViewFavoritesActivity.class));
                }
            });
        }
        // 侧栏：登录/退出
        View sideLogin = findViewByIdCompat(R.id.btnOldViewSideLogin);
        if (sideLogin != null) {
            sideLogin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startLogin(false);
                }
            });
        }
        View sideLogout = findViewByIdCompat(R.id.btnOldViewSideLogout);
        if (sideLogout != null) {
            sideLogout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    logoutBili();
                }
            });
        }
    }

    protected void closeDrawer() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(android.support.v4.view.GravityCompat.START)) {
            drawerLayout.closeDrawer(android.support.v4.view.GravityCompat.START);
        }
    }

    protected void logoutBili() {
        BiliAuthStore.clear(this);
        authCode = null;
        loginInProgress = false;
        stopPolling();
        if (adapter != null) {
            adapter.update(null);
        }
        showGuestUi();
    }

    private void openUpProfile(BiliModels.RecommendItem item) {
        long upMid = extractUpMid(item);
        if (upMid <= 0) {
            Toast.makeText(this, getString(R.string.old_view_up_profile_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, OldViewUpProfileActivity.class);
        intent.putExtra(OldViewUpProfileActivity.EXTRA_UP_MID, upMid);
        if (item != null && item.args != null) {
            intent.putExtra(OldViewUpProfileActivity.EXTRA_UP_NAME, item.args.upName);
        }
        startActivity(intent);
    }

    private long extractUpMid(BiliModels.RecommendItem item) {
        if (item == null || item.args == null) {
            return 0L;
        }
        if (item.args.upId > 0) {
            return item.args.upId;
        }
        if (item.args.mid > 0) {
            return item.args.mid;
        }
        return 0L;
    }
}
