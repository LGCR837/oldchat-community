package com.im.oldchat.ui.news;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.im.oldchat.R;

import java.util.ArrayList;
import java.util.List;

public class MinimalNewsAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<MinimalNewsItem> items = new ArrayList<MinimalNewsItem>();
    private boolean pauseImageLoading;

    public MinimalNewsAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    public void replaceAll(List<MinimalNewsItem> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setPauseImageLoading(boolean pause) {
        if (pauseImageLoading == pause) {
            return;
        }
        pauseImageLoading = pause;
        if (!pause) {
            notifyDataSetChanged();
        }
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return getNewsItem(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public MinimalNewsItem getNewsItem(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_minimal_news, parent, false);
            holder = new Holder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }

        MinimalNewsItem item = getNewsItem(position);
        if (item != null) {
            holder.tvTitle.setText(item.title);
            holder.tvSummary.setText(item.summary);
            holder.tvMeta.setText(safe(item.source) + "  |  " + safe(item.publishTime));
            holder.tvTitle.setTypeface(holder.tvTitle.getTypeface(), Typeface.BOLD);
            String imageUrl = item.imageUrl == null ? "" : item.imageUrl.trim();
            if (imageUrl.length() == 0) {
                holder.ivCover.setTag("");
                holder.ivCover.setImageDrawable(null);
                holder.ivCover.setVisibility(View.GONE);
            } else if (pauseImageLoading) {
                holder.ivCover.setTag("");
                holder.ivCover.setVisibility(View.VISIBLE);
                holder.ivCover.setImageResource(R.drawable.ic_discover_report);
            } else {
                MinimalNewsImageLoader.load(holder.ivCover, imageUrl);
            }
        }
        return convertView;
    }

    private String safe(String text) {
        return text == null || text.trim().length() == 0 ? "-" : text;
    }

    private static class Holder {
        final ImageView ivCover;
        final TextView tvTitle;
        final TextView tvSummary;
        final TextView tvMeta;

        Holder(View root) {
            ivCover = (ImageView) root.findViewById(R.id.ivMinimalNewsCover);
            tvTitle = (TextView) root.findViewById(R.id.tvMinimalNewsTitle);
            tvSummary = (TextView) root.findViewById(R.id.tvMinimalNewsSummary);
            tvMeta = (TextView) root.findViewById(R.id.tvMinimalNewsMeta);
        }
    }
}
