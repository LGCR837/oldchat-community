package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.support.v4.view.ViewCompat;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.data.NotificationReadStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationChatActivity extends BaseActivity {
    @Override
    protected boolean enableSwipeBack() {
        return true;
    }

    public static final String SYSTEM_UID = "SYSTEM";

    private ListView lvNotifications;
    private ProgressBar pbLoading;
    private TextView tvEmpty;
    private ImageView btnMute;
    private String token;

    private NotificationAdapter adapter;
    private List<NotificationItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_chat);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        lvNotifications = findViewByIdCompat(R.id.lvNotifications);
        pbLoading = findViewByIdCompat(R.id.pbLoading);
        tvEmpty = findViewByIdCompat(R.id.tvEmpty);
        btnMute = findViewByIdCompat(R.id.btnMute);

        View btnBack = findViewByIdCompat(R.id.btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });

        lvNotifications = findViewByIdCompat(R.id.lvNotifications);
        final ImageView ivNotifAvatar = findViewByIdCompat(R.id.ivNotifAvatar);
        com.im.oldchat.util.ChatHeaderEnterAnimHelper.runEnterAnimation(this, ivNotifAvatar, null, btnBack, lvNotifications);

        adapter = new NotificationAdapter(this, items);
        lvNotifications.setAdapter(adapter);

        updateMuteIcon();
        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMute();
            }
        });

        loadNotifications();
    }

    private void updateMuteIcon() {
        boolean muted = SettingsStore.isConversationMuted(this, SYSTEM_UID, false);
        ViewCompat.setAlpha(btnMute, muted ? 0.5f : 1.0f);
    }

    private void toggleMute() {
        boolean currentMuted = SettingsStore.isConversationMuted(this, SYSTEM_UID, false);
        SettingsStore.setConversationMuted(this, SYSTEM_UID, false, !currentMuted);
        updateMuteIcon();
        Toast.makeText(this, currentMuted ? "已取消屏蔽" : "已屏蔽通知", Toast.LENGTH_SHORT).show();
    }

    private void loadNotifications() {
        pbLoading.setVisibility(View.VISIBLE);
        lvNotifications.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);

        HttpUtil.get("/notifications?limit=100", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                pbLoading.setVisibility(View.GONE);
                try {
                    JSONObject data = new JSONObject(response);
                    JSONArray arr = data.getJSONArray("notifications");
                    items.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        NotificationItem item = new NotificationItem();
                        item.id = obj.optString("id", "");
                        item.title = obj.optString("title", "");
                        item.body = obj.optString("body", "");
                        item.createdAt = obj.optLong("created_at", 0);
                        items.add(item);
                    }
                    markNotificationsRead(items);
                    clearSystemUnreadCount();
                    adapter.notifyDataSetChanged();

                    if (items.isEmpty()) {
                        tvEmpty.setVisibility(View.VISIBLE);
                        lvNotifications.setVisibility(View.GONE);
                    } else {
                        tvEmpty.setVisibility(View.GONE);
                        lvNotifications.setVisibility(View.VISIBLE);
                    }
                } catch (Exception e) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    lvNotifications.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(int code, String error) {
                pbLoading.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.VISIBLE);
                lvNotifications.setVisibility(View.GONE);
                Toast.makeText(NotificationChatActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void markNotificationsRead(List<NotificationItem> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            NotificationItem item = list.get(i);
            if (item == null || item.id == null || item.id.length() == 0) {
                continue;
            }
            NotificationReadStore.markAsRead(this, item.id);
        }
    }

    private void clearSystemUnreadCount() {
        SharedPreferences prefs = getSharedPreferences("notification", Context.MODE_PRIVATE);
        prefs.edit().putInt("unread_count", 0).apply();
    }

    static class NotificationItem {
        String id;
        String title;
        String body;
        long createdAt;
    }

    static class NotificationAdapter extends BaseAdapter {
        private Context context;
        private List<NotificationItem> items;
        private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        NotificationAdapter(Context context, List<NotificationItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public NotificationItem getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_notification, parent, false);
            }

            NotificationItem item = getItem(position);

            TextView tvTitle = (TextView) convertView.findViewById(R.id.tvTitle);
            TextView tvBody = (TextView) convertView.findViewById(R.id.tvBody);
            TextView tvTime = (TextView) convertView.findViewById(R.id.tvTime);

            if (item.title != null && !item.title.isEmpty()) {
                tvTitle.setText(item.title);
                tvTitle.setVisibility(View.VISIBLE);
            } else {
                tvTitle.setVisibility(View.GONE);
            }

            tvBody.setText(item.body);
            tvTime.setText(sdf.format(new Date(item.createdAt)));

            return convertView;
        }
    }
}
