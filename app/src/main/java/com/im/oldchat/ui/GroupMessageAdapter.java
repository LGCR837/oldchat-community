package com.im.oldchat.ui;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.util.MessagePayload;

import java.util.List;
import java.util.Map;

public class GroupMessageAdapter extends GroupMessageAdapterRenderSupport2 {
    public interface GroupMessageActionListener {
        void onQuote(GroupMessage message, String displayName);

        void onMention(GroupMessage message, String displayName);

        void onCopy(String text);

        void onRecall(GroupMessage message);

        void onReEdit(GroupMessage message);

        void onVoiceTranscribe(GroupMessage message);

        void onForward(GroupMessage message);

        void onEnterMultiSelect(GroupMessage message);
    }

    public interface SelectionModeListener {
        void onSelectionModeChanged(boolean enabled, int count);
    }

    public interface QuoteClickListener {
        void onQuoteClick(MessagePayload.Quote quote);
    }

    public GroupMessageAdapter(android.content.Context context, List<GroupMessage> messages, String myUID) {
        super(context, messages, myUID);
    }

    /** 当前已加载消息涉及的稳定成员身份，用于 SQLite 批量按需查询。 */
    List<String> getLoadedSenderNcuids() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<String>();
        int start = Math.max(0, messages.size() - 300);
        for (int i = start; i < messages.size(); i++) {
            GroupMessage message = messages.get(i);
            if (message == null) continue;
            if (message.sender_ncuid != null && message.sender_ncuid.length() > 0) {
                ids.add(message.sender_ncuid);
            }
            if (message.from_uid != null && message.from_uid.length() > 0) {
                ids.add(message.from_uid);
            }
        }
        return new java.util.ArrayList<String>(ids);
    }

    GroupMessageSyncHelper.MemberMetaSnapshot createMemberMetaSnapshot() {
        return GroupMessageSyncHelper.MemberMetaSnapshot.create(memberMetaVersion,
                nameMap, avatarMap, titleMap, roleMap);
    }

    public void updateMemberMeta(Map<String, String> nameMap,
                                 Map<String, String> avatarMap,
                                 Map<String, String> titleMap,
                                  Map<String, Integer> roleMap) {
        updateMemberMeta(nameMap, avatarMap, titleMap, roleMap, true);
    }

    public void updateMemberMeta(Map<String, String> nameMap,
                                 Map<String, String> avatarMap,
                                 Map<String, String> titleMap,
                                 Map<String, Integer> roleMap,
                                 boolean refreshVisible) {
        if (nameMap != null) {
            this.nameMap = nameMap;
        }
        if (avatarMap != null) {
            this.avatarMap = avatarMap;
        }
        if (titleMap != null) {
            this.titleMap = titleMap;
        }
        if (roleMap != null) {
            this.roleMap = roleMap;
        }
        clearResolvedMemberMetaCache();
        if (refreshVisible) {
            refreshVisibleRows();
        }
    }

    /** 单成员资料解析完成（新消息发送者 lookup 回调）：写入活动映射并刷新可见行。 */
    public void onMemberMetaResolved(com.im.oldchat.models.GroupMember member) {
        if (member == null || member.ncuid == null || member.ncuid.length() == 0) {
            return;
        }
        String key = member.ncuid;
        String name = com.im.oldchat.models.FriendNameResolver.resolve(
                "", member.display_name, member.username, member.uid);
        if (name != null && name.length() > 0) nameMap.put(key, name);
        if (member.avatar_url != null && member.avatar_url.length() > 0) {
            avatarMap.put(key, member.avatar_url);
        }
        if (member.user_title != null && member.user_title.length() > 0) {
            titleMap.put(key, member.user_title);
        }
        roleMap.put(key, Integer.valueOf(member.role));
        clearResolvedMemberMetaCache();
        refreshVisibleRows();
    }
}
