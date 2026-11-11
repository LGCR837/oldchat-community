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
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.GroupJoinRequest;
import com.im.oldchat.util.ImageLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class GroupJoinRequestsActivity extends BaseActivity {
    private ListView lvRequests;
    private String token;
    private String groupId;
    private final List<GroupJoinRequest> requests = new ArrayList<>();
    private RequestAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_requests);

        lvRequests = findViewByIdCompat(R.id.lvRequests);
        groupId = getIntent().getStringExtra("group_id");

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        View btnBack = (View) findViewByIdCompat(R.id.btnGroupRequestsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        adapter = new RequestAdapter();
        lvRequests.setAdapter(adapter);

        loadRequests();
    }

    private void loadRequests() {
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        HttpUtil.get("/groups/requests?group_id=" + groupId, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("requests");
                    requests.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject rObj = arr.getJSONObject(i);
                        GroupJoinRequest r = new GroupJoinRequest();
                        r.request_id = rObj.optString("request_id");
                        r.uid = rObj.optString("uid");
                        r.username = rObj.optString("username");
                        r.display_name = rObj.optString("display_name");
                        r.user_title = rObj.optString("user_title");
                        r.avatar_url = rObj.optString("avatar_url");
                        r.created_at = rObj.optLong("created_at", 0);
                        requests.add(r);
                    }
                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    Toast.makeText(GroupJoinRequestsActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(GroupJoinRequestsActivity.this, "加载失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void approveRequest(String requestId) {
        try {
            JSONObject json = new JSONObject();
            json.put("request_id", requestId);
            json.put("accept", true);
            HttpUtil.post("/groups/approve", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    loadRequests();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(GroupJoinRequestsActivity.this, "操作失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    private class RequestAdapter extends BaseAdapter {
        @Override
        public int getCount() { return requests.size(); }

        @Override
        public Object getItem(int position) { return requests.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(GroupJoinRequestsActivity.this)
                        .inflate(R.layout.item_group_request, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            GroupJoinRequest req = requests.get(position);
            final GroupJoinRequest reqFinal = req;
            String name = req.display_name != null && !req.display_name.isEmpty()
                    ? req.display_name : req.username;
            holder.title.setText(name);
            UserTitleBinder.bind(holder.titleBadge, req.user_title);
            holder.subtitle.setText(req.uid);
            ImageLoader.loadAvatar(holder.avatar, req.avatar_url);
            holder.btnApprove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    approveRequest(reqFinal.request_id);
                }
            });
            return convertView;
        }
    }

    private static class ViewHolder {
        final ImageView avatar;
        final TextView title;
        final TextView subtitle;
        final TextView titleBadge;
        final TextView btnApprove;

        ViewHolder(View view) {
            avatar = view.findViewById(R.id.ivAvatar);
            title = view.findViewById(R.id.tvTitle);
            subtitle = view.findViewById(R.id.tvSubtitle);
            titleBadge = view.findViewById(R.id.tvTitleBadge);
            btnApprove = view.findViewById(R.id.btnApprove);
        }
    }
}
