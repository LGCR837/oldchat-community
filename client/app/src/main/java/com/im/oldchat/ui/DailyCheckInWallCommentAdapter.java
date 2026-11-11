package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.models.DailyCheckInWallComment;
import com.im.oldchat.util.ImageLoader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DailyCheckInWallCommentAdapter extends BaseAdapter {
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final Context context;
    private final List<DailyCheckInWallComment> comments;
    private final LayoutInflater inflater;

    public DailyCheckInWallCommentAdapter(Context context, List<DailyCheckInWallComment> comments) {
        this.context = context;
        this.comments = comments;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return comments == null ? 0 : comments.size();
    }

    @Override
    public Object getItem(int position) {
        return comments.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_moment_comment, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        final DailyCheckInWallComment comment = comments.get(position);
        String name = !TextUtils.isEmpty(comment.fromName) ? comment.fromName : comment.fromUid;
        holder.tvName.setText(name == null ? "" : name);
        UserTitleBinder.bind(holder.tvTitle, comment.fromTitle);
        holder.tvBody.setText(comment.body == null ? "" : comment.body);
        holder.tvTime.setText(comment.createdAt <= 0 ? "" : FORMAT.format(new Date(comment.createdAt * 1000L)));
        ImageLoader.loadAvatar(holder.ivAvatar, comment.fromAvatar);
        holder.ivAvatar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (comment.fromUid == null || comment.fromUid.length() == 0) {
                    return;
                }
                Intent intent = new Intent(context, UserSpaceActivity.class);
                intent.putExtra("uid", comment.fromUid);
                context.startActivity(intent);
            }
        });
        return convertView;
    }

    private static class ViewHolder {
        final ImageView ivAvatar;
        final TextView tvName;
        final TextView tvTitle;
        final TextView tvBody;
        final TextView tvTime;

        ViewHolder(View view) {
            ivAvatar = view.findViewById(R.id.ivCommentAvatar);
            tvName = view.findViewById(R.id.tvCommentName);
            tvTitle = view.findViewById(R.id.tvCommentTitleBadge);
            tvBody = view.findViewById(R.id.tvCommentBody);
            tvTime = view.findViewById(R.id.tvCommentTime);
        }
    }
}
