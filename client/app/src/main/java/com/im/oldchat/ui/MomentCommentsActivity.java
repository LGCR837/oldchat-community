package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.BaseActivity;
import android.support.v7.app.AlertDialog;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.models.MomentCache;
import com.im.oldchat.models.MomentComment;
import com.im.oldchat.util.NetworkStateManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MomentCommentsActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";
    private ListView lvComments;
    private EditText etComment;
    private TextView btnSend;
    private MomentCommentAdapter adapter;
    private final List<MomentComment> comments = new ArrayList<>();
    private String token;
    private String momentId;
    private String myUid;
    private String momentOwnerUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_comments);

        lvComments = findViewByIdCompat(R.id.lvComments);
        etComment = findViewByIdCompat(R.id.etComment);
        btnSend = findViewByIdCompat(R.id.btnSendComment);
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
        momentId = getIntent().getStringExtra("moment_id");
        momentOwnerUid = getIntent().getStringExtra("moment_owner_uid");

        adapter = new MomentCommentAdapter(this, comments);
        lvComments.setAdapter(adapter);

        lvComments.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= comments.size()) {
                    return false;
                }
                MomentComment target = comments.get(position);
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
        if (momentId == null || momentId.isEmpty()) {
            return;
        }
        if (token == null || token.isEmpty() || !NetworkStateManager.getInstance().isServerAvailable()) {
            loadCachedComments();
            return;
        }
        HttpUtil.get("/moments/comments?moment_id=" + momentId + "&limit=50", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.getJSONArray("comments");
                    comments.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cObj = arr.getJSONObject(i);
                        MomentComment c = new MomentComment();
                        c.id = cObj.optString("id");
                        c.moment_id = cObj.optString("moment_id");
                        c.from_uid = cObj.optString("from_uid");
                        c.from_ncuid = cObj.optString("from_ncuid");
                        c.from_name = cObj.optString("from_name");
                        c.from_title = cObj.optString("from_title");
                        c.from_avatar = cObj.optString("from_avatar");
                        c.body = cObj.optString("body");
                        c.created_at = cObj.optLong("created_at");
                        comments.add(c);
                    }
                    java.util.Collections.sort(comments, new java.util.Comparator<MomentComment>() {
                        @Override
                        public int compare(MomentComment a, MomentComment b) {
                            if (a.created_at < b.created_at) {
                                return -1;
                            }
                            if (a.created_at > b.created_at) {
                                return 1;
                            }
                            return 0;
                        }
                    });
                    MomentCache.saveComments(MomentCommentsActivity.this, momentId, comments);
                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    Toast.makeText(MomentCommentsActivity.this, "加载评论失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(MomentCommentsActivity.this, "加载评论失败: " + code, Toast.LENGTH_SHORT).show();
                loadCachedComments();
            }
        });
    }

    private void loadCachedComments() {
        List<MomentComment> cached = MomentCache.getComments(this, momentId);
        if (cached == null || cached.isEmpty()) {
            return;
        }
        comments.clear();
        comments.addAll(cached);
        adapter.notifyDataSetChanged();
    }

    private void sendComment() {
        String body = etComment.getText().toString().trim();
        if (body.isEmpty()) {
            Toast.makeText(this, "请输入评论内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (momentId == null || momentId.isEmpty()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("moment_id", momentId);
            json.put("body", body);
            HttpUtil.post("/moments/comment", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        MomentComment c = new MomentComment();
                        c.id = obj.optString("id");
                        c.moment_id = obj.optString("moment_id");
                        c.from_uid = obj.optString("from_uid");
                        c.from_ncuid = obj.optString("from_ncuid");
                        c.from_name = obj.optString("from_name");
                        c.from_title = obj.optString("from_title");
                        c.from_avatar = obj.optString("from_avatar");
                        c.body = obj.optString("body");
                        c.created_at = obj.optLong("created_at");
                        comments.add(0, c);
                        adapter.notifyDataSetChanged();
                        etComment.setText("");
                    } catch (Exception e) {
                        Toast.makeText(MomentCommentsActivity.this, "发送失败", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MomentCommentsActivity.this, "发送失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean canDeleteComment(MomentComment comment) {
        if (comment == null || myUid == null || myUid.isEmpty()) {
            return false;
        }
        if (myUid.equals(comment.from_uid)) {
            return true;
        }
        return momentOwnerUid != null && myUid.equals(momentOwnerUid);
    }

    private void confirmDeleteComment(final MomentComment comment, final int position) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.moments_comment_delete_title)
                .setMessage(R.string.moments_comment_delete_confirm)
                .setPositiveButton(R.string.moments_delete_action, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteComment(comment, position);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void deleteComment(MomentComment comment, final int position) {
        if (comment == null || comment.id == null || comment.id.isEmpty()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("comment_id", comment.id);
            HttpUtil.post("/moments/comment/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (position >= 0 && position < comments.size()) {
                        comments.remove(position);
                        adapter.notifyDataSetChanged();
                        MomentCache.saveComments(MomentCommentsActivity.this, momentId, comments);
                    }
                    Toast.makeText(MomentCommentsActivity.this, R.string.moments_comment_delete_success, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MomentCommentsActivity.this, R.string.moments_comment_delete_failed, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, R.string.moments_comment_delete_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
