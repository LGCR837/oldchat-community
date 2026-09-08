package com.im.oldchat.ui;

import android.content.Context;
import android.support.v7.view.ContextThemeWrapper;
import android.support.v7.widget.PopupMenu;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.models.ResourceItem;
import com.im.oldchat.util.SettingsPrefs;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ResourceItemAdapter extends BaseAdapter {
    public interface ActionListener {
        void onDownload(ResourceItem item);
        void onShare(ResourceItem item);
        void onLike(ResourceItem item);
        void onComment(ResourceItem item);
        void onReport(ResourceItem item);
        void onFavorite(ResourceItem item);
        void onDelete(ResourceItem item);
    }

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private final Context context;
    private final List<ResourceItem> items;
    private final LayoutInflater inflater;
    private final ActionListener listener;

    public ResourceItemAdapter(Context context, List<ResourceItem> items, ActionListener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return items == null ? 0 : items.size();
    }

    @Override
    public Object getItem(int position) {
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
            convertView = inflater.inflate(R.layout.item_resource, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final ResourceItem item = items.get(position);
        if (item != null) {
            holder.tvName.setText(item.name == null ? "" : item.name);
            String uploader = !TextUtils.isEmpty(item.uploader_name) ? item.uploader_name : item.uploader_uid;
            if (TextUtils.isEmpty(uploader)) {
                uploader = "未知";
            }
            holder.tvUploader.setText("上传者: " + uploader);
            UserTitleBinder.bind(holder.tvUploaderBadge, item.uploader_title);
            String meta = formatSize(item.size_bytes) + " · " + formatTime(item.created_at);
            holder.tvMeta.setText(meta);

            if (item.liked) {
                holder.ivLike.setImageResource(R.drawable.ic_like_filled);
                holder.ivLike.setColorFilter(0xFFFF3B30);
                holder.tvLike.setTextColor(0xFFFF3B30);
                holder.tvLike.setText(item.likes > 0 ? String.valueOf(item.likes) : "已赞");
            } else {
                holder.ivLike.setImageResource(R.drawable.ic_like);
                holder.ivLike.setColorFilter(0xFF999999);
                holder.tvLike.setTextColor(0xFF666666);
                holder.tvLike.setText(item.likes > 0 ? String.valueOf(item.likes) : "赞");
            }

            holder.tvComment.setText(item.comments > 0 ? String.valueOf(item.comments) : "评论");

            holder.btnLike.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onLike(item);
                    }
                }
            });
            holder.btnComment.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onComment(item);
                    }
                }
            });

            holder.btnMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean isNight = SettingsPrefs.isDarkModeEnabled(context);
                    Context themed = new ContextThemeWrapper(context, isNight ? R.style.PopupMenuOverlayDark : R.style.PopupMenuOverlayLight);
                    PopupMenu popupMenu = new PopupMenu(themed, v);
                    popupMenu.getMenuInflater().inflate(R.menu.resource_item_actions, popupMenu.getMenu());
                    android.content.SharedPreferences prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
                    String myUid = prefs.getString("uid", "");
                    if (myUid == null || myUid.length() == 0) {
                        myUid = prefs.getString("user_uid", "");
                    }
                    boolean isOwn = (item.can_delete || (myUid.length() > 0 && item.uploader_uid != null && myUid.equalsIgnoreCase(item.uploader_uid.trim())));
                    android.view.MenuItem deleteItem = popupMenu.getMenu().findItem(R.id.action_resource_delete);
                    if (deleteItem != null) {
                        deleteItem.setVisible(item.can_delete || isOwn);
                    }
                    android.view.MenuItem reportItem = popupMenu.getMenu().findItem(R.id.action_resource_report);
                    if (reportItem != null) {
                        reportItem.setVisible(!isOwn);
                    }
                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                            if (listener == null) {
                                return true;
                            }
                            int id = menuItem.getItemId();
                            if (id == R.id.action_resource_download) {
                                listener.onDownload(item);
                                return true;
                            }
                            if (id == R.id.action_resource_report) {
                                listener.onReport(item);
                                return true;
                            }
                            if (id == R.id.action_resource_favorite) {
                                listener.onFavorite(item);
                                return true;
                            }
                            if (id == R.id.action_resource_delete) {
                                listener.onDelete(item);
                                return true;
                            }
                            return false;
                        }
                    });
                    try {
                        popupMenu.show();
                    } catch (android.view.WindowManager.BadTokenException e) {
                        // Context/Activity was finishing when trying to show popup
                    }
                }
            });
        }
        return convertView;
    }

    private String formatTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        Date date = new Date(seconds * 1000L);
        return TIME_FORMAT.format(date);
    }

    private String formatSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            return "0B";
        }
        float size = sizeBytes;
        if (size < 1024) {
            return (int) size + "B";
        }
        size = size / 1024f;
        if (size < 1024) {
            return String.format(Locale.getDefault(), "%.1fKB", size);
        }
        size = size / 1024f;
        if (size < 1024) {
            return String.format(Locale.getDefault(), "%.1fMB", size);
        }
        size = size / 1024f;
        return String.format(Locale.getDefault(), "%.1fGB", size);
    }

    private static class ViewHolder {
        final TextView tvName;
        final TextView tvUploader;
        final TextView tvUploaderBadge;
        final TextView tvMeta;
        final View btnLike;
        final View btnComment;
        final View btnMore;
        final ImageView ivLike;
        final TextView tvLike;
        final TextView tvComment;

        ViewHolder(View view) {
            tvName = view.findViewById(R.id.tvResourceName);
            tvUploader = view.findViewById(R.id.tvResourceUploader);
            tvUploaderBadge = view.findViewById(R.id.tvResourceUploaderBadge);
            tvMeta = view.findViewById(R.id.tvResourceMeta);
            btnLike = view.findViewById(R.id.btnResourceLike);
            btnComment = view.findViewById(R.id.btnResourceComment);
            btnMore = view.findViewById(R.id.btnResourceMore);
            ivLike = view.findViewById(R.id.ivResourceLike);
            tvLike = view.findViewById(R.id.tvResourceLike);
            tvComment = view.findViewById(R.id.tvResourceComment);
        }
    }
}
