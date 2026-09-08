package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.ChannelState;
import com.im.oldchat.ui.widget.CircleImageView;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

/** Telegram 风格扁平频道列表。 */
final class ChannelDiscoveryAdapter extends BaseAdapter {
    private final Context context;
    private List<ChannelState> channels;

    ChannelDiscoveryAdapter(Context context, List<ChannelState> channels) {
        this.context = context; this.channels = channels;
    }

    void replace(List<ChannelState> value) { channels = value; notifyDataSetChanged(); }
    @Override public int getCount() { return channels == null ? 0 : channels.size(); }
    @Override public ChannelState getItem(int position) { return channels.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL); root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(12), dp(9), dp(12), dp(9)); root.setBackgroundColor(Color.WHITE);
            CircleImageView avatar = new CircleImageView(context);
            root.addView(avatar, new LinearLayout.LayoutParams(dp(52), dp(52)));
            LinearLayout texts = new LinearLayout(context); texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textsLp.leftMargin = dp(12); root.addView(texts, textsLp);
            TextView title = new TextView(context); title.setTextSize(16.5f); title.setTextColor(0xFF17212B); title.setSingleLine(true);
            TextView subtitle = new TextView(context); subtitle.setTextSize(14f); subtitle.setTextColor(0xFF7A8A99); subtitle.setSingleLine(true);
            TextView description = new TextView(context); description.setTextSize(13f); description.setTextColor(0xFF52616F); description.setSingleLine(true);
            texts.addView(title); texts.addView(subtitle); texts.addView(description);
            TextView arrow = new TextView(context); arrow.setText("›"); arrow.setTextSize(28f); arrow.setTextColor(0xFFB2BCC4);
            root.addView(arrow, new LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
            holder = new Holder(); holder.avatar=avatar; holder.title=title; holder.subtitle=subtitle; holder.description=description;
            root.setTag(holder); convertView=root;
        } else holder=(Holder)convertView.getTag();
        ChannelState channel=getItem(position);
        boolean dark=ThemeColorUtil.isDarkAppearance(context);
        convertView.setBackgroundColor(dark?0xFF0E1621:0xFFFFFFFF);
        holder.title.setTextColor(dark?0xFFF2F5F7:0xFF17212B);
        holder.subtitle.setTextColor(dark?0xFF8E9BA7:0xFF7A8A99);
        holder.description.setTextColor(dark?0xFFB8C3CC:0xFF52616F);
        holder.title.setText(channel.name);
        String handle=channel.handle == null || channel.handle.length()==0 ? "" : "@"+channel.handle+"  ·  ";
        holder.subtitle.setText(handle+formatCount(channel.subscriberCount)+" subscribers");
        holder.description.setText(channel.description == null ? "" : channel.description);
        holder.description.setVisibility(channel.description == null || channel.description.length()==0 ? View.GONE : View.VISIBLE);
        if (channel.avatarUrl == null || channel.avatarUrl.length() == 0) {
            holder.avatar.setImageResource(R.drawable.group);
        } else {
            holder.avatar.setTag(channel.avatarUrl); ImageLoader.loadAvatar(holder.avatar, channel.avatarUrl);
        }
        return convertView;
    }

    private String formatCount(int count) {
        if (count >= 10000) return String.format(java.util.Locale.US, "%.1fK", count / 1000f);
        return String.valueOf(count);
    }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    private static final class Holder { CircleImageView avatar; TextView title,subtitle,description; }
}
