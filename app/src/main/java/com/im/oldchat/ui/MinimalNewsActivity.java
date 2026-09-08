package com.im.oldchat.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.ui.news.MinimalNewsAdapter;
import com.im.oldchat.ui.news.MinimalNewsItem;
import com.im.oldchat.ui.news.RssFeedRepository;

import java.util.List;

public class MinimalNewsActivity extends BaseActivity {
    private TextView tvTabHot;
    private TextView tvTabTech;
    private TextView tvTabFinance;
    private TextView tvTabBlock;
    private TextView tvEmpty;
    private TextView tvTitle;
    private ListView listView;
    private MinimalNewsAdapter adapter;
    private String currentFeedId = RssFeedRepository.FEED_SOLIDOT;
    private RssFeedRepository.Feed currentFeed;
    private AsyncTask<Void, Void, List<MinimalNewsItem>> loadTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_minimal_news);
        bindViews();
        bindEvents();
        applyEntranceAnimation();
        switchFeed(RssFeedRepository.FEED_SOLIDOT, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelLoadingTask();
    }

    private void bindViews() {
        tvTabHot = findViewByIdCompat(R.id.tvTabHot);
        tvTabTech = findViewByIdCompat(R.id.tvTabTech);
        tvTabFinance = findViewByIdCompat(R.id.tvTabFinance);
        tvTabBlock = findViewByIdCompat(R.id.tvTabBlock);
        tvEmpty = findViewByIdCompat(R.id.tvMinimalNewsEmpty);
        tvTitle = findViewByIdCompat(R.id.tvMinimalNewsTitleBar);
        listView = findViewByIdCompat(R.id.lvMinimalNews);

        adapter = new MinimalNewsAdapter(this);
        if (listView != null) {
            listView.setAdapter(adapter);
            setupListItemAnimation();
        }
        if (tvEmpty != null) {
            tvEmpty.setTypeface(Typeface.DEFAULT);
        }
    }

    private void bindEvents() {
        View btnBack = findViewByIdCompat(R.id.btnMinimalNewsBack);
        View btnRefresh = findViewByIdCompat(R.id.btnMinimalNewsRefresh);
        View btnSources = findViewByIdCompat(R.id.btnMinimalNewsSources);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearCurrentCacheAndReload();
                }
            });
        }

        if (btnSources != null) {
            btnSources.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showFeedChooser(); }
            });
        }

        bindTab(tvTabHot, RssFeedRepository.FEED_SOLIDOT);
        bindTab(tvTabTech, RssFeedRepository.FEED_SSPAI);
        bindTab(tvTabFinance, RssFeedRepository.FEED_RUANYIFENG);
        bindTab(tvTabBlock, RssFeedRepository.FEED_BBC);

        if (listView != null) {
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    MinimalNewsItem item = adapter.getNewsItem(position);
                    if (item == null) {
                        return;
                    }
                    Intent intent = new Intent(MinimalNewsActivity.this, MinimalNewsDetailActivity.class);
                    intent.putExtra("title", item.title);
                    intent.putExtra("summary", item.summary);
                    intent.putExtra("image", item.imageUrl);
                    intent.putExtra("url", item.articleUrl);
                    intent.putExtra("source", item.source);
                    intent.putExtra("time", item.publishTime);
                    startActivity(intent);
                }
            });
            listView.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    boolean fling = scrollState == SCROLL_STATE_FLING;
                    adapter.setPauseImageLoading(fling);
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                }
            });
        }
    }

    private void bindTab(final TextView tab, final String feedId) {
        if (tab == null) {
            return;
        }
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playTabAnimation(tab);
                switchFeed(feedId, true);
            }
        });
    }

    private void clearCurrentCacheAndReload() {
        RssFeedRepository.invalidate(currentFeedId);
        switchFeed(currentFeedId, true);
    }

    private void switchFeed(String feedId, final boolean toastWhenDone) {
        currentFeedId = feedId;
        currentFeed = RssFeedRepository.findFeed(this, feedId);
        final String requestFeedId = currentFeed.id;
        final RssFeedRepository.Feed requestFeed = currentFeed;
        refreshTabState();
        if (tvTitle != null) tvTitle.setText(currentFeed.name + " RSS");
        setLoadingHint("正在读取 " + currentFeed.name + "...");
        cancelLoadingTask();

        loadTask = new AsyncTask<Void, Void, List<MinimalNewsItem>>() {
            @Override
            protected List<MinimalNewsItem> doInBackground(Void... params) {
                return RssFeedRepository.load(requestFeed);
            }

            @Override
            protected void onPostExecute(List<MinimalNewsItem> data) {
                if (isFinishing() || isCancelled() || !requestFeedId.equals(currentFeedId)) {
                    return;
                }
                adapter.replaceAll(data);
                if (data == null || data.isEmpty()) {
                    setLoadingHint("未读取到文章，请检查 RSS 源或网络");
                } else {
                    if (tvEmpty != null) {
                        tvEmpty.setVisibility(View.GONE);
                    }
                    if (listView != null) {
                        listView.scheduleLayoutAnimation();
                    }
                }
                if (toastWhenDone) {
                    Toast.makeText(MinimalNewsActivity.this, "RSS 已更新", Toast.LENGTH_SHORT).show();
                }
            }
        };
        executeTaskCompat(loadTask);
    }

    private void showFeedChooser() {
        final List<RssFeedRepository.Feed> feeds = RssFeedRepository.getFeeds(this);
        String[] choices = new String[feeds.size() + 2];
        for (int i = 0; i < feeds.size(); i++) {
            choices[i] = feeds.get(i).name + (feeds.get(i).custom ? "（自定义）" : "");
        }
        choices[feeds.size()] = "添加自定义 RSS";
        choices[feeds.size() + 1] = "管理自定义 RSS";
        new AlertDialog.Builder(this)
                .setTitle("订阅源")
                .setItems(choices, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which < feeds.size()) switchFeed(feeds.get(which).id, false);
                        else if (which == feeds.size()) showAddFeedDialog();
                        else showManageFeedsDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddFeedDialog() {
        final LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        final EditText name = new EditText(this);
        name.setHint("名称，例如：我的博客");
        name.setSingleLine(true);
        final EditText url = new EditText(this);
        url.setHint("https://example.com/feed.xml");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name);
        form.addView(url);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加 RSS 源")
                .setView(form)
                .setPositiveButton("添加", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            RssFeedRepository.Feed feed = RssFeedRepository.addCustomFeed(MinimalNewsActivity.this,
                                    name.getText().toString(), url.getText().toString());
                            dialog.dismiss();
                            switchFeed(feed.id, true);
                        } catch (Exception e) {
                            Toast.makeText(MinimalNewsActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private void showManageFeedsDialog() {
        final List<RssFeedRepository.Feed> custom = new java.util.ArrayList<RssFeedRepository.Feed>();
        List<RssFeedRepository.Feed> all = RssFeedRepository.getFeeds(this);
        for (int i = 0; i < all.size(); i++) if (all.get(i).custom) custom.add(all.get(i));
        if (custom.isEmpty()) {
            Toast.makeText(this, "暂无自定义 RSS 源", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[custom.size()];
        for (int i = 0; i < custom.size(); i++) names[i] = custom.get(i).name + "\n" + custom.get(i).url;
        new AlertDialog.Builder(this).setTitle("点击要删除的订阅源").setItems(names,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, final int which) {
                        new AlertDialog.Builder(MinimalNewsActivity.this)
                                .setTitle("删除 " + custom.get(which).name + "？")
                                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        boolean removingCurrent = custom.get(which).id.equals(currentFeedId);
                                        RssFeedRepository.removeCustomFeed(MinimalNewsActivity.this, custom.get(which).id);
                                        if (removingCurrent) switchFeed(RssFeedRepository.FEED_SOLIDOT, false);
                                    }
                                }).setNegativeButton("取消", null).show();
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private void setLoadingHint(String text) {
        if (tvEmpty == null) {
            return;
        }
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(text);
    }

    private void refreshTabState() {
        setTabStyle(tvTabHot, RssFeedRepository.FEED_SOLIDOT.equals(currentFeedId));
        setTabStyle(tvTabTech, RssFeedRepository.FEED_SSPAI.equals(currentFeedId));
        setTabStyle(tvTabFinance, RssFeedRepository.FEED_RUANYIFENG.equals(currentFeedId));
        setTabStyle(tvTabBlock, RssFeedRepository.FEED_BBC.equals(currentFeedId));
    }

    private void setTabStyle(TextView tab, boolean selected) {
        if (tab == null) {
            return;
        }
        tab.setBackgroundResource(selected
                ? R.drawable.bg_minimal_news_tab_active
                : R.drawable.bg_minimal_news_tab_inactive);
        tab.setTextColor(getResources().getColor(selected
                ? R.color.minimal_news_tab_active_text
                : R.color.minimal_news_tab_inactive_text));
        tab.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void applyEntranceAnimation() {
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.minimal_news_header_enter);
        if (tvTabHot != null) {
            tvTabHot.startAnimation(anim);
        }
    }

    private void playTabAnimation(View tab) {
        if (tab == null) {
            return;
        }
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.minimal_news_tab_press);
        tab.startAnimation(anim);
    }

    private void setupListItemAnimation() {
        if (listView == null) {
            return;
        }
        LayoutAnimationController controller = AnimationUtils.loadLayoutAnimation(this,
                R.anim.minimal_news_list_layout);
        listView.setLayoutAnimation(controller);
    }

    private void cancelLoadingTask() {
        if (loadTask != null) {
            loadTask.cancel(true);
            loadTask = null;
        }
    }

    private void executeTaskCompat(AsyncTask<Void, Void, List<MinimalNewsItem>> task) {
        com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task, (Void[]) null);
    }
}
