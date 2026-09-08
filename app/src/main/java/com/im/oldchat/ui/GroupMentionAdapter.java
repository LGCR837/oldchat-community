package com.im.oldchat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.models.GroupMember;
import com.im.oldchat.util.GroupAvatarCache;
import com.im.oldchat.util.ImageLoader;
import java.util.ArrayList;
import java.util.List;

public class GroupMentionAdapter extends BaseAdapter {
    private final Context context;
    private final List<GroupMember> allMembers = new ArrayList<>();
    private final List<GroupMember> filtered = new ArrayList<>();
    private String excludeUid;
    private String excludeNcuid;
    private String query = "";
    private int myRole = 0;

    public GroupMentionAdapter(Context context, List<GroupMember> members, String excludeUid) {
        this.context = context;
        this.excludeUid = excludeUid;
        setMembers(members);
    }

    public void setMyRole(int role) {
        this.myRole = role;
        filter(query);
    }

    public void setMembers(List<GroupMember> members) {
        allMembers.clear();
        mergeMembers(members);
        filter(query);
    }

    /** 合并磁盘/网络搜索结果，不覆盖弹窗打开时已有的会话成员。 */
    public void mergeMembers(List<GroupMember> members) {
        if (members == null || members.isEmpty()) return;
        for (int i = 0; i < members.size(); i++) {
            GroupMember candidate = members.get(i);
            if (candidate == null) continue;
            int existingIndex = findMember(candidate);
            if (existingIndex < 0) {
                allMembers.add(candidate);
            } else {
                GroupMember existing = allMembers.get(existingIndex);
                mergeMemberFields(existing, candidate);
            }
        }
    }

    private int findMember(GroupMember candidate) {
        for (int i = 0; i < allMembers.size(); i++) {
            GroupMember existing = allMembers.get(i);
            if (existing == null) continue;
            if (candidate.ncuid != null && candidate.ncuid.length() > 0
                    && candidate.ncuid.equals(existing.ncuid)) return i;
            if (candidate.uid != null && candidate.uid.length() > 0
                    && candidate.uid.equals(existing.uid)) return i;
        }
        return -1;
    }

    private void mergeMemberFields(GroupMember target, GroupMember source) {
        if ((target.display_name == null || target.display_name.length() == 0)
                && source.display_name != null) target.display_name = source.display_name;
        if ((target.username == null || target.username.length() == 0)
                && source.username != null) target.username = source.username;
        if ((target.avatar_url == null || target.avatar_url.length() == 0)
                && source.avatar_url != null) target.avatar_url = source.avatar_url;
        if ((target.ncuid == null || target.ncuid.length() == 0)
                && source.ncuid != null) target.ncuid = source.ncuid;
        if (source.role > target.role) target.role = source.role;
    }

    public void setExcludeUid(String uid) {
        this.excludeUid = uid;
        filter(query);
    }

    public void setExcludeNcuid(String ncuid) {
        this.excludeNcuid = ncuid;
        filter(query);
    }

    public void filter(String query) {
        this.query = query == null ? "" : query.trim();
        filtered.clear();
        String q = this.query.toLowerCase();

        // Add @all option for admins and owners
        if (myRole >= 1) {
            if (q.length() == 0 || "所有人".contains(q) || "all".contains(q)) {
                GroupMember allMember = new GroupMember();
                allMember.uid = "ALL";
                allMember.display_name = "所有人";
                allMember.username = "all";
                allMember.role = -1; // Special role for @all
                filtered.add(allMember);
            }
        }

        for (int i = 0; i < allMembers.size(); i++) {
            GroupMember member = allMembers.get(i);
            if (member == null || member.uid == null || member.uid.isEmpty()) {
                continue;
            }
            if ((excludeNcuid != null && excludeNcuid.equals(member.ncuid))
                    || (excludeUid != null && excludeUid.equals(member.uid))) {
                continue;
            }
            String name = displayName(member);
            if (q.length() == 0) {
                filtered.add(member);
            } else {
                String nameLower = name == null ? "" : name.toLowerCase();
                String uidLower = member.uid.toLowerCase();
                String usernameLower = member.username == null ? "" : member.username.toLowerCase();
                if (nameLower.contains(q) || uidLower.contains(q) || usernameLower.contains(q)) {
                    filtered.add(member);
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return filtered.size();
    }

    @Override
    public GroupMember getItem(int position) {
        return filtered.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_group_mention, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        GroupMember member = getItem(position);
        String name = displayName(member);
        holder.name.setText(name == null ? "" : name);

        // Special handling for @all
        if ("ALL".equals(member.uid)) {
            holder.uid.setText("提及所有成员");
            holder.avatar.setImageResource(R.drawable.ic_notification);
        } else {
            holder.uid.setText(member.uid == null ? "" : member.uid);
            String avatarUrl = member.avatar_url;
            if (avatarUrl == null || avatarUrl.isEmpty()) {
                String identity = member.ncuid == null || member.ncuid.isEmpty()
                        ? member.uid : member.ncuid;
                avatarUrl = GroupAvatarCache.getCachedAvatar(context, identity);
            }
            ImageLoader.loadAvatarCacheFirstTryOnce(holder.avatar, avatarUrl);
        }
        return convertView;
    }

    private String displayName(GroupMember member) {
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

    private static class ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView uid;

        ViewHolder(View view) {
            avatar = view.findViewById(R.id.ivAvatar);
            name = view.findViewById(R.id.tvName);
            uid = view.findViewById(R.id.tvUid);
        }
    }
}
