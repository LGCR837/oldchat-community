package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import com.im.oldchat.BaseActivity;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.ResourceComment;
import com.im.oldchat.util.NetworkStateManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class ResourceCommentsActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";

    private ListView lvComments;
    private EditText etComment;
    private TextView btnSend;
    private ResourceCommentAdapter adapter;
    private final List<ResourceComment> comments = new ArrayList<>();
    private String token;
    private String myUid;
    private String itemId;
    private String sectionOwnerUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_comments);

        lvComments = findViewByIdCompat(R.id.lvComments);
        etComment = findViewByIdCompat(R.id.etComment);
        btnSend = findViewByIdCompat(R.id.btnSendComment);
        TextView title = (TextView) findViewByIdCompat(R.id.tvCommentsTitle);
        if (title != null) {
            title.setText("资源评论");
        }
        View btnBack = (View) findViewByIdCompat(R.id.btnCommentsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        myUid = prefs.getString("my_uid", "");
        itemId = getIntent().getStringExtra("item_id");
        sectionOwnerUid = getIntent().getStringExtra("section_owner_uid");

        adapter = new ResourceCommentAdapter(this, comments);
        lvComments.setAdapter(adapter);

        lvComments.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= comments.size()) {
                    return false;
                }
                ResourceComment target = comments.get(position);
                if (target == null || !canDeleteComment(target)) {
                    return false;
                }
                confirmDeleteComment(target, position);
                return true;
            }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendComment();
            }
        });
        loadComments();
    }

    private void loadComments() {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            return;
        }
        String encodedItem = itemId;
        try {
            encodedItem = java.net.URLEncoder.encode(itemId, "UTF-8");
        } catch (Exception e) {
            encodedItem = itemId;
        }
        HttpUtil.get("/resources/comments?item_id=" + encodedItem + "&limit=50", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("comments");
                    comments.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cObj = arr.getJSONObject(i);
                        ResourceComment c = new ResourceComment();
                        c.id = cObj.optString("id");
                        c.item_id = cObj.optString("item_id");
                        c.from_uid = cObj.optString("from_uid");
                        c.from_name = cObj.optString("from_name");
                        c.from_title = cObj.optString("from_title");
                        c.from_avatar = cObj.optString("from_avatar");
                        c.body = cObj.optString("body");
                        c.created_at = cObj.optLong("created_at");
                        comments.add(c);
                    }
                    java.util.Collections.sort(comments, new java.util.Comparator<ResourceComment>() {
                        @Override
                        public int compare(ResourceComment a, ResourceComment b) {
                            if (a.created_at < b.created_at) {
                                return -1;
                            }
                            if (a.created_at > b.created_at) {
                                return 1;
                            }
                            return 0;
                        }
                    });
                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    Toast.makeText(ResourceCommentsActivity.this, "加载评论失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(ResourceCommentsActivity.this, "加载评论失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendComment() {
        String body = etComment.getText().toString().trim();
        if (body.isEmpty()) {
            Toast.makeText(this, "请输入评论内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", itemId);
            json.put("body", body);
            HttpUtil.post("/resources/comment", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        ResourceComment c = new ResourceComment();
                        c.id = obj.optString("id");
                        c.item_id = obj.optString("item_id");
                        c.from_uid = obj.optString("from_uid");
                        c.from_name = obj.optString("from_name");
                        c.from_title = obj.optString("from_title");
                        c.from_avatar = obj.optString("from_avatar");
                        c.body = obj.optString("body");
                        c.created_at = obj.optLong("created_at");
                        comments.add(0, c);
                        adapter.notifyDataSetChanged();
                        etComment.setText("");
                    } catch (Exception e) {
                        Toast.makeText(ResourceCommentsActivity.this, "发送失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ResourceCommentsActivity.this, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean canDeleteComment(ResourceComment comment) {
        if (comment == null || myUid == null || myUid.isEmpty()) {
            return false;
        }
        if (myUid.equals(comment.from_uid)) {
            return true;
        }
        return sectionOwnerUid != null && myUid.equals(sectionOwnerUid);
    }

    private void confirmDeleteComment(final ResourceComment comment, final int position) {
        new AlertDialog.Builder(this)
                .setTitle("删除评论")
                .setMessage("确定删除这条评论吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteComment(comment, position);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteComment(ResourceComment comment, final int position) {
        if (comment == null || comment.id == null || comment.id.isEmpty()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("comment_id", comment.id);
            HttpUtil.post("/resources/comment/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (position >= 0 && position < comments.size()) {
                        comments.remove(position);
                        adapter.notifyDataSetChanged();
                    }
                    Toast.makeText(ResourceCommentsActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(ResourceCommentsActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }
}
