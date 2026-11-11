package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;

public class GroupAnnouncementActivity extends BaseActivity {
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_GROUP_NAME = "group_name";
    public static final String EXTRA_ANNOUNCEMENT = "announcement_text";
    public static final String EXTRA_ANNOUNCEMENT_MODE = "announcement_mode";
    public static final String EXTRA_CAN_EDIT = "can_edit_announcement";

    private String token;
    private String groupId;
    private String groupName;
    private String announcementText;
    private int announcementMode;
    private String originalAnnouncementText;
    private int originalAnnouncementMode;
    private boolean canEdit;
    private boolean submitting;

    private LinearLayout layoutEditor;
    private LinearLayout layoutViewer;
    private EditText etAnnouncementContent;
    private RadioGroup rgAnnouncementMode;
    private RadioButton rbAnnouncementOptional;
    private RadioButton rbAnnouncementRequired;
    private TextView tvAnnouncementCount;
    private TextView tvAnnouncementPreview;
    private TextView tvAnnouncementViewerMode;
    private TextView tvAnnouncementViewerContent;
    private TextView btnPrimary;
    private TextView btnDelete;
    private ScrollView svAnnouncementContent;
    private TextView btnArrowUp;
    private TextView btnArrowDown;
    private TextView cursorTouchPad;

    private final GroupManageApi manageApi = new GroupManageApi();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_announcement);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        Intent intent = getIntent();
        groupId = intent.getStringExtra(EXTRA_GROUP_ID);
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME);
        announcementText = intent.getStringExtra(EXTRA_ANNOUNCEMENT);
        announcementMode = intent.getIntExtra(EXTRA_ANNOUNCEMENT_MODE, 0);
        canEdit = intent.getBooleanExtra(EXTRA_CAN_EDIT, false);
        if (announcementText == null) {
            announcementText = "";
        }
        originalAnnouncementText = announcementText;
        originalAnnouncementMode = announcementMode;

        TextView tvTitle = findViewByIdCompat(R.id.tvGroupAnnouncementPageTitle);
        TextView tvGroupName = findViewByIdCompat(R.id.tvGroupAnnouncementGroupName);
        layoutEditor = findViewByIdCompat(R.id.layoutAnnouncementEditor);
        layoutViewer = findViewByIdCompat(R.id.layoutAnnouncementViewer);
        etAnnouncementContent = findViewByIdCompat(R.id.etGroupAnnouncementContent);
        rgAnnouncementMode = findViewByIdCompat(R.id.rgAnnouncementMode);
        rbAnnouncementOptional = findViewByIdCompat(R.id.rbAnnouncementOptional);
        rbAnnouncementRequired = findViewByIdCompat(R.id.rbAnnouncementRequired);
        tvAnnouncementCount = findViewByIdCompat(R.id.tvGroupAnnouncementCount);
        tvAnnouncementPreview = findViewByIdCompat(R.id.tvGroupAnnouncementPreview);
        tvAnnouncementViewerMode = findViewByIdCompat(R.id.tvAnnouncementViewerMode);
        tvAnnouncementViewerContent = findViewByIdCompat(R.id.tvAnnouncementViewerContent);
        btnPrimary = findViewByIdCompat(R.id.btnGroupAnnouncementPrimary);
        btnDelete = findViewByIdCompat(R.id.btnGroupAnnouncementDelete);
        svAnnouncementContent = findViewByIdCompat(R.id.svGroupAnnouncementContent);
        btnArrowUp = findViewByIdCompat(R.id.btnGroupAnnouncementArrowUp);
        btnArrowDown = findViewByIdCompat(R.id.btnGroupAnnouncementArrowDown);
        cursorTouchPad = findViewByIdCompat(R.id.viewGroupAnnouncementCursorPad);

        if (tvTitle != null) {
            tvTitle.setText(R.string.group_announcement_title);
        }
        if (tvGroupName != null) {
            String displayName = groupName == null || groupName.length() == 0 ? "" : groupName;
            tvGroupName.setText(getString(R.string.group_announcement_group_format, displayName));
        }

        View btnBack = findViewByIdCompat(R.id.btnGroupAnnouncementBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        bindAnnouncementArrows();
        bindUiMode();
    }

    private void setPrimaryLeftMargin(int dp) {
        if (btnPrimary == null) {
            return;
        }
        android.view.ViewGroup.LayoutParams params = btnPrimary.getLayoutParams();
        if (!(params instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
        int px = (int) (getResources().getDisplayMetrics().density * dp + 0.5f);
        if (lp.leftMargin != px) {
            lp.leftMargin = px;
            btnPrimary.setLayoutParams(lp);
        }
    }

    private void bindUiMode() {
        if (layoutEditor == null || layoutViewer == null) {
            refreshArrowMode();
            return;
        }
        if (canEdit) {
            layoutEditor.setVisibility(View.VISIBLE);
            layoutViewer.setVisibility(View.GONE);
            bindEditorUi();
            refreshArrowMode();
            return;
        }
        layoutEditor.setVisibility(View.GONE);
        layoutViewer.setVisibility(View.VISIBLE);
        bindViewerUi();
        refreshArrowMode();
    }

    private void bindAnnouncementArrows() {
        if (btnArrowUp != null) {
            btnArrowUp.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    scrollAnnouncementContent(-220);
                }
            });
        }
        if (btnArrowDown != null) {
            btnArrowDown.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (canEdit) {
                        submitAnnouncement();
                        return;
                    }
                    scrollAnnouncementContent(220);
                }
            });
        }
        refreshArrowMode();
    }

    private void refreshArrowMode() {
        if (btnArrowDown == null) {
            return;
        }
        btnArrowDown.setText(canEdit ? "▼发" : "▼");
    }

    private void scrollAnnouncementContent(int dp) {
        if (svAnnouncementContent == null) {
            return;
        }
        int px = (int) (getResources().getDisplayMetrics().density * dp + 0.5f);
        svAnnouncementContent.smoothScrollBy(0, px);
    }

    private void bindCursorTouchPad() {
        if (cursorTouchPad == null) {
            return;
        }
        if (!canEdit || etAnnouncementContent == null) {
            cursorTouchPad.setVisibility(View.GONE);
            cursorTouchPad.setOnTouchListener(null);
            return;
        }
        cursorTouchPad.setVisibility(View.VISIBLE);
        cursorTouchPad.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) {
                    return false;
                }
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    moveAnnouncementCursorByTouch(v, event.getX());
                    return true;
                }
                return action == MotionEvent.ACTION_UP;
            }
        });
    }

    private void moveAnnouncementCursorByTouch(View touchView, float x) {
        if (touchView == null || etAnnouncementContent == null) {
            return;
        }
        Editable editable = etAnnouncementContent.getText();
        int length = editable == null ? 0 : editable.length();
        if (!etAnnouncementContent.isFocused()) {
            etAnnouncementContent.requestFocus();
        }
        if (length <= 0) {
            etAnnouncementContent.setSelection(0);
            return;
        }
        int left = touchView.getPaddingLeft();
        int right = touchView.getWidth() - touchView.getPaddingRight();
        int width = right - left;
        if (width <= 0) {
            etAnnouncementContent.setSelection(length);
            return;
        }
        float ratio = (x - left) / (float) width;
        if (ratio < 0f) {
            ratio = 0f;
        } else if (ratio > 1f) {
            ratio = 1f;
        }
        int index = Math.round(ratio * length);
        if (index < 0) {
            index = 0;
        } else if (index > length) {
            index = length;
        }
        etAnnouncementContent.setSelection(index);
    }

    private void bindEditorUi() {
        if (etAnnouncementContent != null) {
            etAnnouncementContent.setText(announcementText == null ? "" : announcementText);
            etAnnouncementContent.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateEditorPreview();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
        bindCursorTouchPad();
        if (rgAnnouncementMode != null) {
            int modeId = announcementMode == 1 ? R.id.rbAnnouncementRequired : R.id.rbAnnouncementOptional;
            rgAnnouncementMode.check(modeId);
            rgAnnouncementMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    updateEditorPreview();
                }
            });
        }
        if (btnPrimary != null) {
            btnPrimary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    submitAnnouncement();
                }
            });
        }
        if (btnDelete != null) {
            boolean hasAnnouncement = originalAnnouncementText != null && originalAnnouncementText.trim().length() > 0;
            btnDelete.setVisibility(hasAnnouncement ? View.VISIBLE : View.GONE);
            setPrimaryLeftMargin(hasAnnouncement ? 10 : 0);
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDeleteAnnouncement();
                }
            });
        }
        updateEditorPreview();
        updatePrimaryActionState();
    }

    private void bindViewerUi() {
        if (btnDelete != null) {
            btnDelete.setVisibility(View.GONE);
        }
        setPrimaryLeftMargin(0);
        if (tvAnnouncementViewerMode != null) {
            tvAnnouncementViewerMode.setText(announcementMode == 1
                    ? R.string.group_announcement_mode_required
                    : R.string.group_announcement_mode_optional);
        }
        if (tvAnnouncementViewerContent != null) {
            String content = announcementText == null ? "" : announcementText.trim();
            if (content.length() == 0) {
                tvAnnouncementViewerContent.setText(R.string.group_announcement_empty);
            } else {
                tvAnnouncementViewerContent.setText(content);
            }
        }
        if (btnPrimary != null) {
            String content = announcementText == null ? "" : announcementText.trim();
            if (content.length() == 0) {
                btnPrimary.setText(R.string.group_announcement_close);
                btnPrimary.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
                return;
            }
            btnPrimary.setText(R.string.group_announcement_read);
            btnPrimary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    markRead();
                }
            });
        }
    }

    private void updateEditorPreview() {
        String content = etAnnouncementContent == null || etAnnouncementContent.getText() == null
                ? "" : etAnnouncementContent.getText().toString();
        if (tvAnnouncementCount != null) {
            tvAnnouncementCount.setText(getString(R.string.group_announcement_count_format, content.length()));
        }
        if (tvAnnouncementPreview != null) {
            String preview = content.trim();
            if (preview.length() == 0) {
                preview = getString(R.string.group_announcement_empty);
            }
            String modeText = isRequiredModeSelected()
                    ? getString(R.string.group_announcement_mode_required)
                    : getString(R.string.group_announcement_mode_optional);
            tvAnnouncementPreview.setText(getString(R.string.group_announcement_preview_format, modeText, preview));
        }
        updatePrimaryActionState();
    }

    private void updatePrimaryActionState() {
        if (!canEdit || btnPrimary == null) {
            return;
        }
        String currentText = etAnnouncementContent == null || etAnnouncementContent.getText() == null
                ? "" : etAnnouncementContent.getText().toString().trim();
        int currentMode = isRequiredModeSelected() ? 1 : 0;
        boolean hasContent = currentText.length() > 0;
        boolean changed = hasAnnouncementChanged(currentText, currentMode);
        btnPrimary.setText((originalAnnouncementText == null || originalAnnouncementText.trim().length() == 0)
                ? R.string.group_announcement_publish
                : R.string.group_announcement_edit);
        btnPrimary.setEnabled(!submitting && hasContent && changed);
        if (btnDelete != null && btnDelete.getVisibility() == View.VISIBLE) {
            btnDelete.setEnabled(!submitting);
        }
    }

    private boolean hasAnnouncementChanged(String currentText, int currentMode) {
        String originText = originalAnnouncementText == null ? "" : originalAnnouncementText.trim();
        String nowText = currentText == null ? "" : currentText.trim();
        return !originText.equals(nowText) || originalAnnouncementMode != currentMode;
    }

    private boolean isRequiredModeSelected() {
        if (rgAnnouncementMode == null) {
            return announcementMode == 1;
        }
        return rgAnnouncementMode.getCheckedRadioButtonId() == R.id.rbAnnouncementRequired;
    }

    private void submitAnnouncement() {
        if (groupId == null || groupId.length() == 0) {
            Toast.makeText(this, R.string.group_announcement_publish_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (submitting) {
            return;
        }
        String text = etAnnouncementContent == null || etAnnouncementContent.getText() == null
                ? "" : etAnnouncementContent.getText().toString().trim();
        if (text.length() == 0) {
            Toast.makeText(this, R.string.group_announcement_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        final int mode = isRequiredModeSelected() ? 1 : 0;
        if (!hasAnnouncementChanged(text, mode)) {
            Toast.makeText(this, R.string.group_announcement_no_change, Toast.LENGTH_SHORT).show();
            return;
        }
        submitting = true;
        updatePrimaryActionState();
        manageApi.updateAnnouncement(this, token, groupId, text, mode,
                new Runnable() {
                    @Override
                    public void run() {
                        submitting = false;
                        announcementText = etAnnouncementContent.getText() == null
                                ? "" : etAnnouncementContent.getText().toString().trim();
                        announcementMode = mode;
                        originalAnnouncementText = announcementText;
                        originalAnnouncementMode = announcementMode;
                        Toast.makeText(GroupAnnouncementActivity.this,
                                R.string.group_announcement_publish_success,
                                Toast.LENGTH_SHORT).show();
                        Intent data = new Intent();
                        data.putExtra(EXTRA_ANNOUNCEMENT, announcementText);
                        data.putExtra(EXTRA_ANNOUNCEMENT_MODE, announcementMode);
                        setResult(RESULT_OK, data);
                        finish();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        submitting = false;
                        updatePrimaryActionState();
                        Toast.makeText(GroupAnnouncementActivity.this,
                                R.string.group_announcement_publish_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void confirmDeleteAnnouncement() {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.group_announcement_delete)
                .setMessage(R.string.group_announcement_delete_confirm)
                .setPositiveButton(R.string.action_confirm, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteAnnouncement();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void deleteAnnouncement() {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        if (submitting) {
            return;
        }
        submitting = true;
        updatePrimaryActionState();
        manageApi.updateAnnouncement(this, token, groupId, "", announcementMode,
                new Runnable() {
                    @Override
                    public void run() {
                        submitting = false;
                        announcementText = "";
                        originalAnnouncementText = "";
                        originalAnnouncementMode = announcementMode;
                        Toast.makeText(GroupAnnouncementActivity.this,
                                R.string.group_announcement_delete_success,
                                Toast.LENGTH_SHORT).show();
                        Intent data = new Intent();
                        data.putExtra(EXTRA_ANNOUNCEMENT, announcementText);
                        data.putExtra(EXTRA_ANNOUNCEMENT_MODE, announcementMode);
                        setResult(RESULT_OK, data);
                        finish();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        submitting = false;
                        updatePrimaryActionState();
                        Toast.makeText(GroupAnnouncementActivity.this,
                                R.string.group_announcement_delete_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void markRead() {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        manageApi.markAnnouncementRead(this, token, groupId,
                new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(GroupAnnouncementActivity.this,
                                R.string.group_announcement_read,
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(GroupAnnouncementActivity.this,
                                R.string.group_announcement_publish_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
