package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.User;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class GroupInviteActivity extends BaseActivity {
    private ListView lvFriends;
    private EditText etSearch;
    private TextView tvSelected;
    private String token;
    private String groupId;

    private final List<User> allFriends = new ArrayList<User>();
    private final List<User> filteredFriends = new ArrayList<User>();
    private final HashSet<String> selectedUids = new HashSet<String>();
    private final HashSet<String> memberUids = new HashSet<String>();
    private InviteFriendAdapter adapter;

    private int inviteSuccessCount = 0;
    private int inviteAutoRejectedCount = 0;
    private int invitePendingCount = 0;
    private int inviteSkipCount = 0;
    private int inviteFailCount = 0;
    private final List<String> inviteFailNames = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_invite);

        lvFriends = findViewByIdCompat(R.id.lvFriends);
        etSearch = findViewByIdCompat(R.id.etInviteSearch);
        tvSelected = findViewByIdCompat(R.id.tvInviteSelected);
        groupId = getIntent().getStringExtra("group_id");

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        adapter = new InviteFriendAdapter();
        lvFriends.setAdapter(adapter);
        lvFriends.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                toggleSelectAt(position);
            }
        });

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    applyFilter();
                }
            });
        }

        findViewByIdCompat(R.id.btnInvite).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inviteMembers();
            }
        });
        View btnBack = findViewByIdCompat(R.id.btnInviteBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        updateSelectedHint();
        loadExistingMembersThenFriends();
    }

    private void loadExistingMembersThenFriends() {
        if (groupId == null || groupId.length() == 0) {
            loadFriends();
            return;
        }
        String path = "/groups/members?group_id=" + urlEncode(groupId);
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("members");
                    memberUids.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject one = arr.optJSONObject(i);
                            if (one == null) {
                                continue;
                            }
                            String uid = normalizeUid(one.optString("uid", ""));
                            if (uid.length() > 0) {
                                memberUids.add(uid);
                            }
                        }
                    }
                } catch (Exception e) {
                    memberUids.clear();
                }
                loadFriends();
            }

            @Override
            public void onError(int code, String error) {
                memberUids.clear();
                loadFriends();
            }
        });
    }

    private void loadFriends() {
        HttpUtil.get("/friends", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("friends");
                    allFriends.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject uObj = arr.getJSONObject(i);
                        User u = new User();
                        u.id = uObj.optString("id");
                        u.uid = uObj.optString("uid");
                        u.username = uObj.optString("username");
                        u.display_name = uObj.optString("display_name");
                        u.remark_name = uObj.optString("remark_name", "");
                        u.user_title = uObj.optString("user_title");
                        u.avatar_url = uObj.optString("avatar_url");
                        allFriends.add(u);
                    }
                    applyFilter();
                } catch (Exception e) {
                    Toast.makeText(GroupInviteActivity.this, "加载好友失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(GroupInviteActivity.this, "加载好友失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyFilter() {
        String query = etSearch == null || etSearch.getText() == null
                ? "" : etSearch.getText().toString();
        String q = normalize(query);

        filteredFriends.clear();
        for (int i = 0; i < allFriends.size(); i++) {
            User u = allFriends.get(i);
            if (u == null) {
                continue;
            }
            String uid = normalizeUid(u.uid);
            if (uid.length() == 0) {
                continue;
            }
            if (memberUids.contains(uid)) {
                continue;
            }
            if (q.length() > 0 && !matchesUser(u, q)) {
                continue;
            }
            filteredFriends.add(u);
        }

        adapter.notifyDataSetChanged();
        updateSelectedHint();
    }

    private boolean matchesUser(User u, String q) {
        String name = normalize(FriendNameResolver.resolve(u));
        String uid = normalize(u.uid);
        String username = normalize(u.username);
        String display = normalize(u.display_name);
        String remark = normalize(u.remark_name);
        return name.contains(q) || uid.contains(q) || username.contains(q)
                || display.contains(q) || remark.contains(q);
    }

    private void toggleSelectAt(int position) {
        if (position < 0 || position >= filteredFriends.size()) {
            return;
        }
        User u = filteredFriends.get(position);
        if (u == null || u.uid == null || u.uid.length() == 0) {
            return;
        }
        String uid = normalizeUid(u.uid);
        if (uid.length() == 0) {
            return;
        }
        if (selectedUids.contains(uid)) {
            selectedUids.remove(uid);
        } else {
            selectedUids.add(uid);
        }
        adapter.notifyDataSetChanged();
        updateSelectedHint();
    }

    private void updateSelectedHint() {
        if (tvSelected != null) {
            tvSelected.setText("已选择 " + selectedUids.size() + " 人");
        }
    }

    private void inviteMembers() {
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        List<String> uids = new ArrayList<String>();
        for (String uid : selectedUids) {
            if (uid != null && uid.length() > 0) {
                uids.add(uid);
            }
        }
        if (uids.isEmpty()) {
            Toast.makeText(this, "请选择成员", Toast.LENGTH_SHORT).show();
            return;
        }

        inviteSuccessCount = 0;
        inviteAutoRejectedCount = 0;
        invitePendingCount = 0;
        inviteSkipCount = 0;
        inviteFailCount = 0;
        inviteFailNames.clear();
        inviteNext(uids, 0);
    }

    private User findFriendByUid(String uid) {
        if (uid == null) return null;
        for (User user : allFriends) {
            if (user != null && user.uid != null && uid.equalsIgnoreCase(user.uid)) {
                return user;
            }
        }
        return null;
    }

    private void inviteNext(final List<String> uids, final int index) {
        if (index >= uids.size()) {
            StringBuilder summary = new StringBuilder();
            summary.append("邀请已发送").append(inviteSuccessCount).append("人");
            if (inviteAutoRejectedCount > 0) {
                summary.append("，自动拒绝").append(inviteAutoRejectedCount).append("人");
            }
            if (invitePendingCount > 0) {
                summary.append("，等待处理").append(invitePendingCount).append("人");
            }
            if (inviteSkipCount > 0) {
                summary.append("，已在群").append(inviteSkipCount).append("人");
            }
            if (inviteFailCount > 0) {
                summary.append("，失败").append(inviteFailCount).append("人");
            }
            Toast.makeText(this, summary.toString(), Toast.LENGTH_LONG).show();
            if (inviteSuccessCount > 0 || inviteAutoRejectedCount > 0 || invitePendingCount > 0) {
                finish();
            }
            return;
        }

        final String uid = uids.get(index);
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("user_uid", uid);
            User selected = findFriendByUid(uid);
            if (selected != null && selected.ncuid != null && selected.ncuid.length() > 0) {
                json.put("user_ncuid", selected.ncuid);
            }
            HttpUtil.post("/groups/invite", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        String status = new JSONObject(response).optString("status", "pending");
                        if ("auto_rejected".equals(status)) inviteAutoRejectedCount++;
                        else inviteSuccessCount++;
                    } catch (Exception ignored) {
                        inviteSuccessCount++;
                    }
                    inviteNext(uids, index + 1);
                }

                @Override
                public void onError(int code, String error) {
                    if (error != null && error.contains("invitation_pending")) {
                        invitePendingCount++;
                    } else if (code == 409 || (error != null && error.contains("already_member"))) {
                        inviteSkipCount++;
                    } else {
                        inviteFailCount++;
                        String name = resolveDisplayByUid(uid);
                        if (name.length() > 0 && inviteFailNames.size() < 3) {
                            inviteFailNames.add(name);
                        }
                    }
                    inviteNext(uids, index + 1);
                }
            });
        } catch (Exception e) {
            inviteFailCount++;
            inviteNext(uids, index + 1);
        }
    }

    private String resolveDisplayByUid(String uid) {
        if (uid == null || uid.length() == 0) {
            return "";
        }
        for (int i = 0; i < allFriends.size(); i++) {
            User u = allFriends.get(i);
            if (u == null || u.uid == null) {
                continue;
            }
            if (uid.equalsIgnoreCase(u.uid)) {
                String name = FriendNameResolver.resolve(u);
                if (name != null && name.length() > 0) {
                    return name;
                }
                return u.uid;
            }
        }
        return uid;
    }

    private String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.getDefault());
    }

    private String normalizeUid(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.getDefault());
    }

    private class InviteFriendAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredFriends.size();
        }

        @Override
        public Object getItem(int position) {
            if (position < 0 || position >= filteredFriends.size()) {
                return null;
            }
            return filteredFriends.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            InviteFriendHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(GroupInviteActivity.this)
                        .inflate(R.layout.item_group_invite_friend, parent, false);
                holder = new InviteFriendHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (InviteFriendHolder) convertView.getTag();
            }

            User user = (User) getItem(position);
            if (user == null) {
                return convertView;
            }

            String display = FriendNameResolver.resolve(user);
            if (display == null || display.length() == 0) {
                display = user.uid == null ? "" : user.uid;
            }
            holder.tvName.setText(display);
            String uid = user.uid == null ? "" : user.uid;
            String username = user.username == null ? "" : user.username;
            String meta = "UID: " + uid;
            if (username.length() > 0) {
                meta += " · 用户名: " + username;
            }
            holder.tvMeta.setText(meta);

            final String normalizedUid = normalizeUid(uid);
            holder.cbSelect.setOnCheckedChangeListener(null);
            holder.cbSelect.setChecked(selectedUids.contains(normalizedUid));
            holder.cbSelect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleSelectAt(position);
                }
            });
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleSelectAt(position);
                }
            });
            return convertView;
        }
    }

    private static class InviteFriendHolder {
        final TextView tvName;
        final TextView tvMeta;
        final CheckBox cbSelect;

        InviteFriendHolder(View root) {
            tvName = root.findViewById(R.id.tvInviteFriendName);
            tvMeta = root.findViewById(R.id.tvInviteFriendMeta);
            cbSelect = root.findViewById(R.id.cbInviteFriend);
        }
    }
}
