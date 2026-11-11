package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.v7.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.data.SettingsStore;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.GroupCache;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupRecentChatCache;
import com.im.oldchat.util.GroupAvatarCache;
import com.im.oldchat.util.ImageLoader;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

abstract class GroupManageActivitySupport extends BaseActivity {
    private static final AtomicInteger NEXT_VIEW_ID = new AtomicInteger(1);
    protected static final int REQ_PICK_AVATAR = 3101;
    protected static final int REQ_STORAGE = 3102;
    protected static final int REQ_PICK_CHAT_BG = 3103;
    protected static final int REQ_EDIT_ANNOUNCEMENT = 3104;
    protected static final int RESULT_GROUP_RENAMED = Activity.RESULT_FIRST_USER;

    protected ImageView ivGroupAvatar;
    protected TextView tvGroupName;
    protected TextView tvGroupId;
    protected View btnChangeAvatar;
    protected android.support.v7.widget.SwitchCompat switchJoinApproval;
    protected android.support.v7.widget.SwitchCompat switchGlobalMute;
    protected android.support.v7.widget.SwitchCompat switchMuteNotify;
    protected View btnInviteMember;
    protected View btnJoinRequests;
    protected View btnChatBackground;
    protected View btnGroupAnnouncement;
    protected View btnLeaveGroup;
    protected View btnReportGroup;
    protected TextView tvLeaveOrDissolve;
    protected ListView lvMembers;
    protected TextView tvGroupAnnouncement;
    protected GroupMemberAdapter adapter;
    protected final List<GroupMember> members = new ArrayList<>();
    protected final GroupManageApi manageApi = new GroupManageApi();
    protected final GroupExitHelper exitHelper = new GroupExitHelper();
    protected GroupAvatarManager avatarManager;
    protected ChatBackgroundHelper backgroundHelper;
    protected String token;
    protected String groupId;
    protected String groupName;
    protected String announcementText = "";
    protected int announcementMode = 0;
    protected int myRole = 0;
    protected boolean loadingSettings = false;

    protected void applyRoleVisibility() {
        btnChangeAvatar.setVisibility(myRole == 2 ? View.VISIBLE : View.GONE);
        switchJoinApproval.setEnabled(myRole >= 1);
        switchGlobalMute.setEnabled(myRole >= 1);
        btnInviteMember.setVisibility(View.VISIBLE);
        btnJoinRequests.setVisibility(myRole >= 1 ? View.VISIBLE : View.GONE);
        if (tvLeaveOrDissolve != null) {
            tvLeaveOrDissolve.setText(myRole == 2 ? R.string.group_manage_dissolve : R.string.group_manage_exit);
        }
        loadingSettings = true;
        switchMuteNotify.setChecked(SettingsStore.isConversationMuted(this, groupId, true));
        loadingSettings = false;
    }

    protected void loadGroupInfo() {
        manageApi.loadGroupInfo(this, token, groupId, new GroupManageApi.GroupInfoCallback() {
            @Override
            public void onLoaded(Group g) {
                groupName = g.name;
                myRole = g.role;
                updateGroupUI(g);
            }
        });
    }

    protected void updateGroupUI(Group g) {
        tvGroupName.setText(g.name == null ? "" : g.name);
        if (g.avatar_url != null && !g.avatar_url.isEmpty()) {
            ImageLoader.loadAvatarCacheFirstTryOnce(ivGroupAvatar, g.avatar_url);
        } else {
            ivGroupAvatar.setImageResource(R.drawable.group);
        }
        announcementText = g.announcement == null ? "" : g.announcement;
        announcementMode = g.announcement_mode;
        updateAnnouncementUI();
        loadingSettings = true;
        switchJoinApproval.setChecked(g.join_approval);
        switchGlobalMute.setChecked(g.global_mute);
        loadingSettings = false;
        if (adapter != null) {
            adapter.setMyRole(myRole);
        }
        applyRoleVisibility();
    }

    protected void loadMembers() {
        if (lvMembers == null || lvMembers.getVisibility() != View.VISIBLE) {
            return;
        }
        manageApi.loadMembers(this, token, groupId, new GroupManageApi.MembersCallback() {
            @Override
            public void onLoaded(List<GroupMember> list) {
                members.clear();
                members.addAll(list);
                if (adapter != null) {
                    adapter.setMembers(members);
                }
                GroupAvatarCache.updateFromMembers(GroupManageActivitySupport.this, list);
                int memberCount = list == null ? 0 : list.size();
                GroupRecentChatCache.updateMemberCount(GroupManageActivitySupport.this, groupId, memberCount);
                GroupCache.updateMemberCount(GroupManageActivitySupport.this, groupId, memberCount);
                if (lvMembers != null && lvMembers.getVisibility() == View.VISIBLE) {
                    lvMembers.post(new Runnable() {
                        @Override
                        public void run() {
                            updateMemberListHeight();
                        }
                    });
                }
            }
        });
    }

    protected void updateSettings() {
        manageApi.updateSettings(this, token, groupId,
                switchJoinApproval.isChecked(), switchGlobalMute.isChecked());
    }

    protected void kickMember(GroupMember member) {
        manageApi.kickMember(this, token, groupId, member, new Runnable() {
            @Override
            public void run() {
                loadMembers();
            }
        });
    }

    protected void setAdmin(GroupMember member, boolean makeAdmin) {
        manageApi.setAdmin(this, token, groupId, member, makeAdmin, new Runnable() {
            @Override
            public void run() {
                loadMembers();
            }
        });
    }

    protected void openInvite() {
        Intent intent = new Intent(this, GroupInviteActivity.class);
        intent.putExtra("group_id", groupId);
        startActivity(intent);
    }

    protected void openRequests() {
        Intent intent = new Intent(this, GroupJoinRequestsActivity.class);
        intent.putExtra("group_id", groupId);
        startActivity(intent);
    }

    protected void updateMemberListHeight() {
        if (lvMembers == null) {
            return;
        }
        ListAdapter listAdapter = lvMembers.getAdapter();
        if (listAdapter == null) {
            return;
        }
        int totalHeight = 0;
        int widthSpec = View.MeasureSpec.makeMeasureSpec(lvMembers.getWidth(), View.MeasureSpec.AT_MOST);
        for (int i = 0; i < listAdapter.getCount(); i++) {
            View listItem = listAdapter.getView(i, null, lvMembers);
            listItem.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            totalHeight += listItem.getMeasuredHeight();
        }
        int count = listAdapter.getCount();
        int dividerHeight = lvMembers.getDividerHeight();
        ViewGroup.LayoutParams params = lvMembers.getLayoutParams();
        params.height = totalHeight + dividerHeight * Math.max(0, count - 1);
        lvMembers.setLayoutParams(params);
        lvMembers.requestLayout();
    }

    protected void updateAnnouncementUI() {
        if (tvGroupAnnouncement == null) {
            return;
        }
        String text = announcementText == null ? "" : announcementText.trim();
        if (text.length() == 0) {
            tvGroupAnnouncement.setText(R.string.group_announcement_empty);
            return;
        }
        String modeText = getString(announcementMode == 1
                ? R.string.group_announcement_mode_required
                : R.string.group_announcement_mode_optional);
        String preview = text.replace('\n', ' ').replace('\r', ' ').trim();
        while (preview.contains("  ")) {
            preview = preview.replace("  ", " ");
        }
        if (preview.length() > 80) {
            preview = preview.substring(0, 80) + "...";
        }
        tvGroupAnnouncement.setText(getString(R.string.group_announcement_preview_format, modeText, preview));
    }

    protected void openAnnouncementPage() {
        Intent intent = new Intent(this, GroupAnnouncementActivity.class);
        intent.putExtra(GroupAnnouncementActivity.EXTRA_GROUP_ID, groupId);
        intent.putExtra(GroupAnnouncementActivity.EXTRA_GROUP_NAME, groupName);
        intent.putExtra(GroupAnnouncementActivity.EXTRA_ANNOUNCEMENT, announcementText == null ? "" : announcementText);
        intent.putExtra(GroupAnnouncementActivity.EXTRA_ANNOUNCEMENT_MODE, announcementMode);
        intent.putExtra(GroupAnnouncementActivity.EXTRA_CAN_EDIT, myRole >= 1);
        startActivityForResult(intent, REQ_EDIT_ANNOUNCEMENT);
    }

    protected void showAnnouncementViewer() {
        String content = announcementText == null ? "" : announcementText.trim();
        if (content.length() == 0) {
            content = getString(R.string.group_announcement_empty);
        }
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.group_announcement_title)
                .setMessage(content)
                .setPositiveButton(R.string.group_announcement_read, null)
                .show();
    }

    protected void showAnnouncementEditor() {
        final ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 12);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        final EditText input = new EditText(this);
        input.setHint(R.string.group_announcement_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setText(announcementText == null ? "" : announcementText);
        input.setTextColor(getResources().getColor(R.color.color_text_primary));
        input.setHintTextColor(getResources().getColor(R.color.color_text_secondary));
        input.setPadding(padding, padding, padding, padding);
        input.setBackgroundResource(R.drawable.flat_input_bg);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(inputParams);

        final RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbOptional = new RadioButton(this);
        final int optionalId = generateCompatViewId();
        rbOptional.setId(optionalId);
        rbOptional.setText(R.string.group_announcement_mode_optional);
        rbOptional.setTextColor(getResources().getColor(R.color.color_text_primary));
        RadioButton rbRequired = new RadioButton(this);
        final int requiredId = generateCompatViewId();
        rbRequired.setId(requiredId);
        rbRequired.setText(R.string.group_announcement_mode_required);
        rbRequired.setTextColor(getResources().getColor(R.color.color_text_primary));
        modeGroup.addView(rbOptional);
        modeGroup.addView(rbRequired);
        modeGroup.check(announcementMode == 1 ? requiredId : optionalId);

        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        modeParams.topMargin = padding / 2;
        modeGroup.setLayoutParams(modeParams);

        container.addView(input);
        container.addView(modeGroup);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.group_announcement_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_cancel, null);

        int positiveLabel = (announcementText == null || announcementText.trim().length() == 0)
                ? R.string.group_announcement_publish
                : R.string.group_announcement_edit;
        builder.setPositiveButton(positiveLabel, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                String text = input.getText() == null ? "" : input.getText().toString().trim();
                if (text.length() == 0) {
                    Toast.makeText(GroupManageActivitySupport.this, R.string.group_announcement_hint, Toast.LENGTH_SHORT).show();
                    return;
                }
                int mode = modeGroup.getCheckedRadioButtonId() == requiredId ? 1 : 0;
                submitAnnouncement(text, false, mode);
            }
        });

        if (announcementText != null && announcementText.trim().length() > 0) {
            builder.setNeutralButton(R.string.group_announcement_delete, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    confirmDeleteAnnouncement();
                }
            });
        }
        builder.show();
    }

    private int generateCompatViewId() {
        if (Build.VERSION.SDK_INT >= 17) {
            return View.generateViewId();
        }
        while (true) {
            final int result = NEXT_VIEW_ID.get();
            int newValue = result + 1;
            if (newValue > 0x00FFFFFF) {
                newValue = 1;
            }
            if (NEXT_VIEW_ID.compareAndSet(result, newValue)) {
                return result;
            }
        }
    }

    protected void showGroupNameEditor() {
        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 12);
        container.setPadding(padding, padding, padding, padding);

        final EditText input = new EditText(this);
        input.setHint(R.string.group_manage_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(groupName == null ? "" : groupName);
        input.setTextColor(getResources().getColor(R.color.color_text_primary));
        input.setHintTextColor(getResources().getColor(R.color.color_text_secondary));
        input.setPadding(padding, padding, padding, padding);
        input.setBackgroundResource(R.drawable.flat_input_bg);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(inputParams);
        container.addView(input);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.group_manage_name_title)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        final String name = input.getText() == null ? "" : input.getText().toString().trim();
                        if (name.length() == 0 || groupId == null || groupId.length() == 0) {
                            return;
                        }
                        manageApi.updateGroupName(GroupManageActivitySupport.this, token, groupId, name,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        groupName = name;
                                        tvGroupName.setText(name);
                                        GroupCache.updateName(GroupManageActivitySupport.this, groupId, name);
                                        GroupRecentChatCache.updateName(GroupManageActivitySupport.this, groupId, name);
                                        Intent result = new Intent();
                                        result.putExtra("group_name", name);
                                        setResult(RESULT_GROUP_RENAMED, result);
                                        Toast.makeText(GroupManageActivitySupport.this, R.string.group_manage_name_success, Toast.LENGTH_SHORT).show();
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(GroupManageActivitySupport.this, R.string.group_manage_name_failed, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    }
                })
                .show();
    }

    protected void confirmDeleteAnnouncement() {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.group_announcement_delete)
                .setMessage(R.string.group_announcement_delete_confirm)
                .setPositiveButton(R.string.action_confirm, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        submitAnnouncement("", true, announcementMode);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    protected void submitAnnouncement(final String text, final boolean deleting, final int mode) {
        manageApi.updateAnnouncement(this, token, groupId, text, mode,
                new Runnable() {
                    @Override
                    public void run() {
                        announcementText = text;
                        announcementMode = mode;
                        updateAnnouncementUI();
                        Toast.makeText(GroupManageActivitySupport.this,
                                deleting ? R.string.group_announcement_delete_success : R.string.group_announcement_publish_success,
                                Toast.LENGTH_SHORT).show();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(GroupManageActivitySupport.this,
                                deleting ? R.string.group_announcement_delete_failed : R.string.group_announcement_publish_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

}
