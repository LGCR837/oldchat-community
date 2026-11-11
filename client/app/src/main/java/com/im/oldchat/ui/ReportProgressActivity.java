package com.im.oldchat.ui;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import com.im.oldchat.BaseActivity;
import android.view.View;

import com.im.oldchat.R;
import com.im.oldchat.ui.fragments.BugReportProgressFragment;
import com.im.oldchat.ui.fragments.ResourceReportProgressFragment;
import com.im.oldchat.ui.fragments.UserReportProgressFragment;

import java.util.ArrayList;
import java.util.List;

public class ReportProgressActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report_progress);

        View btnBack = (View) findViewByIdCompat(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        ViewPager viewPager = (ViewPager) findViewByIdCompat(R.id.viewPager);

        ProgressPagerAdapter adapter = new ProgressPagerAdapter(getSupportFragmentManager());
        adapter.add(new BugReportProgressFragment(), "Bug反馈");
        adapter.add(new UserReportProgressFragment(), "用户举报");
        adapter.add(new ResourceReportProgressFragment(), "资源举报");
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(2);
    }

    private static class ProgressPagerAdapter extends FragmentPagerAdapter {
        private final List<Fragment> fragments = new ArrayList<Fragment>();
        private final List<String> titles = new ArrayList<String>();

        ProgressPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        void add(Fragment fragment, String title) {
            fragments.add(fragment);
            titles.add(title);
        }

        @Override
        public Fragment getItem(int position) {
            return fragments.get(position);
        }

        @Override
        public int getCount() {
            return fragments.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return titles.get(position);
        }
    }
}
