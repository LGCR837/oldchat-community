package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageLoader;

import org.json.JSONObject;

/**
 * 独立举报页面：展示被举报人/群聊信息、涉案记录证据，支持选择违规类型、编辑举报证词，
 * 支持选择公开法庭初审或单独提交审核团队。
 */
public class ReportActivity extends BaseActivity {
    public static final String EXTRA_TARGET_UID = "target_uid";
    public static final String EXTRA_TARGET_NCUID = "target_ncuid";
    public static final String EXTRA_TARGET_NAME = "target_name";
    public static final String EXTRA_TARGET_AVATAR = "target_avatar";
    public static final String EXTRA_EVIDENCE_TEXT = "evidence_text";
    public static final String EXTRA_SOURCE_TAG = "source_tag";
    public static final String EXTRA_IS_GROUP = "is_group";
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_GROUP_NAME = "group_name";

    private static final String AUTH_PREFS = "auth";

    private String targetUid;
    private String targetNcuid;
    private String targetName;
    private String targetAvatar;
    private String evidenceText;
    private String sourceTag;
    private boolean isGroup = false;
    private String groupId;
    private String groupName;

    private ImageView ivAvatar;
    private TextView tvName;
    private TextView tvUid;
    private TextView tvSource;
    private TextView tvEvidence;
    private RadioGroup rgCategory;
    private RadioGroup rgDestination;
    private RadioButton rbDestCourt;
    private RadioButton rbDestAudit;
    private TextView tvNoticeTitle;
    private TextView tvNoticeContent;
    private EditText etTestimony;
    private TextView tvTestimonyCount;
    private TextView btnSubmit;
    private ProgressBar pbLoading;

    private boolean submitting = false;

    public static void start(Context context, String targetUid, String targetNcuid,
                             String targetName, String targetAvatar,
                             String evidenceText, String sourceTag) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, ReportActivity.class);
        intent.putExtra(EXTRA_TARGET_UID, targetUid);
        intent.putExtra(EXTRA_TARGET_NCUID, targetNcuid);
        intent.putExtra(EXTRA_TARGET_NAME, targetName);
        intent.putExtra(EXTRA_TARGET_AVATAR, targetAvatar);
        intent.putExtra(EXTRA_EVIDENCE_TEXT, evidenceText);
        intent.putExtra(EXTRA_SOURCE_TAG, sourceTag);
        intent.putExtra(EXTRA_IS_GROUP, false);
        context.startActivity(intent);
    }

    public static void startForGroup(Context context, String groupId, String groupName,
                                    String groupAvatar, String evidenceText, String sourceTag) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, ReportActivity.class);
        intent.putExtra(EXTRA_IS_GROUP, true);
        intent.putExtra(EXTRA_GROUP_ID, groupId);
        intent.putExtra(EXTRA_GROUP_NAME, groupName);
        intent.putExtra(EXTRA_TARGET_AVATAR, groupAvatar);
        intent.putExtra(EXTRA_EVIDENCE_TEXT, evidenceText);
        intent.putExtra(EXTRA_SOURCE_TAG, sourceTag);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        readIntentExtras();
        initViews();
        bindData();
        bindEvents();
    }

    private void readIntentExtras() {
        Intent intent = getIntent();
        if (intent != null) {
            isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false);
            groupId = intent.getStringExtra(EXTRA_GROUP_ID);
            groupName = intent.getStringExtra(EXTRA_GROUP_NAME);
            targetUid = intent.getStringExtra(EXTRA_TARGET_UID);
            targetNcuid = intent.getStringExtra(EXTRA_TARGET_NCUID);
            targetName = intent.getStringExtra(EXTRA_TARGET_NAME);
            targetAvatar = intent.getStringExtra(EXTRA_TARGET_AVATAR);
            evidenceText = intent.getStringExtra(EXTRA_EVIDENCE_TEXT);
            sourceTag = intent.getStringExtra(EXTRA_SOURCE_TAG);
        }
        if (groupId == null) groupId = "";
        if (groupName == null) groupName = "";
        if (targetUid == null) targetUid = "";
        if (targetNcuid == null) targetNcuid = "";
        if (isGroup) {
            if (targetName == null || targetName.trim().isEmpty()) {
                targetName = !groupName.isEmpty() ? groupName : groupId;
            }
            if (sourceTag == null || sourceTag.trim().isEmpty()) {
                sourceTag = "来源：群聊 (" + groupId + ")";
            }
        } else {
            if (targetName == null || targetName.trim().isEmpty()) {
                targetName = !targetUid.isEmpty() ? targetUid : "未知用户";
            }
            if (sourceTag == null || sourceTag.trim().isEmpty()) {
                sourceTag = "来源：应用会话";
            }
        }
        if (evidenceText == null || evidenceText.trim().isEmpty()) {
            evidenceText = isGroup ? "【群聊违规举报】" : "【无直接文字记录】用户主页/账号举报";
        }
    }

    private void initViews() {
        ivAvatar = findViewByIdCompat(R.id.ivReportTargetAvatar);
        tvName = findViewByIdCompat(R.id.tvReportTargetName);
        tvUid = findViewByIdCompat(R.id.tvReportTargetUid);
        tvSource = findViewByIdCompat(R.id.tvReportSourceTag);
        tvEvidence = findViewByIdCompat(R.id.tvReportEvidenceContent);
        rgCategory = findViewByIdCompat(R.id.rgReportCategory);
        rgDestination = findViewByIdCompat(R.id.rgReportDestination);
        rbDestCourt = findViewByIdCompat(R.id.rbReportDestCourt);
        rbDestAudit = findViewByIdCompat(R.id.rbReportDestAudit);
        tvNoticeTitle = findViewByIdCompat(R.id.tvReportNoticeTitle);
        tvNoticeContent = findViewByIdCompat(R.id.tvReportNoticeContent);
        etTestimony = findViewByIdCompat(R.id.etReportTestimony);
        tvTestimonyCount = findViewByIdCompat(R.id.tvReportTestimonyCount);
        btnSubmit = findViewByIdCompat(R.id.btnSubmitReport);
        pbLoading = findViewByIdCompat(R.id.pbReportLoading);
    }

    private void bindData() {
        if (tvName != null) {
            tvName.setText(targetName);
        }
        if (tvUid != null) {
            String uidDisplay = isGroup ? ("群ID: " + groupId) : ("UID: " + (!targetUid.isEmpty() ? targetUid : targetNcuid));
            tvUid.setText(uidDisplay);
        }
        if (tvSource != null) {
            tvSource.setText(sourceTag);
        }
        if (tvEvidence != null) {
            tvEvidence.setText(evidenceText);
        }
        if (ivAvatar != null) {
            if (targetAvatar != null && !targetAvatar.trim().isEmpty()) {
                ImageLoader.loadAvatar(ivAvatar, targetAvatar);
            } else {
                ivAvatar.setImageResource(isGroup ? R.drawable.group : R.drawable.usershead);
            }
        }
        if (isGroup) {
            if (rbDestCourt != null) {
                rbDestCourt.setVisibility(View.GONE);
            }
            if (rbDestAudit != null) {
                rbDestAudit.setChecked(true);
                rbDestAudit.setText("提交官方审核团队 (群聊举报)");
            }
        }
        updateDestinationUI();
    }

    private void bindEvents() {
        View btnBack = findViewById(R.id.btnReportBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        if (rgDestination != null) {
            rgDestination.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    updateDestinationUI();
                }
            });
        }

        if (etTestimony != null && tvTestimonyCount != null) {
            etTestimony.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    int len = s != null ? s.length() : 0;
                    tvTestimonyCount.setText(len + "/500");
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        if (btnSubmit != null) {
            btnSubmit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    submitReport();
                }
            });
        }
    }

    private void updateDestinationUI() {
        if (isGroup) {
            if (btnSubmit != null) btnSubmit.setText("提交群聊举报给官方审核员");
            if (tvNoticeTitle != null) tvNoticeTitle.setText("群聊违规审核须知");
            if (tvNoticeContent != null) {
                tvNoticeContent.setText("1. 群聊举报将直接私密加密送入官方内容审核台，由审核团队专属处理。\n2. 群聊违规举报不进入公开法庭。\n3. 官方审核员将调阅涉案群聊上下文记录并根据违规情况处置。\n4. 如果您的举报不合理/无意义，我们可能会封禁您的账户。");
            }
            return;
        }
        boolean isAuditOnly = (rgDestination != null && rgDestination.getCheckedRadioButtonId() == R.id.rbReportDestAudit);
        if (isAuditOnly) {
            if (btnSubmit != null) btnSubmit.setText("单独提交给官方审核员");
            if (tvNoticeTitle != null) tvNoticeTitle.setText("审核团队受理须知");
            if (tvNoticeContent != null) {
                tvNoticeContent.setText("1. 举报信息将直接私密加密送入官方内容审核台，由审核团队专属处理。\n2. 该举报不会公开为法庭案件，其他用户不可见。\n3. 经审核确认违规后将直接执行封禁处置。\n4. 如果您的举报不合理/无意义，我们可能会封禁您的账户。");
            }
        } else {
            if (btnSubmit != null) btnSubmit.setText("提交举报并进入公开法庭");
            if (tvNoticeTitle != null) tvNoticeTitle.setText("公开法庭审理须知");
            if (tvNoticeContent != null) {
                tvNoticeContent.setText("1. 提交后系统将自动生成公开法庭案件，由全服大众陪审团进行民主初审，并由管理员进行二审终审。\n2. 若法庭与二审确认违规并执行封号，违规者将被扣除 100 信誉分。\n3. 请如实提供证据与陈述证词，如果您的举报不合理/无意义，我们可能会封禁您的账户。");
            }
        }
    }

    private String getSelectedCategory() {
        if (rgCategory == null) {
            return "违规行为举报";
        }
        int checkedId = rgCategory.getCheckedRadioButtonId();
        if (checkedId != -1) {
            RadioButton rb = findViewByIdCompat(checkedId);
            if (rb != null && rb.getText() != null) {
                return rb.getText().toString().trim();
            }
        }
        return "违规行为举报";
    }

    private void submitReport() {
        if (submitting) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE);
        String token = prefs.getString("access_token", "");
        String myUid = prefs.getString("uid", "");
        if (myUid == null || myUid.length() == 0) {
            myUid = prefs.getString("user_uid", "");
        }
        if (token == null || token.trim().isEmpty()) {
            Toast.makeText(this, "请先登录账号后再提交举报", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isGroup) {
            if (targetUid.isEmpty() && targetNcuid.isEmpty()) {
                Toast.makeText(this, "被举报人标识无效", Toast.LENGTH_SHORT).show();
                return;
            }
            if (myUid.length() > 0 && myUid.equalsIgnoreCase(targetUid)) {
                Toast.makeText(this, "不能举报自己", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (groupId.isEmpty()) {
                Toast.makeText(this, "涉案群ID无效", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        final boolean isAuditOnly = isGroup || (rgDestination != null && rgDestination.getCheckedRadioButtonId() == R.id.rbReportDestAudit);
        String category = getSelectedCategory();
        String testimony = etTestimony != null && etTestimony.getText() != null
                ? etTestimony.getText().toString().trim() : "";

        String combinedReason = category;
        if (!testimony.isEmpty()) {
            combinedReason = category + " | 举报人证词：" + testimony;
        }

        String formattedEvidence = "【涉案来源】" + sourceTag + "\n"
                + "【涉案记录】\n" + evidenceText;
        if (!testimony.isEmpty()) {
            formattedEvidence = formattedEvidence + "\n\n【举报人详细证词】\n" + testimony;
        }

        try {
            JSONObject json = new JSONObject();
            if (isGroup) {
                json.put("group_id", groupId);
                json.put("reason", combinedReason + "\n\n【证据与证词记录】\n" + formattedEvidence);
            } else {
                json.put("target_uid", targetUid);
                if (!targetNcuid.isEmpty()) {
                    json.put("target_ncuid", targetNcuid);
                }
                json.put("reason", combinedReason);
                json.put("evidence", formattedEvidence);
                json.put("target_channel", isAuditOnly ? "audit" : "public_court");
                json.put("to_public_court", !isAuditOnly);
            }

            setSubmittingState(true);

            String apiPath = isGroup ? "/reports/group" : "/reports/user";
            HttpUtil.post(apiPath, json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setSubmittingState(false);
                            String successMsg;
                            if (isGroup) {
                                successMsg = "群聊举报已提交给官方审核员，我们将调阅群聊上下文进行审核";
                            } else if (isAuditOnly) {
                                successMsg = "举报已私密提交给官方审核员，我们将尽快审核处置";
                            } else {
                                successMsg = "举报已成功提交并送入公开法庭进行审理";
                            }
                            Toast.makeText(ReportActivity.this, successMsg, Toast.LENGTH_LONG).show();
                            setResult(RESULT_OK);
                            finish();
                        }
                    });
                }

                @Override
                public void onError(final int code, final String error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setSubmittingState(false);
                            if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                                return;
                            }
                            String msg = "举报提交失败";
                            if (error != null && !error.isEmpty()) {
                                try {
                                    JSONObject errObj = new JSONObject(error);
                                    if (errObj.has("error")) {
                                        msg = errObj.optString("error");
                                    }
                                } catch (Exception ignored) {
                                    msg = error;
                                }
                            }
                            Toast.makeText(ReportActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } catch (Exception e) {
            setSubmittingState(false);
            Toast.makeText(this, "构造举报请求失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void setSubmittingState(boolean loading) {
        this.submitting = loading;
        if (pbLoading != null) {
            pbLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (btnSubmit != null) {
            boolean isAuditOnly = isGroup || (rgDestination != null && rgDestination.getCheckedRadioButtonId() == R.id.rbReportDestAudit);
            btnSubmit.setEnabled(!loading);
            btnSubmit.setText(loading ? "正在提交举报..." : (isGroup ? "提交群聊举报给官方审核员" : (isAuditOnly ? "单独提交给官方审核员" : "提交举报并进入公开法庭")));
        }
    }
}
