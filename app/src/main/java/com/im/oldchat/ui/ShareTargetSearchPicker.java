package com.im.oldchat.ui;

import android.app.Activity;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.im.oldchat.models.FriendNameResolver;
import com.im.oldchat.models.Group;
import com.im.oldchat.models.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ShareTargetSearchPicker {

    interface FriendSelectCallback {
        void onSelected(User user);
    }

    interface GroupSelectCallback {
        void onSelected(Group group);
    }

    private ShareTargetSearchPicker() {
    }

    static void pickFriend(final Activity activity,
                           final List<User> allFriends,
                           String title,
                           final FriendSelectCallback callback) {
        if (activity == null) {
            return;
        }
        if (allFriends == null || allFriends.isEmpty()) {
            Toast.makeText(activity, "暂无好友", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<User> filtered = new ArrayList<User>(allFriends);
        final List<String> labels = buildFriendLabels(filtered);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_list_item_1, labels);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 12);
        root.setPadding(padding, dp(activity, 6), padding, 0);

        final EditText etSearch = new EditText(activity);
        etSearch.setSingleLine(true);
        etSearch.setHint("搜索好友昵称/UID");
        root.addView(etSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ListView listView = new ListView(activity);
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 320)));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title == null || title.length() == 0 ? "选择好友" : title)
                .setView(root)
                .setNegativeButton("取消", null)
                .create();

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) {
                    return;
                }
                if (callback != null) {
                    callback.onSelected(filtered.get(position));
                }
                dialog.dismiss();
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
                String q = s == null ? "" : s.toString();
                filterFriends(allFriends, filtered, q);
                replaceAdapterItems(adapter, buildFriendLabels(filtered));
            }
        });

        dialog.show();
    }

    static void pickGroup(final Activity activity,
                          final List<Group> allGroups,
                          String title,
                          final GroupSelectCallback callback) {
        if (activity == null) {
            return;
        }
        if (allGroups == null || allGroups.isEmpty()) {
            Toast.makeText(activity, "暂无群聊", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Group> filtered = new ArrayList<Group>(allGroups);
        final List<String> labels = buildGroupLabels(filtered);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_list_item_1, labels);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 12);
        root.setPadding(padding, dp(activity, 6), padding, 0);

        final EditText etSearch = new EditText(activity);
        etSearch.setSingleLine(true);
        etSearch.setHint("搜索群名/群号");
        root.addView(etSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ListView listView = new ListView(activity);
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 320)));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title == null || title.length() == 0 ? "选择群聊" : title)
                .setView(root)
                .setNegativeButton("取消", null)
                .create();

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) {
                    return;
                }
                if (callback != null) {
                    callback.onSelected(filtered.get(position));
                }
                dialog.dismiss();
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
                String q = s == null ? "" : s.toString();
                filterGroups(allGroups, filtered, q);
                replaceAdapterItems(adapter, buildGroupLabels(filtered));
            }
        });

        dialog.show();
    }

    private static void replaceAdapterItems(ArrayAdapter<String> adapter, List<String> values) {
        if (adapter == null) return;
        adapter.clear();
        if (values != null) {
            // ArrayAdapter.addAll(Collection) 从 API 11 才存在。
            for (String value : values) adapter.add(value);
        }
        adapter.notifyDataSetChanged();
    }

    private static List<String> buildFriendLabels(List<User> friends) {
        List<String> labels = new ArrayList<String>();
        if (friends == null) {
            return labels;
        }
        for (int i = 0; i < friends.size(); i++) {
            User one = friends.get(i);
            if (one == null) {
                continue;
            }
            String name = FriendNameResolver.resolve(one);
            if (name == null || name.length() == 0) {
                name = one.uid == null ? "" : one.uid;
            }
            String uid = one.uid == null ? "" : one.uid;
            if (uid.length() > 0 && name.indexOf(uid) < 0) {
                labels.add(name + " (" + uid + ")");
            } else {
                labels.add(name);
            }
        }
        return labels;
    }

    private static List<String> buildGroupLabels(List<Group> groups) {
        List<String> labels = new ArrayList<String>();
        if (groups == null) {
            return labels;
        }
        for (int i = 0; i < groups.size(); i++) {
            Group one = groups.get(i);
            if (one == null) {
                continue;
            }
            String name = safe(one.name);
            String id = safe(one.id);
            if (name.length() == 0) {
                name = id;
            }
            if (id.length() > 0 && name.indexOf(id) < 0) {
                labels.add(name + " (" + id + ")");
            } else {
                labels.add(name);
            }
        }
        return labels;
    }

    private static void filterFriends(List<User> source, List<User> out, String query) {
        out.clear();
        if (source == null || source.isEmpty()) {
            return;
        }
        String q = normalize(query);
        if (q.length() == 0) {
            out.addAll(source);
            return;
        }
        for (int i = 0; i < source.size(); i++) {
            User one = source.get(i);
            if (one == null) {
                continue;
            }
            String name = normalize(FriendNameResolver.resolve(one));
            String uid = normalize(one.uid);
            String username = normalize(one.username);
            String display = normalize(one.display_name);
            String remark = normalize(one.remark_name);
            if (name.contains(q) || uid.contains(q) || username.contains(q)
                    || display.contains(q) || remark.contains(q)) {
                out.add(one);
            }
        }
    }

    private static void filterGroups(List<Group> source, List<Group> out, String query) {
        out.clear();
        if (source == null || source.isEmpty()) {
            return;
        }
        String q = normalize(query);
        if (q.length() == 0) {
            out.addAll(source);
            return;
        }
        for (int i = 0; i < source.size(); i++) {
            Group one = source.get(i);
            if (one == null) {
                continue;
            }
            String name = normalize(one.name);
            String id = normalize(one.id);
            if (name.contains(q) || id.contains(q)) {
                out.add(one);
            }
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.getDefault());
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static int dp(Activity activity, int dp) {
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) (density * dp + 0.5f);
    }
}
