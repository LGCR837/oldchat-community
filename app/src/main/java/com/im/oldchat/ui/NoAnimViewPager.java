package com.im.oldchat.ui;

import android.content.Context;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;

public class NoAnimViewPager extends ViewPager {
    private boolean defaultSmoothScroll;

    public NoAnimViewPager(Context context) {
        super(context);
        init();
    }

    public NoAnimViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        defaultSmoothScroll = false;
    }

    public void setDefaultSmoothScroll(boolean enabled) {
        defaultSmoothScroll = enabled;
    }

    @Override
    public void setCurrentItem(int item) {
        super.setCurrentItem(item, defaultSmoothScroll);
    }

    @Override
    public void setCurrentItem(int item, boolean smoothScroll) {
        super.setCurrentItem(item, smoothScroll);
    }
}
