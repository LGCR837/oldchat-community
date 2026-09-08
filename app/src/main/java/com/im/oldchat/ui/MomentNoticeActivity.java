package com.im.oldchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.data.MomentNoticeStore;
import com.im.oldchat.models.MomentNotice;
import java.util.ArrayList;
import java.util.List;

public class MomentNoticeActivity extends BaseActivity {
    private ListView listView;
    private TextView emptyView;
    private MomentNoticeAdapter adapter;
    private final List<MomentNotice> notices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_notices);

        listView = findViewByIdCompat(R.id.lvMomentNotices);
        emptyView = findViewByIdCompat(R.id.tvNoticeEmpty);
        View btnBack = findViewByIdCompat(R.id.btnNoticeBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        adapter = new MomentNoticeAdapter(this, notices);
        listView.setAdapter(adapter);
        if (emptyView != null) {
            listView.setEmptyView(emptyView);
        }
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= notices.size()) {
                    return;
                }
                MomentNotice notice = notices.get(position);
                if (notice == null || notice.momentId == null || notice.momentId.isEmpty()) {
                    return;
                }
                if ("comment".equals(notice.type)) {
                    Intent intent = new Intent(MomentNoticeActivity.this, MomentCommentsActivity.class);
                    intent.putExtra("moment_id", notice.momentId);
                    intent.putExtra("moment_owner_uid", notice.ownerUid);
                    startActivity(intent);
                } else if ("like".equals(notice.type)) {
                    Intent intent = new Intent(MomentNoticeActivity.this, MomentsActivity.class);
                    intent.putExtra("scroll_moment_id", notice.momentId);
                    startActivity(intent);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        notices.clear();
        notices.addAll(MomentNoticeStore.getNotices(this));
        MomentNoticeStore.clearNotices(this);
        adapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void updateEmpty() {
        boolean empty = notices.isEmpty();
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
    }
}
