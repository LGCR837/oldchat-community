package com.im.oldchat.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.ui.news.MinimalNewsContentExtractor;
import com.im.oldchat.ui.news.MinimalNewsImageLoader;

public class MinimalNewsDetailActivity extends BaseActivity {
    private String articleUrl;
    private TextView tvOriginStatus;
    private TextView tvOriginContent;
    private AsyncTask<Void, Void, String> extractTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_minimal_news_detail);
        bindContent();
        bindActions();
        startExtractOrigin();
        playEntrance();
    }

    @Override
    protected void onDestroy() {
        if (extractTask != null) {
            extractTask.cancel(true);
            extractTask = null;
        }
        super.onDestroy();
    }

    private void bindContent() {
        TextView tvTitle = findViewByIdCompat(R.id.tvMinimalNewsDetailTitle);
        TextView tvSummary = findViewByIdCompat(R.id.tvMinimalNewsDetailSummary);
        TextView tvMeta = findViewByIdCompat(R.id.tvMinimalNewsDetailMeta);
        ImageView ivCover = findViewByIdCompat(R.id.ivMinimalNewsDetailCover);
        tvOriginStatus = findViewByIdCompat(R.id.tvMinimalNewsOriginStatus);
        tvOriginContent = findViewByIdCompat(R.id.tvMinimalNewsOriginContent);

        String title = getIntent().getStringExtra("title");
        String summary = getIntent().getStringExtra("summary");
        String image = getIntent().getStringExtra("image");
        String source = getIntent().getStringExtra("source");
        String time = getIntent().getStringExtra("time");
        articleUrl = getIntent().getStringExtra("url");

        if (tvTitle != null) {
            tvTitle.setText(TextUtils.isEmpty(title) ? "极简简讯" : title);
            tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        }
        if (tvSummary != null) {
            tvSummary.setText(TextUtils.isEmpty(summary) ? "暂无摘要" : summary);
        }
        if (tvMeta != null) {
            String meta = (TextUtils.isEmpty(source) ? "未知来源" : source)
                    + "  |  "
                    + (TextUtils.isEmpty(time) ? "刚刚" : time);
            tvMeta.setText(meta);
        }
        if (ivCover != null) {
            MinimalNewsImageLoader.load(ivCover, image);
        }
        if (tvOriginStatus != null) {
            tvOriginStatus.setText("正在提取原文...");
        }
        if (tvOriginContent != null) {
            tvOriginContent.setText("");
        }
    }

    private void bindActions() {
        View btnBack = findViewByIdCompat(R.id.btnMinimalNewsDetailBack);
        View btnRead = findViewByIdCompat(R.id.btnMinimalNewsRead);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (btnRead != null) {
            btnRead.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openInExternalBrowser();
                }
            });
        }
    }

    private void startExtractOrigin() {
        if (TextUtils.isEmpty(articleUrl)) {
            setOriginResult("原文链接不可用", "");
            return;
        }
        if (extractTask != null) {
            extractTask.cancel(true);
            extractTask = null;
        }

        extractTask = new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                return MinimalNewsContentExtractor.extractFromUrl(articleUrl);
            }

            @Override
            protected void onPostExecute(String content) {
                if (isFinishing() || isCancelled()) {
                    return;
                }
                if (content == null || content.trim().length() == 0) {
                    setOriginResult("原文提取失败", "");
                } else {
                    setOriginResult("原文提取完成", content);
                }
            }
        };
        executeTaskCompat(extractTask);
    }

    private void setOriginResult(String status, String content) {
        if (tvOriginStatus != null) {
            tvOriginStatus.setText(status);
        }
        if (tvOriginContent != null) {
            tvOriginContent.setText(content == null ? "" : content);
        }
    }

    private void executeTaskCompat(AsyncTask<Void, Void, String> task) {
        com.im.oldchat.util.AsyncTaskExecutionCompat.execute(task, (Void[]) null);
    }

    private void openInExternalBrowser() {
        if (TextUtils.isEmpty(articleUrl)) {
            Toast.makeText(this, "原文链接暂不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(articleUrl)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "未找到可用浏览器", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "打开原文失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void playEntrance() {
        View card = findViewByIdCompat(R.id.layoutMinimalNewsDetailCard);
        View origin = findViewByIdCompat(R.id.layoutMinimalNewsOriginCard);
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.minimal_news_detail_enter);
        if (card != null) {
            card.startAnimation(anim);
        }
        if (origin != null) {
            origin.startAnimation(anim);
        }
    }
}
