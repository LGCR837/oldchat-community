package com.im.oldchat.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 每日刮刮乐：每天刮一次，5 个槽位独立随机（谢谢惠顾 / 1 / 5 / 10 / 20 旧币）。
 * 服务端负责每日一次 + 发旧币的原子性，客户端仅展示与触发。
 */
public class ScratchActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";
    private static final String[] PRIZE_LABELS = {"谢谢惠顾", "1 旧币", "5 旧币", "10 旧币", "20 旧币"};

    private TextView tvStatus, tvBalance, tvHint, btnScratch;
    private ProgressBar pbLoading;
    private TextView[] slotViews;
    private String token;
    private boolean loading, alreadyDone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scratch);
        token = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE).getString("access_token", "");
        bindViews();
        bindActions();
        loadStatus();
    }

    private void bindViews() {
        tvStatus = findViewByIdCompat(R.id.tvScratchStatus);
        tvBalance = findViewByIdCompat(R.id.tvScratchBalance);
        tvHint = findViewByIdCompat(R.id.tvScratchHint);
        btnScratch = findViewByIdCompat(R.id.btnScratchGo);
        pbLoading = findViewByIdCompat(R.id.pbScratchLoading);
        slotViews = new TextView[5];
        slotViews[0] = findViewByIdCompat(R.id.tvScratchSlot0);
        slotViews[1] = findViewByIdCompat(R.id.tvScratchSlot1);
        slotViews[2] = findViewByIdCompat(R.id.tvScratchSlot2);
        slotViews[3] = findViewByIdCompat(R.id.tvScratchSlot3);
        slotViews[4] = findViewByIdCompat(R.id.tvScratchSlot4);
    }

    private void bindActions() {
        setClick(R.id.btnScratchBack, finishListener());
        if (btnScratch != null) {
            btnScratch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (loading) return;
                    if (alreadyDone) {
                        Toast.makeText(ScratchActivity.this, "今天已经刮过啦，明天再来吧", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doScratch();
                }
            });
        }
    }

    private void setClick(int id, View.OnClickListener l) {
        View v = findViewByIdCompat(id);
        if (v != null) v.setOnClickListener(l);
    }

    private View.OnClickListener finishListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        };
    }

    private void setLoading(boolean on) {
        loading = on;
        if (pbLoading != null) {
            pbLoading.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private void loadStatus() {
        setLoading(true);
        HttpUtil.get("/me/scratch", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                setLoading(false);
                render(parseResult(response));
            }

            @Override
            public void onError(int code, String error) {
                setLoading(false);
                Toast.makeText(ScratchActivity.this, "加载失败：" + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doScratch() {
        setLoading(true);
        HttpUtil.post("/me/scratch", new JSONObject(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                setLoading(false);
                ScratchResult result = parseResult(response);
                render(result);
                if (result.totalReward > 0) {
                    Toast.makeText(ScratchActivity.this, "恭喜获得 " + result.totalReward + " 旧币！", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ScratchActivity.this, "可惜没有中奖，明天再来~", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                setLoading(false);
                Toast.makeText(ScratchActivity.this, "刮奖失败：" + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void render(ScratchResult result) {
        if (result == null) return;
        alreadyDone = result.alreadyDone;
        if (tvBalance != null) {
            tvBalance.setText("旧币余额：" + result.coinBalance);
        }
        if (result.slots != null && result.slots.length == slotViews.length) {
            for (int i = 0; i < slotViews.length; i++) {
                if (slotViews[i] == null) continue;
                slotViews[i].setText(labelFor(result.slots[i]));
            }
        }
        if (alreadyDone) {
            if (tvStatus != null) {
                tvStatus.setText("今日已刮奖，明天再来吧");
            }
            if (btnScratch != null) {
                btnScratch.setText("明日再来");
            }
            if (tvHint != null) {
                if (result.totalReward > 0) {
                    tvHint.setText("今日获得 " + result.totalReward + " 旧币");
                } else {
                    tvHint.setText("今日未中奖");
                }
            }
        } else {
            if (tvStatus != null) {
                tvStatus.setText("今日还未刮奖，试试手气吧");
            }
            if (btnScratch != null) {
                btnScratch.setText("刮一刮");
            }
            if (tvHint != null) {
                tvHint.setText("中奖概率很高，每天可刮一次");
            }
        }
    }

    private static String labelFor(int value) {
        if (value <= 0) return PRIZE_LABELS[0];
        if (value == 1) return PRIZE_LABELS[1];
        if (value == 5) return PRIZE_LABELS[2];
        if (value == 10) return PRIZE_LABELS[3];
        if (value == 20) return PRIZE_LABELS[4];
        return value + " 旧币";
    }

    private static ScratchResult parseResult(String response) {
        ScratchResult result = new ScratchResult();
        try {
            JSONObject obj = new JSONObject(response);
            result.alreadyDone = obj.optBoolean("already_done", false);
            result.totalReward = obj.optInt("total_reward", 0);
            result.coinBalance = obj.optInt("coin_balance", 0);
            JSONArray arr = obj.optJSONArray("slots");
            if (arr != null && arr.length() > 0) {
                result.slots = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    result.slots[i] = arr.optInt(i, 0);
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static class ScratchResult {
        boolean alreadyDone;
        int totalReward;
        int coinBalance;
        int[] slots;
    }
}
