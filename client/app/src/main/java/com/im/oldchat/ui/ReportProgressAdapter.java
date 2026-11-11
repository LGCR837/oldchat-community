package com.im.oldchat.ui;

import android.support.v7.app.AlertDialog;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.im.oldchat.R;

import java.util.ArrayList;
import java.util.List;

public class ReportProgressAdapter extends RecyclerView.Adapter<ReportProgressAdapter.VH> {

    private final List<ReportProgressRow> items = new ArrayList<ReportProgressRow>();

    public void setItems(List<ReportProgressRow> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_progress_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(final VH holder, int position) {
        final ReportProgressRow row = items.get(position);
        holder.tvTitle.setText(row.title == null ? "" : row.title);
        holder.tvMeta.setText(row.meta == null ? "" : row.meta);
        holder.tvStatus.setText(row.status == null ? "" : row.status);

        final String fullBody = row.body == null ? "" : row.body;
        final int previewLimit = row.bodyPreviewLimit;
        final boolean needPreview = previewLimit > 0 && fullBody.length() > previewLimit;
        if (needPreview) {
            holder.tvBody.setText(buildPreviewText(fullBody, previewLimit));
            holder.tvBody.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetailDialog(holder, row, fullBody);
                }
            });
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetailDialog(holder, row, fullBody);
                }
            });
        } else {
            holder.tvBody.setText(fullBody);
            holder.tvBody.setOnClickListener(null);
            holder.itemView.setOnClickListener(null);
        }

        int bg;
        if (row.statusType == ReportProgressRow.STATUS_SUCCESS) {
            bg = R.drawable.bg_chip_success;
        } else if (row.statusType == ReportProgressRow.STATUS_WARNING) {
            bg = R.drawable.bg_chip_warning;
        } else {
            bg = R.drawable.bg_chip_pending;
        }
        holder.tvStatus.setBackgroundResource(bg);
    }

    private String buildPreviewText(String body, int limit) {
        if (body == null || body.length() <= limit) {
            return body == null ? "" : body;
        }
        String text = body.substring(0, limit).trim();
        if (text.length() == 0) {
            text = body.substring(0, Math.min(limit, body.length()));
        }
        return text + "… 点击查看详情";
    }

    private void showDetailDialog(VH holder, ReportProgressRow row, String fullBody) {
        if (holder == null || holder.itemView == null) {
            return;
        }
        String title = row == null || row.title == null || row.title.length() == 0 ? "反馈详情" : row.title;
        String body = fullBody == null || fullBody.length() == 0 ? "(无内容)" : fullBody;
        new AlertDialog.Builder(holder.itemView.getContext())
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("关闭", null)
                .show();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvBody;
        TextView tvMeta;
        TextView tvStatus;

        VH(View itemView) {
            super(itemView);
            tvTitle = (TextView) itemView.findViewById(R.id.tvTitle);
            tvBody = (TextView) itemView.findViewById(R.id.tvBody);
            tvMeta = (TextView) itemView.findViewById(R.id.tvMeta);
            tvStatus = (TextView) itemView.findViewById(R.id.tvStatus);
        }
    }
}
