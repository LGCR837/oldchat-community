package com.im.oldchat.ui;

import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import com.im.oldchat.R;
import com.im.oldchat.util.SettingsPrefs;

import java.util.ArrayList;
import java.util.Collection;

abstract class GroupChatActivitySupport2 extends GroupChatActivitySupport1 {
    protected void setupTypingWatcher() {
        if (etInput == null) {
            return;
        }
        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ChatSendButtonUi.update(btnSend, etInput, sendingCount > 0,
                        mediaHelper != null && mediaHelper.isVoiceMode);
                if (groupId == null || groupId.isEmpty()) {
                    return;
                }
                if (!SettingsPrefs.isTypingIndicatorEnabled(GroupChatActivitySupport2.this)) {
                    if (isTyping && typingStatusManager != null) {
                        typingStatusManager.stopTyping(GroupChatActivitySupport2.this, token, groupId, true);
                        isTyping = false;
                    }
                    typingHandler.removeCallbacks(typingIdleRunnable);
                    return;
                }
                String text = s == null ? "" : s.toString().trim();
                if (text.length() > 0 && !isTyping) {
                    isTyping = true;
                    if (typingStatusManager != null) {
                        typingStatusManager.startTyping(GroupChatActivitySupport2.this, token, groupId, true);
                    }
                    scheduleTypingIdleStop();
                } else if (text.length() == 0 && isTyping) {
                    isTyping = false;
                    if (typingStatusManager != null) {
                        typingStatusManager.stopTyping(GroupChatActivitySupport2.this, token, groupId, true);
                    }
                    typingHandler.removeCallbacks(typingIdleRunnable);
                } else if (text.length() > 0 && isTyping) {
                    scheduleTypingIdleStop();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                lastDraft = ChatDraftHelper.scheduleSaveDraft(GroupChatActivitySupport2.this, getDraftKey(),
                        s == null ? "" : s.toString(), lastDraft);
            }
        });
    }

    protected void scheduleTypingIdleStop() {
        typingHandler.removeCallbacks(typingIdleRunnable);
        typingHandler.postDelayed(typingIdleRunnable, TYPING_IDLE_MS);
    }

    protected void updateTypingIndicator() {
        setTypingIndicator(typingUsers);
    }

    protected void clearTypingState() {
        typingUsers.clear();
        setTypingIndicator(null);
    }

    protected void setTypingIndicator(Collection<String> uids) {
        boolean hasTyping = uids != null && !uids.isEmpty();
        boolean wasAtBottom = listHelper == null || listHelper.isAtBottom();
        if (adapter != null) {
            if (!hasTyping) {
                adapter.clearTypingIndicator();
            } else {
                adapter.setTypingIndicators(new ArrayList<>(uids));
            }
        }
        if (hasTyping && wasAtBottom && listHelper != null && lvMessages != null) {
            lvMessages.post(new Runnable() {
                @Override public void run() {
                    if (listHelper != null) listHelper.scrollToBottom();
                }
            });
        }
    }

    protected void refreshMyUID() {
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        String current = prefs.getString("my_uid", "");
        if (current == null || current.isEmpty() || current.equals(myUID)) {
            return;
        }
        myUID = current;
        if (adapter != null) {
            adapter.setMyUID(myUID);
        }
        if (listHelper != null) {
            listHelper.setMyUID(myUID);
        }
        if (mentionAdapter != null) {
            mentionAdapter.setExcludeUid(myUID);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    protected void refreshGroupAnnouncement() {
        android.util.Log.w("GroupChatPerf", "refreshGroupAnnouncement: start groupId=" + groupId);
        if (groupId == null || groupId.isEmpty() || token == null || token.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastAnnouncementRefreshAtMs > 0L
                && now - lastAnnouncementRefreshAtMs < ANNOUNCEMENT_REFRESH_THROTTLE_MS) {
            applyAnnouncementDisplay();
            return;
        }
        lastAnnouncementRefreshAtMs = now;
        if (manageApi == null) {
            manageApi = new GroupManageApi();
        }
        manageApi.loadGroupInfo(this, token, groupId, new GroupManageApi.GroupInfoCallback() {
            @Override
            public void onLoaded(com.im.oldchat.models.Group group) {
                if (group == null) {
                    return;
                }
                announcementText = group.announcement == null ? "" : group.announcement.trim();
                announcementMode = group.announcement_mode;
                announcementUpdatedAt = group.announcement_updated_at;
                announcementReadAt = group.announcement_read_at;
                applyAnnouncementDisplay();
            }
        });
    }

    protected void applyAnnouncementDisplay() {
        if (announcementText == null || announcementText.length() == 0) {
            hideAnnouncementBanner();
            return;
        }
        if (!isAnnouncementUnread()) {
            hideAnnouncementBanner();
            return;
        }
        if (announcementMode == ANNOUNCEMENT_MODE_REQUIRED) {
            hideAnnouncementBanner();
            showAnnouncementDialog();
            return;
        }
        showAnnouncementBanner();
    }

    private boolean isAnnouncementUnread() {
        if (announcementText == null || announcementText.length() == 0) {
            return false;
        }
        if (announcementReadAt <= 0) {
            return true;
        }
        if (announcementUpdatedAt <= 0) {
            return false;
        }
        return announcementUpdatedAt > announcementReadAt;
    }

    protected void showAnnouncementBanner() {
        if (announcementBanner == null || tvAnnouncementBanner == null) {
            return;
        }
        tvAnnouncementBanner.setText(getString(R.string.group_announcement_banner_prefix) + announcementText);
        announcementBanner.setVisibility(View.VISIBLE);
    }

    protected void hideAnnouncementBanner() {
        if (announcementBanner != null) {
            announcementBanner.setVisibility(View.GONE);
        }
    }

    protected void showAnnouncementDialog() {
        if (announcementDialogShowing) {
            return;
        }
        announcementDialogShowing = true;
        new android.support.v7.app.AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.group_announcement_title)
                .setMessage(announcementText)
                .setPositiveButton(R.string.group_announcement_read, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        markAnnouncementRead();
                        announcementDialogShowing = false;
                    }
                })
                .setCancelable(false)
                .show();
    }

    protected void markAnnouncementRead() {
        if (manageApi == null || token == null || token.isEmpty()) {
            return;
        }
        manageApi.markAnnouncementRead(this, token, groupId, new Runnable() {
            @Override
            public void run() {
                announcementReadAt = System.currentTimeMillis() / 1000L;
                hideAnnouncementBanner();
            }
        }, new Runnable() {
            @Override
            public void run() {
            }
        });
    }
}
