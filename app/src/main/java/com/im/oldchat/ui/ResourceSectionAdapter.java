package com.im.oldchat.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.models.ResourceSection;
import java.util.List;

public class ResourceSectionAdapter extends BaseAdapter {
    private final Context context;
    private final List<ResourceSection> sections;
    private final LayoutInflater inflater;

    public ResourceSectionAdapter(Context context, List<ResourceSection> sections) {
        this.context = context;
        this.sections = sections;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return sections == null ? 0 : sections.size();
    }

    @Override
    public Object getItem(int position) {
        return sections.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_resource_section, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        ResourceSection section = sections.get(position);
        if (section != null) {
            holder.tvName.setText(section.name == null ? "" : section.name);
            String owner = !TextUtils.isEmpty(section.owner_name) ? section.owner_name : section.owner_uid;
            if (TextUtils.isEmpty(owner)) {
                owner = "未知";
            }
            if (section.is_owner) {
                owner = "我创建";
            }
            String countText = "资源 " + section.resource_count;
            holder.tvOwner.setText(owner);
            UserTitleBinder.bind(holder.tvOwnerBadge, section.owner_title);
            holder.tvMeta.setText(countText);
        }
        return convertView;
    }

    private static class ViewHolder {
        final TextView tvName;
        final TextView tvOwner;
        final TextView tvOwnerBadge;
        final TextView tvMeta;

        ViewHolder(View view) {
            tvName = view.findViewById(R.id.tvResourceSectionName);
            tvOwner = view.findViewById(R.id.tvResourceSectionOwner);
            tvOwnerBadge = view.findViewById(R.id.tvResourceSectionOwnerBadge);
            tvMeta = view.findViewById(R.id.tvResourceSectionMeta);
        }
    }
}
