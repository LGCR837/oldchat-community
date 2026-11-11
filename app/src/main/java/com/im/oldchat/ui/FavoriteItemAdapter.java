package com.im.oldchat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.FavoriteItem;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FavoriteItemAdapter extends BaseAdapter {
    public interface ActionListener {
        void onOpen(FavoriteItem item);
        void onRemove(FavoriteItem item, int position);
    }

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private final LayoutInflater inflater;
    private final List<FavoriteItem> items;
    private final ActionListener listener;

    public FavoriteItemAdapter(Context context, List<FavoriteItem> items, ActionListener listener) {
        this.inflater = LayoutInflater.from(context);
        this.items = items;
        this.listener = listener;
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
    public View getView(final int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_favorite, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        final FavoriteItem item = (FavoriteItem) getItem(position);
        if (item == null) {
            return convertView;
        }
        holder.tvType.setText(typeLabel(item.type));
        holder.tvTitle.setText(item.title == null || item.title.length() == 0 ? "未命名收藏" : item.title);
        String subtitle = item.subtitle == null ? "" : item.subtitle;
        if (subtitle.length() == 0) {
            subtitle = item.media_url == null ? "" : item.media_url;
        }
        holder.tvSubtitle.setText(subtitle);
        holder.tvTime.setText(formatTime(item.created_at));

        holder.btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onOpen(item);
                }
            }
        });
        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onRemove(item, position);
                }
            }
        });
        return convertView;
    }

    private String typeLabel(String type) {
        if (type == null) {
            return "收藏";
        }
        if ("chat_image".equals(type)) {
            return "聊天图片";
        }
        if ("chat_voice".equals(type)) {
            return "聊天语音";
        }
        if ("chat_video".equals(type)) {
            return "聊天视频";
        }
        if ("resource_file".equals(type)) {
            return "资源文件";
        }
        if ("emoji_pack".equals(type)) {
            return "表情包";
        }
        if ("music_song".equals(type)) {
            return "歌曲";
        }
        return "收藏";
    }

    private String formatTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        Date date = new Date(seconds * 1000L);
        return TIME_FORMAT.format(date);
    }

    private static class ViewHolder {
        final TextView tvType;
        final TextView tvTitle;
        final TextView tvSubtitle;
        final TextView tvTime;
        final TextView btnOpen;
        final TextView btnDelete;

        ViewHolder(View view) {
            tvType = view.findViewById(R.id.tvFavoriteType);
            tvTitle = view.findViewById(R.id.tvFavoriteTitle);
            tvSubtitle = view.findViewById(R.id.tvFavoriteSubtitle);
            tvTime = view.findViewById(R.id.tvFavoriteTime);
            btnOpen = view.findViewById(R.id.btnFavoriteOpen);
            btnDelete = view.findViewById(R.id.btnFavoriteDelete);
        }
    }
}
