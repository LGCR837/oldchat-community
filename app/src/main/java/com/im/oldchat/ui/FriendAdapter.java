package com.im.oldchat.ui;

import android.content.Context;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.User;
import com.im.oldchat.ui.widget.TiltFrameLayout;

import java.util.ArrayList;
import java.util.List;

public class FriendAdapter extends BaseAdapter {
    private static final int TYPE_SECTION = 0;
    private static final int TYPE_REQUEST = 1;
    private static final int TYPE_GROUP = 2;
    private static final int TYPE_FRIEND = 3;
    private static final int TYPE_SYSTEM = 4;

    private static final String SEC_GROUPS = "groups";
    private static final String SEC_RECENT = "recent";
    private static final String SEC_REQUESTS = "requests";
    private static final String SEC_FRIENDS = "friends";

    public static final String SYSTEM_UID = "SYSTEM";

    private final List<Object> items = new ArrayList<Object>();
    private final List<Group> groups = new ArrayList<Group>();
    private final List<User> recentFriends = new ArrayList<User>();
    private final List<User> otherFriends = new ArrayList<User>();
    private final List<FriendRequestItem> requests = new ArrayList<FriendRequestItem>();

    private boolean groupsExpanded = true;
    private boolean recentExpanded = true;
    private boolean requestsExpanded = true;
    private boolean friendsExpanded = true;
    private String query = "";
    private String animExpandSectionKey;
    private final SparseBooleanArray animatedRowPositions = new SparseBooleanArray();

    private final Context context;
    private final LayoutInflater inflater;
    private final OnFriendClickListener listener;

    public interface OnFriendClickListener {
        void onFriendClick(User friend);
        void onAvatarClick(User friend);
        void onRequestAccept(String requestId);
        void onRequestReject(String requestId);
        void onGroupClick(Group group);
        void onSystemNotificationClick();
    }

    public static class SystemNotificationEntry {
        public static final SystemNotificationEntry INSTANCE = new SystemNotificationEntry();
    }

    public static class SectionHeader {
        public final String key;
        public final String title;
        public final int count;
        public final boolean expanded;
        public final boolean collapsible;

        SectionHeader(String key, String title, int count, boolean expanded, boolean collapsible) {
            this.key = key;
            this.title = title;
            this.count = count;
            this.expanded = expanded;
            this.collapsible = collapsible;
        }
    }

    public static class FriendRequestItem {
        public static final String TYPE_FRIEND = "friend";
        public static final String TYPE_GROUP_INVITE = "group_invite";
        public String id;
        public String requestType = TYPE_FRIEND;
        public String fromUID;
        public String fromName;
        public String fromTitle;
        public String avatarUrl;
        public String groupId;
        public String groupName;

        public FriendRequestItem(String id, String uid, String name, String title, String avatarUrl) {
            this.id = id;
            this.fromUID = uid;
            this.fromName = name;
            this.fromTitle = title;
            this.avatarUrl = avatarUrl;
        }

        public static FriendRequestItem groupInvite(String id, String groupId, String groupName,
                                                     String groupAvatar, String inviterUid, String inviterName) {
            FriendRequestItem item = new FriendRequestItem(id, inviterUid, inviterName, "", groupAvatar);
            item.requestType = TYPE_GROUP_INVITE;
            item.groupId = groupId;
            item.groupName = groupName;
            return item;
        }

        public boolean isGroupInvite() {
            return TYPE_GROUP_INVITE.equals(requestType);
        }
    }

    public FriendAdapter(Context context, OnFriendClickListener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    public void setData(List<Group> groups, List<User> recentFriends, List<User> otherFriends, List<FriendRequestItem> requests) {
        this.groups.clear();
        this.recentFriends.clear();
        this.otherFriends.clear();
        this.requests.clear();
        if (groups != null) this.groups.addAll(groups);
        if (recentFriends != null) this.recentFriends.addAll(recentFriends);
        if (otherFriends != null) this.otherFriends.addAll(otherFriends);
        if (requests != null) this.requests.addAll(requests);
        clearExpandAnimationState();
        rebuildItems();
    }

    public void filter(String query) {
        this.query = query == null ? "" : query.trim();
        clearExpandAnimationState();
        rebuildItems();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 5;
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof SectionHeader) return TYPE_SECTION;
        if (item instanceof FriendRequestItem) return TYPE_REQUEST;
        if (item instanceof Group) return TYPE_GROUP;
        if (item instanceof SystemNotificationEntry) return TYPE_SYSTEM;
        return TYPE_FRIEND;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        int type = getItemViewType(position);
        Object item = items.get(position);
        if (type == TYPE_SECTION) {
            return applySeamlessRow(bindSectionView(item, convertView, parent));
        }
        View out;
        if (type == TYPE_SYSTEM) out = FriendAdapterRender.bindSystem(context, convertView, parent, inflater, listener);
        else if (type == TYPE_REQUEST) out = FriendAdapterRender.bindRequest(convertView, parent, inflater, (FriendRequestItem) item, listener);
        else if (type == TYPE_GROUP) out = FriendAdapterRender.bindGroup(convertView, parent, inflater, (Group) item, listener);
        else out = FriendAdapterRender.bindFriend(convertView, parent, inflater, (User) item, listener);
        maybeApplyExpandAnimation(out, position);
        return applySeamlessRow(out);
    }

    private View applySeamlessRow(View row) {
        if (row == null) {
            return null;
        }
        row.setBackgroundResource(R.drawable.bg_seamless_row);
        if (row instanceof TiltFrameLayout) {
            ((TiltFrameLayout) row).setTiltEnabled(false);
        }
        if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
            ((ViewGroup) row).getChildAt(0).setBackgroundResource(R.drawable.bg_seamless_row);
        }
        return row;
    }

    private void rebuildItems() {
        items.clear();
        String q = query == null ? "" : query.trim();
        if (q.length() > 0) {
            rebuildSearchItems(q.toLowerCase());
            notifyDataSetChanged();
            return;
        }

        appendSection(SEC_GROUPS, "GROUPS", groups.size(), groupsExpanded);
        if (groupsExpanded) items.addAll(groups);

        appendSection(SEC_RECENT, "RECENT", recentFriends.size(), recentExpanded);
        if (recentExpanded) items.addAll(recentFriends);

        appendSection(SEC_REQUESTS, "REQUESTS", requests.size(), requestsExpanded);
        if (requestsExpanded) {
            items.add(SystemNotificationEntry.INSTANCE);
            items.addAll(requests);
        }

        appendSection(SEC_FRIENDS, "FRIENDS", otherFriends.size(), friendsExpanded);
        if (friendsExpanded) items.addAll(otherFriends);

        notifyDataSetChanged();
    }

    private void rebuildSearchItems(String qLower) {
        List<Object> results = new ArrayList<Object>();
        if (containsIgnoreCase("\u7CFB\u7EDF\u901A\u77E5 \u5B98\u65B9\u516C\u544A\u548C\u901A\u77E5", qLower)
                || containsIgnoreCase("\u7CFB\u7EDF\u901A\u77E5", qLower)) {
            results.add(SystemNotificationEntry.INSTANCE);
        }
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            if (g != null && (containsIgnoreCase(g.name, qLower) || containsIgnoreCase(g.id, qLower))) results.add(g);
        }
        for (int i = 0; i < requests.size(); i++) {
            FriendRequestItem r = requests.get(i);
            if (r != null && (containsIgnoreCase(r.fromName, qLower) || containsIgnoreCase(r.fromUID, qLower)
                    || containsIgnoreCase(r.groupName, qLower) || containsIgnoreCase(r.groupId, qLower))) results.add(r);
        }
        for (int i = 0; i < recentFriends.size(); i++) if (matchesUser(recentFriends.get(i), qLower)) results.add(recentFriends.get(i));
        for (int i = 0; i < otherFriends.size(); i++) if (matchesUser(otherFriends.get(i), qLower)) results.add(otherFriends.get(i));

        if (results.isEmpty()) {
            items.add(new SectionHeader(null, "\u65E0\u5339\u914D\u7ED3\u679C", 0, true, false));
        } else {
            items.add(new SectionHeader(null, "\u641C\u7D22\u7ED3\u679C", results.size(), true, false));
            items.addAll(results);
        }
    }

    private void appendSection(String key, String title, int count, boolean expanded) {
        items.add(new SectionHeader(key, title, count, expanded, true));
    }

    private View bindSectionView(Object item, View convertView, ViewGroup parent) {
        if (convertView == null) convertView = inflater.inflate(R.layout.item_friend_section_header, parent, false);
        final SectionHeader header = (SectionHeader) item;
        TextView title = (TextView) convertView.findViewById(R.id.tvSectionTitle);
        TextView badge = (TextView) convertView.findViewById(R.id.tvBadge);
        View dot = convertView.findViewById(R.id.vSectionDot);
        View touch = convertView.findViewById(R.id.sectionHeaderTouch);
        final View clickTarget = touch == null ? convertView : touch;
        title.setText(header.title);
        badge.setText(header.count > 99 ? "99+" : String.valueOf(header.count));
        badge.setBackgroundResource(R.drawable.bg_pastel_pill_yellow);
        badge.setTextColor(context.getResources().getColor(R.color.home_count_text_muted));
        dot.setBackgroundResource(header.expanded ? R.drawable.bg_pastel_dot_open : R.drawable.bg_pastel_dot_closed);
        if (!header.collapsible || header.key == null || query.length() > 0) {
            convertView.setOnClickListener(null);
            clickTarget.setOnClickListener(null);
            return convertView;
        }
        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSection(header.key);
            }
        };
        convertView.setOnClickListener(click);
        clickTarget.setOnClickListener(click);
        return convertView;
    }

    private void toggleSection(String key) {
        boolean wasExpanded = isSectionExpanded(key);
        if (SEC_GROUPS.equals(key)) groupsExpanded = !groupsExpanded;
        else if (SEC_RECENT.equals(key)) recentExpanded = !recentExpanded;
        else if (SEC_REQUESTS.equals(key)) requestsExpanded = !requestsExpanded;
        else if (SEC_FRIENDS.equals(key)) friendsExpanded = !friendsExpanded;
        boolean nowExpanded = isSectionExpanded(key);
        if (!wasExpanded && nowExpanded) {
            animExpandSectionKey = key;
            animatedRowPositions.clear();
        } else {
            clearExpandAnimationState();
        }
        rebuildItems();
    }

    private boolean isSectionExpanded(String key) {
        if (SEC_GROUPS.equals(key)) return groupsExpanded;
        if (SEC_RECENT.equals(key)) return recentExpanded;
        if (SEC_REQUESTS.equals(key)) return requestsExpanded;
        if (SEC_FRIENDS.equals(key)) return friendsExpanded;
        return false;
    }

    private void clearExpandAnimationState() { animExpandSectionKey = null; animatedRowPositions.clear(); }

    private void maybeApplyExpandAnimation(View row, int position) {
        if (row == null) return;
        if (animExpandSectionKey == null || query.length() > 0) {
            row.clearAnimation();
            return;
        }
        if (!isInAnimatedSection(position)) {
            row.clearAnimation();
            return;
        }
        if (animatedRowPositions.get(position)) return;
        animatedRowPositions.put(position, true);
        int sectionRowIndex = getSectionRowIndex(position);
        AnimationSet set = new AnimationSet(true);
        set.setInterpolator(new DecelerateInterpolator());
        set.setDuration(220L);
        if (sectionRowIndex >= 0) {
            long offset = sectionRowIndex * 22L;
            if (offset > 120L) offset = 120L;
            set.setStartOffset(offset);
        }
        set.addAnimation(new AlphaAnimation(0.3f, 1f));
        set.addAnimation(new TranslateAnimation(0f, 0f, dp(12), 0f));
        row.startAnimation(set);
    }

    private boolean isInAnimatedSection(int position) {
        for (int i = position; i >= 0; i--) {
            Object item = items.get(i);
            if (item instanceof SectionHeader) {
                SectionHeader header = (SectionHeader) item;
                return header.key != null && header.key.equals(animExpandSectionKey);
            }
        }
        return false;
    }

    private int getSectionRowIndex(int position) {
        int index = 0;
        for (int i = position - 1; i >= 0; i--) {
            Object item = items.get(i);
            if (item instanceof SectionHeader) return index;
            index++;
        }
        return -1;
    }

    private float dp(int value) {
        return context.getResources().getDisplayMetrics().density * value;
    }

    private boolean matchesUser(User u, String qLower) {
        if (u == null) return false;
        return containsIgnoreCase(u.remark_name, qLower) || containsIgnoreCase(u.display_name, qLower)
                || containsIgnoreCase(u.username, qLower) || containsIgnoreCase(u.uid, qLower);
    }

    private boolean containsIgnoreCase(String text, String qLower) {
        if (qLower == null || qLower.length() == 0) return true;
        if (text == null) return false;
        try { return text.toLowerCase().contains(qLower); } catch (Exception e) { return false; }
    }
}
