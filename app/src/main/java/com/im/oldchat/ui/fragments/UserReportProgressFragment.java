package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.ui.ChatTimeFormatter;
import com.im.oldchat.ui.ReportProgressAdapter;
import com.im.oldchat.ui.ReportProgressRow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class UserReportProgressFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private ReportProgressAdapter adapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_progress_list, container, false);
        swipeRefresh = (SwipeRefreshLayout) v.findViewById(R.id.swipeRefresh);
        recyclerView = (RecyclerView) v.findViewById(R.id.recyclerView);
        tvEmpty = (TextView) v.findViewById(R.id.tvEmpty);

        recyclerView.setLayoutManager(new com.im.oldchat.ui.widget.SafeLinearLayoutManager(getActivity()));
        adapter = new ReportProgressAdapter();
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                load();
            }
        });

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (getActivity() == null) {
            return;
        }
        swipeRefresh.setRefreshing(true);

        SharedPreferences prefs = getActivity().getSharedPreferences("auth", Context.MODE_PRIVATE);
        String token = prefs.getString("access_token", "");
        if (token == null || token.length() == 0) {
            swipeRefresh.setRefreshing(false);
            Toast.makeText(getActivity(), "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }

        HttpUtil.get("/reports/user?limit=50", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                final List<ReportProgressRow> rows = parse(response);
                if (getActivity() == null) {
                    return;
                }
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        swipeRefresh.setRefreshing(false);
                        adapter.setItems(rows);
                        updateEmpty(rows);
                    }
                });
            }

            @Override
            public void onError(int code, String error) {
                if (getActivity() == null) {
                    return;
                }
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        swipeRefresh.setRefreshing(false);
                        Toast.makeText(getActivity(), "加载失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void updateEmpty(List<ReportProgressRow> rows) {
        boolean empty = rows == null || rows.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setText("暂无用户举报");
    }

    private List<ReportProgressRow> parse(String response) {
        List<ReportProgressRow> out = new ArrayList<ReportProgressRow>();
        try {
            JSONObject obj = new JSONObject(response);
            JSONArray arr = obj.optJSONArray("reports");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                String targetUid = it.optString("target_uid", "");
                String reporterUid = it.optString("reporter_uid", "");
                String reason = it.optString("reason", "");
                String status = it.optString("status", "pending");
                String result = it.optString("result", "");
                long createdAt = it.optLong("created_at", 0);

                ReportProgressRow row = new ReportProgressRow();
                row.title = "举报用户: " + targetUid;
                row.body = reason;
                row.status = mapReportStatus(status);
                row.statusType = mapReportStatusType(status);
                String timeStr = createdAt > 0 ? ChatTimeFormatter.formatMonthDayHourMinute(createdAt * 1000L) : "";
                String meta = "时间: " + timeStr;
                if (reporterUid != null && reporterUid.length() > 0) {
                    meta = meta + "\n举报人: " + reporterUid;
                }
                if (result != null && result.length() > 0) {
                    meta = meta + "\n结果: " + result;
                }
                row.meta = meta;
                out.add(row);
            }
        } catch (Exception e) {
            // ignore
        }
        return out;
    }

    private String mapReportStatus(String status) {
        if ("handled".equals(status)) {
            return "已处理";
        }
        if ("rejected".equals(status)) {
            return "已驳回";
        }
        return "待处理";
    }

    private int mapReportStatusType(String status) {
        if ("handled".equals(status)) {
            return ReportProgressRow.STATUS_SUCCESS;
        }
        if ("rejected".equals(status)) {
            return ReportProgressRow.STATUS_WARNING;
        }
        return ReportProgressRow.STATUS_PENDING;
    }
}
