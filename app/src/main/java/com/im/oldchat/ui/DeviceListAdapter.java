package com.im.oldchat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.im.oldchat.R;
import java.util.List;

public class DeviceListAdapter extends BaseAdapter {
    public static class DeviceItem {
        public String deviceId;
        public String deviceName;
        public String platform;
        public String appVersion;
        public long lastSeen;
    }

    private final Context context;
    private final List<DeviceItem> items;
    private final String currentDeviceId;

    public DeviceListAdapter(Context context, List<DeviceItem> items, String currentDeviceId) {
        this.context = context;
        this.items = items;
        this.currentDeviceId = currentDeviceId == null ? "" : currentDeviceId;
    }

    @Override
    public int getCount() {
        return items == null ? 0 : items.size();
    }

    @Override
    public DeviceItem getItem(int position) {
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
            convertView = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        DeviceItem item = getItem(position);
        if (item == null) {
            holder.tvName.setText("");
            holder.tvMeta.setText("");
            return convertView;
        }

        String title = (item.deviceName != null && !item.deviceName.isEmpty())
                ? item.deviceName
                : (item.deviceId == null ? "" : item.deviceId);
        if (item.deviceId != null && !item.deviceId.isEmpty() && item.deviceId.equals(currentDeviceId)) {
            title = title + " (本机)";
        }
        holder.tvName.setText(title);

        String meta = buildMeta(item);
        holder.tvMeta.setText(meta);
        return convertView;
    }

    private String buildMeta(DeviceItem item) {
        String platformLabel = item.platform == null ? "" : item.platform.trim();
        if ("android".equalsIgnoreCase(platformLabel)) {
            platformLabel = "Android";
        } else if ("ios".equalsIgnoreCase(platformLabel)) {
            platformLabel = "iOS";
        }
        String versionLabel = item.appVersion == null ? "" : item.appVersion.trim();
        if (versionLabel.length() > 0) {
            versionLabel = "v" + versionLabel;
        }
        String timeLabel = item.lastSeen > 0 ? ChatTimeFormatter.formatTime(item.lastSeen) : "";

        StringBuilder sb = new StringBuilder();
        if (platformLabel.length() > 0) {
            sb.append(platformLabel);
        }
        if (versionLabel.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(versionLabel);
        }
        if (timeLabel.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("最近使用 ").append(timeLabel);
        }
        if (sb.length() == 0 && item.deviceId != null) {
            sb.append("ID: ").append(item.deviceId);
        }
        return sb.toString();
    }

    static class ViewHolder {
        TextView tvName;
        TextView tvMeta;

        ViewHolder(View v) {
            tvName = v.findViewById(R.id.tvDeviceName);
            tvMeta = v.findViewById(R.id.tvDeviceMeta);
        }
    }
}
