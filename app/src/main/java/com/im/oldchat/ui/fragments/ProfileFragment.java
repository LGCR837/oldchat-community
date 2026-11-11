package com.im.oldchat.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.ui.ProfileEditActivity;
import com.im.oldchat.ui.FavoritesActivity;
import com.im.oldchat.ui.FileCenterActivity;
import com.im.oldchat.ui.SettingsActivity;
import com.im.oldchat.util.ImageLoader;
import org.json.JSONObject;

public class ProfileFragment extends Fragment {
    private static final String PROFILE_CACHE_PREFS = "profile_cache";
    private static final String PROFILE_CACHE_KEY = "me_profile_json";
    private ImageView ivAvatar;
    private TextView tvUser;
    private TextView tvTitleBadge;
    private TextView tvEmail;
    private TextView tvWalletBalance;
    private TextView tvReputationScore;
    private View profileCard;
    private View btnMySpace;
    private View btnMyFavorites;
    private View btnMyFiles;
    private View btnMyPresenceStatus;
    private TextView tvMyPresenceValue;
    private View btnSettings;
    private View btnDonate;
    private String token;
    private String currentAvatarUrl;
    private String currentPresenceStatus = "online";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);
        if (com.im.oldchat.util.ThemeColorUtil.isMinimalDark(view.getContext())) {
            view.setBackgroundColor(0xFF08090A);
        }

        ivAvatar = view.findViewById(R.id.ivAvatar);
        tvUser = view.findViewById(R.id.tvMyUsername);
        tvTitleBadge = view.findViewById(R.id.tvMyTitleBadge);
        tvEmail = view.findViewById(R.id.tvMyEmail);
        tvWalletBalance = view.findViewById(R.id.tvWalletBalance);
        tvReputationScore = view.findViewById(R.id.tvReputationScore);
        profileCard = view.findViewById(R.id.profile_card);
        btnMySpace = view.findViewById(R.id.btnMySpace);
        btnMyFavorites = view.findViewById(R.id.btnMyFavorites);
        btnMyFiles = view.findViewById(R.id.btnMyFiles);
        btnMyPresenceStatus = view.findViewById(R.id.btnMyPresenceStatus);
        tvMyPresenceValue = view.findViewById(R.id.tvMyPresenceValue);
        btnSettings = view.findViewById(R.id.btnSettings);
        btnDonate = view.findViewById(R.id.btnDonate);
        // 与发现页一致：所有主题下"我的"页面按钮无边框、背景与页面融为一体，且无倾斜动画。
        int[] seamlessIds = new int[]{R.id.profile_card, R.id.btnMySpace, R.id.btnMyFavorites,
                R.id.btnMyFiles, R.id.btnMyPresenceStatus, R.id.btnSettings, R.id.btnDonate};
        for (int i = 0; i < seamlessIds.length; i++) {
            View row = view.findViewById(seamlessIds[i]);
            if (row != null) {
                row.setBackgroundResource(R.drawable.bg_seamless_row);
            }
        }
        disableTiltRecursively(view);

        SharedPreferences prefs = getActivity().getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        if (profileCard != null) {
            profileCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(getActivity(), ProfileEditActivity.class));
                }
            });
        }
        if (btnMySpace != null) {
            btnMySpace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getActivity(), com.im.oldchat.ui.UserSpaceActivity.class);
                    startActivity(intent);
                }
            });
        }
        if (btnMyFavorites != null) {
            btnMyFavorites.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(getActivity(), FavoritesActivity.class));
                }
            });
        }
        if (btnMyFiles != null) {
            btnMyFiles.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(getActivity(), FileCenterActivity.class));
                }
            });
        }
        if (btnMyPresenceStatus != null) {
            btnMyPresenceStatus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPresenceDialog();
                }
            });
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(getActivity(), SettingsActivity.class));
                }
            });
        }
        if (btnDonate != null) {
            btnDonate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 直接展示赞助对话框（支付宝/微信双码 + 说明）。
                    android.app.Activity host = getActivity();
                    if (host == null) {
                        return;
                    }
                    View dialogView = host.getLayoutInflater().inflate(
                            R.layout.dialog_support_donate, null);
                    new android.support.v7.app.AlertDialog.Builder(host,
                            R.style.AppDialogTheme)
                            .setTitle("赞助支持")
                            .setView(dialogView)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        }

        loadProfile();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProfile();
    }

    private void loadProfile() {
        final long started = com.im.oldchat.util.ScreenPerfTracer.now();        loadCachedProfile();
        HttpUtil.get("/me", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                com.im.oldchat.util.ScreenPerfTracer.trace(getActivity(), "ProfileFragment", "profile_http", started, "result=ok");
                try {
                    persistProfileCache(response);
                    applyProfile(response);
                } catch (Exception e) {
                    Toast.makeText(getActivity(), "加载资料失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                com.im.oldchat.util.ScreenPerfTracer.trace(getActivity(), "ProfileFragment", "profile_http", started, "result=error code=" + code);
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(getActivity(), "加载资料失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadCachedProfile() {
        if (getActivity() == null) {
            return;
        }
        SharedPreferences prefs = getActivity().getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE);
        String cached = prefs.getString(PROFILE_CACHE_KEY, "");
        if (cached == null || cached.isEmpty()) {
            return;
        }
        applyProfile(cached);
    }

    private void persistProfileCache(String payload) {
        if (getActivity() == null || payload == null) {
            return;
        }
        SharedPreferences prefs = getActivity().getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PROFILE_CACHE_KEY, payload).apply();
    }

    private void applyProfile(String payload) {
        try {
            JSONObject obj = new JSONObject(payload);
            String uid = obj.optString("uid", "");
            String username = obj.optString("username", "");
            String displayName = obj.optString("display_name", "");
            // 存 display_name 到 auth prefs：发送 pending 消息时渲染自己的名字用。
            try {
                if (displayName != null && !displayName.isEmpty()) {
                    getActivity().getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                            .edit().putString("display_name", displayName).apply();
                }
            } catch (Throwable ignored) {
            }
            String userTitle = obj.optString("user_title", "");
            currentAvatarUrl = obj.optString("avatar_url", "");
            String primary = displayName;
            if (primary == null || primary.isEmpty()) {
                primary = uid;
            }
            if (tvUser != null) {
                tvUser.setText(primary == null ? "" : primary);
            }
            com.im.oldchat.ui.UserTitleBinder.bind(tvTitleBadge, userTitle);
            StringBuilder secondary = new StringBuilder();
            if (uid != null && !uid.isEmpty()) {
                secondary.append("UID: ").append(uid);
            }
            if (username != null && !username.isEmpty()) {
                if (secondary.length() > 0) {
                    secondary.append("  ");
                }
                secondary.append("用户名: ").append(username);
            }
            if (tvEmail != null) {
                tvEmail.setText(secondary.toString());
            }
            int balance = obj.optInt("coin_balance", 0);
            int reputationScore = obj.optInt("reputation_score", 0);
            currentPresenceStatus = obj.optString("presence_status", "online");
            if (tvMyPresenceValue != null) {
                tvMyPresenceValue.setText("invisible".equals(currentPresenceStatus) ? "隐身" : "在线");
            }
            if (tvWalletBalance != null) {
                String amountText = getString(R.string.profile_wallet_amount_format, balance);
                tvWalletBalance.setText(getString(R.string.wallet_balance_label) + ": " + amountText);
            }
            if (tvReputationScore != null) {
                String scoreText = getString(R.string.profile_reputation_format, reputationScore);
                tvReputationScore.setText(getString(R.string.profile_reputation_label) + ": " + scoreText);
            }
            ImageLoader.loadAvatar(ivAvatar, currentAvatarUrl);
        } catch (Exception e) {
        }
    }

    private void showPresenceDialog() {
        if (getActivity() == null) return;
        final String[] items = {"在线", "隐身"};
        int selected = "invisible".equals(currentPresenceStatus) ? 1 : 0;
        android.support.v7.app.AlertDialog.Builder builder = new android.support.v7.app.AlertDialog.Builder(getActivity());
        builder.setTitle("选择在线状态");
        builder.setSingleChoiceItems(items, selected, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
                String newStatus = which == 1 ? "invisible" : "online";
                updatePresenceStatus(newStatus);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void updatePresenceStatus(final String status) {
        if (getActivity() == null || token == null || token.isEmpty()) return;
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("presence_status", status);
            HttpUtil.post("/me/presence", body, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    if (getActivity() == null) return;
                    currentPresenceStatus = status;
                    if (tvMyPresenceValue != null) {
                        tvMyPresenceValue.setText("invisible".equals(status) ? "隐身" : "在线");
                    }
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(response);
                        String uid = obj.optString("uid", "");
                        if (uid.length() > 0) {
                            com.im.oldchat.util.UserPresenceCache.put(uid, true, status);
                        }
                    } catch (Exception e) {
                    }
                }
                @Override
                public void onError(int code, String error) {
                    if (getActivity() == null) return;
                    Toast.makeText(getActivity(), "更新状态失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(getActivity(), "更新状态失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void disableTiltRecursively(View view) {
        if (view == null) return;
        if (view instanceof com.im.oldchat.ui.widget.TiltFrameLayout) {
            ((com.im.oldchat.ui.widget.TiltFrameLayout) view).setTiltEnabled(false);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableTiltRecursively(group.getChildAt(i));
            }
        }
    }
}
