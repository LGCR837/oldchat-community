package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.User;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class GroupCreateActivity extends BaseActivity {
    private EditText etGroupName;
    private ListView lvFriends;
    private String token;
    private final List<User> friends = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_create);

        etGroupName = findViewByIdCompat(R.id.etGroupName);
        lvFriends = findViewByIdCompat(R.id.lvFriends);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        findViewByIdCompat(R.id.btnCreateGroup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createGroup();
            }
        });
        View btnBack = (View) findViewByIdCompat(R.id.btnGroupCreateBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        loadFriends();
    }

    private void loadFriends() {
        HttpUtil.get("/friends", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("friends");
                    friends.clear();
                    List<String> names = new ArrayList<>();
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
                        friends.add(u);
                        String title = FriendNameResolver.resolve(u);
                        names.add(title + " (" + u.uid + ")");
                    }
                    adapter = new ArrayAdapter<>(GroupCreateActivity.this,
                            android.R.layout.simple_list_item_multiple_choice, names);
                    lvFriends.setAdapter(adapter);
                } catch (Exception e) {
                    Toast.makeText(GroupCreateActivity.this, "加载好友失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(GroupCreateActivity.this, "加载好友失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void createGroup() {
        String name = etGroupName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入群名称", Toast.LENGTH_SHORT).show();
            return;
        }
        final String nameFinal = name;
        SparseBooleanArray checked = lvFriends.getCheckedItemPositions();
        JSONArray memberUids = new JSONArray();
        JSONArray memberNcuids = new JSONArray();
        int selectedCount = 0;
        for (int i = 0; i < friends.size(); i++) {
            if (checked != null && checked.get(i)) {
                selectedCount++;
                User u = friends.get(i);
                if (u.uid != null && !u.uid.isEmpty()) {
                    memberUids.put(u.uid);
                }
                if (u.ncuid != null && !u.ncuid.isEmpty()) {
                    memberNcuids.put(u.ncuid);
                }
            }
        }
        final int selectedCountFinal = selectedCount;
        try {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("member_uids", memberUids);
            if (selectedCount > 0 && memberNcuids.length() == selectedCount) {
                json.put("member_ncuids", memberNcuids);
            }
            HttpUtil.post("/groups/create", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        String groupId = obj.optString("group_id");
                        if (selectedCountFinal > 0) {
                            int sent = obj.optInt("invitation_count", 0);
                            int rejected = obj.optInt("auto_rejected_count", 0);
                            String message = sent > 0 ? "群聊已创建，邀请已发送，成员同意后加入" : "群聊已创建";
                            if (rejected > 0) message += "，" + rejected + "人已自动拒绝";
                            Toast.makeText(GroupCreateActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                        Intent intent = new Intent(GroupCreateActivity.this, GroupChatActivity.class);
                        intent.putExtra("group_id", groupId);
                        intent.putExtra("group_name", nameFinal);
                        startActivity(intent);
                        finish();
                    } catch (Exception e) {
                        Toast.makeText(GroupCreateActivity.this, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(GroupCreateActivity.this, "创建失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
        }
    }
}
