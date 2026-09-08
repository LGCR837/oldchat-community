package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MessagePayload;

import java.util.List;

final class ForwardMessageDetailDialog {
    private ForwardMessageDetailDialog() {
    }

    static void show(Context context, MessagePayload.ForwardBundle bundle) {
        if (context == null || bundle == null || bundle.items == null || bundle.items.isEmpty()) {
            return;
        }
        List<MessagePayload.ForwardItem> data = ForwardMessageComposer.copyItems(bundle.items);
        if (data.isEmpty()) {
            return;
        }
        ListView listView = new ListView(context);
        listView.setBackgroundColor(ContextCompat.getColor(context, R.color.color_surface));
        listView.setDivider(new ColorDrawable(ContextCompat.getColor(context, R.color.color_divider)));
        listView.setDividerHeight(dp(context, 1));
        listView.setAdapter(new ForwardItemAdapter(context, data));
        listView.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 360)));
        String title = bundle.title == null ? "" : bundle.title.trim();
        if (title.length() == 0) {
            title = context.getString(R.string.forward_bundle_title);
        }
        new AlertDialog.Builder(context, R.style.AppDialogTheme)
                .setTitle(title)
                .setView(listView)
                .setPositiveButton(R.string.forward_detail_close, null)
                .show();
    }

    private static class ForwardItemAdapter extends BaseAdapter {
        private final Context context;
        private final List<MessagePayload.ForwardItem> items;
        private final int senderColor;
        private final int contentColor;

        ForwardItemAdapter(Context context, List<MessagePayload.ForwardItem> items) {
            this.context = context;
            this.items = items;
            this.senderColor = ContextCompat.getColor(context, R.color.color_text_secondary);
            this.contentColor = ContextCompat.getColor(context, R.color.color_text_primary);
        }

        @Override
        public int getCount() {
            return items == null ? 0 : items.size();
        }

        @Override
        public Object getItem(int position) {
            if (items == null || position < 0 || position >= items.size()) {
                return null;
            }
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));

                ImageView avatar = new ImageView(context);
                LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36));
                avatarLp.rightMargin = dp(context, 10);
                row.addView(avatar, avatarLp);

                LinearLayout textBox = new LinearLayout(context);
                textBox.setOrientation(LinearLayout.VERTICAL);
                textBox.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView sender = new TextView(context);
                sender.setTextColor(senderColor);
                sender.setTextSize(13f);
                sender.setSingleLine(true);
                sender.setIncludeFontPadding(false);

                TextView content = new TextView(context);
                content.setTextColor(contentColor);
                content.setTextSize(14f);
                content.setMaxLines(3);
                content.setIncludeFontPadding(false);

                textBox.addView(sender);
                textBox.addView(content);
                row.addView(textBox);
                convertView = row;
                holder = new ViewHolder();
                holder.avatar = avatar;
                holder.sender = sender;
                holder.content = content;
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            MessagePayload.ForwardItem item = items.get(position);
            String senderText = item == null ? "" : safe(item.fromName);
            if (senderText.length() == 0) {
                senderText = item == null ? "" : safe(item.fromUid);
            }
            if (senderText.length() == 0) {
                senderText = context.getString(R.string.forward_sender_unknown);
            }
            String contentText = item == null ? "" : safe(item.text);
            if (contentText.length() == 0) {
                contentText = context.getString(R.string.forward_preview_empty);
            }
            holder.sender.setText(senderText);
            holder.content.setText(contentText);

            String avatarUrl = item == null ? "" : safe(item.fromAvatar);
            if (avatarUrl.length() == 0) {
                holder.avatar.setImageResource(R.drawable.ic_avatar_placeholder);
                holder.avatar.setTag(null);
            } else {
                Object tag = holder.avatar.getTag();
                if (tag == null || !avatarUrl.equals(tag)) {
                    holder.avatar.setTag(avatarUrl);
                    ImageLoader.loadAvatar(holder.avatar, avatarUrl);
                }
            }
            return convertView;
        }
    }

    private static class ViewHolder {
        ImageView avatar;
        TextView sender;
        TextView content;
    }

    private static int dp(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (density * dp + 0.5f);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
