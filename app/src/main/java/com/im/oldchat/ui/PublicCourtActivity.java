package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PublicCourtActivity extends BaseActivity {
    private static final int LOCK_VOTE_COUNT = 10;
    private static final int PAGE_SIZE = 20;
    private static final int LOAD_MORE_THRESHOLD = 3;

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private CourtAdapter adapter;
    private String token;

    private boolean loading;
    private boolean loadingMore;
    private boolean hasMore = true;
    private long nextBefore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_public_court);

        View btnBack = findViewByIdCompat(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        swipeRefresh = findViewByIdCompat(R.id.swipeRefresh);
        recyclerView = findViewByIdCompat(R.id.recyclerView);
        tvEmpty = findViewByIdCompat(R.id.tvEmpty);

        recyclerView.setLayoutManager(new com.im.oldchat.ui.widget.SafeLinearLayoutManager(this));
        adapter = new CourtAdapter();
        adapter.updateFooterState(false, true);
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading || loadingMore || !hasMore) {
                    return;
                }
                RecyclerView.LayoutManager manager = rv.getLayoutManager();
                if (!(manager instanceof LinearLayoutManager)) {
                    return;
                }
                int total = adapter.getItemCount();
                if (total <= 1) {
                    return;
                }
                int lastVisible = ((LinearLayoutManager) manager).findLastVisibleItemPosition();
                if (lastVisible >= total - LOAD_MORE_THRESHOLD) {
                    loadMoreCases();
                }
            }
        });

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refreshCases();
            }
        });

        adapter.setListener(new CourtAdapter.Listener() {
            @Override
            public void onOpenDetail(CourtCaseItem item) {
                if (item == null || item.caseId == null || item.caseId.length() == 0) {
                    return;
                }
                Intent intent = new Intent(PublicCourtActivity.this, PublicCourtCaseDetailActivity.class);
                intent.putExtra("case_id", item.caseId);
                startActivity(intent);
            }

            @Override
            public void onStatement(final CourtCaseItem item) {
                if (item == null || item.caseId == null || item.caseId.length() == 0) {
                    return;
                }
                if (!item.canStatement) {
                    Toast.makeText(PublicCourtActivity.this, "你不是当事方，不能提交陈述", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!"open".equals(item.status)) {
                    Toast.makeText(PublicCourtActivity.this, "案件已锁定，无法再修改陈述", Toast.LENGTH_SHORT).show();
                    return;
                }
                View form = LayoutInflater.from(PublicCourtActivity.this).inflate(R.layout.dialog_public_court_statement, null);
                final EditText etReason = form.findViewById(R.id.etStatementReason);
                final EditText etEvidence = form.findViewById(R.id.etStatementEvidence);
                etReason.setText(item.statementReason == null ? "" : item.statementReason);
                etEvidence.setText(item.statementEvidence == null ? "" : item.statementEvidence);
                new AlertDialog.Builder(PublicCourtActivity.this)
                        .setTitle("提交陈述与证据")
                        .setView(form)
                        .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String reason = etReason.getText() == null ? "" : etReason.getText().toString().trim();
                                String evidence = etEvidence.getText() == null ? "" : etEvidence.getText().toString().trim();
                                submitStatement(item.caseId, reason, evidence);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        refreshCases();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCases();
    }

    private void refreshCases() {
        requestCases(true);
    }

    private void loadMoreCases() {
        requestCases(false);
    }

    private void requestCases(final boolean reset) {
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            if (reset) {
                swipeRefresh.setRefreshing(false);
            }
            return;
        }
        if (loading) {
            return;
        }
        if (!reset) {
            if (loadingMore || !hasMore) {
                return;
            }
            if (adapter.getCaseCount() <= 0 || nextBefore <= 0) {
                return;
            }
        }

        loading = true;
        loadingMore = !reset;
        if (reset) {
            hasMore = true;
            nextBefore = 0L;
            swipeRefresh.setRefreshing(true);
            adapter.updateFooterState(false, true);
        } else {
            adapter.updateFooterState(true, hasMore);
        }

        StringBuilder path = new StringBuilder("/public-court/cases?status=all&limit=").append(PAGE_SIZE);
        if (!reset && nextBefore > 0) {
            path.append("&before=").append(nextBefore);
        }

        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                final List<CourtCaseItem> items = parseCases(response);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        loadingMore = false;
                        if (reset) {
                            swipeRefresh.setRefreshing(false);
                            adapter.setItems(items);
                        } else {
                            adapter.appendItems(items);
                        }

                        if (items != null && !items.isEmpty()) {
                            CourtCaseItem last = items.get(items.size() - 1);
                            nextBefore = last.createdAt;
                        }
                        hasMore = items != null && items.size() >= PAGE_SIZE && nextBefore > 0;
                        adapter.updateFooterState(false, hasMore);

                        boolean empty = adapter.getCaseCount() <= 0;
                        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                    }
                });
            }

            @Override
            public void onError(int code, String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        loadingMore = false;
                        if (reset) {
                            swipeRefresh.setRefreshing(false);
                        }
                        adapter.updateFooterState(false, hasMore);
                        Toast.makeText(PublicCourtActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void submitStatement(String caseId, String reason, String evidence) {
        try {
            JSONObject json = new JSONObject();
            json.put("reason", reason == null ? "" : reason);
            json.put("evidence", evidence == null ? "" : evidence);
            HttpUtil.post("/public-court/cases/" + caseId + "/statement", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PublicCourtActivity.this, "陈述已保存", Toast.LENGTH_SHORT).show();
                            refreshCases();
                        }
                    });
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PublicCourtActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private List<CourtCaseItem> parseCases(String response) {
        List<CourtCaseItem> out = new ArrayList<CourtCaseItem>();
        try {
            JSONObject obj = new JSONObject(response);
            JSONArray arr = obj.optJSONArray("cases");
            if (arr == null) {
                return out;
            }
            SharedPreferences auth = getSharedPreferences("auth", Context.MODE_PRIVATE);
            String myUid = auth.getString("my_uid", "");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                CourtCaseItem row = new CourtCaseItem();
                row.caseId = it.optString("id", "");
                row.reporterUid = it.optString("reporter_uid", "");
                row.reporterName = it.optString("reporter_name", "");
                row.reporterAvatar = it.optString("reporter_avatar", "");
                row.defendantUid = it.optString("defendant_uid", "");
                row.defendantName = it.optString("defendant_name", "");
                row.defendantAvatar = it.optString("defendant_avatar", "");
                row.reportReason = it.optString("report_reason", "");
                row.reportEvidence = it.optString("report_evidence", "");
                row.defenseReason = it.optString("defense_reason", "");
                row.defenseEvidence = it.optString("defense_evidence", "");
                row.myVote = it.optString("my_vote", "");
                row.status = it.optString("status", "open");
                row.verdict = it.optString("verdict", "");
                row.banHours = it.optInt("ban_hours", 0);
                row.adminNote = it.optString("admin_note", "");
                row.banVoteCount = it.optInt("ban_vote_count", 0);
                row.keepVoteCount = it.optInt("keep_vote_count", 0);
                int totalVote = it.optInt("total_vote_count", 0);
                row.totalVoteCount = totalVote > 0 ? totalVote : (row.banVoteCount + row.keepVoteCount);
                long createdAt = it.optLong("created_at", 0);
                row.createdAt = createdAt;

                StringBuilder meta = new StringBuilder();
                if (createdAt > 0) {
                    meta.append("开庭: ").append(ChatTimeFormatter.formatMonthDayHourMinute(createdAt * 1000L));
                }
                if (row.myVote != null && row.myVote.length() > 0) {
                    if (meta.length() > 0) {
                        meta.append("  ·  ");
                    }
                    meta.append("我的票: ").append("ban".equals(row.myVote) ? "封禁" : "不封");
                }
                row.meta = meta.toString();

                boolean meReporter = myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(row.reporterUid);
                boolean meDefendant = myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(row.defendantUid);
                row.canStatement = meReporter || meDefendant || (row.myVote != null && row.myVote.length() > 0);
                if (meReporter) {
                    row.statementReason = row.reportReason;
                    row.statementEvidence = row.reportEvidence;
                } else if (meDefendant) {
                    row.statementReason = row.defenseReason;
                    row.statementEvidence = row.defenseEvidence;
                }
                out.add(row);
            }
        } catch (Exception e) {
            // ignore
        }
        return out;
    }

    private static String statusLabel(String status) {
        if ("pending_review".equals(status)) {
            return "待二审（已锁定）";
        }
        if ("closed".equals(status)) {
            return "已结案";
        }
        if ("withdrawn".equals(status)) {
            return "已撤销";
        }
        return "投票中";
    }

    private static class CourtCaseItem {
        String caseId;
        String reporterUid;
        String reporterName;
        String reporterAvatar;
        String defendantUid;
        String defendantName;
        String defendantAvatar;
        String reportReason;
        String reportEvidence;
        String defenseReason;
        String defenseEvidence;
        String myVote;
        String status;
        String verdict;
        int banHours;
        String adminNote;
        int banVoteCount;
        int keepVoteCount;
        int totalVoteCount;
        long createdAt;
        String meta;
        boolean canStatement;
        String statementReason;
        String statementEvidence;
    }

    private static class CourtAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_RULES = 1;
        private static final int VIEW_TYPE_CASE = 2;
        private static final int VIEW_TYPE_FOOTER = 3;

        interface Listener {
            void onOpenDetail(CourtCaseItem item);
            void onStatement(CourtCaseItem item);
        }

        private final List<CourtCaseItem> items = new ArrayList<CourtCaseItem>();
        private Listener listener;
        private boolean footerLoading;
        private boolean footerHasMore = true;

        void setItems(List<CourtCaseItem> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            notifyDataSetChanged();
        }

        void appendItems(List<CourtCaseItem> list) {
            if (list == null || list.isEmpty()) {
                return;
            }
            items.addAll(list);
            notifyDataSetChanged();
        }

        void updateFooterState(boolean loadingMore, boolean hasMore) {
            footerLoading = loadingMore;
            footerHasMore = hasMore;
            notifyDataSetChanged();
        }

        int getCaseCount() {
            return items.size();
        }

        private boolean showFooter() {
            return !items.isEmpty();
        }

        void setListener(Listener l) {
            listener = l;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_RULES;
            }
            if (showFooter() && position == getItemCount() - 1) {
                return VIEW_TYPE_FOOTER;
            }
            return VIEW_TYPE_CASE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_RULES) {
                View header = inflater.inflate(R.layout.item_public_court_rules_header, parent, false);
                return new RulesVH(header);
            }
            if (viewType == VIEW_TYPE_FOOTER) {
                View footer = inflater.inflate(R.layout.item_public_court_footer, parent, false);
                return new FooterVH(footer);
            }
            View v = inflater.inflate(R.layout.item_public_court_case, parent, false);
            return new CaseVH(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (getItemViewType(position) == VIEW_TYPE_RULES) {
                RulesVH rulesVH = (RulesVH) holder;
                rulesVH.tvRulesTitle.setText("审理规则");
                rulesVH.tvRulesBody.setText("1. 投票需提供证据（可上传截图）\n2. 达到 10 票自动锁定，进入待二审\n3. 锁定后仅可查看，等待管理员裁决");
                return;
            }
            if (getItemViewType(position) == VIEW_TYPE_FOOTER) {
                FooterVH footerVH = (FooterVH) holder;
                if (footerLoading) {
                    footerVH.tvFooter.setText("正在加载更多案件...");
                } else if (footerHasMore) {
                    footerVH.tvFooter.setText("上滑加载更多");
                } else {
                    footerVH.tvFooter.setText("没有更多案件了");
                }
                return;
            }


            final CourtCaseItem item = items.get(position - 1);
            CaseVH caseHolder = (CaseVH) holder;
            caseHolder.tvTitle.setText("案件 #" + safe(item.caseId));
            caseHolder.tvReporterName.setText(displayUserName(item.reporterName, item.reporterUid));
            caseHolder.tvDefendantName.setText(displayUserName(item.defendantName, item.defendantUid));
            ImageLoader.loadAvatar(caseHolder.ivReporterAvatar, item.reporterAvatar);
            ImageLoader.loadAvatar(caseHolder.ivDefendantAvatar, item.defendantAvatar);
            caseHolder.tvStatus.setText(statusLabel(item.status));
            applyStatusChipStyle(caseHolder.tvStatus, item.status);
            caseHolder.tvProgress.setText(buildProgressText(item));
            bindVoteBar(caseHolder, item);

            String reason = safeOrDefault(item.reportReason, "(未填写)");
            String evidence = summarizeEvidence(item.reportEvidence);
            String body = "举报理由\n" + reason + "\n\n证据摘要\n" + evidence;
            if ("pending_review".equals(item.status) || "closed".equals(item.status) || "withdrawn".equals(item.status)) {
                String verdictLabel = "ban".equals(item.verdict) ? "封禁" : ("keep".equals(item.verdict) ? "不封禁" : "待定");
                body = body + "\n\n阶段结果\n" + verdictLabel + ("ban".equals(item.verdict) ? (" " + item.banHours + "h") : "");
            }
            caseHolder.tvBody.setText(body);
            caseHolder.tvMeta.setText(buildMetaText(item));

            // 渲染富媒体（涉案音乐、图片、文件）
            PublicCourtMediaHelper.bindMediaViews(caseHolder.itemView.getContext(), caseHolder.layoutEvidenceMedia, item.reportReason + "\n" + item.reportEvidence);

            boolean voting = "open".equals(item.status);
            caseHolder.btnStatement.setVisibility(View.VISIBLE);
            if (!voting) {
                caseHolder.btnStatement.setEnabled(false);
                caseHolder.btnStatement.setText("withdrawn".equals(item.status) ? "已撤销" : "已锁定");
            } else if (item.canStatement) {
                caseHolder.btnStatement.setEnabled(true);
                caseHolder.btnStatement.setText("补充观点");
            } else {
                caseHolder.btnStatement.setEnabled(false);
                caseHolder.btnStatement.setText("去详情投票");
            }

            caseHolder.btnDetail.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onOpenDetail(item);
                    }
                }
            });
            caseHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onOpenDetail(item);
                    }
                }
            });
            caseHolder.btnStatement.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onStatement(item);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size() + 1 + (showFooter() ? 1 : 0);
        }

        static class RulesVH extends RecyclerView.ViewHolder {
            TextView tvRulesTitle;
            TextView tvRulesBody;

            RulesVH(View itemView) {
                super(itemView);
                tvRulesTitle = itemView.findViewById(R.id.tvRulesTitle);
                tvRulesBody = itemView.findViewById(R.id.tvRulesBody);
            }
        }

        static class FooterVH extends RecyclerView.ViewHolder {
            TextView tvFooter;

            FooterVH(View itemView) {
                super(itemView);
                tvFooter = itemView.findViewById(R.id.tvFooter);
            }
        }

        static class CaseVH extends RecyclerView.ViewHolder {
            TextView tvTitle;
            ImageView ivReporterAvatar;
            ImageView ivDefendantAvatar;
            TextView tvReporterName;
            TextView tvDefendantName;
            TextView tvStatus;
            TextView tvProgress;
            View viewVoteBanBar;
            View viewVoteKeepBar;
            TextView tvBody;
            LinearLayout layoutEvidenceMedia;
            TextView tvMeta;
            TextView btnDetail;
            TextView btnStatement;

            CaseVH(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvTitle);
                ivReporterAvatar = itemView.findViewById(R.id.ivReporterAvatar);
                ivDefendantAvatar = itemView.findViewById(R.id.ivDefendantAvatar);
                tvReporterName = itemView.findViewById(R.id.tvReporterName);
                tvDefendantName = itemView.findViewById(R.id.tvDefendantName);
                tvStatus = itemView.findViewById(R.id.tvStatus);
                tvProgress = itemView.findViewById(R.id.tvProgress);
                viewVoteBanBar = itemView.findViewById(R.id.viewVoteBanBar);
                viewVoteKeepBar = itemView.findViewById(R.id.viewVoteKeepBar);
                tvBody = itemView.findViewById(R.id.tvBody);
                layoutEvidenceMedia = itemView.findViewById(R.id.layoutEvidenceMedia);
                tvMeta = itemView.findViewById(R.id.tvMeta);
                btnDetail = itemView.findViewById(R.id.btnDetail);
                btnStatement = itemView.findViewById(R.id.btnStatement);
            }
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }

        private static String safeOrDefault(String value, String def) {
            if (value == null || value.length() == 0) {
                return def;
            }
            return value;
        }

        private static String displayUserName(String name, String uid) {
            if (name != null && name.length() > 0) {
                if (uid != null && uid.length() > 0) {
                    return name + " (" + uid + ")";
                }
                return name;
            }
            return uid == null || uid.length() == 0 ? "匿名用户" : uid;
        }

        /** 状态胶囊样式：与详情页一致，四种状态四种颜色。 */
        private static void applyStatusChipStyle(TextView tvStatus, String st) {
            if (tvStatus == null) {
                return;
            }
            android.content.res.Resources res = tvStatus.getContext().getResources();
            if ("open".equals(st)) {
                tvStatus.setBackgroundResource(R.drawable.bg_court_chip_open);
                tvStatus.setTextColor(res.getColor(R.color.court_chip_open_text));
            } else if ("pending_review".equals(st)) {
                tvStatus.setBackgroundResource(R.drawable.bg_court_chip_pending);
                tvStatus.setTextColor(res.getColor(R.color.court_chip_pending_text));
            } else if ("closed".equals(st)) {
                tvStatus.setBackgroundResource(R.drawable.bg_court_chip_closed);
                tvStatus.setTextColor(res.getColor(R.color.court_chip_closed_text));
            } else {
                tvStatus.setBackgroundResource(R.drawable.bg_court_chip_withdrawn);
                tvStatus.setTextColor(res.getColor(R.color.court_chip_withdrawn_text));
            }
        }

        /** 双色投票比例柱：封禁(红) / 不封禁(绿)，按票数比例分配权重。 */
        private static void bindVoteBar(CaseVH holder, CourtCaseItem item) {
            if (holder.viewVoteBanBar == null || holder.viewVoteKeepBar == null) {
                return;
            }
            int total = item.banVoteCount + item.keepVoteCount;
            if (total <= 0) {
                holder.viewVoteBanBar.setVisibility(View.GONE);
                holder.viewVoteKeepBar.setVisibility(View.GONE);
                return;
            }
            holder.viewVoteBanBar.setVisibility(View.VISIBLE);
            holder.viewVoteKeepBar.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams banLp = (LinearLayout.LayoutParams) holder.viewVoteBanBar.getLayoutParams();
            LinearLayout.LayoutParams keepLp = (LinearLayout.LayoutParams) holder.viewVoteKeepBar.getLayoutParams();
            banLp.weight = item.banVoteCount;
            keepLp.weight = item.keepVoteCount;
            holder.viewVoteBanBar.setLayoutParams(banLp);
            holder.viewVoteKeepBar.setLayoutParams(keepLp);
        }

        private static String buildProgressText(CourtCaseItem item) {
            if (item == null) {
                return "";
            }
            String prefix;
            if ("open".equals(item.status)) {
                prefix = "投票进度 " + item.totalVoteCount + "/" + LOCK_VOTE_COUNT;
            } else {
                prefix = "最终票数 " + item.totalVoteCount;
            }
            return prefix + "  ·  封禁 " + item.banVoteCount + "  ·  不封禁 " + item.keepVoteCount;
        }

        private static String buildMetaText(CourtCaseItem item) {
            if (item == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (item.meta != null && item.meta.length() > 0) {
                sb.append(item.meta);
            }
            if ("open".equals(item.status)) {
                if (sb.length() > 0) {
                    sb.append("  ·  ");
                }
                if (item.canStatement) {
                    sb.append("你可补充观点");
                } else {
                    sb.append("投票后可补充观点");
                }
            }
            return sb.toString();
        }

        private static String summarizeEvidence(String evidence) {
            if (evidence == null || evidence.trim().length() == 0) {
                return "(未提交)";
            }
            String[] lines = evidence.split("\\n");
            boolean hasImageUrl = false;
            for (String line : lines) {
                String value = line == null ? "" : line.trim();
                if (value.length() == 0) {
                    continue;
                }
                if (looksLikeImageUrl(value)) {
                    hasImageUrl = true;
                    continue;
                }
                return value;
            }
            if (hasImageUrl) {
                return "已上传截图证据";
            }
            return "(未提交)";
        }

        private static boolean looksLikeImageUrl(String text) {
            if (text == null) {
                return false;
            }
            String lower = text.toLowerCase(Locale.US);
            boolean http = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/");
            boolean imageLike = lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                    || lower.contains(".gif") || lower.contains(".webp") || lower.contains("/media/");
            return http && imageLike;
        }
    }
}
