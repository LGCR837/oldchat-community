package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
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
import com.im.oldchat.service.MusicPlaybackService;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PublicCourtCaseDetailActivity extends BaseActivity {
    private static final int LOCK_VOTE_COUNT = 10;
    private static final int REQ_PICK_EVIDENCE_IMAGE = 9101;
    private static final long MAX_EVIDENCE_IMAGE_BYTES = 1024L * 1024L;

    private TextView tvTitle;
    private TextView tvCaseId;
    private TextView tvStatus;
    private TextView tvOpenTime;
    private TextView tvParticipants;
    private TextView tvVoteSummary;
    private TextView tvStageHint;
    private TextView tvActionHint;
    private TextView tvReportReason;
    private TextView tvReportEvidence;
    private TextView tvDefenseReason;
    private TextView tvDefenseEvidence;
    private TextView tvAdminResult;
    private TextView tvAdminNote;
    private TextView tvReporterName;
    private TextView tvDefendantName;
    private ImageView ivReporterAvatar;
    private ImageView ivDefendantAvatar;
    private LinearLayout layoutReportEvidenceMedia;
    private LinearLayout layoutDefenseEvidenceMedia;
    private LinearLayout layoutStatements;
    private LinearLayout layoutDiscussions;
    private LinearLayout layoutMergedReports;
    private TextView tvMergedReportPage;
    private TextView btnMergedReportPrev;
    private TextView btnMergedReportNext;
    private TextView btnVoteBan;
    private TextView btnVoteKeep;
    private TextView btnStatement;
    private TextView btnRefresh;
    private TextView btnWithdraw;
    private TextView btnDiscussionSend;

    private String token;
    private String myUid;
    private String caseId;

    private String status;
    private String reporterUid;
    private String reporterName;
    private String reporterAvatar;
    private String defendantUid;
    private String defendantName;
    private String defendantAvatar;
    private String reportReason;
    private String reportEvidence;
    private String defenseReason;
    private String defenseEvidence;
    private String myVote;
    private int mergedReportPage = 1;
    private int mergedReportPageSize = 5;
    private int mergedReportTotal = 0;
    private final List<StatementItem> statements = new ArrayList<StatementItem>();
    private final List<DiscussionItem> discussions = new ArrayList<DiscussionItem>();
    private final List<MergedReportItem> mergedReports = new ArrayList<MergedReportItem>();
    private final List<String> pendingEvidenceUrls = new ArrayList<String>();
    private EditText etDiscussionInput;
    private EditText statementReasonInput;
    private EditText statementEvidenceInput;
    private TextView statementUploadStatus;
    private EditText activeEvidenceInput;
    private TextView activeEvidenceUploadStatus;
    private AlertDialog activeEvidenceDialog;
    private boolean canWithdraw = false;

    private TextView tvReporterUid;
    private TextView tvDefendantUid;
    private View layoutReporterBox;
    private View layoutDefendantBox;
    private View layoutMyVoteBadge;
    private TextView tvMyVoteBadge;
    private View layoutAdminVerdictCard;
    private View layoutMergedReportsCard;
    private TextView chipSupportReporter;
    private TextView chipSupportDefendant;
    private TextView chipLackEvidence;
    private TextView chipClearViolation;
    private TextView chipSuggestBan;
    private TextView chipSuggestKeep;

    private View layoutVoteBar;
    private View viewVoteBanBar;
    private View viewVoteKeepBar;

    private View layoutChatContextBox;
    private TextView tvChatContextTitle;
    private TextView btnHideChatContext;
    private TextView btnLoadEarlierContext;
    private LinearLayout layoutContextMessages;
    private TextView btnLoadLaterContext;

    private String currentGroupId;
    private String currentMsgId;
    private long currentMinSeq = -1;
    private long currentMaxSeq = -1;
    private boolean hasMoreBeforeContext = false;
    private boolean hasMoreAfterContext = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_public_court_case_detail);

        caseId = getIntent().getStringExtra("case_id");
        if (caseId == null) {
            caseId = "";
        }

        SharedPreferences auth = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = auth.getString("access_token", "");
        myUid = auth.getString("my_uid", "");

        View btnBack = findViewByIdCompat(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        tvTitle = findViewByIdCompat(R.id.tvTitle);
        tvCaseId = findViewByIdCompat(R.id.tvCaseId);
        tvStatus = findViewByIdCompat(R.id.tvStatus);
        tvOpenTime = findViewByIdCompat(R.id.tvOpenTime);
        tvParticipants = findViewByIdCompat(R.id.tvParticipants);
        tvVoteSummary = findViewByIdCompat(R.id.tvVoteSummary);
        tvStageHint = findViewByIdCompat(R.id.tvStageHint);
        tvActionHint = findViewByIdCompat(R.id.tvActionHint);
        tvReportReason = findViewByIdCompat(R.id.tvReportReason);
        tvReportEvidence = findViewByIdCompat(R.id.tvReportEvidence);
        layoutReportEvidenceMedia = findViewByIdCompat(R.id.layoutReportEvidenceMedia);
        tvDefenseReason = findViewByIdCompat(R.id.tvDefenseReason);
        tvDefenseEvidence = findViewByIdCompat(R.id.tvDefenseEvidence);
        layoutDefenseEvidenceMedia = findViewByIdCompat(R.id.layoutDefenseEvidenceMedia);
        tvAdminResult = findViewByIdCompat(R.id.tvAdminResult);
        tvAdminNote = findViewByIdCompat(R.id.tvAdminNote);
        tvReporterName = findViewByIdCompat(R.id.tvReporterName);
        tvDefendantName = findViewByIdCompat(R.id.tvDefendantName);
        tvReporterUid = findViewByIdCompat(R.id.tvReporterUid);
        tvDefendantUid = findViewByIdCompat(R.id.tvDefendantUid);
        layoutReporterBox = findViewByIdCompat(R.id.layoutReporterBox);
        layoutDefendantBox = findViewByIdCompat(R.id.layoutDefendantBox);
        ivReporterAvatar = findViewByIdCompat(R.id.ivReporterAvatar);
        ivDefendantAvatar = findViewByIdCompat(R.id.ivDefendantAvatar);
        layoutMyVoteBadge = findViewByIdCompat(R.id.layoutMyVoteBadge);
        tvMyVoteBadge = findViewByIdCompat(R.id.tvMyVoteBadge);
        layoutAdminVerdictCard = findViewByIdCompat(R.id.layoutAdminVerdictCard);
        layoutMergedReportsCard = findViewByIdCompat(R.id.layoutMergedReportsCard);

        chipSupportReporter = findViewByIdCompat(R.id.chipSupportReporter);
        chipSupportDefendant = findViewByIdCompat(R.id.chipSupportDefendant);
        chipLackEvidence = findViewByIdCompat(R.id.chipLackEvidence);
        chipClearViolation = findViewByIdCompat(R.id.chipClearViolation);
        chipSuggestBan = findViewByIdCompat(R.id.chipSuggestBan);
        chipSuggestKeep = findViewByIdCompat(R.id.chipSuggestKeep);

        setupChipClick(chipSupportReporter, "[支持原告]");
        setupChipClick(chipSupportDefendant, "[支持被告]");
        setupChipClick(chipLackEvidence, "[证据不足]");
        setupChipClick(chipClearViolation, "[事实清楚]");
        setupChipClick(chipSuggestBan, "[建议封禁]");
        setupChipClick(chipSuggestKeep, "[建议免罚]");

        layoutStatements = findViewByIdCompat(R.id.layoutStatements);
        layoutDiscussions = findViewByIdCompat(R.id.layoutDiscussions);
        layoutMergedReports = findViewByIdCompat(R.id.layoutMergedReports);
        tvMergedReportPage = findViewByIdCompat(R.id.tvMergedReportPage);
        btnMergedReportPrev = findViewByIdCompat(R.id.btnMergedReportPrev);
        btnMergedReportNext = findViewByIdCompat(R.id.btnMergedReportNext);
        btnVoteBan = findViewByIdCompat(R.id.btnVoteBan);
        btnVoteKeep = findViewByIdCompat(R.id.btnVoteKeep);
        btnStatement = findViewByIdCompat(R.id.btnStatement);
        btnRefresh = findViewByIdCompat(R.id.btnRefresh);
        btnWithdraw = findViewByIdCompat(R.id.btnWithdraw);
        etDiscussionInput = findViewByIdCompat(R.id.etDiscussionInput);
        btnDiscussionSend = findViewByIdCompat(R.id.btnDiscussionSend);

        layoutVoteBar = findViewByIdCompat(R.id.layoutVoteBar);
        viewVoteBanBar = findViewByIdCompat(R.id.viewVoteBanBar);
        viewVoteKeepBar = findViewByIdCompat(R.id.viewVoteKeepBar);

        layoutChatContextBox = findViewByIdCompat(R.id.layoutChatContextBox);
        tvChatContextTitle = findViewByIdCompat(R.id.tvChatContextTitle);
        btnHideChatContext = findViewByIdCompat(R.id.btnHideChatContext);
        btnLoadEarlierContext = findViewByIdCompat(R.id.btnLoadEarlierContext);
        layoutContextMessages = findViewByIdCompat(R.id.layoutContextMessages);
        btnLoadLaterContext = findViewByIdCompat(R.id.btnLoadLaterContext);

        if (btnHideChatContext != null) {
            btnHideChatContext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (layoutChatContextBox != null) {
                        layoutChatContextBox.setVisibility(View.GONE);
                    }
                }
            });
        }
        if (btnLoadEarlierContext != null) {
            btnLoadEarlierContext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!TextUtils.isEmpty(currentGroupId) && currentMinSeq > 0) {
                        loadChatContext(currentGroupId, currentMsgId, currentMinSeq, 0, true);
                    }
                }
            });
        }
        if (btnLoadLaterContext != null) {
            btnLoadLaterContext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!TextUtils.isEmpty(currentGroupId) && currentMaxSeq > 0) {
                        loadChatContext(currentGroupId, currentMsgId, 0, currentMaxSeq, false);
                    }
                }
            });
        }

        if (btnWithdraw != null) {
            btnWithdraw.setVisibility(View.GONE);
        }

        tvTitle.setText("案件详情");
        tvCaseId.setText(caseId);

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadCaseDetail();
            }
        });

        btnVoteBan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVoteDialog("ban");
            }
        });
        btnVoteKeep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVoteDialog("keep");
            }
        });
        btnStatement.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStatementDialog();
            }
        });
        if (btnWithdraw != null) {
            btnWithdraw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showWithdrawDialog();
                }
            });
        }
        if (btnDiscussionSend != null) {
            btnDiscussionSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    submitDiscussion();
                }
            });
        }
        if (btnMergedReportPrev != null) {
            btnMergedReportPrev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mergedReportPage > 1) {
                        mergedReportPage = mergedReportPage - 1;
                        loadCaseDetail();
                    }
                }
            });
        }
        if (btnMergedReportNext != null) {
            btnMergedReportNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int totalPages = 1;
                    if (mergedReportPageSize > 0) {
                        totalPages = (mergedReportTotal + mergedReportPageSize - 1) / mergedReportPageSize;
                    }
                    if (totalPages < 1) {
                        totalPages = 1;
                    }
                    if (mergedReportPage < totalPages) {
                        mergedReportPage = mergedReportPage + 1;
                        loadCaseDetail();
                    }
                }
            });
        }

        loadCaseDetail();
    }

    private void loadCaseDetail() {
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (caseId == null || caseId.length() == 0) {
            Toast.makeText(this, "案件ID无效", Toast.LENGTH_SHORT).show();
            return;
        }
        String detailPath = "/public-court/cases/" + caseId
                + "?report_page=" + mergedReportPage
                + "&report_page_size=" + mergedReportPageSize;
        HttpUtil.get(detailPath, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                parseAndBind(response);
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PublicCourtCaseDetailActivity.this, "加载案件详情失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void parseAndBind(String response) {
        try {
            JSONObject obj = new JSONObject(response);
            JSONObject item = obj.optJSONObject("case");
            if (item == null) {
                return;
            }

            reporterUid = item.optString("reporter_uid", "");
            reporterName = item.optString("reporter_name", "");
            reporterAvatar = item.optString("reporter_avatar", "");
            defendantUid = item.optString("defendant_uid", "");
            defendantName = item.optString("defendant_name", "");
            defendantAvatar = item.optString("defendant_avatar", "");
            reportReason = item.optString("report_reason", "");
            reportEvidence = item.optString("report_evidence", "");
            defenseReason = item.optString("defense_reason", "");
            defenseEvidence = item.optString("defense_evidence", "");
            myVote = item.optString("my_vote", "");
            status = item.optString("status", "open");

            final String verdict = item.optString("verdict", "");
            final int banHours = item.optInt("ban_hours", 0);
            final String adminNote = item.optString("admin_note", "");
            final int banVoteCount = item.optInt("ban_vote_count", 0);
            final int keepVoteCount = item.optInt("keep_vote_count", 0);
            int totalVoteCount = item.optInt("total_vote_count", 0);
            if (totalVoteCount <= 0) {
                totalVoteCount = banVoteCount + keepVoteCount;
            }
            final long createdAt = item.optLong("created_at", 0);
            final long closedAt = item.optLong("closed_at", 0);

            statements.clear();
            JSONArray arr = obj.optJSONArray("statements");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject st = arr.optJSONObject(i);
                    if (st == null) {
                        continue;
                    }
                    StatementItem row = new StatementItem();
                    row.userUid = st.optString("user_uid", "");
                    row.userName = st.optString("user_name", "");
                    row.userAvatar = st.optString("user_avatar", "");
                    row.role = st.optString("role", "");
                    row.reason = st.optString("reason", "");
                    row.evidence = st.optString("evidence", "");
                    row.updatedAt = st.optLong("updated_at", st.optLong("created_at", 0));
                    statements.add(row);
                }
            }

            discussions.clear();
            JSONArray discussArr = obj.optJSONArray("discussions");
            if (discussArr != null) {
                for (int i = 0; i < discussArr.length(); i++) {
                    JSONObject ds = discussArr.optJSONObject(i);
                    if (ds == null) {
                        continue;
                    }
                    DiscussionItem itemDiscussion = new DiscussionItem();
                    itemDiscussion.id = ds.optString("id", "");
                    itemDiscussion.userUid = ds.optString("user_uid", "");
                    itemDiscussion.userName = ds.optString("user_name", "");
                    itemDiscussion.userAvatar = ds.optString("user_avatar", "");
                    itemDiscussion.body = ds.optString("body", "");
                    itemDiscussion.createdAt = ds.optLong("created_at", 0);
                    discussions.add(itemDiscussion);
                }
            }

            mergedReports.clear();
            mergedReportTotal = obj.optInt("merged_report_total", 0);
            int serverReportPage = obj.optInt("merged_report_page", mergedReportPage);
            if (serverReportPage < 1) {
                serverReportPage = 1;
            }
            int serverReportPageSize = obj.optInt("merged_report_page_size", mergedReportPageSize);
            if (serverReportPageSize < 1) {
                serverReportPageSize = 5;
            }
            mergedReportPage = serverReportPage;
            mergedReportPageSize = serverReportPageSize;
            JSONArray mergedArr = obj.optJSONArray("merged_reports");
            if (mergedArr != null) {
                for (int i = 0; i < mergedArr.length(); i++) {
                    JSONObject mr = mergedArr.optJSONObject(i);
                    if (mr == null) {
                        continue;
                    }
                    MergedReportItem row = new MergedReportItem();
                    row.reportId = mr.optString("report_id", "");
                    row.reporterUid = mr.optString("reporter_uid", "");
                    row.reporterName = mr.optString("reporter_name", "");
                    row.reporterAvatar = mr.optString("reporter_avatar", "");
                    row.reason = mr.optString("reason", "");
                    row.createdAt = mr.optLong("created_at", 0);
                    mergedReports.add(row);
                }
            }
            if (mergedReportTotal <= 0) {
                mergedReportTotal = mergedReports.size();
            }

            final String myVoteReason = item.optString("my_vote_reason", "");
            final int finalTotalVoteCount = totalVoteCount;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvCaseId.setText("案件编号：" + caseId);
                    setupLongClickCopy(tvCaseId, caseId);
                    applyStatusBadgeStyle(tvStatus, status);
                    tvParticipants.setText("投票进度：" + finalTotalVoteCount + "/" + LOCK_VOTE_COUNT + "（满 10 票自动锁定）");
                    tvVoteSummary.setText("票数汇总：封禁 " + banVoteCount + " 票  ·  不封禁 " + keepVoteCount + " 票");
                    tvOpenTime.setText(createdAt > 0 ? ("开庭时间：" + ChatTimeFormatter.formatYearMonthDayHourMinute(createdAt * 1000L)) : "开庭时间：-");

                    int totalVotes = banVoteCount + keepVoteCount;
                    if (layoutVoteBar != null && viewVoteBanBar != null && viewVoteKeepBar != null) {
                        if (totalVotes <= 0) {
                            layoutVoteBar.setVisibility(View.GONE);
                        } else {
                            layoutVoteBar.setVisibility(View.VISIBLE);
                            LinearLayout.LayoutParams banLp = (LinearLayout.LayoutParams) viewVoteBanBar.getLayoutParams();
                            LinearLayout.LayoutParams keepLp = (LinearLayout.LayoutParams) viewVoteKeepBar.getLayoutParams();
                            banLp.weight = banVoteCount > 0 ? banVoteCount : 0.001f;
                            keepLp.weight = keepVoteCount > 0 ? keepVoteCount : 0.001f;
                            viewVoteBanBar.setLayoutParams(banLp);
                            viewVoteKeepBar.setLayoutParams(keepLp);
                        }
                    }

                    if (layoutMyVoteBadge != null && tvMyVoteBadge != null) {
                        if (!TextUtils.isEmpty(myVote)) {
                            layoutMyVoteBadge.setVisibility(View.VISIBLE);
                            String voteTag = "ban".equals(myVote) ? "[投了封禁]" : "[投了不封禁]";
                            String reasonSummary = TextUtils.isEmpty(myVoteReason) ? "未填写理由" : myVoteReason;
                            tvMyVoteBadge.setText("[陪审投票] 您已投票：" + voteTag + "\n理由说明：" + reasonSummary);
                        } else {
                            layoutMyVoteBadge.setVisibility(View.GONE);
                        }
                    }

                    tvReporterName.setText(displayUserName(reporterName, reporterUid));
                    tvDefendantName.setText(displayUserName(defendantName, defendantUid));
                    if (tvReporterUid != null) {
                        tvReporterUid.setText("UID: " + (TextUtils.isEmpty(reporterUid) ? "-" : reporterUid));
                    }
                    if (tvDefendantUid != null) {
                        tvDefendantUid.setText("UID: " + (TextUtils.isEmpty(defendantUid) ? "-" : defendantUid));
                    }
                    ImageLoader.loadAvatar(ivReporterAvatar, reporterAvatar);
                    ImageLoader.loadAvatar(ivDefendantAvatar, defendantAvatar);

                    if (layoutReporterBox != null) {
                        layoutReporterBox.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                openUserProfile(reporterUid);
                            }
                        });
                    }
                    if (layoutDefendantBox != null) {
                        layoutDefendantBox.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                openUserProfile(defendantUid);
                            }
                        });
                    }

                    if (mergedReportTotal > 1) {
                        tvReportReason.setText("该案件已叠加 " + mergedReportTotal + " 条举报，详见页面底部“叠加举报记录”分页列表。");
                        tvReportEvidence.setText("当前摘要：" + summarizeEvidence(reportEvidence));
                    } else {
                        tvReportReason.setText(safeOrDefault(reportReason, "(举报方未填写)"));
                        tvReportEvidence.setText(summarizeEvidence(reportEvidence));
                    }
                    setupLongClickCopy(tvReportReason, reportReason);
                    setupLongClickCopy(tvReportEvidence, reportEvidence);

                    PublicCourtMediaHelper.bindMediaViews(PublicCourtCaseDetailActivity.this, layoutReportEvidenceMedia, reportReason + "\n" + reportEvidence, new PublicCourtMediaHelper.OnChatContextClickListener() {
                        @Override
                        public void onOpenChatContext(String groupId, String msgId) {
                            currentMinSeq = -1;
                            currentMaxSeq = -1;
                            loadChatContext(groupId, msgId, 0, 0, false);
                        }
                    });

                    tvDefenseReason.setText(safeOrDefault(defenseReason, "(被举报方未提交观点)"));
                    tvDefenseEvidence.setText(summarizeEvidence(defenseEvidence));
                    setupLongClickCopy(tvDefenseReason, defenseReason);
                    setupLongClickCopy(tvDefenseEvidence, defenseEvidence);

                    PublicCourtMediaHelper.bindMediaViews(PublicCourtCaseDetailActivity.this, layoutDefenseEvidenceMedia, defenseReason + "\n" + defenseEvidence);

                    String resultText = "尚未二审裁决";
                    if ("ban".equals(verdict)) {
                        resultText = "终审裁决：[裁定违规] 封禁 " + banHours + " 小时";
                    } else if ("keep".equals(verdict)) {
                        resultText = "终审裁决：[裁定无罪] 维持保留不封禁";
                    }
                    if (closedAt > 0) {
                        resultText = resultText + "（结案于 " + ChatTimeFormatter.formatYearMonthDayHourMinute(closedAt * 1000L) + "）";
                    }
                    tvAdminResult.setText(resultText);
                    tvAdminNote.setText(safeOrDefault(adminNote, "(管理员尚未填写备注)"));
                    setupLongClickCopy(tvAdminNote, adminNote);

                    bindStatements();
                    bindDiscussions();
                    bindMergedReports();

                    if (layoutMergedReportsCard != null) {
                        layoutMergedReportsCard.setVisibility(mergedReportTotal > 0 ? View.VISIBLE : View.GONE);
                    }

                    boolean allowVote = "open".equals(status);
                    boolean meReporter = myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(reporterUid);
                    boolean meDefendant = myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(defendantUid);
                    canWithdraw = meReporter && ("open".equals(status) || "pending_review".equals(status));
                    bindActionState(allowVote, meReporter, meDefendant, finalTotalVoteCount);
                }
            });
        } catch (Exception e) {
            // ignore parse errors
        }
    }

    private void bindStatements() {
        if (layoutStatements == null) {
            return;
        }
        layoutStatements.removeAllViews();
        if (statements.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂无观点陈述");
            tv.setTextColor(getResources().getColor(R.color.color_text_secondary));
            tv.setTextSize(13f);
            layoutStatements.addView(tv);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final StatementItem item : statements) {
            View row = inflater.inflate(R.layout.item_public_court_statement, layoutStatements, false);
            ImageView ivAvatar = row.findViewById(R.id.ivAvatar);
            TextView tvName = row.findViewById(R.id.tvName);
            TextView tvRole = row.findViewById(R.id.tvRole);
            TextView tvReason = row.findViewById(R.id.tvReason);
            TextView tvEvidence = row.findViewById(R.id.tvEvidence);
            TextView tvUpdatedAt = row.findViewById(R.id.tvUpdatedAt);
            LinearLayout layoutEvidenceImages = row.findViewById(R.id.layoutEvidenceImages);

            tvName.setText(displayUserName(item.userName, item.userUid));
            tvRole.setText(roleLabel(item.role));
            applyRoleStyle(tvRole, item.role);
            tvReason.setText("观点：" + safeOrDefault(item.reason, "(未填写)"));
            tvEvidence.setText("证据说明：" + summarizeEvidence(item.evidence));
            setupLongClickCopy(tvReason, item.reason);
            setupLongClickCopy(tvEvidence, item.evidence);

            if (item.updatedAt > 0) {
                tvUpdatedAt.setText("更新于 " + ChatTimeFormatter.formatMonthDayHourMinute(item.updatedAt * 1000L));
            } else {
                tvUpdatedAt.setText("");
            }
            ImageLoader.loadAvatar(ivAvatar, item.userAvatar);

            View.OnClickListener userClick = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUserProfile(item.userUid);
                }
            };
            ivAvatar.setOnClickListener(userClick);
            tvName.setOnClickListener(userClick);

            bindEvidenceImages(layoutEvidenceImages, item.evidence);
            layoutStatements.addView(row);
        }
    }

    private void bindMergedReports() {
        if (layoutMergedReports == null) {
            return;
        }
        layoutMergedReports.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        if (mergedReports.isEmpty()) {
            TextView tv = new TextView(this);
            if (mergedReportTotal > 0) {
                tv.setText("本页暂无叠加举报记录");
            } else {
                tv.setText("暂无叠加举报记录");
            }
            tv.setTextColor(getResources().getColor(R.color.color_text_secondary));
            tv.setTextSize(13f);
            layoutMergedReports.addView(tv);
        } else {
            for (final MergedReportItem item : mergedReports) {
                View row = inflater.inflate(R.layout.item_public_court_merged_report, layoutMergedReports, false);
                ImageView ivAvatar = row.findViewById(R.id.ivReporterAvatar);
                TextView tvName = row.findViewById(R.id.tvReporterName);
                TextView tvCreatedAt = row.findViewById(R.id.tvCreatedAt);
                TextView tvReason = row.findViewById(R.id.tvReason);
                TextView tvReportId = row.findViewById(R.id.tvReportId);

                tvName.setText(displayUserName(item.reporterName, item.reporterUid));
                if (item.createdAt > 0) {
                    tvCreatedAt.setText(ChatTimeFormatter.formatMonthDayHourMinute(item.createdAt * 1000L));
                } else {
                    tvCreatedAt.setText("-");
                }
                tvReason.setText(safeOrDefault(item.reason, "(未填写)"));
                tvReportId.setText("举报ID：" + safeOrDefault(item.reportId, "-"));
                setupLongClickCopy(tvReason, item.reason);
                ImageLoader.loadAvatar(ivAvatar, item.reporterAvatar);

                View.OnClickListener userClick = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openUserProfile(item.reporterUid);
                    }
                };
                ivAvatar.setOnClickListener(userClick);
                tvName.setOnClickListener(userClick);

                layoutMergedReports.addView(row);
            }
        }

        int totalPages = 1;
        if (mergedReportPageSize > 0) {
            totalPages = (mergedReportTotal + mergedReportPageSize - 1) / mergedReportPageSize;
        }
        if (totalPages < 1) {
            totalPages = 1;
        }
        if (mergedReportPage > totalPages) {
            mergedReportPage = totalPages;
        }
        if (mergedReportPage < 1) {
            mergedReportPage = 1;
        }

        if (tvMergedReportPage != null) {
            if (mergedReportTotal <= 0) {
                tvMergedReportPage.setText("暂无叠加举报");
            } else {
                tvMergedReportPage.setText("第 " + mergedReportPage + "/" + totalPages + " 页 · 共 " + mergedReportTotal + " 条");
            }
        }
        if (btnMergedReportPrev != null) {
            boolean enabled = mergedReportPage > 1;
            btnMergedReportPrev.setEnabled(enabled);
            ViewCompat.setAlpha(btnMergedReportPrev, enabled ? 1f : 0.45f);
        }
        if (btnMergedReportNext != null) {
            boolean enabled = mergedReportPage < totalPages;
            btnMergedReportNext.setEnabled(enabled);
            ViewCompat.setAlpha(btnMergedReportNext, enabled ? 1f : 0.45f);
        }
    }

    private void bindEvidenceImages(LinearLayout container, String evidence) {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        List<String> urls = collectImageUrls(evidence);
        if (urls.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        for (final String url : urls) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(160));
            lp.bottomMargin = dp(6);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0x11000000);
            ImageLoader.load(iv, url);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ImagePreviewActivity.start(PublicCourtCaseDetailActivity.this, url);
                }
            });
            container.addView(iv);
        }
    }

    private void loadChatContext(final String groupId, final String msgId, final long beforeSeq, final long afterSeq, final boolean isPrepend) {
        if (TextUtils.isEmpty(groupId)) {
            return;
        }
        currentGroupId = groupId;
        if (!TextUtils.isEmpty(msgId)) {
            currentMsgId = msgId;
        }
        if (layoutChatContextBox != null) {
            layoutChatContextBox.setVisibility(View.VISIBLE);
        }
        if (tvChatContextTitle != null) {
            tvChatContextTitle.setText("[上下文] 涉案群聊记录 (群: " + groupId + ")");
        }

        StringBuilder url = new StringBuilder("/public-court/cases/").append(caseId).append("/chat-context?group_id=").append(groupId);
        if (!TextUtils.isEmpty(currentMsgId)) {
            url.append("&msg_id=").append(currentMsgId);
        }
        if (beforeSeq > 0) {
            url.append("&before_seq=").append(beforeSeq);
        }
        if (afterSeq > 0) {
            url.append("&after_seq=").append(afterSeq);
        }
        url.append("&limit=10");

        HttpUtil.get(url.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    if (!obj.optBoolean("ok", false)) {
                        final String errMsg = obj.optString("error", "加载群聊上下文失败");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(PublicCourtCaseDetailActivity.this, errMsg, Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }
                    final long minSeq = obj.optLong("min_seq", -1);
                    final long maxSeq = obj.optLong("max_seq", -1);
                    final boolean hasMoreBefore = obj.optBoolean("has_more_before", false);
                    final boolean hasMoreAfter = obj.optBoolean("has_more_after", false);
                    final JSONArray arr = obj.optJSONArray("messages");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (beforeSeq <= 0 && afterSeq <= 0 && layoutContextMessages != null) {
                                layoutContextMessages.removeAllViews();
                            }
                            if (minSeq > 0 && (currentMinSeq <= 0 || minSeq < currentMinSeq)) {
                                currentMinSeq = minSeq;
                            }
                            if (maxSeq > 0 && (currentMaxSeq <= 0 || maxSeq > currentMaxSeq)) {
                                currentMaxSeq = maxSeq;
                            }
                            hasMoreBeforeContext = hasMoreBefore;
                            hasMoreAfterContext = hasMoreAfter;

                            if (btnLoadEarlierContext != null) {
                                btnLoadEarlierContext.setVisibility(hasMoreBeforeContext ? View.VISIBLE : View.GONE);
                            }
                            if (btnLoadLaterContext != null) {
                                btnLoadLaterContext.setVisibility(hasMoreAfterContext ? View.VISIBLE : View.GONE);
                            }

                            if (arr != null && arr.length() > 0 && layoutContextMessages != null) {
                                LayoutInflater inflater = LayoutInflater.from(PublicCourtCaseDetailActivity.this);
                                List<View> newViews = new ArrayList<View>();
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject msgObj = arr.optJSONObject(i);
                                    if (msgObj == null) {
                                        continue;
                                    }
                                    View msgView = buildContextMessageItemView(inflater, msgObj);
                                    newViews.add(msgView);
                                }
                                if (isPrepend) {
                                    for (int i = 0; i < newViews.size(); i++) {
                                        layoutContextMessages.addView(newViews.get(i), i);
                                    }
                                } else {
                                    for (int i = 0; i < newViews.size(); i++) {
                                        layoutContextMessages.addView(newViews.get(i));
                                    }
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void onError(int code, String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PublicCourtCaseDetailActivity.this, "加载群聊上下文网络错误", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private View buildContextMessageItemView(LayoutInflater inflater, JSONObject msgObj) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        final boolean isTarget = msgObj.optBoolean("is_target", false);
        if (isTarget) {
            row.setBackgroundResource(R.drawable.flat_button_danger_bg);
        } else {
            row.setBackgroundResource(R.drawable.bg_profile_item);
        }

        // Header: Sender + Time + Target Tag
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvSender = new TextView(this);
        String name = msgObj.optString("sender_name", "");
        String uid = msgObj.optString("sender_uid", "");
        if (TextUtils.isEmpty(name)) {
            name = uid;
        }
        tvSender.setText(name + (!TextUtils.isEmpty(uid) ? (" (" + uid + ")") : ""));
        tvSender.setTextColor(isTarget ? Color.WHITE : getResources().getColor(R.color.color_text_primary));
        tvSender.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSender.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams senderLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvSender.setLayoutParams(senderLp);
        header.addView(tvSender);

        if (isTarget) {
            TextView tvTargetBadge = new TextView(this);
            tvTargetBadge.setText("[涉案举报消息]");
            tvTargetBadge.setTextColor(Color.YELLOW);
            tvTargetBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvTargetBadge.setTypeface(Typeface.DEFAULT_BOLD);
            tvTargetBadge.setPadding(0, 0, dp(6), 0);
            header.addView(tvTargetBadge);
        }

        TextView tvTime = new TextView(this);
        tvTime.setText(msgObj.optString("created_at", ""));
        tvTime.setTextColor(isTarget ? 0xccffffff : getResources().getColor(R.color.color_text_secondary));
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        header.addView(tvTime);

        row.addView(header);

        // Quote bubble if present
        JSONObject quote = msgObj.optJSONObject("quote");
        if (quote != null) {
            LinearLayout qBox = new LinearLayout(this);
            qBox.setOrientation(LinearLayout.VERTICAL);
            qBox.setBackgroundColor(isTarget ? 0x33000000 : 0x15000000);
            int qPad = dp(6);
            qBox.setPadding(qPad, qPad, qPad, qPad);
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            qLp.topMargin = dp(4);
            qBox.setLayoutParams(qLp);

            String qUser = quote.optString("from_name", quote.optString("from_uid", ""));
            String qText = quote.optString("text", quote.optString("body", ""));
            TextView tvQ = new TextView(this);
            tvQ.setText("[引用 @" + qUser + "] " + qText);
            tvQ.setTextColor(isTarget ? 0xeeffffff : getResources().getColor(R.color.color_text_secondary));
            tvQ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            qBox.addView(tvQ);
            row.addView(qBox);
        }

        // Body text
        String text = msgObj.optString("text", msgObj.optString("body", ""));
        if (!TextUtils.isEmpty(text)) {
            TextView tvText = new TextView(this);
            tvText.setText(text);
            tvText.setTextColor(isTarget ? Color.WHITE : getResources().getColor(R.color.color_text_primary));
            tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvText.setPadding(0, dp(4), 0, 0);
            row.addView(tvText);
        }

        // Image / Media
        final String mediaUrl = msgObj.optString("media_url", "");
        final String thumbUrl = msgObj.optString("thumb_url", "");
        final String displayImg = !TextUtils.isEmpty(thumbUrl) ? thumbUrl : mediaUrl;
        if (!TextUtils.isEmpty(displayImg) && displayImg.matches("(?i).*\\.(jpg|jpeg|png|gif|webp).*")) {
            ImageView iv = new ImageView(this);
            int imgSize = dp(120);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
            imgLp.topMargin = dp(4);
            iv.setLayoutParams(imgLp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0x22000000);
            ImageLoader.load(iv, displayImg);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ImagePreviewActivity.start(PublicCourtCaseDetailActivity.this, !TextUtils.isEmpty(mediaUrl) ? mediaUrl : displayImg);
                }
            });
            row.addView(iv);
        }

        return row;
    }

    private int dp(int val) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (d * val + 0.5f);
    }

    private String getStatusLabel(String st) {
        if ("pending_review".equals(st)) {
            return "待二审";
        }
        if ("closed".equals(st)) {
            return "已结案";
        }
        if ("withdrawn".equals(st)) {
            return "已撤销";
        }
        return "投票中";
    }

    private void bindActionState(boolean allowVote, boolean meReporter, boolean meDefendant, int totalVoteCount) {
        boolean voted = hasMyVote();
        boolean canStatementNow = allowVote && (meReporter || meDefendant || voted);

        if (allowVote) {
            btnVoteBan.setEnabled(true);
            btnVoteKeep.setEnabled(true);
            btnVoteBan.setText("投封禁");
            btnVoteKeep.setText("投不封");
        } else {
            btnVoteBan.setEnabled(false);
            btnVoteKeep.setEnabled(false);
            if ("closed".equals(status)) {
                btnVoteBan.setText("已结案");
                btnVoteKeep.setText("已结案");
            } else if ("withdrawn".equals(status)) {
                btnVoteBan.setText("已撤销");
                btnVoteKeep.setText("已撤销");
            } else {
                btnVoteBan.setText("已锁定");
                btnVoteKeep.setText("待二审");
            }
        }

        btnStatement.setVisibility(View.VISIBLE);
        if (canStatementNow) {
            btnStatement.setEnabled(true);
            btnStatement.setText("补充观点");
        } else {
            btnStatement.setEnabled(false);
            if (!allowVote) {
                btnStatement.setText("已锁定");
            } else {
                btnStatement.setText("先投票后可补充");
            }
        }

        if (btnWithdraw != null) {
            if (canWithdraw) {
                btnWithdraw.setVisibility(View.VISIBLE);
                btnWithdraw.setEnabled(true);
            } else {
                btnWithdraw.setVisibility(View.GONE);
                btnWithdraw.setEnabled(false);
            }
        }

        boolean canDiscuss = !"closed".equals(status) && !"withdrawn".equals(status);
        if (btnDiscussionSend != null) {
            btnDiscussionSend.setEnabled(canDiscuss);
            btnDiscussionSend.setText(canDiscuss ? "发送" : "已关闭");
        }
        if (etDiscussionInput != null) {
            etDiscussionInput.setEnabled(canDiscuss);
        }

        if (tvStageHint != null) {
            if (allowVote) {
                if (totalVoteCount >= LOCK_VOTE_COUNT - 2) {
                    tvStageHint.setText("当前接近锁定票数，请尽快补充有效证据。\n锁定后将进入管理员二审。");
                } else {
                    tvStageHint.setText("当前为公开投票阶段。\n达到 10 票后自动锁定并转入二审。");
                }
            } else if ("withdrawn".equals(status)) {
                tvStageHint.setText("本案已由发起者撤销举报，案件结束。");
            } else if ("closed".equals(status)) {
                tvStageHint.setText("本案已结案，可查看最终裁决与历史观点。");
            } else {
                tvStageHint.setText("本案已锁定，当前只可查看，等待管理员二审。");
            }
        }

        if (tvActionHint != null) {
            if ("withdrawn".equals(status)) {
                tvActionHint.setText("本案已撤销，仅可查看历史观点和讨论。");
            } else if (!allowVote) {
                tvActionHint.setText("当前阶段不可再投票或补充观点。可点击刷新查看最新进度。");
            } else if (meReporter) {
                tvActionHint.setText("你是举报方：可投票、补充观点，也可撤销举报。\n撤销后案件将结束。");
            } else if (meDefendant) {
                tvActionHint.setText("你是被举报方：可投票，也可持续补充观点和证据。");
            } else if (voted) {
                tvActionHint.setText("你已参与投票：可继续补充观点和证据。\n也可在下方参与讨论。");
            } else {
                tvActionHint.setText("你尚未投票：先投票后才可补充观点。\n投票时需提交证据。你也可以先参与讨论。");
            }
        }
    }

    private void setupChipClick(TextView chip, final String tagText) {
        if (chip == null) {
            return;
        }
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (etDiscussionInput != null) {
                    String current = etDiscussionInput.getText() != null ? etDiscussionInput.getText().toString() : "";
                    if (current.isEmpty()) {
                        etDiscussionInput.setText(tagText + " ");
                    } else {
                        etDiscussionInput.setText(current + " " + tagText + " ");
                    }
                    etDiscussionInput.setSelection(etDiscussionInput.getText().length());
                }
            }
        });
    }

    private void setupLongClickCopy(View view, final String text) {
        if (view == null) {
            return;
        }
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!TextUtils.isEmpty(text)) {
                    com.im.oldchat.util.ClipboardUtil.copyText(PublicCourtCaseDetailActivity.this, text);
                    return true;
                }
                return false;
            }
        });
    }

    private void openUserProfile(String uid) {
        if (TextUtils.isEmpty(uid)) {
            return;
        }
        Intent intent = new Intent(this, UserSpaceActivity.class);
        intent.putExtra("uid", uid);
        startActivity(intent);
    }

    private void applyStatusBadgeStyle(TextView tvStatus, String st) {
        if (tvStatus == null) {
            return;
        }
        tvStatus.setText(getStatusLabel(st));
        if ("open".equals(st)) {
            tvStatus.setBackgroundResource(R.drawable.bg_chip_warning);
            tvStatus.setTextColor(getResources().getColor(R.color.color_text_primary));
        } else if ("pending_review".equals(st)) {
            tvStatus.setBackgroundResource(R.drawable.bg_chip_pending);
            tvStatus.setTextColor(Color.WHITE);
        } else if ("closed".equals(st)) {
            tvStatus.setBackgroundResource(R.drawable.bg_chip_success);
            tvStatus.setTextColor(Color.WHITE);
        } else {
            tvStatus.setBackgroundResource(R.drawable.flat_button_bg);
            tvStatus.setTextColor(getResources().getColor(R.color.color_text_secondary));
        }
    }

    private void applyRoleStyle(TextView tvRole, String role) {
        if (tvRole == null) {
            return;
        }
        if ("reporter".equals(role)) {
            tvRole.setBackgroundResource(R.drawable.bg_chip_warning);
            tvRole.setTextColor(getResources().getColor(R.color.color_text_primary));
            return;
        }
        if ("defendant".equals(role)) {
            tvRole.setBackgroundResource(R.drawable.bg_chip_pending);
            tvRole.setTextColor(getResources().getColor(R.color.color_on_primary));
            return;
        }
        if ("jury".equals(role)) {
            tvRole.setBackgroundResource(R.drawable.bg_chip_success);
            tvRole.setTextColor(getResources().getColor(R.color.color_on_primary));
            return;
        }
        tvRole.setBackgroundResource(R.drawable.flat_button_bg);
        tvRole.setTextColor(getResources().getColor(R.color.color_text_secondary));
    }

    private String summarizeEvidence(String evidence) {
        if (TextUtils.isEmpty(evidence)) {
            return "(未填写)";
        }
        String[] lines = evidence.split("\\n");
        ArrayList<String> desc = new ArrayList<String>();
        int imageCount = 0;
        for (String line : lines) {
            String value = line == null ? "" : line.trim();
            if (value.length() == 0) {
                continue;
            }
            if (looksLikeImageUrlLine(value)) {
                imageCount++;
                continue;
            }
            desc.add(value);
        }
        StringBuilder sb = new StringBuilder();
        if (!desc.isEmpty()) {
            for (int i = 0; i < desc.size(); i++) {
                if (i > 0) {
                    sb.append("；");
                }
                sb.append(desc.get(i));
            }
        }
        if (imageCount > 0) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("已上传截图").append(imageCount).append("张");
        }
        if (sb.length() == 0) {
            return "(未填写)";
        }
        return sb.toString();
    }

    private boolean looksLikeImageUrlLine(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        boolean http = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/");
        boolean imageLike = lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                || lower.contains(".gif") || lower.contains(".webp") || lower.contains("/media/");
        return http && imageLike;
    }

    private void bindDiscussions() {
        if (layoutDiscussions == null) {
            return;
        }
        layoutDiscussions.removeAllViews();
        if (discussions.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂无讨论，欢迎补充观点");
            tv.setTextColor(getResources().getColor(R.color.color_text_secondary));
            tv.setTextSize(13f);
            layoutDiscussions.addView(tv);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final DiscussionItem item : discussions) {
            View row = inflater.inflate(R.layout.item_public_court_discussion, layoutDiscussions, false);
            ImageView ivAvatar = row.findViewById(R.id.ivAvatar);
            TextView tvName = row.findViewById(R.id.tvName);
            TextView tvBody = row.findViewById(R.id.tvBody);
            TextView tvCreatedAt = row.findViewById(R.id.tvCreatedAt);
            tvName.setText(displayUserName(item.userName, item.userUid));
            tvBody.setText(safeOrDefault(item.body, "(未填写)"));
            setupLongClickCopy(tvBody, item.body);
            if (item.createdAt > 0) {
                tvCreatedAt.setText(ChatTimeFormatter.formatMonthDayHourMinute(item.createdAt * 1000L));
            } else {
                tvCreatedAt.setText("-");
            }
            ImageLoader.loadAvatar(ivAvatar, item.userAvatar);

            View.OnClickListener userClick = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUserProfile(item.userUid);
                }
            };
            ivAvatar.setOnClickListener(userClick);
            tvName.setOnClickListener(userClick);

            layoutDiscussions.addView(row);
        }
    }

    private void submitDiscussion() {
        if ("closed".equals(status) || "withdrawn".equals(status)) {
            Toast.makeText(this, "案件已结束，不能继续讨论", Toast.LENGTH_SHORT).show();
            return;
        }
        if (etDiscussionInput == null) {
            return;
        }
        String body = etDiscussionInput.getText() == null ? "" : etDiscussionInput.getText().toString().trim();
        if (TextUtils.isEmpty(body)) {
            Toast.makeText(this, "请输入讨论内容", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject req = new JSONObject();
            req.put("body", body);
            HttpUtil.post("/public-court/cases/" + caseId + "/discussion", req, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (etDiscussionInput != null) {
                                etDiscussionInput.setText("");
                            }
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "讨论已发送", Toast.LENGTH_SHORT).show();
                            loadCaseDetail();
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
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "发送讨论失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "发送讨论失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showWithdrawDialog() {
        if (!canWithdraw) {
            Toast.makeText(this, "仅举报发起者可撤销", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText et = new EditText(this);
        et.setHint("可选：填写撤销原因");
        et.setMinLines(2);
        et.setMaxLines(4);
        new AlertDialog.Builder(this)
                .setTitle("撤销举报")
                .setMessage("确认撤销该公开法庭案件？撤销后案件将结束。")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认撤销", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String reason = et.getText() == null ? "" : et.getText().toString().trim();
                        submitWithdraw(reason);
                    }
                })
                .show();
    }

    private void submitWithdraw(String reason) {
        try {
            JSONObject req = new JSONObject();
            req.put("reason", reason == null ? "" : reason);
            HttpUtil.post("/public-court/cases/" + caseId + "/withdraw", req, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "已撤销举报", Toast.LENGTH_SHORT).show();
                            loadCaseDetail();
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
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "撤销失败", Toast.LENGTH_SHORT).show();
                            if (code == 409 || code == 403) {
                                loadCaseDetail();
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "撤销失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showVoteDialog(final String vote) {
        if (!"open".equals(status)) {
            Toast.makeText(this, "案件已锁定，不能继续投票", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingEvidenceUrls.clear();

        View form = LayoutInflater.from(this).inflate(R.layout.dialog_public_court_statement, null);
        final EditText etReason = form.findViewById(R.id.etStatementReason);
        final EditText etEvidence = form.findViewById(R.id.etStatementEvidence);
        final TextView tvHint = form.findViewById(R.id.tvStatementUploadHint);
        TextView btnPickEvidence = form.findViewById(R.id.btnPickEvidence);
        TextView tvUploadStatus = form.findViewById(R.id.tvEvidenceUploadStatus);

        if (tvHint != null) {
            tvHint.setText("投票必须提交证据，可上传截图");
        }
        if (etReason != null) {
            etReason.setHint("投票理由（选填）");
            etReason.setMinLines(2);
        }
        if (etEvidence != null) {
            etEvidence.setHint("证据（必填，可写描述或上传截图链接）");
        }

        activeEvidenceInput = etEvidence;
        activeEvidenceUploadStatus = tvUploadStatus;
        refreshEvidenceUploadStatus();

        if (btnPickEvidence != null) {
            btnPickEvidence.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickEvidenceImage();
                }
            });
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("投票" + ("ban".equals(vote) ? "封禁" : "不封禁"))
                .setView(form)
                .setPositiveButton("提交", null)
                .setNegativeButton("取消", null)
                .create();
        activeEvidenceDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                if (activeEvidenceDialog == dialog) {
                    activeEvidenceDialog = null;
                    activeEvidenceInput = null;
                    activeEvidenceUploadStatus = null;
                }
                pendingEvidenceUrls.clear();
            }
        });
        dialog.show();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String reason = etReason == null || etReason.getText() == null
                            ? "" : etReason.getText().toString().trim();
                    String evidenceText = etEvidence == null || etEvidence.getText() == null
                            ? "" : etEvidence.getText().toString().trim();
                    String mergedEvidence = mergeEvidence(evidenceText, pendingEvidenceUrls);
                    if (TextUtils.isEmpty(mergedEvidence)) {
                        Toast.makeText(PublicCourtCaseDetailActivity.this,
                                "请先填写证据或上传截图", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    submitVote(vote, reason, mergedEvidence);
                    dialog.dismiss();
                }
            });
        }
    }

    private void submitVote(String vote, String reason, String evidence) {
        try {
            JSONObject body = new JSONObject();
            body.put("vote", vote);
            body.put("reason", reason == null ? "" : reason);
            body.put("evidence", evidence == null ? "" : evidence);
            HttpUtil.post("/public-court/cases/" + caseId + "/vote", body, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "投票成功", Toast.LENGTH_SHORT).show();
                            loadCaseDetail();
                        }
                    });
                }

                @Override
                public void onError(final int code, final String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PublicCourtCaseDetailActivity.this,
                                    buildVoteErrorMessage(code, error), Toast.LENGTH_SHORT).show();
                            if (code == 409) {
                                loadCaseDetail();
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "投票失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showStatementDialog() {
        if (!"open".equals(status)) {
            Toast.makeText(this, "案件已锁定，不能修改陈述", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean meReporter = myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(reporterUid);
        boolean meDefendant = myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(defendantUid);
        if (!(meReporter || meDefendant || hasMyVote())) {
            Toast.makeText(this, "请先参与投票后再提交观点", Toast.LENGTH_SHORT).show();
            return;
        }

        View form = LayoutInflater.from(this).inflate(R.layout.dialog_public_court_statement, null);
        statementReasonInput = form.findViewById(R.id.etStatementReason);
        statementEvidenceInput = form.findViewById(R.id.etStatementEvidence);
        TextView btnPickEvidence = form.findViewById(R.id.btnPickEvidence);
        statementUploadStatus = form.findViewById(R.id.tvEvidenceUploadStatus);

        StatementItem mine = findMyStatement();
        if (mine != null) {
            statementReasonInput.setText(mine.reason == null ? "" : mine.reason);
            statementEvidenceInput.setText(mine.evidence == null ? "" : mine.evidence);
        } else {
            if (meReporter) {
                statementReasonInput.setText(reportReason == null ? "" : reportReason);
                statementEvidenceInput.setText(reportEvidence == null ? "" : reportEvidence);
            } else {
                if (meDefendant) {
                    statementReasonInput.setText(defenseReason == null ? "" : defenseReason);
                    statementEvidenceInput.setText(defenseEvidence == null ? "" : defenseEvidence);
                }
            }
        }
        pendingEvidenceUrls.clear();
        activeEvidenceInput = statementEvidenceInput;
        activeEvidenceUploadStatus = statementUploadStatus;
        refreshEvidenceUploadStatus();

        btnPickEvidence.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickEvidenceImage();
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("提交观点与证据")
                .setView(form)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String reason = statementReasonInput.getText() == null ? "" : statementReasonInput.getText().toString().trim();
                        String evidence = statementEvidenceInput.getText() == null ? "" : statementEvidenceInput.getText().toString().trim();
                        String mergedEvidence = mergeEvidence(evidence, pendingEvidenceUrls);
                        submitStatement(reason, mergedEvidence);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        activeEvidenceDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                if (activeEvidenceDialog == dialog) {
                    activeEvidenceDialog = null;
                    activeEvidenceInput = null;
                    activeEvidenceUploadStatus = null;
                }
                pendingEvidenceUrls.clear();
            }
        });
        dialog.show();
    }

    private void pickEvidenceImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择截图证据"), REQ_PICK_EVIDENCE_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK_EVIDENCE_IMAGE) {
            Uri uri = data.getData();
            if (uri == null) {
                Toast.makeText(this, "未选择图片", Toast.LENGTH_SHORT).show();
                return;
            }
            uploadEvidenceImage(uri);
        }
    }

    private void uploadEvidenceImage(Uri uri) {
        if (uri == null) {
            return;
        }
        final byte[] data = readUriBytesSafe(uri, MAX_EVIDENCE_IMAGE_BYTES);
        if (data == null || data.length == 0) {
            Toast.makeText(this, "截图读取失败或超过1MB", Toast.LENGTH_SHORT).show();
            return;
        }
        final String fileName = safeFileName(uri, "court_evidence.jpg");
        final String mime = resolveImageMime(uri, fileName);
        if (activeEvidenceUploadStatus != null) {
            activeEvidenceUploadStatus.setText("上传中...");
        }
        HttpUtil.postMultipart("/media", data, fileName, mime, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    String url = obj.optString("url", "");
                    if (TextUtils.isEmpty(url)) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(PublicCourtCaseDetailActivity.this, "上传失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }
                    pendingEvidenceUrls.add(url);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refreshEvidenceUploadStatus();
                            String current = activeEvidenceInput != null && activeEvidenceInput.getText() != null
                                    ? activeEvidenceInput.getText().toString().trim() : "";
                            String merged = mergeEvidence(current, pendingEvidenceUrls);
                            if (activeEvidenceInput != null) {
                                activeEvidenceInput.setText(merged);
                                activeEvidenceInput.setSelection(merged.length());
                            }
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "截图证据已上传", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "上传失败", Toast.LENGTH_SHORT).show();
                            refreshEvidenceUploadStatus();
                        }
                    });
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PublicCourtCaseDetailActivity.this, "上传失败", Toast.LENGTH_SHORT).show();
                        refreshEvidenceUploadStatus();
                    }
                });
            }
        });
    }

    private void refreshEvidenceUploadStatus() {
        if (activeEvidenceUploadStatus == null) {
            return;
        }
        if (pendingEvidenceUrls.isEmpty()) {
            activeEvidenceUploadStatus.setText("未上传截图");
        } else {
            activeEvidenceUploadStatus.setText("已上传截图：" + pendingEvidenceUrls.size() + " 张");
        }
    }

    private String mergeEvidence(String textEvidence, List<String> urls) {
        String text = textEvidence == null ? "" : textEvidence.trim();
        ArrayList<String> merged = new ArrayList<String>();
        if (!TextUtils.isEmpty(text)) {
            String[] lines = text.split("\\n");
            for (String line : lines) {
                String l = line == null ? "" : line.trim();
                if (l.length() > 0) {
                    merged.add(l);
                }
            }
        }
        if (urls != null) {
            for (String url : urls) {
                if (TextUtils.isEmpty(url)) {
                    continue;
                }
                if (!merged.contains(url)) {
                    merged.add(url);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(merged.get(i));
        }
        return sb.toString();
    }

    private List<String> collectImageUrls(String evidence) {
        ArrayList<String> out = new ArrayList<String>();
        if (TextUtils.isEmpty(evidence)) {
            return out;
        }
        String[] lines = evidence.split("\\n");
        for (String line : lines) {
            String value = line == null ? "" : line.trim();
            if (value.length() == 0) {
                continue;
            }
            String lower = value.toLowerCase(Locale.US);
            boolean http = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/");
            boolean imageLike = lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                    || lower.contains(".gif") || lower.contains(".webp") || lower.contains("/media/");
            if (http && imageLike) {
                out.add(value);
            }
        }
        return out;
    }

    private StatementItem findMyStatement() {
        if (myUid == null || myUid.length() == 0) {
            return null;
        }
        for (StatementItem item : statements) {
            if (item == null) {
                continue;
            }
            if (myUid.equalsIgnoreCase(item.userUid)) {
                return item;
            }
        }
        return null;
    }

    private boolean hasMyVote() {
        return myVote != null && myVote.length() > 0;
    }

    private String roleLabel(String role) {
        if ("reporter".equals(role)) {
            return "举报方";
        }
        if ("defendant".equals(role)) {
            return "被举报方";
        }
        if ("jury".equals(role)) {
            return "陪审团";
        }
        return "参与者";
    }

    private String displayUserName(String userName, String uid) {
        if (!TextUtils.isEmpty(userName)) {
            if (!TextUtils.isEmpty(uid)) {
                return userName + " (" + uid + ")";
            }
            return userName;
        }
        return safeOrDefault(uid, "匿名用户");
    }

    private void submitStatement(String reason, String evidence) {
        try {
            JSONObject body = new JSONObject();
            body.put("reason", reason == null ? "" : reason);
            body.put("evidence", evidence == null ? "" : evidence);
            HttpUtil.post("/public-court/cases/" + caseId + "/statement", body, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "陈述已保存", Toast.LENGTH_SHORT).show();
                            loadCaseDetail();
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
                            Toast.makeText(PublicCourtCaseDetailActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String safeFileName(Uri uri, String fallback) {
        if (uri == null) {
            return fallback;
        }
        String name = queryDisplayName(uri);
        if (TextUtils.isEmpty(name)) {
            return fallback;
        }
        return name;
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) {
            return "";
        }
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            name = null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (name == null || name.length() == 0) {
            String path = uri.getLastPathSegment();
            if (path != null && path.length() > 0) {
                name = path;
            }
        }
        return name == null ? "" : name;
    }

    private String resolveImageMime(Uri uri, String fileName) {
        String type = null;
        try {
            type = getContentResolver().getType(uri);
        } catch (Exception e) {
            type = null;
        }
        if (!TextUtils.isEmpty(type)) {
            return type;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private byte[] readUriBytesSafe(Uri uri, long maxBytes) {
        if (uri == null) {
            return null;
        }
        InputStream is = null;
        java.io.ByteArrayOutputStream bos = null;
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) {
                return null;
            }
            bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buf)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return null;
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (Exception e) {
                }
            }
        }
    }

    private String buildVoteErrorMessage(int code, String error) {
        String err = error == null ? "" : error;
        if (code == 400 && containsErrorCode(err, "invalid_evidence")) {
            return "投票必须提交证据";
        }
        if (code == 400 && containsErrorCode(err, "invalid_vote")) {
            return "投票选项无效，请重试";
        }
        if (code == 403 && containsErrorCode(err, "reputation_too_low")) {
            return "信誉分需高于50才能投票";
        }
        if (code == 403 && containsErrorCode(err, "forbidden")) {
            return "你没有投票权限";
        }
        if (code == 409 && containsErrorCode(err, "case_closed")) {
            return "案件已锁定，不能继续投票";
        }
        if (code == 404 && containsErrorCode(err, "not_found")) {
            return "案件不存在或已删除";
        }
        return "投票失败，请稍后重试";
    }

    private boolean containsErrorCode(String error, String code) {
        if (TextUtils.isEmpty(error) || TextUtils.isEmpty(code)) {
            return false;
        }
        String raw = error.toLowerCase(Locale.US);
        String target = code.toLowerCase(Locale.US);
        if (raw.contains(target)) {
            return true;
        }
        try {
            JSONObject obj = new JSONObject(error);
            String errCode = obj.optString("error", "");
            if (target.equalsIgnoreCase(errCode)) {
                return true;
            }
            String errMsg = obj.optString("message", "");
            return errMsg != null && errMsg.toLowerCase(Locale.US).contains(target);
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeOrDefault(String value, String def) {
        if (value == null || value.length() == 0) {
            return def;
        }
        return value;
    }

    private static class StatementItem {
        String userUid;
        String userName;
        String userAvatar;
        String role;
        String reason;
        String evidence;
        long updatedAt;
    }

    private static class DiscussionItem {
        String id;
        String userUid;
        String userName;
        String userAvatar;
        String body;
        long createdAt;
    }

    private static class MergedReportItem {
        String reportId;
        String reporterUid;
        String reporterName;
        String reporterAvatar;
        String reason;
        long createdAt;
    }
}
