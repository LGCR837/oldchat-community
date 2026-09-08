package com.im.oldchat;

import android.support.v7.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.view.ViewPager;
import com.im.oldchat.BaseActivity;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.api.UpdatesEventListener;
import com.im.oldchat.api.WSManager;
import com.im.oldchat.data.FriendRequestStore;
import com.im.oldchat.data.NotificationReadStore;
import com.im.oldchat.ui.LoginActivity;
import com.im.oldchat.ui.NoAnimViewPager;
import com.im.oldchat.ui.TopStatusBar;
import com.im.oldchat.ui.fragments.ChatsFragment;
import com.im.oldchat.ui.fragments.DiscoverFragment;
import com.im.oldchat.ui.fragments.FriendsFragment;
import com.im.oldchat.ui.fragments.ProfileFragment;
import com.im.oldchat.util.NetworkStateManager;
import com.im.oldchat.util.UpdateManager;
import com.im.oldchat.util.AvatarSyncManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {

    private LinearLayout tabLayout;
    private ViewPager viewPager;
    private final List<View> tabViews = new ArrayList<View>();
    private boolean hasCheckedNotifications = false;
    private TopStatusBar topStatusBar;
    private View friendsBadge;
    private boolean refreshingRequests = false;

    @Override
    protected int getOpenEnterAnimation() {
        return 0;
    }

    @Override
    protected int getOpenExitAnimation() {
        return 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String token = prefs.getString("access_token", null);
        if (token == null) {
            token = prefs.getString("token", null);
        }

        if (token == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        if (com.im.oldchat.util.ThemeColorUtil.isMinimalDark(this)) {
            View root = findViewByIdCompat(R.id.main_page_root);
            if (root != null) root.setBackgroundColor(0xFF08090A);
        }
        applyBottomSafeAreaPadding();

        WSManager.getInstance().start(this);
        // 账号级 pts 同步体系初始化（本地 pts 恢复 + 事件监听注册）。
        try {
            UpdatesEventListener.getInstance().init(this);
        } catch (Throwable ignored) {
        }
        // Android 2.3 先保持主页低内存运行，不在首屏加载 Lua 运行时与脚本列表。
        if (android.os.Build.VERSION.SDK_INT > 10) {
            com.im.oldchat.lua.LuaAppSyncManager.sync(this, null);
        }

        topStatusBar = findViewByIdCompat(R.id.topStatusBar);
        if (topStatusBar != null) {
            topStatusBar.setOnRetryClickListener(new TopStatusBar.RetryClickListener() {
                @Override
                public void onRetry() {
                    SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
                    String token = prefs.getString("access_token", null);
                    if (token == null) {
                        token = prefs.getString("token", null);
                    }
                    if (token == null || token.length() == 0) {
                        startActivity(new Intent(MainActivity.this, LoginActivity.class));
                        finish();
                        return;
                    }
                    WSManager.getInstance().start(MainActivity.this);
                    refreshChatHome();
                    refreshFriendRequestsBadge();
                    checkSystemNotifications();
                }
            });
        }
        viewPager = findViewByIdCompat(R.id.viewpager);
        setupViewPager(viewPager);

        tabLayout = findViewByIdCompat(R.id.tabs);
        if (com.im.oldchat.util.ThemeColorUtil.isMinimalDark(this) && tabLayout != null) {
            tabLayout.setBackgroundColor(0xFF151719);
        }
        setupTabViews();

        AvatarSyncManager.syncAll(this, token);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android 2.3 堆小：从聊天页返回时，旧 Activity 的 bitmap 可能未被 GC 及时回收，
        // 直接重建主页布局会因堆满 OOM。主动 gc 让 Dalvik 尽快回收残留内存。
        if (android.os.Build.VERSION.SDK_INT <= 10) {
            System.gc();
        }
        WSManager.getInstance().start(this);
        refreshChatHome();
        refreshFriendRequestsBadge();

        // 每次回到主界面都检测更新（但有24小时间隔限制）
        UpdateManager.check(this);

        // 只在第一次onResume时检查通知
        if (!hasCheckedNotifications) {
            hasCheckedNotifications = true;
            checkSystemNotifications();
        }
    }

    private void setupViewPager(ViewPager viewPager) {
        ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
        adapter.addFragment(new ChatsFragment(), "聊天");
        adapter.addFragment(new FriendsFragment(), "好友");
        adapter.addFragment(new DiscoverFragment(), "发现");
        adapter.addFragment(new ProfileFragment(), "我的");
        viewPager.setOffscreenPageLimit(3);
        viewPager.setAdapter(adapter);
        if (viewPager instanceof NoAnimViewPager) {
            ((NoAnimViewPager) viewPager).setDefaultSmoothScroll(false);
        }
    }

    private void refreshChatHome() {
        if (viewPager == null) {
            return;
        }
        String tag = "android:switcher:" + viewPager.getId() + ":0";
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if (fragment instanceof ChatsFragment) {
            ((ChatsFragment) fragment).refreshRecents();
        }
    }

    private void setupTabViews() {
        tabLayout.removeAllViews();
        tabViews.clear();
        setupTabView(0, R.drawable.ic_tab_chat_alt, "聊天");
        setupTabView(1, R.drawable.ic_tab_friends, "好友");
        setupTabView(2, R.drawable.news, "发现");
        setupTabView(3, R.drawable.ic_tab_profile, "我的");
        updateTabSelection(viewPager.getCurrentItem());
        updateFriendsBadge(FriendRequestStore.getPendingCount(this));
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateTabSelection(position);
            }
        });
    }

    private void setupTabView(int position, int iconRes, String text) {
        View view = LayoutInflater.from(this).inflate(R.layout.view_tab_item, tabLayout, false);
        ImageView icon = (ImageView) view.findViewById(R.id.tabIcon);
        TextView label = (TextView) view.findViewById(R.id.tabLabel);
        View badge = (View) view.findViewById(R.id.tabBadge);
        try {
            Drawable d = android.support.v4.content.ContextCompat.getDrawable(this, iconRes);
            if (d != null) {
                icon.setImageDrawable(d);
            }
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "Failed to load tab icon: " + iconRes, t);
        }
        label.setText(text);
        try {
            ColorStateList tint = getResources().getColorStateList(R.color.tab_icon_tint);
            if (tint != null) {
                label.setTextColor(tint);
                applyIconTint(icon, tint);
            }
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "Failed to load tab_icon_tint", t);
        }
        view.setClickable(true);
        view.setFocusable(true);
        if (position == 2) {
            // “发现”底部按钮只负责切页，不让后层整块容器参与磁贴式倾斜/按压遮罩。
            if (view instanceof com.im.oldchat.ui.widget.TiltFrameLayout) {
                ((com.im.oldchat.ui.widget.TiltFrameLayout) view).setTiltEnabled(false);
            }
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
        final int tabIndex = position;
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (viewPager != null) {
                    viewPager.setCurrentItem(tabIndex, false);
                }
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        );
        tabLayout.addView(view, params);
        tabViews.add(view);
        if (position == 1) {
            friendsBadge = badge;
        }
    }

    private void applyBottomSafeAreaPadding() {
        // 全面屏手势条（Android 9+）可能遮挡底部工具栏，读取系统 inset 增加底部 padding。
        if (android.os.Build.VERSION.SDK_INT < 28) {
            return;
        }
        final View tabBar = findViewByIdCompat(R.id.tabs);
        if (tabBar == null) {
            return;
        }
        try {
            tabBar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                    int bottomInset = 0;
                    if (insets != null) {
                        try {
                            bottomInset = insets.getSystemWindowInsetBottom();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (bottomInset > 0) {
                        v.setPadding(0, 0, 0, bottomInset);
                    }
                    return insets;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void updateTabSelection(int selectedPosition) {
        for (int i = 0; i < tabViews.size(); i++) {
            setTabSelected(tabViews.get(i), i == selectedPosition);
        }
    }

    private void setTabSelected(View tabView, boolean selected) {
        if (tabView == null) {
            return;
        }
        ImageView icon = (ImageView) tabView.findViewById(R.id.tabIcon);
        TextView label = (TextView) tabView.findViewById(R.id.tabLabel);
        View indicator = (View) tabView.findViewById(R.id.tabIndicator);
        tabView.setSelected(selected);
        if (icon != null) {
            icon.setSelected(selected);
        }
        if (label != null) {
            label.setSelected(selected);
            label.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (indicator != null) {
            indicator.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void applyIconTint(ImageView icon, ColorStateList tint) {
        if (icon == null || tint == null) {
            return;
        }
        Drawable src = icon.getDrawable();
        if (src == null) {
            return;
        }
        Drawable wrapped = DrawableCompat.wrap(src.mutate());
        DrawableCompat.setTintList(wrapped, tint);
        icon.setImageDrawable(wrapped);
    }

    public void updateFriendsBadge(int count) {
        if (friendsBadge == null) {
            return;
        }
        friendsBadge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    private void refreshFriendRequestsBadge() {
        if (refreshingRequests) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        final String token = prefs.getString("access_token", "");
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            updateFriendsBadge(FriendRequestStore.getPendingCount(this));
            return;
        }
        refreshingRequests = true;
        if (topStatusBar != null) {
            topStatusBar.setLoading(true);
        }
        HttpUtil.get("/friends/requests", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                refreshingRequests = false;
                if (topStatusBar != null) {
                    topStatusBar.setLoading(false);
                }
                int count = 0;
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("requests");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject rObj = arr.getJSONObject(i);
                        if (rObj.optInt("status", 1) == 0) {
                            count++;
                        }
                    }
                } catch (Exception e) {
                    count = FriendRequestStore.getPendingCount(MainActivity.this);
                }
                FriendRequestStore.setPendingCount(MainActivity.this, count);
                updateFriendsBadge(count);
            }

            @Override
            public void onError(int code, String error) {
                refreshingRequests = false;
                if (topStatusBar != null) {
                    topStatusBar.setLoading(false);
                }
                updateFriendsBadge(FriendRequestStore.getPendingCount(MainActivity.this));
            }
        });
    }

    private void checkSystemNotifications() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String token = prefs.getString("access_token", "");

        HttpUtil.get("/notifications?limit=1", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject data = new JSONObject(response);
                    JSONArray arr = data.getJSONArray("notifications");

                    if (arr.length() > 0) {
                        JSONObject notification = arr.getJSONObject(0);
                        String id = notification.optString("id", "");
                        String title = notification.optString("title", "");
                        String body = notification.optString("body", "");
                        boolean important = notification.optBoolean("important", false);
                        final String idFinal = id;
                        final String titleFinal = title;
                        final String bodyFinal = body;
                        final boolean importantFinal = important;

                        // 普通通知：已读就不显示
                        // 重要通知：总是显示，除非已明确标记不再提醒
                        if (!id.isEmpty() && !NotificationReadStore.isRead(MainActivity.this, id)) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showNotificationDialog(idFinal, titleFinal, bodyFinal, importantFinal);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onError(int code, String error) {
                // 静默失败，不影响用户体验
            }
        });
    }

    private void showNotificationDialog(String notificationId, String title, String body, boolean important) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppDialogTheme);
        final String notificationIdFinal = notificationId;

        if (title != null && !title.isEmpty()) {
            builder.setTitle(title);
        } else {
            builder.setTitle("系统通知");
        }

        builder.setMessage(body);
        builder.setCancelable(false);

        if (important) {
            // 重要通知：需要勾选"不再提醒"
            final boolean[] dontShowAgain = {false};

            android.view.View checkboxView = getLayoutInflater().inflate(
                android.R.layout.select_dialog_singlechoice, null);
            android.widget.CheckBox checkbox = new android.widget.CheckBox(this);
            checkbox.setText("不再提醒");
            checkbox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    dontShowAgain[0] = isChecked;
                }
            });

            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(50, 20, 50, 20);
            layout.addView(checkbox);

            builder.setView(layout);
            builder.setPositiveButton("知道了", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    if (dontShowAgain[0]) {
                        NotificationReadStore.markAsRead(MainActivity.this, notificationIdFinal);
                    }
                    dialog.dismiss();
                }
            });
        } else {
            // 普通通知：点击"知道了"即标记为已读
            builder.setPositiveButton("知道了", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    NotificationReadStore.markAsRead(MainActivity.this, notificationIdFinal);
                    dialog.dismiss();
                }
            });
        }

        builder.show();
    }

    class ViewPagerAdapter extends FragmentPagerAdapter {
        private final List<Fragment> mFragmentList = new ArrayList<>();
        private final List<String> mFragmentTitleList = new ArrayList<>();

        public ViewPagerAdapter(FragmentManager manager) {
            super(manager);
        }

        @Override
        public Fragment getItem(int position) {
            return mFragmentList.get(position);
        }

        @Override
        public int getCount() {
            return mFragmentList.size();
        }

        public void addFragment(Fragment fragment, String title) {
            mFragmentList.add(fragment);
            mFragmentTitleList.add(title);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return mFragmentTitleList.get(position);
        }
    }
}
