package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.ui.widget.ButtonFlowLayout;
import com.im.oldchat.util.ButtonTidVerifier;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.SettingsPrefs;

import org.json.JSONObject;

/**
 * 消息内联按钮面板：渲染在气泡下方，支持 send_text/open_url/send_image/reply_msg/form。
 * 每个按钮点击前先离线验签 tid，无效则不渲染。
 */
public final class MessageButtonPanel {

    public interface ButtonCallback {
        /** 触发 send_text / send_image：把预设内容发到当前会话。 */
        void onSendPayload(MessagePayload.Button button, String toType, String toId);
    }

    private MessageButtonPanel() {
    }

    /** 渲染按钮面板到容器。messageId 用于离线验签。 */
    public static void render(LinearLayout container, MessagePayload payload,
                              final String messageId, final String toType, final String toId,
                              final ButtonCallback callback) {
        if (container == null || payload == null || payload.buttons == null || payload.buttons.isEmpty()) {
            return;
        }
        container.removeAllViews();
        final Context context = container.getContext();

        // 过滤出验证通过的按钮
        java.util.ArrayList<MessagePayload.Button> valid = new java.util.ArrayList<MessagePayload.Button>();
        for (int i = 0; i < payload.buttons.size(); i++) {
            MessagePayload.Button b = payload.buttons.get(i);
            if (b == null) {
                continue;
            }
            if (b.tid != null && !b.tid.isEmpty()) {
                if (ButtonTidVerifier.verify(b, messageId)) {
                    valid.add(b);
                }
                // tid 无效的按钮忽略（防伪造）
            } else {
                valid.add(b);
            }
        }
        if (valid.isEmpty()) {
            return;
        }

        if (container instanceof ButtonFlowLayout) {
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            ((ButtonFlowLayout) container).setMaxRowWidth(Math.min(dp(context, 280),
                    Math.max(dp(context, 160), screen - dp(context, 92))));
        }
        for (int i = 0; i < valid.size(); i++) {
            final MessagePayload.Button button = valid.get(i);
            final boolean expired = "form".equals(button.action) && button.expiresAt > 0L
                    && System.currentTimeMillis() / 1000L >= button.expiresAt;
            TextView btn = new TextView(context);
            String label = button.text == null ? "" : button.text;
            btn.setText(expired ? label + "（已过期）" : label);
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(13f);
            btn.setTextColor(expired ? 0xFF9CA3AF : 0xFF2563EB);
            btn.setMinWidth(dp(context, 58));
            btn.setMinHeight(dp(context, 36));
            btn.setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7));
            btn.setSingleLine(true);
            // View#setBackground(Drawable) 是 API 16 才引入，Android 4.0/4.0.x 需用 setBackgroundDrawable。
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                btn.setBackground(makeButtonBackground(context));
            } else {
                btn.setBackgroundDrawable(makeButtonBackground(context));
            }
            container.addView(btn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (expired) {
                        Toast.makeText(context, "表单已过期", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    handleClick(context, button, messageId, toType, toId, callback);
                }
            });
        }
    }

    private static void handleClick(Context context, final MessagePayload.Button button,
                                    final String messageId, final String toType, final String toId,
                                    final ButtonCallback callback) {
        String action = button.action == null ? "" : button.action;
        if ("open_url".equals(action)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(button.data));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if ("reply_msg".equals(action)) {
            // 填入输入框：客户端无法直接访问输入框，走回调让服务端返回（或在 UI 层处理）
            Toast.makeText(context, "reply: " + button.data, Toast.LENGTH_SHORT).show();
            return;
        }
        if ("form".equals(action)) {
            BotFormDialog.show(context, button.data, new BotFormDialog.SubmitCallback() {
                @Override public void onSubmit(String formData) {
                    fireCallback(context, messageId, button, toType, toId, formData);
                }
            });
            return;
        }
        if ("send_text".equals(action) || "send_image".equals(action)) {
            if (callback != null) {
                callback.onSendPayload(button, toType, toId);
            } else {
                Toast.makeText(context, "点击发送", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // 未知 action：调服务端回调，让服务端决定（防客户端伪造 action）
        fireCallback(context, messageId, button, toType, toId, "");
    }

    private static void fireCallback(final Context context, String messageId, MessagePayload.Button button,
                                     String toType, String toId, final String formData) {
        try {
            String token = null;
            try {
                token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                        .getString("access_token", "");
            } catch (Throwable ignored) {
            }
            if (token == null || token.length() == 0) {
                return;
            }
            JSONObject body = new JSONObject();
            body.put("msg_id", messageId);
            body.put("to_type", toType);
            body.put("to_id", toId);
            body.put("btn_index", button.index);
            body.put("tid", button.tid);
            body.put("nonce", button.nonce);
            body.put("action", button.action);
            if (formData != null && formData.length() > 0) body.put("form_data", formData);
            com.im.oldchat.api.HttpUtil.post("/v2/buttons/callback", body, token,
                    new com.im.oldchat.api.HttpUtil.Callback() {
                        @Override
                        public void onSuccess(String response) {
                            if (formData != null && formData.length() > 0) {
                                Toast.makeText(context, "表单已提交", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onError(int code, String error) {
                            String tip = "提交失败";
                            try {
                                JSONObject result = new JSONObject(error == null ? "{}" : error);
                                String errorCode = result.optString("code", "");
                                if ("tid_used".equals(errorCode)) tip = "你已经提交过该表单";
                                else if ("form_expired".equals(errorCode)) tip = "表单已过期";
                                else if ("invalid_tid".equals(errorCode)) tip = "表单验证失败，请刷新消息";
                            } catch (Exception ignored) { }
                            Toast.makeText(context, tip, Toast.LENGTH_SHORT).show();
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static android.graphics.drawable.Drawable makeButtonBackground(Context context) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        // 跟随"圆角按钮"开关：开=16dp 圆角，关=直角。
        boolean rounded = com.im.oldchat.util.SettingsPrefs.isRoundedButtonsEnabled(context);
        d.setCornerRadius(rounded ? dp(context, 16) : 0f);
        d.setStroke(dp(context, 1), 0xFFD1D5DB);
        d.setColor(Color.WHITE);
        return d;
    }
}
