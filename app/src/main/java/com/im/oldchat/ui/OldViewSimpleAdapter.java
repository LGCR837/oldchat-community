package com.im.oldchat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.util.ImageLoader;
import java.util.ArrayList;
import java.util.List;

public class OldViewSimpleAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<OldViewSimpleItem> items = new ArrayList<OldViewSimpleItem>();

    public OldViewSimpleAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void update(List<OldViewSimpleItem> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    public void append(List<OldViewSimpleItem> data) {
        if (data != null) {
            items.addAll(data);
            notifyDataSetChanged();
        }
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        if (position < 0 || position >= items.size()) {
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
            convertView = inflater.inflate(R.layout.item_old_view_video, parent, false);
            holder = new ViewHolder();
            holder.cover = (ImageView) convertView.findViewById(R.id.ivOldViewCover);
            holder.title = (TextView) convertView.findViewById(R.id.tvOldViewTitle);
            holder.meta = (TextView) convertView.findViewById(R.id.tvOldViewMeta);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        OldViewSimpleItem item = items.get(position);
        if (item != null) {
            holder.title.setText(item.title != null ? item.title : "");
            holder.meta.setText(item.meta != null ? item.meta : "");
            String coverUrl = BiliApi.normalizeUrl(item.cover);
            if (coverUrl != null && coverUrl.length() > 0) {
                ImageLoader.load(holder.cover, coverUrl);
            } else {
                holder.cover.setImageResource(android.R.drawable.ic_media_play);
            }
        }
        return convertView;
    }

    private static class ViewHolder {
        ImageView cover;
        TextView title;
        TextView meta;
    }
}
