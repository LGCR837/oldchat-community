package com.im.oldchat.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.models.MomentNotice;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MomentNoticeAdapter extends BaseAdapter {
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final Context context;
    private final List<MomentNotice> notices;
    private final LayoutInflater inflater;

    public MomentNoticeAdapter(Context context, List<MomentNotice> notices) {
        this.context = context;
        this.notices = notices;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return notices == null ? 0 : notices.size();
    }

    @Override
    public Object getItem(int position) {
        return notices.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_moment_notice, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        MomentNotice notice = notices.get(position);
        String title;
        if ("comment".equals(notice.type)) {
            title = notice.delta > 0 ? ("有" + notice.delta + "条新评论") : "有新评论";
        } else if ("like".equals(notice.type)) {
            title = notice.delta > 0 ? ("有" + notice.delta + "个新赞") : "有新赞";
        } else {
            title = "动态互动";
        }
        holder.tvTitle.setText(title);

        String body = notice.momentBody;
        if (TextUtils.isEmpty(body)) {
            body = "动态";
        }
        holder.tvBody.setText(body);

        holder.tvTime.setText(formatTime(notice.createdAt));

        return convertView;
    }

    private String formatTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        Date date = new Date(seconds * 1000L);
        return TIME_FORMAT.format(date);
    }

    private static class ViewHolder {
        final TextView tvTitle;
        final TextView tvBody;
        final TextView tvTime;

        ViewHolder(View view) {
            tvTitle = view.findViewById(R.id.tvNoticeTitle);
            tvBody = view.findViewById(R.id.tvNoticeBody);
            tvTime = view.findViewById(R.id.tvNoticeTime);
        }
    }
}
