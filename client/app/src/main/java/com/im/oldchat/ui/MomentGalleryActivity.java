package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.ImageLoader;
import java.util.ArrayList;
import java.util.List;

public class MomentGalleryActivity extends BaseActivity {
    private static final String EXTRA_URLS = "urls";
    private static final String EXTRA_INDEX = "index";

    public static void start(Context context, ArrayList<String> urls, int index) {
        if (context == null || urls == null || urls.isEmpty()) {
            return;
        }
        Intent intent = new Intent(context, MomentGalleryActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, urls);
        intent.putExtra(EXTRA_INDEX, index);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_gallery);

        final ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        if (urls == null || urls.isEmpty()) {
            finish();
            return;
        }
        int startIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (startIndex < 0 || startIndex >= urls.size()) {
            startIndex = 0;
        }

        final TextView tvIndex = (TextView) findViewByIdCompat(R.id.tvGalleryIndex);
        ImageView btnBack = (ImageView) findViewByIdCompat(R.id.btnGalleryBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        ViewPager pager = (ViewPager) findViewByIdCompat(R.id.vpMomentGallery);
        final GalleryAdapter adapter = new GalleryAdapter(urls);
        pager.setAdapter(adapter);
        pager.setCurrentItem(startIndex, false);
        updateIndex(tvIndex, startIndex, urls.size());
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateIndex(tvIndex, position, urls.size());
            }
        });
    }

    private void updateIndex(TextView view, int position, int total) {
        if (view == null) {
            return;
        }
        view.setText((position + 1) + "/" + total);
    }

    private static class GalleryAdapter extends PagerAdapter {
        private final List<String> urls;

        GalleryAdapter(List<String> urls) {
            this.urls = urls;
        }

        @Override
        public int getCount() {
            return urls == null ? 0 : urls.size();
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            FrameLayout frame = new FrameLayout(container.getContext());
            frame.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            ZoomImageView imageView = new ZoomImageView(container.getContext());
            FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            imageView.setLayoutParams(imageLp);

            final ProgressBar progressBar = new ProgressBar(container.getContext());
            FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            progressLp.gravity = Gravity.CENTER;
            progressBar.setLayoutParams(progressLp);
            progressBar.setIndeterminate(true);

            frame.addView(imageView);
            frame.addView(progressBar);
            progressBar.setVisibility(View.VISIBLE);

            ImageLoader.loadLarge(imageView, urls.get(position), new com.im.oldchat.util.ImageLoader.ImageLoadListener() {
                @Override
                public void onComplete(String url) {
                    progressBar.setVisibility(View.GONE);
                }
            });
            container.addView(frame);
            return frame;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            if (object instanceof View) {
                container.removeView((View) object);
            }
        }
    }
}
