package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.models.DailyCheckInWallComment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DailyCheckInWallCommentsActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";
    private final List<DailyCheckInWallComment> comments = new ArrayList<DailyCheckInWallComment>();
    private String token;
    private String postId;
    private DailyCheckInWallCommentAdapter adapter;
    private EditText etComment;
    private boolean commentChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_comments);
        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        postId = getIntent().getStringExtra("post_id");
        adapter = new DailyCheckInWallCommentAdapter(this, comments);
        ListView listView = findViewByIdCompat(R.id.lvComments);
        etComment = findViewByIdCompat(R.id.etComment);
        TextView title = findViewByIdCompat(R.id.tvCommentsTitle);
        if (title != null) {
            title.setText("签到墙评论");
        }
        if (listView != null) {
            listView.setAdapter(adapter);
        }
        setClick(R.id.btnCommentsBack, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        setClick(R.id.btnSendComment, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendComment();
            }
        });
        loadComments();
    }

    private void loadComments() {
        DailyCheckInWallActionHelper.requestComments(token, postId, new DailyCheckInWallActionHelper.SimpleCallback() {
            @Override
            public void onSuccess(String response) {
                bindComments(response, false);
            }

            @Override
            public void onFailed(int code, String error, boolean suppressed) {
                if (!suppressed) {
                    Toast.makeText(DailyCheckInWallCommentsActivity.this, "加载评论失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void sendComment() {
        String body = etComment == null || etComment.getText() == null ? "" : etComment.getText().toString().trim();
        if (body.length() == 0) {
            Toast.makeText(this, "请输入评论内容", Toast.LENGTH_SHORT).show();
            return;
        }
        DailyCheckInWallActionHelper.createComment(token, postId, body, new DailyCheckInWallActionHelper.SimpleCallback() {
            @Override
            public void onSuccess(String response) {
                if (etComment != null) {
                    etComment.setText("");
                }
                bindComments(response, true);
                commentChanged = true;
                loadComments();
            }

            @Override
            public void onFailed(int code, String error, boolean suppressed) {
                if (!suppressed) {
                    Toast.makeText(DailyCheckInWallCommentsActivity.this,
                            DailyCheckInWallSupport.resolveCommentError(code, error), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void bindComments(String response, boolean single) {
        try {
            JSONObject obj = new JSONObject(response);
            if (!single) {
                comments.clear();
                JSONArray arr = obj.optJSONArray("comments");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    appendComment(arr.optJSONObject(i));
                }
            } else {
                appendComment(obj.optJSONObject("comment"));
            }
            Collections.sort(comments, new Comparator<DailyCheckInWallComment>() {
                @Override
                public int compare(DailyCheckInWallComment a, DailyCheckInWallComment b) {
                    if (a.createdAt < b.createdAt) {
                        return -1;
                    }
                    if (a.createdAt > b.createdAt) {
                        return 1;
                    }
                    return 0;
                }
            });
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            Toast.makeText(this, "评论解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendComment(JSONObject obj) {
        DailyCheckInWallComment item = DailyCheckInWallSupport.parseComment(obj);
        if (item != null) {
            comments.add(item);
        }
    }

    private void setClick(int id, View.OnClickListener listener) {
        View view = findViewByIdCompat(id);
        if (view != null) {
            view.setOnClickListener(listener);
        }
    }

    @Override
    public void finish() {
        if (commentChanged) {
            Intent data = new Intent();
            data.putExtra("post_id", postId);
            data.putExtra("comment_count", comments.size());
            setResult(Activity.RESULT_OK, data);
        }
        super.finish();
    }
}
