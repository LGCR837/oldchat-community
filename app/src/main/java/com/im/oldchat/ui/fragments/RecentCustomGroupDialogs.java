package com.im.oldchat.ui.fragments;

import android.content.DialogInterface;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;

/** 自定义会话分组的新建、移动和管理对话框。 */
final class RecentCustomGroupDialogs {
    private RecentCustomGroupDialogs() { }

    static void showCreate(final Fragment fragment, final PastelRecentActions.RefreshCallback callback) {
        if (!usable(fragment)) return;
        final EditText input = new EditText(fragment.getActivity());
        input.setHint("分组名称（最多16字）");
        new AlertDialog.Builder(fragment.getActivity())
                .setTitle("新建分组")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText() == null ? "" : input.getText().toString();
                        if (RecentCustomGroupStore.create(fragment.getActivity(), name)) {
                            toast(fragment, "分组已创建");
                            refresh(callback);
                        } else {
                            toast(fragment, "名称为空或分组已存在");
                        }
                    }
                }).show();
    }

    static void showMove(final Fragment fragment, final RecentItem item,
                         final PastelRecentActions.RefreshCallback callback) {
        if (!usable(fragment) || item == null || item.id == null) return;
        final List<RecentCustomGroupStore.Group> groups = RecentCustomGroupStore.list(fragment.getActivity());
        final String[] labels = new String[groups.size() + 1];
        labels[0] = "会话（不分组）";
        for (int i = 0; i < groups.size(); i++) labels[i + 1] = groups.get(i).name;
        new AlertDialog.Builder(fragment.getActivity())
                .setTitle("移动到分组")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String target = which == 0 ? "" : groups.get(which - 1).id;
                        RecentCustomGroupStore.assign(fragment.getActivity(), item.isGroup, item.id, target);
                        toast(fragment, which == 0 ? "已移回会话" : "已移动到“" + groups.get(which - 1).name + "”");
                        refresh(callback);
                    }
                }).show();
    }

    static void showManage(final Fragment fragment, final RecentCustomGroupStore.Group group,
                           final PastelRecentActions.RefreshCallback callback) {
        if (!usable(fragment) || group == null) return;
        final String[] actions = new String[]{"重命名", "删除分组", "向上移动", "向下移动"};
        new AlertDialog.Builder(fragment.getActivity())
                .setTitle(group.name)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) showRename(fragment, group, callback);
                        else if (which == 1) confirmDelete(fragment, group, callback);
                        else if (which == 2) move(fragment, group, -1, callback);
                        else if (which == 3) move(fragment, group, 1, callback);
                    }
                }).show();
    }

    private static void showRename(final Fragment fragment, final RecentCustomGroupStore.Group group,
                                   final PastelRecentActions.RefreshCallback callback) {
        final EditText input = new EditText(fragment.getActivity());
        input.setText(group.name);
        input.setSelection(group.name.length());
        new AlertDialog.Builder(fragment.getActivity())
                .setTitle("重命名分组")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText() == null ? "" : input.getText().toString();
                        if (RecentCustomGroupStore.rename(fragment.getActivity(), group.id, name)) {
                            toast(fragment, "分组已重命名");
                            refresh(callback);
                        } else {
                            toast(fragment, "名称为空或分组已存在");
                        }
                    }
                }).show();
    }

    private static void confirmDelete(final Fragment fragment, final RecentCustomGroupStore.Group group,
                                      final PastelRecentActions.RefreshCallback callback) {
        new AlertDialog.Builder(fragment.getActivity())
                .setTitle("删除分组")
                .setMessage("删除“" + group.name + "”？其中的会话会移回默认会话区。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        RecentCustomGroupStore.delete(fragment.getActivity(), group.id);
                        toast(fragment, "分组已删除");
                        refresh(callback);
                    }
                }).show();
    }

    private static void move(Fragment fragment, RecentCustomGroupStore.Group group, int delta,
                             PastelRecentActions.RefreshCallback callback) {
        if (RecentCustomGroupStore.move(fragment.getActivity(), group.id, delta)) {
            refresh(callback);
        } else {
            toast(fragment, delta < 0 ? "已经在最上方" : "已经在最下方");
        }
    }

    private static boolean usable(Fragment fragment) {
        return fragment != null && fragment.isAdded() && fragment.getActivity() != null
                && !fragment.getActivity().isFinishing();
    }

    private static void toast(Fragment fragment, String text) {
        if (usable(fragment)) Toast.makeText(fragment.getActivity(), text, Toast.LENGTH_SHORT).show();
    }

    private static void refresh(PastelRecentActions.RefreshCallback callback) {
        if (callback != null) callback.requestRefresh();
    }
}
