package com.im.oldchat.ui;

import android.os.Bundle;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliAuthStore;

public class OldViewUpProfileActivity extends OldViewUpProfileSupport1 {
    public static final String EXTRA_UP_MID = "up_mid";
    public static final String EXTRA_UP_NAME = "up_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_old_view_up_profile);
        bindViews();
        bindActions();
        readArgs();
        if (upMid <= 0L) {
            Toast.makeText(this, getString(R.string.old_view_up_profile_unavailable), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        renderBaseInfo();
        accessToken = BiliAuthStore.getAccessToken(this);
        cookie = BiliAuthStore.getCookies(this);
        setupVideoList();
        updateFollowButton();
        queryFollowState();
        switchTab(false);
        loadProfile();
        loadVideos(1, true);
    }
}
