package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.MusicComment;
import com.im.oldchat.util.NetworkStateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class MusicCommentsActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";

    private ListView lvComments;
    private EditText etComment;
    private TextView btnSend;
    private MusicCommentAdapter adapter;
    private final List<MusicComment> comments = new ArrayList<MusicComment>();
    private String token;
    private String myUid;
    private String itemId;
    private String ownerUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_comments);

        lvComments = findViewByIdCompat(R.id.lvComments);
        etComment = findViewByIdCompat(R.id.etComment);
        btnSend = findViewByIdCompat(R.id.btnSendComment);
        TextView title = findViewByIdCompat(R.id.tvCommentsTitle);
        if (title != null) {
            title.setText("歌曲评论");
        }
        View btnBack = findViewByIdCompat(R.id.btnCommentsBack);
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
        ownerUid = getIntent().getStringExtra("owner_uid");

        adapter = new MusicCommentAdapter(this, comments);
        lvComments.setAdapter(adapter);

        lvComments.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= comments.size()) {
                    return false;
                }
                MusicComment target = comments.get(position);
                if (target == null || !canDeleteComment(target)) {
                    return false;
                }
                confirmDeleteComment(target, position);
                return true;
            }
        });

        if (btnSend != null) {
            btnSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sendComment();
                }
            });
        }
        loadComments();
    }

    private void loadComments() {
        if (itemId == null || itemId.length() == 0) {
            return;
        }
        if (token == null || token.length() == 0 || !NetworkStateManager.getInstance().isServerAvailable()) {
            return;
        }
        String encodedItem = itemId;
        try {
            encodedItem = URLEncoder.encode(itemId, "UTF-8");
        } catch (Exception e) {
            encodedItem = itemId;
        }
        HttpUtil.get("/music/plaza/comments?item_id=" + encodedItem + "&limit=50", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("comments");
                    comments.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject cObj = arr.optJSONObject(i);
                            if (cObj == null) {
                                continue;
                            }
                            MusicComment c = new MusicComment();
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
                    }
                    java.util.Collections.sort(comments, new java.util.Comparator<MusicComment>() {
                        @Override
                        public int compare(MusicComment a, MusicComment b) {
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
                    Toast.makeText(MusicCommentsActivity.this, "加载评论失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(MusicCommentsActivity.this, "加载评论失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendComment() {
        if (itemId == null || itemId.length() == 0) {
            return;
        }
        String body = etComment == null || etComment.getText() == null ? "" : etComment.getText().toString().trim();
        if (body.length() == 0) {
            Toast.makeText(this, "请输入评论内容", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", itemId);
            json.put("body", body);
            HttpUtil.post("/music/plaza/comment", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        MusicComment c = new MusicComment();
                        c.id = obj.optString("id");
                        c.item_id = obj.optString("item_id");
                        c.from_uid = obj.optString("from_uid");
                        c.from_name = obj.optString("from_name");
                        c.from_title = obj.optString("from_title");
                        c.from_avatar = obj.optString("from_avatar");
                        c.body = obj.optString("body");
                        c.created_at = obj.optLong("created_at");
                        comments.add(c);
                        java.util.Collections.sort(comments, new java.util.Comparator<MusicComment>() {
                            @Override
                            public int compare(MusicComment a, MusicComment b) {
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
                        if (etComment != null) {
                            etComment.setText("");
                        }
                    } catch (Exception e) {
                        Toast.makeText(MusicCommentsActivity.this, "发送失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicCommentsActivity.this, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean canDeleteComment(MusicComment comment) {
        if (comment == null || myUid == null || myUid.length() == 0) {
            return false;
        }
        if (myUid.equals(comment.from_uid)) {
            return true;
        }
        return ownerUid != null && myUid.equals(ownerUid);
    }

    private void confirmDeleteComment(final MusicComment comment, final int position) {
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

    private void deleteComment(MusicComment comment, final int position) {
        if (comment == null || comment.id == null || comment.id.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("comment_id", comment.id);
            HttpUtil.post("/music/plaza/comment/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (position >= 0 && position < comments.size()) {
                        comments.remove(position);
                        adapter.notifyDataSetChanged();
                    }
                    Toast.makeText(MusicCommentsActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicCommentsActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }
}
