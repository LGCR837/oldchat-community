package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.view.ViewCompat;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageCompressUtil;
import com.im.oldchat.util.ImageLoader;

import org.json.JSONObject;

public class RedPacketSendActivity extends BaseActivity {
    public static final String EXTRA_TO_UID = "to_uid";
    public static final String EXTRA_TO_NCUID = "to_ncuid";
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_TARGET_NAME = "target_name";
    public static final String EXTRA_MESSAGE_JSON = "message_json";

    private static final int RED_PACKET_TITLE_MAX_LENGTH = 20;
    private static final int REQ_PICK_COVER = 4601;
    private static final int MAX_COVER_BYTES = 1024 * 1024;

    private EditText etTitle;
    private EditText etAmount;
    private EditText etCount;
    private TextView tvTarget;
    private TextView btnSend;
    private ProgressBar pbSend;

    private ImageView ivCoverPreview;
    private TextView tvCoverHint;
    private TextView btnPickCover;
    private TextView btnClearCover;

    private String token;
    private String toUid;
    private String toNcuid;
    private String groupId;
    private String targetName;
    private boolean isGroup;
    private boolean sending;
    private boolean coverUploading;
    private String coverUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_red_packet_send);

        View btnBack = findViewByIdCompat(R.id.btnRedPacketSendBack);
        etTitle = findViewByIdCompat(R.id.etRedPacketTitle);
        etAmount = findViewByIdCompat(R.id.etRedPacketAmount);
        etCount = findViewByIdCompat(R.id.etRedPacketCount);
        tvTarget = findViewByIdCompat(R.id.tvRedPacketTarget);
        btnSend = findViewByIdCompat(R.id.btnSendRedPacket);
        pbSend = findViewByIdCompat(R.id.pbRedPacketSend);

        ivCoverPreview = findViewByIdCompat(R.id.ivRedPacketCoverPreview);
        tvCoverHint = findViewByIdCompat(R.id.tvRedPacketCoverHint);
        btnPickCover = findViewByIdCompat(R.id.btnRedPacketPickCover);
        btnClearCover = findViewByIdCompat(R.id.btnRedPacketClearCover);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        Intent intent = getIntent();
        toUid = intent.getStringExtra(EXTRA_TO_UID);
        toNcuid = intent.getStringExtra(EXTRA_TO_NCUID);
        groupId = intent.getStringExtra(EXTRA_GROUP_ID);
        targetName = intent.getStringExtra(EXTRA_TARGET_NAME);
        isGroup = groupId != null && !groupId.isEmpty();

        if (tvTarget != null && targetName != null && !targetName.isEmpty()) {
            tvTarget.setText(getString(R.string.red_packet_target_format, targetName));
            tvTarget.setVisibility(View.VISIBLE);
        } else if (tvTarget != null) {
            tvTarget.setVisibility(View.GONE);
        }

        if (!isGroup && etCount != null) {
            etCount.setVisibility(View.GONE);
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        if (btnSend != null) {
            btnSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    submitRedPacket();
                }
            });
        }

        if (btnPickCover != null) {
            btnPickCover.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickCover();
                }
            });
        }

        if (btnClearCover != null) {
            btnClearCover.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    coverUrl = "";
                    refreshCoverUi();
                }
            });
        }

        refreshCoverUi();
        refreshActionState();
    }

    private void pickCover() {
        if (coverUploading || sending) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.red_packet_image_pick)), REQ_PICK_COVER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_COVER) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            uploadCover(data.getData());
        }
    }

    private void uploadCover(Uri uri) {
        if (uri == null) {
            return;
        }
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_SHORT).show();
            return;
        }

        final byte[] imageData;
        try {
            imageData = ImageCompressUtil.compressToBytes(getContentResolver(), uri, 1024, MAX_COVER_BYTES);
        } catch (Exception e) {
            Toast.makeText(this, "封面处理失败，请换一张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (imageData == null || imageData.length == 0) {
            Toast.makeText(this, "封面处理失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (imageData.length > MAX_COVER_BYTES) {
            Toast.makeText(this, "封面需小于1MB，请换图后重试", Toast.LENGTH_SHORT).show();
            return;
        }

        setCoverUploading(true);
        HttpUtil.postMultipart("/media", imageData, "red_packet_cover.jpg", "image/jpeg", token,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        setCoverUploading(false);
                        try {
                            JSONObject obj = new JSONObject(response);
                            String url = obj.optString("url", "");
                            if (url == null || url.trim().isEmpty()) {
                                Toast.makeText(RedPacketSendActivity.this, "封面上传失败", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            coverUrl = url.trim();
                            refreshCoverUi();
                            Toast.makeText(RedPacketSendActivity.this, "封面已上传", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(RedPacketSendActivity.this, "封面上传失败", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(int code, String error) {
                        setCoverUploading(false);
                        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                            return;
                        }
                        Toast.makeText(RedPacketSendActivity.this, "封面上传失败", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void refreshCoverUi() {
        if (ivCoverPreview != null) {
            if (coverUrl != null && !coverUrl.trim().isEmpty()) {
                ImageLoader.load(ivCoverPreview, coverUrl.trim());
            } else {
                ivCoverPreview.setImageResource(R.drawable.ic_red_packet);
            }
        }

        if (btnClearCover != null) {
            boolean hasCover = coverUrl != null && !coverUrl.trim().isEmpty();
            btnClearCover.setVisibility(hasCover ? View.VISIBLE : View.GONE);
        }

        if (tvCoverHint != null) {
            if (coverUploading) {
                tvCoverHint.setText("封面上传中...");
            } else if (coverUrl != null && !coverUrl.trim().isEmpty()) {
                tvCoverHint.setText("已选择红包封面");
            } else {
                tvCoverHint.setText(R.string.red_packet_image_hint);
            }
        }
    }

    private void submitRedPacket() {
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_SHORT).show();
            return;
        }
        if (coverUploading) {
            Toast.makeText(this, "封面上传中，请稍后", Toast.LENGTH_SHORT).show();
            return;
        }

        int amount = parseInt(etAmount == null ? null : etAmount.getText().toString());
        if (amount <= 0) {
            Toast.makeText(this, R.string.red_packet_amount_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        int count = 1;
        if (isGroup) {
            if (groupId == null || groupId.isEmpty()) {
                Toast.makeText(this, R.string.red_packet_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            count = parseInt(etCount == null ? null : etCount.getText().toString());
            if (count <= 0 || count < 2) {
                Toast.makeText(this, R.string.red_packet_count_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (isGroup && amount < count) {
            Toast.makeText(this, R.string.red_packet_amount_too_small, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject json = new JSONObject();
            String title = etTitle == null ? "" : etTitle.getText().toString().trim();
            if (title.length() > RED_PACKET_TITLE_MAX_LENGTH) {
                Toast.makeText(this, R.string.red_packet_title_too_long, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!title.isEmpty()) {
                json.put("title", title);
            }
            json.put("total_amount", amount);
            json.put("total_count", count);
            if (coverUrl != null && !coverUrl.trim().isEmpty()) {
                json.put("cover_url", coverUrl.trim());
            }
            if (isGroup) {
                json.put("group_id", groupId);
            } else {
                if ((toUid == null || toUid.isEmpty()) && (toNcuid == null || toNcuid.isEmpty())) {
                    Toast.makeText(RedPacketSendActivity.this, R.string.red_packet_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (toUid != null && toUid.length() > 0) {
                    json.put("to_uid", toUid);
                }
                if (toNcuid != null && toNcuid.length() > 0) {
                    json.put("to_ncuid", toNcuid);
                }
            }

            setSending(true);
            HttpUtil.post("/redpackets/send", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    setSending(false);
                    Intent data = new Intent();
                    data.putExtra(EXTRA_MESSAGE_JSON, response);
                    setResult(RESULT_OK, data);
                    finish();
                }

                @Override
                public void onError(int code, String error) {
                    setSending(false);
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (error != null && error.contains("red_packet_insufficient")) {
                        Toast.makeText(RedPacketSendActivity.this, R.string.red_packet_insufficient, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (error != null && error.contains("red_packet_amount_invalid")) {
                        Toast.makeText(RedPacketSendActivity.this, R.string.red_packet_amount_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (error != null && error.contains("red_packet_count_invalid")) {
                        Toast.makeText(RedPacketSendActivity.this, R.string.red_packet_count_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (error != null && error.contains("red_packet_amount_too_small")) {
                        Toast.makeText(RedPacketSendActivity.this, R.string.red_packet_amount_too_small, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (error != null && error.contains("red_packet_title_too_long")) {
                        Toast.makeText(RedPacketSendActivity.this, R.string.red_packet_title_too_long, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (error != null && error.contains("invalid_cover_url")) {
                        Toast.makeText(RedPacketSendActivity.this, "封面地址无效，请重新上传", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(RedPacketSendActivity.this,
                            getString(R.string.message_send_failed) + " (" + code + ")",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            setSending(false);
            Toast.makeText(this, R.string.message_send_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void setSending(boolean sending) {
        this.sending = sending;
        if (pbSend != null) {
            pbSend.setVisibility(sending ? View.VISIBLE : View.GONE);
        }
        refreshActionState();
    }

    private void setCoverUploading(boolean uploading) {
        this.coverUploading = uploading;
        refreshCoverUi();
        refreshActionState();
    }

    private void refreshActionState() {
        boolean blocked = sending || coverUploading;
        if (btnSend != null) {
            btnSend.setEnabled(!blocked);
            ViewCompat.setAlpha(btnSend, blocked ? 0.65f : 1f);
        }
        if (btnPickCover != null) {
            btnPickCover.setEnabled(!blocked);
            ViewCompat.setAlpha(btnPickCover, blocked ? 0.65f : 1f);
        }
        if (btnClearCover != null) {
            btnClearCover.setEnabled(!blocked);
            ViewCompat.setAlpha(btnClearCover, blocked ? 0.65f : 1f);
        }
        if (etTitle != null) {
            etTitle.setEnabled(!blocked);
        }
        if (etAmount != null) {
            etAmount.setEnabled(!blocked);
        }
        if (etCount != null) {
            etCount.setEnabled(!blocked);
        }
    }

    private int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (Exception e) {
            return 0;
        }
    }
}
