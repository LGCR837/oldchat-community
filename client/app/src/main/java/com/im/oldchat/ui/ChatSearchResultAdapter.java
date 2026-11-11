package com.im.oldchat.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

public class ChatSearchResultAdapter extends BaseAdapter {
    public static class Item {
        public String id;
        public String fromUid;
        public String msgType;
        public String body;
        public long createdAt;
    }

    private final Context context;
    private final List<Item> items;
    private final boolean groupMode;
    private String keyword = "";

    public ChatSearchResultAdapter(Context context, List<Item> items, boolean groupMode) {
        this.context = context;
        this.items = items;
        this.groupMode = groupMode;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword == null ? "" : keyword.trim();
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
            convertView = LayoutInflater.from(context).inflate(R.layout.item_chat_search_message, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Item item = (Item) getItem(position);
        if (item == null) {
            holder.tvMeta.setText("");
            holder.tvType.setText("");
            holder.tvPreview.setText("");
            holder.tvJump.setText("");
            holder.tvType.setBackgroundResource(R.drawable.bg_search_type_text);
            return convertView;
        }

        String from = item.fromUid == null || item.fromUid.length() == 0 ? "成员" : item.fromUid;
        String time = ChatTimeFormatter.formatTime(item.createdAt);
        if (time == null || time.length() == 0) {
            time = "未知时间";
        }

        String typeLabel = labelForType(item.msgType);
        if (groupMode) {
            holder.tvMeta.setText(from + " · " + time + " · " + typeLabel);
        } else {
            holder.tvMeta.setText(time + " · " + typeLabel);
        }

        holder.tvType.setText(typeLabel);
        holder.tvType.setBackgroundResource(backgroundForType(item.msgType));
        holder.tvJump.setText("定位");

        String preview = ChatMessageUtil.previewForType(item.msgType, item.body);
        if (preview == null || preview.length() == 0) {
            preview = "(空消息)";
        }
        holder.tvPreview.setText(highlightKeyword(preview));
        return convertView;
    }

    private CharSequence highlightKeyword(String preview) {
        if (preview == null) {
            return "";
        }
        if (keyword == null || keyword.length() == 0) {
            return preview;
        }
        String sourceLower = preview.toLowerCase();
        String keywordLower = keyword.toLowerCase();
        int start = 0;
        int color = ThemeColorUtil.getPrimaryColor(context);
        SpannableString span = new SpannableString(preview);
        while (start < sourceLower.length()) {
            int index = sourceLower.indexOf(keywordLower, start);
            if (index < 0) {
                break;
            }
            int end = index + keywordLower.length();
            if (end > index) {
                span.setSpan(new ForegroundColorSpan(color), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end;
        }
        return span;
    }

    private String labelForType(String msgType) {
        String t = msgType == null ? "" : msgType.toLowerCase();
        if ("image".equals(t)) {
            return "图片";
        }
        if ("video".equals(t)) {
            return "视频";
        }
        if ("voice".equals(t)) {
            return "语音";
        }
        if ("resource".equals(t)) {
            return "资源";
        }
        return "文本";
    }

    private int backgroundForType(String msgType) {
        String t = msgType == null ? "" : msgType.toLowerCase();
        if ("image".equals(t)) {
            return R.drawable.bg_search_type_image;
        }
        if ("video".equals(t)) {
            return R.drawable.bg_search_type_video;
        }
        if ("voice".equals(t)) {
            return R.drawable.bg_search_type_voice;
        }
        if ("resource".equals(t)) {
            return R.drawable.bg_search_type_resource;
        }
        return R.drawable.bg_search_type_text;
    }

    private static class ViewHolder {
        final TextView tvMeta;
        final TextView tvType;
        final TextView tvPreview;
        final TextView tvJump;

        ViewHolder(View view) {
            tvMeta = view.findViewById(R.id.tvSearchItemMeta);
            tvType = view.findViewById(R.id.tvSearchItemType);
            tvPreview = view.findViewById(R.id.tvSearchItemPreview);
            tvJump = view.findViewById(R.id.tvSearchItemJump);
        }
    }
}
