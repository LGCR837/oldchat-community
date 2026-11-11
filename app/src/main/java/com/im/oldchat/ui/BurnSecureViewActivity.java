package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MessagePayload;

public class BurnSecureViewActivity extends BaseActivity {
    private static final String EXTRA_MESSAGE_ID = "extra_message_id";
    private static final String EXTRA_BURN_TEXT = "extra_burn_text";
    private static final String EXTRA_BURN_SECONDS = "extra_burn_seconds";
    private static final String EXTRA_MESSAGE_TYPE = "extra_message_type";
    private static final String EXTRA_MEDIA_URL = "extra_media_url";
    private static final String EXTRA_THUMB_URL = "extra_thumb_url";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvCountdown;
    private TextView tvContent;
    private Runnable countdownTask;
    private int remainSeconds;

    public static void start(Context context, String messageId, String text, int seconds) {
        start(context, messageId, text, "text", "", "", seconds);
    }

    public static void start(Context context, String messageId, String text, String type,
                             String mediaUrl, String thumbUrl, int seconds) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, BurnSecureViewActivity.class);
        intent.putExtra(EXTRA_MESSAGE_ID, messageId == null ? "" : messageId);
        intent.putExtra(EXTRA_BURN_TEXT, text == null ? "" : text);
        intent.putExtra(EXTRA_MESSAGE_TYPE, type == null ? "text" : type);
        intent.putExtra(EXTRA_MEDIA_URL, mediaUrl == null ? "" : mediaUrl);
        intent.putExtra(EXTRA_THUMB_URL, thumbUrl == null ? "" : thumbUrl);
        intent.putExtra(EXTRA_BURN_SECONDS, seconds);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_burn_secure_view);

        tvCountdown = findViewByIdCompat(R.id.tvBurnCountdown);
        tvContent = findViewByIdCompat(R.id.tvBurnContent);
        TextView tvQuote = findViewByIdCompat(R.id.tvBurnQuote);
        ImageView ivImage = findViewByIdCompat(R.id.ivBurnImage);
        TextView tvClose = findViewByIdCompat(R.id.tvBurnClose);

        String text = getIntent().getStringExtra(EXTRA_BURN_TEXT);
        remainSeconds = getIntent().getIntExtra(EXTRA_BURN_SECONDS, 0);
        if (remainSeconds <= 0) {
            remainSeconds = 10;
        }

        String type = getIntent().getStringExtra(EXTRA_MESSAGE_TYPE);
        String mediaUrl = getIntent().getStringExtra(EXTRA_MEDIA_URL);
        String thumbUrl = getIntent().getStringExtra(EXTRA_THUMB_URL);
        MessagePayload payload = MessagePayload.fromBody(text);
        String plainText = payload.text == null ? "" : payload.text.trim();
        if (payload.quote != null && tvQuote != null) {
            String quoteName = payload.quote.fromName == null ? "" : payload.quote.fromName.trim();
            String quoteText = payload.quote.text == null ? "" : payload.quote.text.trim();
            if (quoteText.length() == 0) quoteText = ChatMessageUtil.quotePreview(
                    payload.quote.type, payload.quote.mediaKind, payload.quote.text);
            tvQuote.setText((quoteName.length() == 0 ? "引用消息" : quoteName) + "\n" + quoteText);
            tvQuote.setVisibility(View.VISIBLE);
        }
        if ("image".equalsIgnoreCase(type) && ivImage != null) {
            String imageUrl = mediaUrl == null || mediaUrl.length() == 0 ? thumbUrl : mediaUrl;
            if (imageUrl != null && imageUrl.length() > 0) {
                ivImage.setVisibility(View.VISIBLE);
                ImageLoader.load(ivImage, imageUrl);
            }
        }
        if (plainText.length() == 0 && !"image".equalsIgnoreCase(type)) {
            plainText = getString(R.string.chat_burn_message_empty);
        }
        tvContent.setText(plainText);
        tvContent.setVisibility(plainText.length() == 0 ? View.GONE : View.VISIBLE);
        renderCountdown();

        if (tvClose != null) {
            tvClose.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    finish();
                }
            });
        }
        startCountdown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCountdown();
    }

    private void startCountdown() {
        stopCountdown();
        countdownTask = new Runnable() {
            @Override
            public void run() {
                remainSeconds--;
                if (remainSeconds <= 0) {
                    finish();
                    return;
                }
                renderCountdown();
                handler.postDelayed(this, 1000L);
            }
        };
        handler.postDelayed(countdownTask, 1000L);
    }

    private void stopCountdown() {
        if (countdownTask != null) {
            handler.removeCallbacks(countdownTask);
            countdownTask = null;
        }
    }

    private void renderCountdown() {
        if (tvCountdown == null) {
            return;
        }
        tvCountdown.setText(getString(R.string.chat_burn_view_countdown, remainSeconds));
    }
}
