package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GroupMemberAdapter extends BaseAdapter {
    public interface ActionListener {
        void onKick(GroupMember member);
        void onToggleAdmin(GroupMember member, boolean makeAdmin);
    }

    private final Context context;
    private List<GroupMember> members;
    private int myRole;
    private final ActionListener listener;
    private final Map<String, String> avatarFallbackMap = new HashMap<String, String>();

    public GroupMemberAdapter(Context context, List<GroupMember> members, int myRole, ActionListener listener) {
        this.context = context;
        this.members = members;
        this.myRole = myRole;
        this.listener = listener;
        rebuildAvatarFallbackMap();
    }

    public void setMembers(List<GroupMember> members) {
        this.members = members;
        rebuildAvatarFallbackMap();
        notifyDataSetChanged();
    }

    public void setMyRole(int myRole) {
        this.myRole = myRole;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return members == null ? 0 : members.size();
    }

    @Override
    public GroupMember getItem(int position) {
        if (members == null || position < 0 || position >= members.size()) {
            return null;
        }
        return members.get(position);
    }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        GroupMember member = getItem(position);
        if (member == null) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_group_member, parent, false);
                convertView.setTag(new ViewHolder(convertView));
            }
            return convertView;
        }
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_group_member, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        String name = member.display_name != null && !member.display_name.isEmpty()
                ? member.display_name : member.username;
        holder.title.setText(name == null ? "" : name);
        bindRoleBadge(holder.roleBadge, member.role);
        UserTitleBinder.bind(holder.titleBadge, member.user_title);
        holder.subtitle.setText(member.uid);
        String avatarUrl = member.avatar_url;
        if (TextUtils.isEmpty(avatarUrl)) {
            avatarUrl = avatarFallbackMap.get(member.ncuid);
            if (TextUtils.isEmpty(avatarUrl)) {
                avatarUrl = avatarFallbackMap.get(normalizeUid(member.ncuid));
            }
        }
        ImageLoader.loadAvatarCacheFirstTryOnce(holder.avatar, avatarUrl);

        boolean isOwner = member.role == 2;
        boolean isAdmin = member.role == 1;
        boolean canKick = myRole >= 1 && !isOwner;
        boolean canSetAdmin = myRole == 2 && !isOwner;

        holder.member = member;
        holder.listener = listener;
        holder.makeAdmin = !isAdmin;
        holder.btnKick.setVisibility(canKick ? View.VISIBLE : View.GONE);
        holder.btnAdmin.setVisibility(canSetAdmin ? View.VISIBLE : View.GONE);
        holder.btnAdmin.setText(isAdmin ? "取消管理员" : "设为管理员");

        return convertView;
    }

    private void rebuildAvatarFallbackMap() {
        avatarFallbackMap.clear();
        if (members == null || members.isEmpty()) {
            return;
        }
        Map<String, String> lookup = new HashMap<String, String>();
        for (int i = 0; i < members.size(); i++) {
            GroupMember member = members.get(i);
            if (member == null || TextUtils.isEmpty(member.ncuid)) {
                continue;
            }
            if (!TextUtils.isEmpty(member.avatar_url)) {
                avatarFallbackMap.put(member.ncuid, member.avatar_url);
                avatarFallbackMap.put(normalizeUid(member.ncuid), member.avatar_url);
            } else {
                lookup.put(member.ncuid, "");
                lookup.put(normalizeUid(member.ncuid), "");
            }
        }
        if (!lookup.isEmpty()) {
            GroupAvatarCache.fillMissing(context, lookup);
            for (int i = 0; i < members.size(); i++) {
                GroupMember member = members.get(i);
                if (member == null || TextUtils.isEmpty(member.ncuid) || !TextUtils.isEmpty(member.avatar_url)) {
                    continue;
                }
                String avatarUrl = lookup.get(member.ncuid);
                if (TextUtils.isEmpty(avatarUrl)) {
                    avatarUrl = lookup.get(normalizeUid(member.ncuid));
                }
                if (!TextUtils.isEmpty(avatarUrl)) {
                    member.avatar_url = avatarUrl;
                    avatarFallbackMap.put(member.ncuid, avatarUrl);
                    avatarFallbackMap.put(normalizeUid(member.ncuid), avatarUrl);
                }
            }
        }
    }

    private String normalizeUid(String uid) {
        if (uid == null) {
            return "";
        }
        String out = uid.trim();
        while (out.startsWith("@")) {
            out = out.substring(1).trim();
        }
        if (out.length() == 0) {
            return "";
        }
        return out.toUpperCase(Locale.US);
    }

    private void bindRoleBadge(TextView badge, int role) {
        if (badge == null) {
            return;
        }
        if (role == 2) {
            badge.setText("群主");
            badge.setBackgroundResource(R.drawable.bg_badge_owner);
            badge.setVisibility(View.VISIBLE);
        } else if (role == 1) {
            badge.setText("管理员");
            badge.setBackgroundResource(R.drawable.bg_badge_admin);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private static class ViewHolder {
        final ImageView avatar;
        final TextView title;
        final TextView subtitle;
        final TextView roleBadge;
        final TextView titleBadge;
        final TextView btnKick;
        final TextView btnAdmin;
        GroupMember member;
        ActionListener listener;
        boolean makeAdmin;

        ViewHolder(final View view) {
            avatar = view.findViewById(R.id.ivAvatar);
            title = view.findViewById(R.id.tvTitle);
            subtitle = view.findViewById(R.id.tvSubtitle);
            roleBadge = view.findViewById(R.id.tvRoleBadge);
            titleBadge = view.findViewById(R.id.tvTitleBadge);
            btnKick = view.findViewById(R.id.btnKick);
            btnAdmin = view.findViewById(R.id.btnAdmin);
            btnKick.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null && member != null) {
                        listener.onKick(member);
                    }
                }
            });
            btnAdmin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null && member != null) {
                        listener.onToggleAdmin(member, makeAdmin);
                    }
                }
            });
            avatar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (member == null || member.ncuid == null || member.ncuid.isEmpty()) {
                        return;
                    }
                    Context context = view.getContext();
                    Intent intent = new Intent(context, UserSpaceActivity.class);
                    intent.putExtra("ncuid", member.ncuid);
                    context.startActivity(intent);
                }
            });
        }
    }
}
