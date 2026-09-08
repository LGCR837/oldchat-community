package com.im.oldchat.ui;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.v4.view.ViewCompat;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
public class ChatMediaHelper extends ChatMediaHelperVoiceSupport {
    private final ChatFileUploadHelper fileUploadHelper;
    private ChatInlineEmojiPanelController inlineEmojiPanelController;
    private final ChatToolPopup toolPopup;
    public interface MediaCallback {
        void onMediaReady(String type, String url, String thumbUrl, int durationMs);
    }
    public interface SendStateListener {
        void onSendState(boolean sending);
    }
    public interface ImageUploadListener {
        void onQueued(String localId, String previewUri);
        void onProgress(String localId, int percent);
        void onUploaded(String localId, String url, String thumbUrl);
        void onUploaded(String localId, String url, String thumbUrl, String originalUrl);
        void onFailed(String localId, String error);
    }
    public ChatMediaHelper(Activity activity, String token, android.widget.EditText etInput, Button btnSend,
                           View btnToggleVoice, View btnPlus, View btnHoldToTalk, View actionPanel,
                           View btnActionImage, View btnActionVideo, View videoProgressContainer,
                           ProgressBar videoProgressBar, TextView videoProgressText, MediaCallback callback,
                           SendStateListener sendStateListener,
                           ChatFileUploadHelper.FileUploadListener fileUploadListener) {
        super(activity, token, etInput, btnSend, btnToggleVoice, btnPlus, btnHoldToTalk, actionPanel,
                btnActionImage, btnActionVideo, videoProgressContainer, videoProgressBar,
                videoProgressText, callback, sendStateListener);
        fileUploadHelper = new ChatFileUploadHelper(activity, sendStateListener, fileUploadListener);
        toolPopup = null;
        if (actionPanel != null) {
            initActionPanelComponents();
        }
    }

    public void ensureActionPanel() {
        if (actionPanel != null) {
            return;
        }
        if (activity != null) {
            View stub = activity.findViewById(R.id.stub_action_panel);
            if (stub instanceof android.view.ViewStub) {
                actionPanel = ((android.view.ViewStub) stub).inflate();
                initActionPanelComponents();
            } else {
                actionPanel = activity.findViewById(R.id.action_panel);
                if (actionPanel != null) {
                    initActionPanelComponents();
                }
            }
        }
    }

    private void initActionPanelComponents() {
        if (actionPanel == null) return;
        btnActionImage = actionPanel.findViewById(R.id.btnActionImage);
        btnActionVideo = actionPanel.findViewById(R.id.btnActionVideo);
        if (btnActionImage != null) {
            btnActionImage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideActionPanel();
                    pickImage();
                }
            });
        }
        if (btnActionVideo != null) {
            btnActionVideo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideActionPanel();
                    pickVideo();
                }
            });
        }
        View btnActionRedPacket = actionPanel.findViewById(R.id.btnActionRedPacket);
        if (btnActionRedPacket != null) {
            btnActionRedPacket.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideActionPanel();
                    if (activity instanceof ChatActivitySupport) {
                        ((ChatActivitySupport) activity).openRedPacketSend();
                    } else if (activity instanceof GroupChatActivitySupport0) {
                        ((GroupChatActivitySupport0) activity).openRedPacketSend();
                    }
                }
            });
        }
        inlineEmojiPanelController = new ChatInlineEmojiPanelController(activity, actionPanel,
                new ChatInlineEmojiPanelController.EmojiSendCallback() {
                    @Override
                    public void onEmojiSend(String path, boolean isGif) {
                        dispatchEmojiSend(path, isGif);
                    }
                },
                new ChatInlineEmojiPanelController.FileActionsListener() {
                    @Override
                    public void onLosslessImage() {
                        hideActionPanel();
                        if (fileUploadHelper != null) {
                            fileUploadHelper.pickLosslessImage();
                        }
                    }

                    @Override
                    public void onLosslessVideo() {
                        hideActionPanel();
                        if (fileUploadHelper != null) {
                            fileUploadHelper.pickLosslessVideo();
                        }
                    }

                    @Override
                    public void onSendDoc() {
                        hideActionPanel();
                        if (fileUploadHelper != null) {
                            fileUploadHelper.pickFile();
                        }
                    }
                });
        ChatActionTileHelper.init(activity, actionPanel);
        ChatBurnActionUiHelper.bind(activity, actionPanel);
    }
    public void bind() {
        com.im.oldchat.util.AutofillCompatApi26.disableAutofill(etInput);
        if (btnToggleVoice != null) {
            btnToggleVoice.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleVoiceMode();
                }
            });
        }
        if (btnPlus != null) {
            btnPlus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleActionPanel();
                }
            });
        }
        if (etInput != null) {
            etInput.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (actionsVisible) {
                        hideActionPanel();
                    }
                }
            });
            etInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus && actionsVisible) {
                        hideActionPanel();
                    }
                }
            });
        }
        if (btnActionImage != null) {
            btnActionImage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideActionPanel();
                    pickImage();
                }
            });
        }
        if (btnActionVideo != null) {
            btnActionVideo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideActionPanel();
                    pickVideo();
                }
            });
        }
        View btnActionEmoji = activity.findViewById(R.id.btnActionEmoji);
        if (btnActionEmoji != null) {
            final View.OnClickListener emojiClick = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showInlineEmojiPanel();
                }
            };
            btnActionEmoji.setOnClickListener(emojiClick);
            btnActionEmoji.post(new Runnable() {
                @Override
                public void run() {
                    View button = activity == null ? null : activity.findViewById(R.id.btnActionEmoji);
                    if (button != null) {
                        button.setOnClickListener(emojiClick);
                    }
                }
            });
        }
        ChatBurnActionUiHelper.bind(activity);
        if (btnHoldToTalk != null) {
            btnHoldToTalk.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return onHoldToTalkTouch(v, event);
                }
            });
        }
    }
    public void onPause() {
        onPauseVoice();
        if (toolPopup != null) toolPopup.dismiss();
    }
    public void setImageUploadListener(ImageUploadListener listener) { imageUploadListener = listener; }
    public void hideActionPanel() {
        if (actionsVisible) {
            animatePlusIcon(false);
        }
        actionsVisible = false;
        if (toolPopup != null) toolPopup.dismiss();
        if (inlineEmojiPanelController != null) {
            inlineEmojiPanelController.showToolsPanel();
        }
        com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(actionPanel, View.GONE);
    }
    public void syncBurnModeUiState() { ChatBurnActionUiHelper.sync(activity); }
    public void showToolsPanel() {
        ensureActionPanel();
        if (actionPanel == null) return;
        hideKeyboard();
        if (etInput != null) {
            etInput.clearFocus();
        }
        if (!actionsVisible) {
            animatePlusIcon(true);
        }
        actionsVisible = true;
        if (inlineEmojiPanelController != null) {
            inlineEmojiPanelController.showToolsPanel();
        }
        com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(actionPanel, View.VISIBLE);
    }
    public void toggleActionPanel() {
        ensureActionPanel();
        if (actionPanel == null) return;
        if (actionsVisible && actionPanel.getVisibility() == View.VISIBLE) {
            hideActionPanel();
        } else {
            showToolsPanel();
        }
    }
    public void showInlineEmojiPanel() {
        ensureActionPanel();
        if (actionPanel == null) return;
        hideKeyboard();
        if (etInput != null) {
            etInput.clearFocus();
        }
        if (!actionsVisible) {
            animatePlusIcon(true);
        }
        actionsVisible = true;
        com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(actionPanel, View.VISIBLE);
        if (inlineEmojiPanelController != null) {
            inlineEmojiPanelController.showEmojiPanel();
        }
    }
    public boolean handlePermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode == REQ_RECORD_AUDIO) {
            return handleRecordPermissionResult(grantResults);
        }
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingPick == PICK_VIDEO) {
                    pickVideo();
                } else {
                    pickImage();
                }
            } else {
                Toast.makeText(activity, "No storage permission", Toast.LENGTH_SHORT).show();
            }
            pendingPick = PICK_NONE;
            return true;
        }
        return false;
    }
    public boolean handleActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (inlineEmojiPanelController != null && inlineEmojiPanelController.handleActivityResult(requestCode, resultCode, data)) {
            return true;
        }
        if (fileUploadHelper != null && fileUploadHelper.handleActivityResult(requestCode, resultCode, data)) {
            return true;
        }
        if (requestCode == REQ_PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                persistSelectedMediaPermission(data, uri);
                uploadImage(uri);
            }
            return true;
        }
        if (requestCode == REQ_PICK_VIDEO && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadVideo(uri);
            }
            return true;
        }
        return false;
    }
    private void toggleVoiceMode() {
        isVoiceMode = !isVoiceMode;
        if (isVoiceMode) {
            hideKeyboard();
            if (etInput != null) {
                etInput.clearFocus();
                etInput.post(new Runnable() {
                    @Override
                    public void run() {
                        hideKeyboard();
                    }
                });
            }
            if (btnSend != null) {
                btnSend.clearFocus();
                btnSend.setEnabled(false);
            }
            View inputTextContainer = etInput == null ? null : (View) etInput.getParent();
            com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(inputTextContainer, View.GONE);
            com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(etInput, View.GONE);
            com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(btnSend, View.GONE);
            com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(btnHoldToTalk, View.VISIBLE);
            animateModeView(btnHoldToTalk);
            animateToggleIcon();
            if (btnHoldToTalk != null) {
                btnHoldToTalk.requestFocus();
            }
            if (btnToggleVoice instanceof ImageView) {
                ((ImageView) btnToggleVoice).setImageResource(R.drawable.chatting_setmode_keyboard_btn_normal);
            }
            if (actionsVisible && actionPanel != null) {
                actionsVisible = false;
                if (inlineEmojiPanelController != null) {
                    inlineEmojiPanelController.showToolsPanel();
                }
                com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(actionPanel, View.GONE);
            }
            return;
        }
        if (btnHoldToTalk != null) {
            btnHoldToTalk.clearFocus();
        }
        View inputTextContainer = etInput == null ? null : (View) etInput.getParent();
        com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(inputTextContainer, View.VISIBLE);
        com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(etInput, View.VISIBLE);
        com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(btnSend, View.VISIBLE);
        com.im.oldchat.util.AutofillCompatApi26.safeSetVisibility(btnHoldToTalk, View.GONE);
        animateModeView(etInput);
        animateModeView(btnSend);
        animateToggleIcon();
        ChatSendButtonUi.update(btnSend, etInput, false, false);
        if (btnToggleVoice instanceof ImageView) {
            ((ImageView) btnToggleVoice).setImageResource(R.drawable.chatting_setmode_voice_btn_normal);
        }
    }
    private void animateModeView(View view) {
        if (view == null || activity == null
                || com.im.oldchat.util.UiPerformance.shouldReduceMotion(activity)) {
            return;
        }
        AnimationSet set = new AnimationSet(true);
        set.setDuration(160L);
        set.addAnimation(new AlphaAnimation(0.25f, 1f));
        set.addAnimation(new ScaleAnimation(0.96f, 1f, 0.96f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f));
        view.clearAnimation();
        view.startAnimation(set);
    }

    private void animateToggleIcon() {
        if (btnToggleVoice == null || activity == null
                || com.im.oldchat.util.UiPerformance.shouldReduceMotion(activity)) {
            return;
        }
        RotateAnimation rotate = new RotateAnimation(-35f, 0f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        rotate.setDuration(160L);
        btnToggleVoice.clearAnimation();
        btnToggleVoice.startAnimation(rotate);
    }

    private void animatePlusIcon(boolean expanded) {
        if (btnPlus == null || activity == null
                || com.im.oldchat.util.UiPerformance.shouldReduceMotion(activity)) {
            return;
        }
        float fromDegree = expanded ? 0f : 45f;
        float toDegree = expanded ? 45f : 0f;
        RotateAnimation rotate = new RotateAnimation(fromDegree, toDegree,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        rotate.setDuration(expanded ? 240L : 200L);
        rotate.setInterpolator(expanded ? new DecelerateInterpolator(1.8f) : new AccelerateDecelerateInterpolator());
        rotate.setFillAfter(true);
        btnPlus.clearAnimation();
        btnPlus.startAnimation(rotate);
    }
    private void dispatchEmojiSend(String path, boolean isGif) {
        if (path == null || path.length() == 0 || activity == null) {
            return;
        }
        if (activity instanceof ChatActivitySupport) {
            ChatActivitySupport support = (ChatActivitySupport) activity;
            if (support.messageSender != null) {
                support.messageSender.sendEmojiFromPath(path, isGif, support.quoteDraft);
                hideActionPanel();
            }
            return;
        }
        if (activity instanceof GroupChatActivitySupport0) {
            GroupChatActivitySupport0 support = (GroupChatActivitySupport0) activity;
            if (support.messageSender != null) {
                support.messageSender.sendEmojiFromPath(path, isGif, support.quoteDraft);
                hideActionPanel();
            }
        }
    }
    private void hideKeyboard() {
        if (activity == null) {
            return;
        }
        View anchor = activity.getCurrentFocus();
        if (anchor == null) {
            anchor = etInput != null ? etInput : (actionPanel != null ? actionPanel : btnPlus);
        }
        if (anchor == null && activity.getWindow() != null) {
            anchor = activity.getWindow().getDecorView();
        }
        if (anchor == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        }
    }
    private void pickImage() {
        android.content.Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent = new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
        } else if (android.os.Build.VERSION.SDK_INT >= 19) {
            intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        } else {
            intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        }
        intent.setType("image/*");
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivityForResult(intent, REQ_PICK_IMAGE);
    }

    private void persistSelectedMediaPermission(android.content.Intent data, Uri uri) {
        if (activity == null || data == null || uri == null || android.os.Build.VERSION.SDK_INT < 19) {
            return;
        }
        try {
            int flags = data.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) {
                activity.getContentResolver().takePersistableUriPermission(uri, flags);
            }
        } catch (Exception ignored) {
            // 部分图库只提供临时权限；上传协调器会立即复制到应用缓存。
        }
    }
    private void pickVideo() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        activity.startActivityForResult(android.content.Intent.createChooser(intent, "Select video"), REQ_PICK_VIDEO);
    }
}
