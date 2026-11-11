package com.im.oldchat.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.v4.view.ViewCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.im.oldchat.R;
import com.im.oldchat.ui.widget.CircleImageView;

/**
 * 类似 Telegram X 的进入聊天页面共享元素过渡动画。
 * 1. 进入：页面主体 100% 实体侧滑入场，浮层头像从列表条目像素原位起飞至顶栏；
 * 2. 返回：走系统级视差转场（chat_slide_in_left_parallax / chat_slide_out_right），
 *    保证底层主页/聊天页面实时完整渲染并平滑微移展现，零黑屏零延迟。
 * 兼容 minSdk 9 (Android 2.3+)。
 */
public final class ChatHeaderEnterAnimHelper {
    public static final String EXTRA_ANIM_AVATAR_X = "anim_avatar_x";
    public static final String EXTRA_ANIM_AVATAR_Y = "anim_avatar_y";
    public static final String EXTRA_ANIM_AVATAR_W = "anim_avatar_w";
    public static final String EXTRA_ANIM_AVATAR_H = "anim_avatar_h";

    private static final long DURATION_ENTER_MS = 250L;

    private ChatHeaderEnterAnimHelper() {
    }

    public static void attachAvatarLocation(Intent intent, View avatarView) {
        if (intent == null || avatarView == null) {
            return;
        }
        int[] location = new int[2];
        avatarView.getLocationOnScreen(location);
        intent.putExtra(EXTRA_ANIM_AVATAR_X, location[0]);
        intent.putExtra(EXTRA_ANIM_AVATAR_Y, location[1]);
        intent.putExtra(EXTRA_ANIM_AVATAR_W, avatarView.getWidth());
        intent.putExtra(EXTRA_ANIM_AVATAR_H, avatarView.getHeight());
    }

    public static boolean hasAnimationData(Intent intent) {
        if (intent == null) {
            return false;
        }
        int srcX = intent.getIntExtra(EXTRA_ANIM_AVATAR_X, -1);
        int srcY = intent.getIntExtra(EXTRA_ANIM_AVATAR_Y, -1);
        int srcW = intent.getIntExtra(EXTRA_ANIM_AVATAR_W, 0);
        int srcH = intent.getIntExtra(EXTRA_ANIM_AVATAR_H, 0);
        return srcX >= 0 && srcY >= 0 && srcW > 0 && srcH > 0;
    }

    public static void runEnterAnimation(final Activity activity,
                                         final ImageView targetAvatar,
                                         final View tvTitle,
                                         final View btnBack,
                                         final View contentView) {
        if (activity == null || targetAvatar == null) {
            return;
        }
        if (UiPerformance.shouldReduceMotion(activity)) {
            return;
        }

        Intent intent = activity.getIntent();
        if (!hasAnimationData(intent)) {
            return;
        }

        final int rawSrcX = intent.getIntExtra(EXTRA_ANIM_AVATAR_X, 0);
        final int rawSrcY = intent.getIntExtra(EXTRA_ANIM_AVATAR_Y, 0);
        final int srcW = intent.getIntExtra(EXTRA_ANIM_AVATAR_W, 0);
        final int srcH = intent.getIntExtra(EXTRA_ANIM_AVATAR_H, 0);

        final View pageRoot = activity.findViewById(R.id.chat_page_root);
        final int screenW = activity.getResources().getDisplayMetrics().widthPixels;

        // 1. 立即隐藏顶栏原生头像，严防第一帧在顶栏出现造成闪烁
        targetAvatar.setVisibility(View.INVISIBLE);

        // 2. 页面整页初始处于右侧 35% 处（保持 100% 实体不透明度）
        if (pageRoot != null) {
            ViewCompat.setTranslationX(pageRoot, screenW * 0.35f);
        }
        if (tvTitle != null) {
            ViewCompat.setAlpha(tvTitle, 0f);
            ViewCompat.setTranslationX(tvTitle, 16f);
        }
        if (btnBack != null) {
            ViewCompat.setAlpha(btnBack, 0f);
        }

        // 3. 立即创建浮层头像并挂载到 DecorView，保证从第 0 帧起就在列表原位展示
        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        final CircleImageView flightAvatar = new CircleImageView(activity);
        flightAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable d = targetAvatar.getDrawable();
        if (d != null) {
            flightAvatar.setImageDrawable(d.getConstantState() != null ? d.getConstantState().newDrawable() : d);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(srcW, srcH, Gravity.TOP | Gravity.LEFT);
        flightAvatar.setLayoutParams(lp);
        ViewCompat.setTranslationX(flightAvatar, rawSrcX);
        ViewCompat.setTranslationY(flightAvatar, rawSrcY);
        decorView.addView(flightAvatar);

        // 4. 等待视图就绪后计算终点坐标并启动平滑插值动画
        targetAvatar.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                targetAvatar.getViewTreeObserver().removeOnPreDrawListener(this);

                int[] decorLoc = new int[2];
                decorView.getLocationOnScreen(decorLoc);

                final int[] targetLoc = new int[2];
                targetAvatar.getLocationOnScreen(targetLoc);
                final int targetW = targetAvatar.getWidth();
                final int targetH = targetAvatar.getHeight();

                if (targetW <= 0 || targetH <= 0 || activity.isFinishing()) {
                    if (pageRoot != null) {
                        ViewCompat.setTranslationX(pageRoot, 0f);
                    }
                    decorView.removeView(flightAvatar);
                    targetAvatar.setVisibility(View.VISIBLE);
                    return true;
                }

                final float startX = rawSrcX - decorLoc[0];
                final float startY = rawSrcY - decorLoc[1];
                float pageCurrentTx = pageRoot != null ? ViewCompat.getTranslationX(pageRoot) : 0f;
                final float endX = targetLoc[0] - decorLoc[0] - pageCurrentTx;
                final float endY = targetLoc[1] - decorLoc[1];

                // 同步修正浮层头像初始精确位置
                ViewCompat.setTranslationX(flightAvatar, startX);
                ViewCompat.setTranslationY(flightAvatar, startY);

                // 启动整页侧滑入场
                if (pageRoot != null) {
                    ViewCompat.animate(pageRoot)
                            .translationX(0f)
                            .setDuration(DURATION_ENTER_MS)
                            .setInterpolator(new DecelerateInterpolator(1.8f))
                            .start();
                }

                if (tvTitle != null) {
                    ViewCompat.animate(tvTitle)
                            .alpha(1.0f)
                            .translationX(0f)
                            .setDuration(DURATION_ENTER_MS)
                            .setStartDelay(30)
                            .start();
                }

                if (btnBack != null) {
                    ViewCompat.animate(btnBack)
                            .alpha(1.0f)
                            .setDuration(DURATION_ENTER_MS)
                            .setStartDelay(20)
                            .start();
                }

                if (Build.VERSION.SDK_INT >= 11) {
                    ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                    animator.setDuration(DURATION_ENTER_MS);
                    animator.setInterpolator(new DecelerateInterpolator(1.8f));
                    animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            float fraction = animation.getAnimatedFraction();
                            float curX = startX + (endX - startX) * fraction;
                            float curY = startY + (endY - startY) * fraction;
                            float curW = srcW + (targetW - srcW) * fraction;
                            float curH = srcH + (targetH - srcH) * fraction;

                            ViewCompat.setTranslationX(flightAvatar, curX);
                            ViewCompat.setTranslationY(flightAvatar, curY);
                            ViewGroup.LayoutParams currentLp = flightAvatar.getLayoutParams();
                            if (currentLp != null) {
                                currentLp.width = (int) (curW + 0.5f);
                                currentLp.height = (int) (curH + 0.5f);
                                flightAvatar.setLayoutParams(currentLp);
                            }
                        }
                    });
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            decorView.removeView(flightAvatar);
                            targetAvatar.setVisibility(View.VISIBLE);
                        }
                    });
                    animator.start();
                } else {
                    decorView.removeView(flightAvatar);
                    targetAvatar.setVisibility(View.VISIBLE);
                }

                return true;
            }
        });
    }
}
