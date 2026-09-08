package com.im.oldchat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.im.oldchat.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RedPacketClaimAdapter extends BaseAdapter {
    public static class Claim {
        public String name;
        public int amount;
        public long createdAt;
    }

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    private final Context context;
    private final List<Claim> claims;

    public RedPacketClaimAdapter(Context context) {
        this.context = context;
        this.claims = new ArrayList<Claim>();
    }

    public void setClaims(List<Claim> items) {
        claims.clear();
        if (items != null) {
            claims.addAll(items);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return claims.size();
    }

    @Override
    public Object getItem(int position) {
        return claims.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_red_packet_claim, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        Claim claim = claims.get(position);
        if (holder.name != null) {
            holder.name.setText(claim.name == null ? "" : claim.name);
        }
        if (holder.amount != null) {
            holder.amount.setText(context.getString(R.string.red_packet_claim_amount_format, claim.amount));
        }
        if (holder.time != null) {
            if (claim.createdAt > 0) {
                holder.time.setText(TIME_FORMAT.format(new Date(claim.createdAt * 1000L)));
                holder.time.setVisibility(View.VISIBLE);
            } else {
                holder.time.setText("");
                holder.time.setVisibility(View.GONE);
            }
        }
        return convertView;
    }

    private static class ViewHolder {
        final TextView name;
        final TextView time;
        final TextView amount;

        ViewHolder(View view) {
            name = view.findViewById(R.id.tvClaimName);
            time = view.findViewById(R.id.tvClaimTime);
            amount = view.findViewById(R.id.tvClaimAmount);
        }
    }
}
