package com.im.oldchat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RedPacketDetailActivity extends BaseActivity {
    public static final String EXTRA_PACKET_ID = "packet_id";

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private String token;
    private String packetId;
    private ImageView ivCover;
    private TextView tvTitle;
    private TextView tvStatus;
    private TextView tvCreatedAt;
    private TextView tvTotalAmount;
    private TextView tvTotalCount;
    private TextView tvClaimedAmount;
    private TextView tvClaimedCount;
    private TextView tvEmpty;
    private ListView lvClaims;
    private RedPacketClaimAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_red_packet_detail);

        View btnBack = findViewByIdCompat(R.id.btnRedPacketDetailBack);
        ivCover = findViewByIdCompat(R.id.ivRedPacketDetailCover);
        tvTitle = findViewByIdCompat(R.id.tvRedPacketDetailTitle);
        tvStatus = findViewByIdCompat(R.id.tvRedPacketDetailStatus);
        tvCreatedAt = findViewByIdCompat(R.id.tvRedPacketDetailCreatedAt);
        tvTotalAmount = findViewByIdCompat(R.id.tvRedPacketDetailTotalAmount);
        tvTotalCount = findViewByIdCompat(R.id.tvRedPacketDetailTotalCount);
        tvClaimedAmount = findViewByIdCompat(R.id.tvRedPacketDetailClaimedAmount);
        tvClaimedCount = findViewByIdCompat(R.id.tvRedPacketDetailClaimedCount);
        tvEmpty = findViewByIdCompat(R.id.tvRedPacketDetailEmpty);
        lvClaims = findViewByIdCompat(R.id.lvRedPacketClaims);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        packetId = getIntent().getStringExtra(EXTRA_PACKET_ID);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        adapter = new RedPacketClaimAdapter(this);
        if (lvClaims != null) {
            lvClaims.setAdapter(adapter);
            if (tvEmpty != null) {
                lvClaims.setEmptyView(tvEmpty);
            }
        }

        loadDetail();
    }

    private void loadDetail() {
        if (packetId == null || packetId.isEmpty()) {
            Toast.makeText(this, R.string.red_packet_invalid, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        HttpUtil.get("/redpackets/" + packetId, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                applyDetail(response);
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(RedPacketDetailActivity.this, R.string.red_packet_open_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyDetail(String response) {
        try {
            JSONObject obj = new JSONObject(response);
            String title = obj.optString("title", "");
            if (title == null || title.isEmpty()) {
                title = getString(R.string.red_packet_title_default);
            }
            if (tvTitle != null) {
                tvTitle.setText(title);
            }

            String coverUrl = obj.optString("cover_url", "");
            if (ivCover != null) {
                if (coverUrl != null && !coverUrl.trim().isEmpty()) {
                    ImageLoader.load(ivCover, coverUrl.trim());
                } else {
                    ivCover.setImageResource(R.drawable.ic_red_packet);
                }
            }

            String status = obj.optString("status", "active");
            boolean done = "done".equalsIgnoreCase(status) || obj.optInt("remaining_count", 0) <= 0;
            if (tvStatus != null) {
                tvStatus.setText(done ? getString(R.string.red_packet_status_done) : getString(R.string.red_packet_status_open));
            }

            long createdAt = obj.optLong("created_at", 0);
            if (tvCreatedAt != null) {
                if (createdAt > 0) {
                    tvCreatedAt.setText("创建时间：" + TIME_FORMAT.format(new Date(createdAt * 1000L)));
                } else {
                    tvCreatedAt.setText("创建时间：未知");
                }
            }

            int totalAmount = obj.optInt("total_amount", 0);
            int totalCount = obj.optInt("total_count", 0);
            int claimedAmount = obj.optInt("claimed_amount", totalAmount - obj.optInt("remaining_amount", 0));
            int claimedCount = obj.optInt("claimed_count", totalCount - obj.optInt("remaining_count", 0));
            if (tvTotalAmount != null) {
                tvTotalAmount.setText(getString(R.string.red_packet_total_amount_format, totalAmount));
            }
            if (tvTotalCount != null) {
                tvTotalCount.setText(getString(R.string.red_packet_total_count_format, totalCount));
            }
            if (tvClaimedAmount != null) {
                tvClaimedAmount.setText(getString(R.string.red_packet_claimed_amount_format, claimedAmount));
            }
            if (tvClaimedCount != null) {
                tvClaimedCount.setText(getString(R.string.red_packet_claimed_count_format, claimedCount));
            }

            List<RedPacketClaimAdapter.Claim> claims = new ArrayList<RedPacketClaimAdapter.Claim>();
            JSONArray arr = obj.optJSONArray("claims");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cObj = arr.optJSONObject(i);
                    if (cObj == null) {
                        continue;
                    }
                    RedPacketClaimAdapter.Claim claim = new RedPacketClaimAdapter.Claim();
                    String name = cObj.optString("display_name", "");
                    if (name == null || name.isEmpty()) {
                        name = cObj.optString("uid", "");
                    }
                    claim.name = name;
                    claim.amount = cObj.optInt("amount", 0);
                    claim.createdAt = cObj.optLong("created_at", 0);
                    claims.add(claim);
                }
            }
            if (adapter != null) {
                adapter.setClaims(claims);
            }
        } catch (Exception e) {
        }
    }
}
