package com.im.oldchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;
import java.util.ArrayList;
import java.util.List;

public class OldViewFavoritesActivity extends BaseActivity {
    private ProgressBar pbLoading;
    private ListView lvList;
    private TextView tvEmpty;
    private OldViewSimpleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_old_view_list);

        TextView title = findViewByIdCompat(R.id.tvOldViewListTitle);
        if (title != null) {
            title.setText(R.string.old_view_favorites_title);
        }
        View btnBack = (View) findViewByIdCompat(R.id.btnOldViewListBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        pbLoading = findViewByIdCompat(R.id.pbOldViewListLoading);
        lvList = findViewByIdCompat(R.id.lvOldViewList);
        tvEmpty = findViewByIdCompat(R.id.tvOldViewListEmpty);
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.old_view_login_required);
        }
        if (lvList != null) {
            lvList.setEmptyView(tvEmpty);
        }

        adapter = new OldViewSimpleAdapter(this);
        if (lvList != null) {
            lvList.setAdapter(adapter);
            lvList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    Object obj = adapter != null ? adapter.getItem(position) : null;
                    if (!(obj instanceof OldViewSimpleItem)) {
                        return;
                    }
                    openFolder((OldViewSimpleItem) obj);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null || adapter.getCount() == 0) {
            loadFavorites();
        }
    }

    private void loadFavorites() {
        String cookie = BiliAuthStore.getCookies(this);
        if (cookie == null || cookie.length() == 0) {
            if (tvEmpty != null) {
                tvEmpty.setText(R.string.old_view_login_required);
            }
            return;
        }
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.old_view_favorites_empty);
        }
        long mid = BiliAuthStore.getMid(this);
        if (mid <= 0) {
            fetchMidAndLoad(cookie);
        } else {
            requestFolders(mid, cookie);
        }
    }

    private void fetchMidAndLoad(final String cookie) {
        showLoading(true);
        BiliApi.requestNav(cookie, new BiliApi.ApiCallback<BiliModels.NavResult>() {
            @Override
            public void onSuccess(BiliModels.NavResult response) {
                if (response != null && response.code == 0 && response.data != null) {
                    long mid = response.data.mid;
                    BiliAuthStore.saveMid(OldViewFavoritesActivity.this, mid);
                    requestFolders(mid, cookie);
                } else {
                    showLoading(false);
                    Toast.makeText(OldViewFavoritesActivity.this,
                            response != null && response.message != null ? response.message : "获取用户信息失败",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                if (error != null && error.length() > 0) {
                    Toast.makeText(OldViewFavoritesActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void requestFolders(long mid, String cookie) {
        showLoading(true);
        BiliApi.requestFavFolders(mid, cookie, new BiliApi.ApiCallback<BiliModels.FavFolderResult>() {
            @Override
            public void onSuccess(BiliModels.FavFolderResult response) {
                showLoading(false);
                if (response != null && response.code == 0 && response.data != null) {
                    List<OldViewSimpleItem> items = mapFolders(response.data.list);
                    if (adapter != null) {
                        adapter.update(items);
                    }
                } else if (response != null) {
                    Toast.makeText(OldViewFavoritesActivity.this,
                            response.message != null ? response.message : "获取收藏夹失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                if (error != null && error.length() > 0) {
                    Toast.makeText(OldViewFavoritesActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private List<OldViewSimpleItem> mapFolders(List<BiliModels.FavFolder> list) {
        List<OldViewSimpleItem> result = new ArrayList<OldViewSimpleItem>();
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.size(); i++) {
            BiliModels.FavFolder folder = list.get(i);
            if (folder == null) {
                continue;
            }
            OldViewSimpleItem item = new OldViewSimpleItem();
            item.title = folder.title;
            item.meta = "共 " + folder.mediaCount + " 个视频";
            item.aid = folder.id;
            result.add(item);
        }
        return result;
    }

    private void openFolder(OldViewSimpleItem item) {
        if (item == null || item.aid <= 0) {
            return;
        }
        Intent intent = new Intent(this, OldViewFavoriteDetailActivity.class);
        intent.putExtra(OldViewFavoriteDetailActivity.EXTRA_MEDIA_ID, item.aid);
        intent.putExtra(OldViewFavoriteDetailActivity.EXTRA_TITLE, item.title != null ? item.title : "");
        startActivity(intent);
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) {
            pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}
