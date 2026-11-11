package com.im.oldchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AbsListView;
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
import java.util.Locale;

public class OldViewFavoriteDetailActivity extends BaseActivity {
    public static final String EXTRA_MEDIA_ID = "media_id";
    public static final String EXTRA_TITLE = "title";

    private ProgressBar pbLoading;
    private ListView lvList;
    private TextView tvEmpty;
    private OldViewSimpleAdapter adapter;
    private long mediaId = 0L;
    private int page = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_old_view_list);

        Intent intent = getIntent();
        if (intent != null) {
            mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, 0L);
        }
        String titleText = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;

        TextView title = findViewByIdCompat(R.id.tvOldViewListTitle);
        if (title != null) {
            title.setText(titleText != null && titleText.length() > 0 ? titleText : getString(R.string.old_view_favorite_detail_title));
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
                    openDetail((OldViewSimpleItem) obj);
                }
            });
            lvList.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    if (!hasMore || isLoading) {
                        return;
                    }
                    if (totalItemCount <= 0) {
                        return;
                    }
                    int last = firstVisibleItem + visibleItemCount;
                    if (last >= totalItemCount - 2) {
                        loadMedia(false);
                    }
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null || adapter.getCount() == 0) {
            page = 1;
            hasMore = true;
            loadMedia(true);
        }
    }

    private void loadMedia(final boolean clear) {
        if (mediaId <= 0 || isLoading || !hasMore) {
            return;
        }
        String cookie = BiliAuthStore.getCookies(this);
        if (cookie == null || cookie.length() == 0) {
            if (tvEmpty != null) {
                tvEmpty.setText(R.string.old_view_login_required);
            }
            return;
        }
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.old_view_favorite_empty);
        }
        isLoading = true;
        showLoading(true);
        BiliApi.requestFavResources(mediaId, page, 20, cookie, new BiliApi.ApiCallback<BiliModels.FavResourceResult>() {
            @Override
            public void onSuccess(BiliModels.FavResourceResult response) {
                isLoading = false;
                showLoading(false);
                if (response != null && response.code == 0 && response.data != null) {
                    List<OldViewSimpleItem> items = mapMedias(response.data.medias);
                    if (adapter != null) {
                        if (clear) {
                            adapter.update(items);
                        } else {
                            adapter.append(items);
                        }
                    }
                    hasMore = response.data.hasMore;
                    if (hasMore) {
                        page++;
                    }
                } else if (response != null) {
                    Toast.makeText(OldViewFavoriteDetailActivity.this,
                            response.message != null ? response.message : "加载失败", Toast.LENGTH_SHORT).show();
                    hasMore = false;
                }
            }

            @Override
            public void onError(String error) {
                isLoading = false;
                showLoading(false);
                if (error != null && error.length() > 0) {
                    Toast.makeText(OldViewFavoriteDetailActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private List<OldViewSimpleItem> mapMedias(List<BiliModels.FavMedia> list) {
        List<OldViewSimpleItem> result = new ArrayList<OldViewSimpleItem>();
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.size(); i++) {
            BiliModels.FavMedia media = list.get(i);
            if (media == null) {
                continue;
            }
            OldViewSimpleItem item = new OldViewSimpleItem();
            item.title = media.title;
            item.cover = media.cover;
            String author = media.upper != null ? media.upper.name : "";
            String duration = formatDuration(media.duration);
            StringBuilder meta = new StringBuilder();
            if (author != null && author.length() > 0) {
                meta.append(author);
            }
            if (duration.length() > 0) {
                if (meta.length() > 0) {
                    meta.append(" · ");
                }
                meta.append("时长 ").append(duration);
            }
            item.meta = meta.toString();
            String bvid = media.bvid != null ? media.bvid : media.bvId;
            item.bvid = bvid;
            result.add(item);
        }
        return result;
    }

    private String formatDuration(int duration) {
        if (duration <= 0) {
            return "";
        }
        int minutes = duration / 60;
        int seconds = duration % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void openDetail(OldViewSimpleItem item) {
        if (item == null) {
            return;
        }
        Intent intent = new Intent(this, OldViewVideoDetailActivity.class);
        if (item.bvid != null && item.bvid.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_BVID, item.bvid);
        }
        if (item.cover != null && item.cover.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_COVER, item.cover);
        }
        if (item.title != null) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_TITLE, item.title);
        }
        startActivity(intent);
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) {
            pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}
