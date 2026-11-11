package com.im.oldchat.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.im.oldchat.R;

import java.util.List;

final class ChatInlineEmojiGridAdapter extends BaseAdapter {
    interface Listener {
        void onAddClicked();

        void onEmojiClicked(EmojiStore.EmojiItem item);

        void onEmojiLongClicked(EmojiStore.EmojiItem item);
    }

    private final Activity activity;
    private final List<EmojiStore.EmojiItem> filteredItems;
    private final Listener listener;

    ChatInlineEmojiGridAdapter(Activity activity, List<EmojiStore.EmojiItem> filteredItems, Listener listener) {
        this.activity = activity;
        this.filteredItems = filteredItems;
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return filteredItems.size() + 1;
    }

    @Override
    public Object getItem(int position) {
        return getItemByPosition(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    EmojiStore.EmojiItem getItemByPosition(int position) {
        int index = position - 1;
        if (index < 0 || index >= filteredItems.size()) {
            return null;
        }
        return filteredItems.get(index);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        FrameLayout root = (FrameLayout) convertView;
        Holder holder;
        if (root == null) {
            root = new FrameLayout(activity);
            root.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(64)));
            ImageView image = new ImageView(activity);
            FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(dpToPx(48), dpToPx(48));
            imageLp.gravity = Gravity.CENTER;
            image.setLayoutParams(imageLp);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundResource(R.drawable.bg_chat_panel_item);
            root.addView(image);

            TextView plus = new TextView(activity);
            FrameLayout.LayoutParams plusLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            plusLp.gravity = Gravity.CENTER;
            plus.setLayoutParams(plusLp);
            plus.setText("+");
            plus.setTextSize(26f);
            plus.setTextColor(activity.getResources().getColor(R.color.colorTextPrimary));
            root.addView(plus);

            holder = new Holder();
            holder.image = image;
            holder.plus = plus;
            root.setTag(holder);
        } else {
            holder = (Holder) root.getTag();
        }

        if (position == 0) {
            holder.plus.setVisibility(View.VISIBLE);
            holder.image.setTag(null);
            holder.image.setImageDrawable(null);
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onAddClicked();
                    }
                }
            });
            root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return true;
                }
            });
            return root;
        }

        holder.plus.setVisibility(View.GONE);
        final EmojiStore.EmojiItem item = getItemByPosition(position);
        EmojiBitmapLoader.load(activity, holder.image, item == null ? "" : item.path, dpToPx(48));
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null && item != null) {
                    listener.onEmojiClicked(item);
                }
            }
        });
        root.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null && item != null) {
                    listener.onEmojiLongClicked(item);
                }
                return true;
            }
        });
        return root;
    }

    private int dpToPx(int dp) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.max(1, (int) (dp * density + 0.5f));
    }

    private static class Holder {
        ImageView image;
        TextView plus;
    }
}
