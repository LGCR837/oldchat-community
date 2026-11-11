package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.api.WSManager;

/** 标题下方的聊天同步/连接状态行（正常连接时隐藏，仅在断线或同步时显示）。 */
public class ChatSyncBannerHelper {
    private static final long ENTER_DURATION_MS = 240L;
    private static final long EXIT_DURATION_MS = 220L;

    private final View stateContainer;
    private final View stateDot;
    private final TextView stateText;
    private boolean syncing;
    private boolean connected;
    private boolean connectionFailed;
    private boolean isCurrentlyVisible;

    public ChatSyncBannerHelper(Activity activity) {
        stateContainer = activity.findViewById(R.id.llChatConnectionStatus);
        stateDot = activity.findViewById(R.id.vChatConnectionDot);
        stateText = (TextView) activity.findViewById(R.id.tvChatConnectionStatus);
        connected = WSManager.getInstance().isConnected();
        connectionFailed = false;
        isCurrentlyVisible = false;
        if (stateText != null) stateText.setTag(this);
        render(false);
    }

    /** 同步开始：已连接时黄色；断线时仍以红色断线为最高优先级。 */
    public void show(String text) {
        syncing = true;
        render(true);
    }

    /** 兼容旧调用，状态文案由状态机统一生成。 */
    public void updateText(String text) {
    }

    public void hideImmediately() {
        syncing = false;
        render(false);
    }

    public void startFinishAnimation() {
        syncing = false;
        render(true);
    }

    /** WebSocket 回调入口，单聊/群聊共用。 */
    public static void onConnectionChanged(Activity activity, boolean isConnected) {
        if (activity == null) return;
        TextView text = (TextView) activity.findViewById(R.id.tvChatConnectionStatus);
        if (text == null || !(text.getTag() instanceof ChatSyncBannerHelper)) return;
        ChatSyncBannerHelper helper = (ChatSyncBannerHelper) text.getTag();
        helper.connected = isConnected;
        helper.connectionFailed = !isConnected;
        helper.render(true);
    }

    public void release() {
        if (stateContainer != null) {
            stateContainer.clearAnimation();
        }
        if (stateText != null && stateText.getTag() == this) stateText.setTag(null);
    }

    public boolean isVisible() {
        return syncing || connectionFailed;
    }

    public long getShownAt() {
        return 0L;
    }

    private void render(boolean animate) {
        if (stateContainer == null || stateDot == null || stateText == null) return;

        boolean shouldShow = connectionFailed || syncing;

        if (shouldShow) {
            if (connectionFailed) {
                apply("断开连接", R.drawable.bg_chat_state_red, Color.rgb(239, 68, 68));
            } else {
                apply("同步中", R.drawable.bg_chat_state_yellow, Color.rgb(251, 191, 36));
            }

            if (!isCurrentlyVisible) {
                isCurrentlyVisible = true;
                stateContainer.clearAnimation();
                stateContainer.setVisibility(View.VISIBLE);
                stateDot.setVisibility(View.VISIBLE);
                stateText.setVisibility(View.VISIBLE);

                if (animate) {
                    float offsetPx = dpToPx(stateContainer.getContext(), 6f);
                    AnimationSet set = new AnimationSet(true);
                    set.setInterpolator(new DecelerateInterpolator(1.5f));
                    set.setDuration(ENTER_DURATION_MS);

                    AlphaAnimation alpha = new AlphaAnimation(0.0f, 1.0f);
                    alpha.setDuration(ENTER_DURATION_MS);
                    set.addAnimation(alpha);

                    TranslateAnimation translate = new TranslateAnimation(0f, 0f, -offsetPx, 0f);
                    translate.setDuration(ENTER_DURATION_MS);
                    set.addAnimation(translate);

                    stateContainer.startAnimation(set);
                }
            }
        } else {
            if (isCurrentlyVisible) {
                isCurrentlyVisible = false;
                stateContainer.clearAnimation();

                if (animate) {
                    float offsetPx = dpToPx(stateContainer.getContext(), 6f);
                    AnimationSet set = new AnimationSet(true);
                    set.setInterpolator(new AccelerateInterpolator(1.5f));
                    set.setDuration(EXIT_DURATION_MS);

                    AlphaAnimation alpha = new AlphaAnimation(1.0f, 0.0f);
                    alpha.setDuration(EXIT_DURATION_MS);
                    set.addAnimation(alpha);

                    TranslateAnimation translate = new TranslateAnimation(0f, 0f, 0f, -offsetPx);
                    translate.setDuration(EXIT_DURATION_MS);
                    set.addAnimation(translate);

                    set.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {
                        }

                        @Override
                        public void onAnimationEnd(Animation animation) {
                            if (!isCurrentlyVisible && stateContainer != null) {
                                stateContainer.setVisibility(View.GONE);
                                if (stateDot != null) stateDot.setVisibility(View.GONE);
                                if (stateText != null) stateText.setVisibility(View.GONE);
                            }
                        }

                        @Override
                        public void onAnimationRepeat(Animation animation) {
                        }
                    });

                    stateContainer.startAnimation(set);
                } else {
                    stateContainer.setVisibility(View.GONE);
                    stateDot.setVisibility(View.GONE);
                    stateText.setVisibility(View.GONE);
                }
            } else {
                stateContainer.setVisibility(View.GONE);
                stateDot.setVisibility(View.GONE);
                stateText.setVisibility(View.GONE);
            }
        }
    }

    private static float dpToPx(Context context, float dp) {
        if (context == null) return dp * 2f;
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    private void apply(String text, int dotDrawable, int textColor) {
        stateText.setText(text);
        stateText.setTextColor(textColor);
        stateDot.setBackgroundResource(dotDrawable);
    }
}
