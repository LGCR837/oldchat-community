package com.im.oldchat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliModels;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ThemeColorUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OldViewCommentAdapter extends BaseAdapter {
    public interface OnCommentActionListener {
        void onLikeCommentRequested(BiliModels.CommentReply reply, boolean targetLike);

        void onReplyCommentRequested(BiliModels.CommentReply reply);

        void onLoadMoreRepliesRequested(BiliModels.CommentReply reply);
    }

    private final LayoutInflater inflater;
    private final List<BiliModels.CommentReply> items = new ArrayList<BiliModels.CommentReply>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
    private OnCommentActionListener actionListener;

    public OldViewCommentAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnCommentActionListener(OnCommentActionListener listener) {
        this.actionListener = listener;
    }

    public void update(List<BiliModels.CommentReply> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
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
            convertView = inflater.inflate(R.layout.item_old_view_comment, parent, false);
            holder = new ViewHolder();
            holder.avatar = (ImageView) convertView.findViewById(R.id.ivOldViewCommentAvatar);
            holder.name = (TextView) convertView.findViewById(R.id.tvOldViewCommentName);
            holder.tag = (TextView) convertView.findViewById(R.id.tvOldViewCommentTag);
            holder.content = (TextView) convertView.findViewById(R.id.tvOldViewCommentContent);
            holder.time = (TextView) convertView.findViewById(R.id.tvOldViewCommentTime);
            holder.btnLike = (TextView) convertView.findViewById(R.id.tvOldViewCommentLike);
            holder.btnReply = (TextView) convertView.findViewById(R.id.tvOldViewCommentReply);
            holder.replyCount = (TextView) convertView.findViewById(R.id.tvOldViewCommentReplyCount);
            holder.replies = (TextView) convertView.findViewById(R.id.tvOldViewCommentReplies);
            holder.replyToggle = (TextView) convertView.findViewById(R.id.tvOldViewCommentReplyToggle);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        if (position < 0 || position >= items.size()) {
            return convertView;
        }

        BiliModels.CommentReply reply = items.get(position);
        bindItem(holder, reply);
        return convertView;
    }

    private void bindItem(ViewHolder holder, final BiliModels.CommentReply reply) {
        if (reply == null) {
            return;
        }
        String name = reply.member != null ? reply.member.uname : "";
        String content = reply.content != null ? reply.content.message : "";
        holder.name.setText(name != null ? name : "");
        holder.content.setText(content != null ? content : "");
        holder.time.setText(formatTime(reply.ctime));

        String tagText = "";
        if (reply.topComment) {
            tagText = "置顶";
        } else if (reply.hotComment) {
            tagText = "推荐";
        }
        if (tagText.length() > 0) {
            holder.tag.setVisibility(View.VISIBLE);
            holder.tag.setText(tagText);
        } else {
            holder.tag.setVisibility(View.GONE);
            holder.tag.setText("");
        }

        int likeCount = reply.like > 0 ? reply.like : 0;
        holder.btnLike.setText((reply.likedByMe ? "已赞 " : "点赞 ") + likeCount);
        holder.btnLike.setTextColor(reply.likedByMe
                ? ThemeColorUtil.getPrimaryColor(holder.btnLike.getContext())
                : holder.btnLike.getResources().getColor(R.color.color_text_primary));
        holder.btnReply.setText("回复");

        if (reply.rcount > 0) {
            holder.replyCount.setVisibility(View.VISIBLE);
            holder.replyCount.setText("回复 " + reply.rcount);
        } else {
            holder.replyCount.setVisibility(View.GONE);
            holder.replyCount.setText("");
        }

        holder.btnLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) {
                    actionListener.onLikeCommentRequested(reply, !reply.likedByMe);
                }
            }
        });
        holder.btnReply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) {
                    actionListener.onReplyCommentRequested(reply);
                }
            }
        });

        bindReplies(holder.replies, holder.replyToggle, reply);

        String avatar = reply.member != null ? reply.member.avatar : null;
        avatar = BiliApi.normalizeUrl(avatar);
        if (avatar != null && avatar.length() > 0) {
            ImageLoader.loadAvatar(holder.avatar, avatar);
        } else {
            holder.avatar.setImageResource(R.drawable.ic_avatar_placeholder);
        }
    }

    private String formatTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        try {
            return dateFormat.format(new Date(seconds * 1000L));
        } catch (Exception e) {
            return "";
        }
    }

    private void bindReplies(TextView replyView, TextView toggleView, final BiliModels.CommentReply rootReply) {
        if (replyView == null || toggleView == null || rootReply == null) {
            return;
        }
        List<BiliModels.CommentReply> replies = rootReply.replies != null
                ? rootReply.replies : new ArrayList<BiliModels.CommentReply>();
        int total = rootReply.rcount > 0 ? rootReply.rcount : replies.size();

        if (total <= 0) {
            replyView.setVisibility(View.GONE);
            replyView.setText("");
            replyView.setOnClickListener(null);
            toggleView.setVisibility(View.GONE);
            toggleView.setText("");
            toggleView.setOnClickListener(null);
            return;
        }

        int displayCount = rootReply.showAllReplies ? replies.size() : Math.min(2, replies.size());
        SpannableStringBuilder content = buildReplyText(replyView, replies, displayCount);
        if (content.length() == 0) {
            content.append("有 ").append(String.valueOf(total)).append(" 条回复");
        }
        replyView.setText(content);
        replyView.setVisibility(View.VISIBLE);
        replyView.setOnClickListener(null);

        String toggleText = buildToggleText(rootReply, total, replies.size(), displayCount);
        if (!TextUtils.isEmpty(toggleText)) {
            toggleView.setVisibility(View.VISIBLE);
            toggleView.setText(toggleText);
            toggleView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (actionListener != null) {
                        actionListener.onLoadMoreRepliesRequested(rootReply);
                    }
                }
            });
        } else {
            toggleView.setVisibility(View.GONE);
            toggleView.setText("");
            toggleView.setOnClickListener(null);
        }
    }

    private SpannableStringBuilder buildReplyText(TextView replyView,
                                                  List<BiliModels.CommentReply> replies,
                                                  int displayCount) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        int limit = Math.min(displayCount, replies.size());
        int nameColor = ThemeColorUtil.getPrimaryColor(replyView.getContext());
        for (int i = 0; i < limit; i++) {
            BiliModels.CommentReply item = replies.get(i);
            if (item == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("- ");
            String name = item.member != null ? item.member.uname : "";
            if (TextUtils.isEmpty(name)) {
                name = "匿名用户";
            }
            int nameStart = builder.length();
            builder.append(name);
            if (item.like > 0) {
                builder.append(" (").append(String.valueOf(item.like)).append(")");
            }
            int nameEnd = builder.length();
            builder.setSpan(new ForegroundColorSpan(nameColor), nameStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new StyleSpan(Typeface.BOLD), nameStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            String msg = item.content != null ? item.content.message : "";
            if (!TextUtils.isEmpty(msg)) {
                builder.append(": ").append(msg);
            }
        }
        return builder;
    }

    private String buildToggleText(BiliModels.CommentReply rootReply,
                                   int total,
                                   int loadedCount,
                                   int shownCount) {
        boolean hasMoreOnServer = rootReply.rcount > 0 && total > loadedCount;
        if (!rootReply.showAllReplies && (total > shownCount || hasMoreOnServer)) {
            return "展开全部 " + total + " 条回复";
        }
        if (rootReply.showAllReplies && total > 2) {
            return "收起回复";
        }
        if (loadedCount == 0 && total > 0) {
            return "查看 " + total + " 条回复";
        }
        return null;
    }

    private static class ViewHolder {
        ImageView avatar;
        TextView name;
        TextView tag;
        TextView content;
        TextView time;
        TextView btnLike;
        TextView btnReply;
        TextView replyCount;
        TextView replies;
        TextView replyToggle;
    }
}
