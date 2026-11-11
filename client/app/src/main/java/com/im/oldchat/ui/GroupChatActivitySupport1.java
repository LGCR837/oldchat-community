package com.im.oldchat.ui;

import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;

import com.im.oldchat.R;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.models.GroupMemberMetaCache;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.MessagePayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

abstract class GroupChatActivitySupport1 extends GroupChatActivitySupport0 {
    private boolean mentionSyncTriggered;

    protected void mentionUser(GroupMessage msg, String displayName) {
        suppressMentionTrigger = true;
        GroupChatUiActions.insertMention(etInput, msg, displayName);
        suppressMentionTrigger = false;
        if (msg != null) {
            addMentionDraft(msg.from_uid, msg.sender_ncuid, displayName);
        }
    }

    protected void setupMentionInput() {
        if (etInput == null) {
            return;
        }
        etInput.addTextChangedListener(new TextWatcher() {
            private int lastStart = 0;
            private int lastCount = 0;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                lastStart = start;
                lastCount = count;
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressMentionTrigger) {
                    return;
                }
                String content = s == null ? "" : s.toString();
                if (content.length() == 0) {
                    mentionDrafts.clear();
                    return;
                }
                pruneMentions(content);
                // 只检查“本次输入变更区间”内的 @（且前一个字符是空白/开头）才触发选择器。
                // 不能全段扫描：@完人后继续输入文字时，全段扫描会重复命中历史 @，导致每输入一个字弹一次。
                int start = Math.max(0, lastStart);
                int end = Math.min(content.length(), lastStart + lastCount);
                // 兼容输入法首字符事件 lastCount=0（新增字符但 count 为 0）：至少检查输入位置。
                if (lastCount == 0 && start > 0 && start < content.length()) {
                    end = Math.min(content.length(), start + 1);
                }
                boolean triggered = false;
                for (int i = start; i < end; i++) {
                    if (content.charAt(i) == '@'
                            && (i == 0 || Character.isWhitespace(content.charAt(i - 1)))) {
                        showMentionPicker();
                        triggered = true;
                        break;
                    }
                }
                if (!triggered && mentionDialog != null && mentionDialog.isShowing()) {
                    // 输入内容偏离 @ 选区后自动收起选择器（如继续打字或删除 @）。
                    String tail = content.length() >= 1 ? content.substring(content.length() - 1) : "";
                    boolean stillAt = content.endsWith("@")
                            || (content.length() > 1 && content.charAt(content.length() - 2) == '@'
                                    && Character.isWhitespace(content.charAt(content.length() - 1)));
                    if (!stillAt && !hasActiveMentionDraft(content)) {
                        closeMentionPicker();
                    }
                }
            }
        });
    }

    private void rebuildMentionMembersFromSession() {
        HashSet<String> seen = new HashSet<String>();
        boolean allowSessionMerge = mentionMembers.isEmpty();
        for (int i = 0; i < mentionMembers.size(); i++) {
            GroupMember existing = mentionMembers.get(i);
            if (existing == null || existing.uid == null || existing.uid.isEmpty()) {
                continue;
            }
            seen.add(existing.uid);
            if (existing.display_name == null || existing.display_name.isEmpty()) {
                existing.display_name = resolveDisplayName(existing.uid);
            }
            if (existing.avatar_url == null || existing.avatar_url.isEmpty()) {
                existing.avatar_url = avatarMap.get(existing.uid);
            }
            Integer existingRole = roleMap.get(existing.uid);
            if (existingRole != null) {
                existing.role = existingRole.intValue();
            }
        }
        if (!allowSessionMerge) {
            return;
        }
        for (int i = messageList.size() - 1; i >= 0; i--) {
            GroupMessage msg = messageList.get(i);
            if (msg == null || msg.from_uid == null || msg.from_uid.isEmpty()) {
                continue;
            }
            String uid = msg.from_uid;
            if (seen.contains(uid)) {
                continue;
            }
            seen.add(uid);
            GroupMember member = new GroupMember();
            member.uid = uid;
            member.ncuid = msg.sender_ncuid;
            member.display_name = resolveDisplayName(uid);
            member.avatar_url = avatarMap.get(uid);
            Integer role = roleMap.get(uid);
            member.role = role == null ? 0 : role.intValue();
            mentionMembers.add(member);
        }
    }

    /** 关闭 @成员选择器（输入偏离 @ 选区时调用）。 */
    private void closeMentionPicker() {
        if (mentionDialog != null && mentionDialog.isShowing()) {
            try {
                mentionDialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 文本中是否仍有未完成的 @mention（mention 标记 = @名字）。 */
    private boolean hasActiveMentionDraft(String content) {
        if (content == null || content.isEmpty() || mentionDrafts.isEmpty()) {
            return false;
        }
        for (MessagePayload.Mention mention : mentionDrafts) {
            if (mention == null) {
                continue;
            }
            String tag = buildMentionTag(mention);
            if (tag != null && tag.length() > 1 && content.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private void showMentionPicker() {
        if (mentionDialog != null && mentionDialog.isShowing()) {
            return;
        }
        rebuildMentionMembersFromSession();
        View view = getLayoutInflater().inflate(R.layout.dialog_group_mention, null);
        final EditText etSearch = (EditText) view.findViewById(R.id.etMentionSearch);
        final ListView lvMembers = (ListView) view.findViewById(R.id.lvMentionList);
        mentionAdapter = new GroupMentionAdapter(this, mentionMembers, myUID);
        String myNcuid = getSharedPreferences("auth", MODE_PRIVATE).getString("my_ncuid", "");
        mentionAdapter.setExcludeNcuid(myNcuid);
        mentionAdapter.setMyRole(myRole);
        lvMembers.setAdapter(mentionAdapter);
        final int[] queryGeneration = new int[]{0};
        loadMentionMembersFromCache("", etSearch, ++queryGeneration[0]);
        lvMembers.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                GroupMember member = mentionAdapter.getItem(position);
                insertMention(member);
                if (mentionDialog != null) {
                    mentionDialog.dismiss();
                }
            }
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                final String query = s == null ? "" : s.toString().trim();
                // 先对弹窗打开时已有的会话成员做即时内存过滤，避免等待磁盘查询时
                // 列表闪空，也保证尚未写入缓存的新成员可以被搜到。
                if (mentionAdapter != null) mentionAdapter.filter(query);
                final int generation = ++queryGeneration[0];
                etSearch.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (generation == queryGeneration[0]) {
                            loadMentionMembersFromCache(query, etSearch, generation);
                        }
                    }
                }, 250L);
            }
        });
        mentionDialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("选择成员")
                .setView(view)
                .setNegativeButton("取消", null)
                .create();
        mentionDialog.show();
    }

    private void loadMentionMembersFromCache(final String query, final EditText search,
                                               final int generation) {
        GroupMentionCacheQuery.load(this, groupId, query, new GroupMentionCacheQuery.Callback() {
            @Override public void onResult(String loadedQuery, List<GroupMember> rows) {
                if (mentionAdapter == null || isFinishing()) return;
                String current = search.getText() == null ? "" : search.getText().toString().trim();
                if (!current.equals(loadedQuery)) return;
                // 有搜索词时直接显示本地缓存过滤结果，避免"过滤结果 <50 就触发全量同步"导致
                // 每次击键都空白/闪烁。仅在无搜索词的全量加载时，才判断缓存是否足够并同步。
                boolean hasQuery = loadedQuery != null && loadedQuery.length() > 0;
                if (!hasQuery) {
                    boolean cacheEmpty = rows == null || rows.isEmpty();
                    boolean cacheThin = rows != null && rows.size() < 50;
                    if (cacheEmpty || cacheThin) {
                        // 全量缓存不足才触发一次分页同步，完成后刷新列表。
                        syncMembersForMention(query, search, generation);
                        return;
                    }
                }
                if (hasQuery) {
                    mentionAdapter.mergeMembers(rows);
                } else {
                    mentionAdapter.setMembers(rows);
                }
                mentionAdapter.filter(loadedQuery);
            }
        });
    }

    /** @选择器本地无数据时触发一次分页同步，完成后重查并刷新列表。 */
    private void syncMembersForMention(final String query, final EditText search,
                                       final int generation) {
        if (token == null || token.length() == 0 || groupId == null || groupId.length() == 0) {
            return;
        }
        new GroupMemberPagedSync(this, token, groupId, new GroupMemberPagedSync.Callback() {
            @Override public void onComplete(int total) {
                if (mentionAdapter == null || isFinishing()) return;
                GroupMentionCacheQuery.load(GroupChatActivitySupport1.this, groupId, query,
                        new GroupMentionCacheQuery.Callback() {
                    @Override public void onResult(String loadedQuery, List<GroupMember> rows) {
                        if (mentionAdapter == null || isFinishing()) return;
                        String current = search.getText() == null ? ""
                                : search.getText().toString().trim();
                        if (!current.equals(loadedQuery)) return;
                        if (loadedQuery != null && loadedQuery.length() > 0) {
                            mentionAdapter.mergeMembers(rows);
                        } else {
                            mentionAdapter.setMembers(rows);
                        }
                        mentionAdapter.filter(loadedQuery);
                    }
                });
            }

            @Override public void onError() {
            }
        }).start();
    }

    private void insertMention(GroupMember member) {
        if (member == null || etInput == null) {
            return;
        }
        String name = resolveMemberName(member);
        if (name == null || name.isEmpty()) {
            return;
        }
        addMentionDraft(member.uid, member.ncuid, name);
        Editable text = etInput.getText();
        if (text == null) {
            return;
        }
        int cursor = etInput.getSelectionStart();
        if (cursor < 0 || cursor > text.length()) {
            cursor = text.length();
        }
        int start = findMentionStart(text, cursor);
        suppressMentionTrigger = true;
        text.replace(start, cursor, "@" + name + " ");
        suppressMentionTrigger = false;
    }

    private int findMentionStart(CharSequence text, int cursor) {
        if (text == null || cursor <= 0) {
            return cursor;
        }
        for (int i = cursor - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                return i;
            }
            if (Character.isWhitespace(c)) {
                break;
            }
        }
        return cursor;
    }

    private void addMentionDraft(String uid, String ncuid, String name) {
        if ((uid == null || uid.isEmpty()) && (ncuid == null || ncuid.isEmpty())) {
            return;
        }
        String display = name == null || name.isEmpty() ? (uid == null || uid.isEmpty() ? ncuid : uid) : name;
        for (MessagePayload.Mention mention : mentionDrafts) {
            if (mention == null) {
                continue;
            }
            boolean sameUid = uid != null && !uid.isEmpty() && uid.equals(mention.uid);
            boolean sameNcuid = ncuid != null && !ncuid.isEmpty() && ncuid.equals(mention.ncuid);
            if (sameUid || sameNcuid) {
                if (mention.uid == null || mention.uid.isEmpty()) {
                    mention.uid = uid;
                }
                if (mention.ncuid == null || mention.ncuid.isEmpty()) {
                    mention.ncuid = ncuid;
                }
                mention.name = display;
                return;
            }
        }
        MessagePayload.Mention mention = new MessagePayload.Mention();
        mention.uid = uid;
        mention.ncuid = ncuid;
        mention.name = display;
        mentionDrafts.add(mention);
    }

    private void pruneMentions(String content) {
        if (mentionDrafts.isEmpty()) {
            return;
        }
        String text = content == null ? "" : content;
        for (int i = mentionDrafts.size() - 1; i >= 0; i--) {
            MessagePayload.Mention mention = mentionDrafts.get(i);
            if (mention == null || ((mention.ncuid == null || mention.ncuid.isEmpty())
                    && (mention.uid == null || mention.uid.isEmpty()))) {
                mentionDrafts.remove(i);
                continue;
            }
            if (!text.contains(buildMentionTag(mention))) {
                mentionDrafts.remove(i);
            }
        }
    }

    private String buildMentionTag(MessagePayload.Mention mention) {
        if (mention == null) {
            return "";
        }
        String name = mention.name == null || mention.name.isEmpty()
                ? (mention.uid != null && !mention.uid.isEmpty() ? mention.uid : mention.ncuid)
                : mention.name;
        return "@" + (name == null ? "" : name);
    }

    @Override
    protected List<MessagePayload.Mention> collectMentions(String content) {
        if (content == null || content.isEmpty() || mentionDrafts.isEmpty()) {
            return null;
        }
        List<MessagePayload.Mention> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (MessagePayload.Mention mention : mentionDrafts) {
            if (mention == null) {
                continue;
            }
            String identity = mention.ncuid != null && !mention.ncuid.isEmpty()
                    ? mention.ncuid : mention.uid;
            if (identity == null || identity.isEmpty() || seen.contains(identity)) {
                continue;
            }
            if (content.contains(buildMentionTag(mention))) {
                result.add(mention);
                seen.add(identity);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private String resolveMemberName(GroupMember member) {
        if (member == null) {
            return "";
        }
        if (member.display_name != null && !member.display_name.isEmpty()) {
            return member.display_name;
        }
        if (member.username != null && !member.username.isEmpty()) {
            return member.username;
        }
        return member.uid == null ? "" : member.uid;
    }
}
