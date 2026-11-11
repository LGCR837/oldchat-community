package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.view.ViewCompat;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.models.DailyCheckInWallMessage;
import com.im.oldchat.models.DailyCheckInWallState;
import com.im.oldchat.util.ImageLoader;

import java.util.ArrayList;
import java.util.Random;

public class DailyCheckInWallActivity extends BaseActivity {
    private static final String AUTH_PREFS = "auth";
    private static final int REQ_PICK_IMAGE = 1451;
    private static final int REQ_COMMENTS = 1452;
    private static final long SWITCH_INTERVAL_MS = 3000L;

    private final Random random = new Random();
    private final ArrayList<DailyCheckInWallMessage> randomMessages = new ArrayList<DailyCheckInWallMessage>();
    private TextView tvCount, tvStatus, tvHint, tvMyPost, tvRandomName, tvRandomBody, tvRandomStat;
    private TextView btnCheckIn, btnPublish, btnSwitch, btnLike, btnComment, btnMyLikes, btnMyComments;
    private EditText etMessage;
    private ImageView ivPreview, ivRandomImage, ivRandomAvatar;
    private View cardMyPost, btnRemoveImage, composerPanel, pbLoading, cardRandom;
    private String token;
    private boolean checkedIn, alreadyPosted, loading, publishing, liking;
    private DailyCheckInWallSupport.PickedImage pickedImage;
    private DailyCheckInWallMessage currentRandomMessage, myPost;
    private long lastSwitchAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_daily_checkin_wall);
        token = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE).getString("access_token", "");
        bindViews();
        bindActions();
        syncUi();
        loadWall();
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_COMMENTS) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                applyCommentResult(data);
            }
            return;
        }
        if (requestCode != REQ_PICK_IMAGE || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        pickedImage = DailyCheckInWallSupport.loadPickedImage(this, data.getData());
        if (pickedImage == null) {
            clearPickedImage();
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        ivPreview.setImageBitmap(pickedImage.preview);
        ivPreview.setVisibility(View.VISIBLE);
        btnRemoveImage.setVisibility(View.VISIBLE);
        if (etMessage.getText() != null && etMessage.getText().toString().trim().length() > 0) {
            etMessage.setText("");
        }
        syncUi();
    }
    private void bindViews() {
        tvCount = findViewByIdCompat(R.id.tvDailyCheckInCount); tvStatus = findViewByIdCompat(R.id.tvDailyCheckInStatus);
        tvHint = findViewByIdCompat(R.id.tvDailyCheckInHint); tvMyPost = findViewByIdCompat(R.id.tvDailyCheckInMyPost);
        tvRandomName = findViewByIdCompat(R.id.tvDailyCheckInRandomName); tvRandomBody = findViewByIdCompat(R.id.tvDailyCheckInRandomBody);
        tvRandomStat = findViewByIdCompat(R.id.tvDailyCheckInRandomStat); btnCheckIn = findViewByIdCompat(R.id.btnDailyCheckInAction);
        btnPublish = findViewByIdCompat(R.id.btnDailyCheckInPublish); btnSwitch = findViewByIdCompat(R.id.btnDailyCheckInSwitch);
        btnLike = findViewByIdCompat(R.id.btnDailyCheckInLike); btnComment = findViewByIdCompat(R.id.btnDailyCheckInComment);
        btnMyLikes = findViewByIdCompat(R.id.btnDailyCheckInMyLikes); btnMyComments = findViewByIdCompat(R.id.btnDailyCheckInMyComments);
        etMessage = findViewByIdCompat(R.id.etDailyCheckInMessage); ivPreview = findViewByIdCompat(R.id.ivDailyCheckInPreview);
        ivRandomAvatar = findViewByIdCompat(R.id.ivDailyCheckInRandomAvatar);
        ivRandomImage = findViewByIdCompat(R.id.ivDailyCheckInRandomImage); cardMyPost = findViewByIdCompat(R.id.cardDailyCheckInMyPost);
        btnRemoveImage = findViewByIdCompat(R.id.btnDailyCheckInRemoveImage); composerPanel = findViewByIdCompat(R.id.panelDailyCheckInComposer);
        pbLoading = findViewByIdCompat(R.id.pbDailyCheckInLoading); cardRandom = findViewByIdCompat(R.id.cardDailyCheckInRandom);
    }
    private void bindActions() {
        setClick(R.id.btnDailyCheckInBack, finishListener());
        setClick(R.id.btnDailyCheckInRefresh, refreshListener());
        btnCheckIn.setOnClickListener(checkInListener()); btnPublish.setOnClickListener(publishListener());
        btnSwitch.setOnClickListener(switchListener()); btnLike.setOnClickListener(likeListener());
        btnComment.setOnClickListener(commentListener()); btnMyLikes.setOnClickListener(myLikesListener());
        btnMyComments.setOnClickListener(myCommentsListener()); btnRemoveImage.setOnClickListener(removeImageListener());
        setClick(R.id.btnDailyCheckInPickImage, pickImageListener());
        ivRandomImage.setOnClickListener(openImageListener());
        attachSwipeSwitch(cardRandom);
    }

    /** 随机留言卡片支持左右滑动手势切换。 */
    private void attachSwipeSwitch(final View target) {
        if (target == null) {
            return;
        }
        final android.view.GestureDetector detector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) {
                            return false;
                        }
                        float dx = e2.getX() - e1.getX();
                        float dy = Math.abs(e2.getY() - e1.getY());
                        if (Math.abs(dx) > 80f && Math.abs(dx) > dy * 1.5f) {
                            switchRandomCard(false, dx > 0f ? 1 : -1);
                            return true;
                        }
                        return false;
                    }
                });
        target.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                boolean handled = detector.onTouchEvent(event);
                if (event.getAction() == android.view.MotionEvent.ACTION_UP && !handled) {
                    v.performClick();
                }
                return handled;
            }
        });
    }
    private void loadWall() {
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loading = true;
        syncUi();
        DailyCheckInWallActionHelper.loadWall(token, new DailyCheckInWallActionHelper.SimpleCallback() {
            @Override public void onSuccess(String response) {
                loading = false;
                bindState(DailyCheckInWallNetworkHelper.parseWallState(response));
            }
            @Override public void onFailed(int code, String error, boolean suppressed) {
                loading = false;
                syncUi();
                if (!suppressed) Toast.makeText(DailyCheckInWallActivity.this, "加载签到墙失败", Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void bindState(DailyCheckInWallState state) {
        if (state == null) {
            syncUi();
            return;
        }
        checkedIn = state.checkedIn;
        alreadyPosted = state.alreadyPosted;
        myPost = state.myPost;
        tvCount.setText(String.valueOf(state.checkInCount));
        DailyCheckInWallSupport.bindMyPost(cardMyPost, tvMyPost, myPost);
        randomMessages.clear();
        randomMessages.addAll(state.featuredMessages);
        if (currentRandomMessage == null || !containsMessage(currentRandomMessage.id)) {
            currentRandomMessage = null;
        }
        switchRandomCard(true);
        syncUi();
    }

    private void requestDailyCheckIn() {
        if (loading || publishing || checkedIn) return;
        publishing = true;
        syncUi();
        DailyCheckInWallActionHelper.requestCheckIn(token, new DailyCheckInWallActionHelper.SimpleCallback() {
            @Override public void onSuccess(String response) {
                publishing = false; checkedIn = true; syncUi();
                Toast.makeText(DailyCheckInWallActivity.this, DailyCheckInWallSupport.parseCheckInToast(response), Toast.LENGTH_SHORT).show();
                loadWall();
            }
            @Override public void onFailed(int code, String error, boolean suppressed) {
                publishing = false;
                if (code == 409) {
                    checkedIn = true; loadWall(); Toast.makeText(DailyCheckInWallActivity.this, "今天已签到", Toast.LENGTH_SHORT).show();
                } else {
                    syncUi(); if (!suppressed) Toast.makeText(DailyCheckInWallActivity.this, "签到失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void submitPost() {
        if (!checkedIn) { Toast.makeText(this, "请先签到再留言", Toast.LENGTH_SHORT).show(); return; }
        if (alreadyPosted) { Toast.makeText(this, "今天已经留言过了", Toast.LENGTH_SHORT).show(); return; }
        final String text = etMessage.getText() == null ? "" : etMessage.getText().toString().trim();
        if (text.length() == 0 && pickedImage == null) { Toast.makeText(this, "请输入文字或选择图片", Toast.LENGTH_SHORT).show(); return; }
        publishing = true; syncUi();
        if (pickedImage == null) { createWallPost(text, "", ""); return; }
        DailyCheckInWallActionHelper.uploadImage(token, pickedImage, new DailyCheckInWallActionHelper.UploadCallback() {
            @Override public void onUploaded(String imageUrl, String thumbUrl) { createWallPost(text, imageUrl, thumbUrl); }
            @Override public void onFailed(int code, String error, boolean suppressed) {
                publishing = false; syncUi(); if (!suppressed) Toast.makeText(DailyCheckInWallActivity.this, "图片上传失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void createWallPost(String text, String imageUrl, String thumbUrl) {
        DailyCheckInWallActionHelper.createPost(token, text, imageUrl, thumbUrl, new DailyCheckInWallActionHelper.SimpleCallback() {
            @Override public void onSuccess(String response) {
                publishing = false; alreadyPosted = true; clearPickedImage(); etMessage.setText("");
                myPost = DailyCheckInWallNetworkHelper.parseCreatedPost(response); DailyCheckInWallSupport.bindMyPost(cardMyPost, tvMyPost, myPost);
                syncUi(); Toast.makeText(DailyCheckInWallActivity.this, "今日留言已发布", Toast.LENGTH_SHORT).show(); loadWall();
            }
            @Override public void onFailed(int code, String error, boolean suppressed) {
                publishing = false; syncUi();
                if (!suppressed) Toast.makeText(DailyCheckInWallActivity.this, DailyCheckInWallSupport.resolvePostError(code, error), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void switchRandomCard(boolean force) {
        switchRandomCard(force, 0);
    }

    private void switchRandomCard(boolean force, int direction) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastSwitchAt < SWITCH_INTERVAL_MS) {
            Toast.makeText(this, "3秒后再换一个", Toast.LENGTH_SHORT).show();
            return;
        }
        lastSwitchAt = now;

        // 执行切换动画（direction: -1 向左滑出、1 向右滑出、0 无方向）
        animateCardSwitch(new Runnable() {
            @Override
            public void run() {
                // 动画中间切换数据
                if (randomMessages.isEmpty()) currentRandomMessage = null;
                else if (randomMessages.size() == 1) currentRandomMessage = randomMessages.get(0);
                else {
                    DailyCheckInWallMessage next = currentRandomMessage;
                    while (next == currentRandomMessage) next = randomMessages.get(random.nextInt(randomMessages.size()));
                    currentRandomMessage = next;
                }
                bindRandomCard();
            }
        }, direction);
    }

    private void animateCardSwitch(final Runnable switchDataRunnable, int direction) {
        if (cardRandom == null) {
            switchDataRunnable.run();
            return;
        }

        final int sign = direction >= 0 ? 1 : -1;
        final int distance = Math.max(160, cardRandom.getWidth());

        // 旧卡向滑动方向滑出 + 淡出
        AnimationSet fadeOut = new AnimationSet(true);
        fadeOut.setDuration(160);
        fadeOut.addAnimation(new AlphaAnimation(1f, 0f));
        fadeOut.addAnimation(new TranslateAnimation(0, sign * distance, 0, 0));

        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                // 切换数据
                switchDataRunnable.run();
                // 更新UI状态（确保点赞/评论按钮可用）
                syncUi();

                // 新卡从相反方向滑入 + 淡入
                AnimationSet fadeIn = new AnimationSet(true);
                fadeIn.setDuration(200);
                fadeIn.addAnimation(new AlphaAnimation(0f, 1f));
                fadeIn.addAnimation(new TranslateAnimation(-sign * distance, 0, 0, 0));
                fadeIn.setInterpolator(new android.view.animation.DecelerateInterpolator());
                cardRandom.startAnimation(fadeIn);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });

        cardRandom.startAnimation(fadeOut);
    }

    private void bindRandomCard() {
        DailyCheckInWallMessage item = currentRandomMessage;
        tvRandomName.setText(item == null ? "暂无留言" : item.getDisplayName());
        tvRandomBody.setText(DailyCheckInWallSupport.buildMessagePreview(item));
        tvRandomStat.setText(DailyCheckInWallSupport.buildStatLine(item));
        if (ivRandomAvatar != null) {
            if (item != null && item.avatarUrl != null && item.avatarUrl.length() > 0) {
                ImageLoader.loadAvatar(ivRandomAvatar, item.avatarUrl);
            } else {
                ivRandomAvatar.setImageResource(R.drawable.ic_avatar_placeholder);
            }
        }
        if (item != null && item.hasImage()) { ivRandomImage.setVisibility(View.VISIBLE); ImageLoader.load(ivRandomImage, item.getPreviewUrl()); }
        else { ivRandomImage.setVisibility(View.GONE); ivRandomImage.setImageDrawable(null); }
    }

    private void toggleLike() {
        if (currentRandomMessage == null || liking) return;
        liking = true; syncUi();
        final boolean targetLike = !currentRandomMessage.likedByMe;
        DailyCheckInWallActionHelper.toggleLike(token, currentRandomMessage.id, targetLike, new DailyCheckInWallActionHelper.SimpleCallback() {
            @Override public void onSuccess(String response) {
                liking = false; currentRandomMessage.likedByMe = targetLike;
                currentRandomMessage.likeCount = DailyCheckInWallNetworkHelper.parseLikeCount(response, currentRandomMessage.likeCount);
                if (myPost != null && currentRandomMessage.id.equals(myPost.id)) { myPost.likeCount = currentRandomMessage.likeCount; myPost.likedByMe = currentRandomMessage.likedByMe; }
                bindRandomCard(); syncUi();
            }
            @Override public void onFailed(int code, String error, boolean suppressed) {
                liking = false; syncUi(); if (!suppressed) Toast.makeText(DailyCheckInWallActivity.this, "点赞失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openComments(String postId) {
        if (TextUtils.isEmpty(postId)) return;
        Intent intent = new Intent(this, DailyCheckInWallCommentsActivity.class);
        intent.putExtra("post_id", postId);
        startActivityForResult(intent, REQ_COMMENTS);
    }

    private void applyCommentResult(Intent data) {
        String postId = data.getStringExtra("post_id");
        int commentCount = data.getIntExtra("comment_count", -1);
        if (TextUtils.isEmpty(postId) || commentCount < 0) {
            return;
        }
        updateCommentCount(myPost, postId, commentCount);
        updateCommentCount(currentRandomMessage, postId, commentCount);
        syncUi();
        bindRandomCard();
    }

    private void updateCommentCount(DailyCheckInWallMessage item, String postId, int commentCount) {
        if (item == null || item.id == null || !item.id.equals(postId)) {
            return;
        }
        item.commentCount = commentCount;
    }

    private void loadMyLikes() {
        if (myPost == null || TextUtils.isEmpty(myPost.id)) return;
        DailyCheckInWallActionHelper.requestLikes(token, myPost.id, new DailyCheckInWallActionHelper.SimpleCallback() {
            @Override public void onSuccess(String response) { showMyLikes(response); }
            @Override public void onFailed(int code, String error, boolean suppressed) {
                if (!suppressed) Toast.makeText(DailyCheckInWallActivity.this, "加载点赞失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showMyLikes(String response) {
        new android.app.AlertDialog.Builder(this).setTitle("谁点赞了")
                .setItems(DailyCheckInWallSupport.parseLikeDisplayNames(response), null)
                .setPositiveButton("知道了", null).show();
    }

    private void clearPickedImage() {
        pickedImage = null; ivPreview.setImageBitmap(null); ivPreview.setVisibility(View.GONE); btnRemoveImage.setVisibility(View.GONE); syncUi();
    }

    private void syncUi() {
        boolean canPublish = !loading && !publishing && checkedIn && !alreadyPosted;
        boolean hasRandom = currentRandomMessage != null;
        pbLoading.setVisibility(loading ? View.VISIBLE : View.GONE); btnCheckIn.setText(checkedIn ? "今日已签到" : "立即签到");
        btnCheckIn.setEnabled(!loading && !publishing && !checkedIn); btnSwitch.setEnabled(!loading && !randomMessages.isEmpty());
        btnLike.setEnabled(hasRandom && !liking); btnComment.setEnabled(hasRandom); btnMyLikes.setEnabled(myPost != null); btnMyComments.setEnabled(myPost != null);
        ViewCompat.setAlpha(btnCheckIn, btnCheckIn.isEnabled() ? 1f : 0.5f); ViewCompat.setAlpha(btnSwitch, btnSwitch.isEnabled() ? 1f : 0.5f);
        ViewCompat.setAlpha(btnLike, btnLike.isEnabled() ? 1f : 0.5f); ViewCompat.setAlpha(btnComment, btnComment.isEnabled() ? 1f : 0.5f);
        btnPublish.setEnabled(canPublish); ViewCompat.setAlpha(btnPublish, canPublish ? 1f : 0.5f); composerPanel.setEnabled(canPublish);
        ViewCompat.setAlpha(composerPanel, canPublish ? 1f : 0.55f); ViewCompat.setAlpha(cardRandom, hasRandom ? 1f : 0.72f);
        etMessage.setEnabled(canPublish && pickedImage == null);
        tvStatus.setText(checkedIn ? (alreadyPosted ? "你今天已签到并留言" : "你今天已签到，可以发布一条留言") : "先签到，再留下今日一句话或一张图");
        tvHint.setText(randomMessages.isEmpty() ? "今天还没有可浏览的随机留言" : "点一下换一个，切换间隔 3 秒");
        btnLike.setText(hasRandom ? (currentRandomMessage.likedByMe ? "已赞 " + currentRandomMessage.likeCount : "点赞 " + currentRandomMessage.likeCount) : "点赞");
        btnComment.setText(hasRandom ? "评论 " + currentRandomMessage.commentCount : "评论");
    }

    private boolean containsMessage(String id) {
        for (int i = 0; i < randomMessages.size(); i++) if (id != null && id.equals(randomMessages.get(i).id)) return true;
        return false;
    }
    private void setClick(int id, View.OnClickListener l) { View v = findViewByIdCompat(id); if (v != null) v.setOnClickListener(l); }
    private View.OnClickListener finishListener() { return new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }; }
    private View.OnClickListener refreshListener() { return new View.OnClickListener() { @Override public void onClick(View v) { loadWall(); } }; }
    private View.OnClickListener checkInListener() { return new View.OnClickListener() { @Override public void onClick(View v) { requestDailyCheckIn(); } }; }
    private View.OnClickListener publishListener() { return new View.OnClickListener() { @Override public void onClick(View v) { submitPost(); } }; }
    private View.OnClickListener switchListener() { return new View.OnClickListener() { @Override public void onClick(View v) { switchRandomCard(false); } }; }
    private View.OnClickListener likeListener() { return new View.OnClickListener() { @Override public void onClick(View v) { toggleLike(); } }; }
    private View.OnClickListener commentListener() { return new View.OnClickListener() { @Override public void onClick(View v) { if (currentRandomMessage != null) openComments(currentRandomMessage.id); } }; }
    private View.OnClickListener myLikesListener() { return new View.OnClickListener() { @Override public void onClick(View v) { loadMyLikes(); } }; }
    private View.OnClickListener myCommentsListener() { return new View.OnClickListener() { @Override public void onClick(View v) { if (myPost != null) openComments(myPost.id); } }; }
    private View.OnClickListener removeImageListener() { return new View.OnClickListener() { @Override public void onClick(View v) { clearPickedImage(); } }; }
    private View.OnClickListener openImageListener() { return new View.OnClickListener() { @Override public void onClick(View v) { if (currentRandomMessage != null && currentRandomMessage.hasImage()) ImagePreviewActivity.start(DailyCheckInWallActivity.this, currentRandomMessage.imageUrl); } }; }
    private View.OnClickListener pickImageListener() { return new View.OnClickListener() { @Override public void onClick(View v) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT); intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择签到图片"), REQ_PICK_IMAGE);
    } }; }
}
