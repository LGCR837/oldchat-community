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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OldViewHistoryActivity extends BaseActivity {
    private ProgressBar pbLoading;
    private ListView lvList;
    private TextView tvEmpty;
    private OldViewSimpleAdapter adapter;
    private boolean isLoading = false;
    private boolean hasMore = true;
    private long cursorMax = 0L;
    private long cursorViewAt = 0L;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_old_view_list);

        TextView title = findViewByIdCompat(R.id.tvOldViewListTitle);
        if (title != null) {
            title.setText(R.string.old_view_history_title);
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
                        loadHistory(false);
                    }
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null || adapter.getCount() == 0) {
            resetAndLoad();
        }
    }

    private void resetAndLoad() {
        cursorMax = 0L;
        cursorViewAt = 0L;
        hasMore = true;
        if (adapter != null) {
            adapter.update(null);
        }
        loadHistory(true);
    }

    private void loadHistory(final boolean clear) {
        if (isLoading || !hasMore) {
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
            tvEmpty.setText(R.string.old_view_history_empty);
        }
        isLoading = true;
        showLoading(true);
        BiliApi.requestHistory(cursorMax, cursorViewAt, 20, cookie, new BiliApi.ApiCallback<BiliModels.HistoryResult>() {
            @Override
            public void onSuccess(BiliModels.HistoryResult response) {
                isLoading = false;
                showLoading(false);
                if (response != null && response.code == 0 && response.data != null) {
                    List<OldViewSimpleItem> items = mapHistory(response.data.list);
                    if (adapter != null) {
                        if (clear) {
                            adapter.update(items);
                        } else {
                            adapter.append(items);
                        }
                    }
                    if (response.data.cursor != null) {
                        cursorMax = response.data.cursor.max;
                        cursorViewAt = response.data.cursor.viewAt;
                    }
                    hasMore = items != null && !items.isEmpty();
                } else if (response != null) {
                    Toast.makeText(OldViewHistoryActivity.this,
                            response.message != null ? response.message : "获取历史失败", Toast.LENGTH_SHORT).show();
                    hasMore = false;
                }
            }

            @Override
            public void onError(String error) {
                isLoading = false;
                showLoading(false);
                if (error != null && error.length() > 0) {
                    Toast.makeText(OldViewHistoryActivity.this, error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private List<OldViewSimpleItem> mapHistory(List<BiliModels.HistoryItem> list) {
        List<OldViewSimpleItem> result = new ArrayList<OldViewSimpleItem>();
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.size(); i++) {
            BiliModels.HistoryItem item = list.get(i);
            if (item == null) {
                continue;
            }
            OldViewSimpleItem simple = new OldViewSimpleItem();
            simple.title = item.title;
            simple.cover = item.cover;
            simple.meta = buildMeta(item);
            if (item.history != null) {
                simple.aid = item.history.oid;
                simple.bvid = item.history.bvid;
            }
            result.add(simple);
        }
        return result;
    }

    private String buildMeta(BiliModels.HistoryItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.authorName != null && item.authorName.length() > 0) {
            sb.append(item.authorName);
        }
        String viewTime = formatTime(item.viewAt);
        if (viewTime.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(viewTime);
        }
        if (item.progress > 0 && item.duration > 0) {
            int percent = (int) Math.min(100, Math.round(item.progress * 100f / item.duration));
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("进度 ").append(percent).append("%");
        }
        return sb.toString();
    }

    private String formatTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        try {
            return dateFormat.format(new Date(seconds * 1000L));
        } catch (Exception e) {
            return "";
        }
    }

    private void openDetail(OldViewSimpleItem item) {
        if (item == null) {
            return;
        }
        Intent intent = new Intent(this, OldViewVideoDetailActivity.class);
        if (item.bvid != null && item.bvid.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_BVID, item.bvid);
        }
        if (item.aid > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_AID, item.aid);
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
