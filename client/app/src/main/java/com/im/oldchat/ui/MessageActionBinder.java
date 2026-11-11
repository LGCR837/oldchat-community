package com.im.oldchat.ui;

import android.content.Context;
import android.support.v7.view.ContextThemeWrapper;
import android.support.v7.widget.PopupMenu;
import android.view.View;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.DownloadUtil;
import com.im.oldchat.util.MessagePayload;
import com.im.oldchat.util.SettingsPrefs;

public class MessageActionBinder {
    private static final int MENU_ID_COPY = 1;
    private static final int MENU_ID_QUOTE = 2;
    private static final int MENU_ID_REPORT = 3;
    private static final int MENU_ID_SAVE = 4;
    private static final int MENU_ID_SAVE_EMOJI = 5;
    private static final int MENU_ID_FAVORITE = 6;
    private static final int MENU_ID_RECALL = 7;
    private static final int MENU_ID_VOICE_TRANSCRIBE = 8;
    private static final int MENU_ID_FORWARD = 9;
    private static final int MENU_ID_MULTI_FORWARD = 10;

    public interface RecallChecker {
        boolean canRecall(Message msg);
    }

    public static void bind(final Context context,
                            View bubble,
                            View text,
                            View image,
                            View voiceRow,
                            View voiceTranscript,
                            final Message msg,
                            final MessagePayload payload,
                            final boolean isMine,
                            final MessageAdapter.MessageActionListener actionListener,
                            final RecallChecker recallChecker) {
        if (actionListener == null) {
            return;
        }
        final View.OnLongClickListener listener = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                ContextThemeWrapper themeWrapper = new ContextThemeWrapper(
                        context,
                        SettingsPrefs.isDarkModeEnabled(context)
                                ? R.style.PopupMenuOverlayDark
                                : R.style.PopupMenuOverlayLight);
                PopupMenu menu = new PopupMenu(themeWrapper, v);

                final String msgType = msg.msg_type == null ? "text" : msg.msg_type.toLowerCase();
                final boolean isVoice = "voice".equals(msgType);
                final boolean isEmoji = payload != null && "emoji".equals(payload.mediaKind);
                final boolean isImage = "image".equals(msgType);
                final boolean localOnly = ChatMessageActionGuard.isLocalOnly(msg);
                final boolean canSaveMedia = (isImage || isEmoji)
                        && msg.media_url != null && msg.media_url.length() > 0;
                final boolean canSaveEmoji = !isMine && isEmoji
                        && msg.media_url != null && msg.media_url.length() > 0;
                final boolean canFavoriteMedia = ("image".equals(msgType) || isVoice || "video".equals(msgType))
                        && msg.media_url != null && msg.media_url.length() > 0;

                int order = 0;
                if ("text".equals(msgType)) {
                    menu.getMenu().add(0, MENU_ID_COPY, order++, R.string.message_action_copy);
                }
                if (!localOnly) {
                    menu.getMenu().add(0, MENU_ID_QUOTE, order++, R.string.message_action_reply);
                    menu.getMenu().add(0, MENU_ID_FORWARD, order++, R.string.message_action_forward);
                    menu.getMenu().add(0, MENU_ID_MULTI_FORWARD, order++, R.string.message_action_multi_forward);
                }
                if (!isMine && !localOnly) {
                    menu.getMenu().add(0, MENU_ID_REPORT, order++, R.string.report_user_menu);
                }
                if (!localOnly && canSaveMedia) {
                    menu.getMenu().add(0, MENU_ID_SAVE, order++, R.string.message_action_save);
                }
                if (!localOnly && canSaveEmoji) {
                    menu.getMenu().add(0, MENU_ID_SAVE_EMOJI, order++, R.string.message_action_add_emoji);
                }
                if (!localOnly && canFavoriteMedia) {
                    menu.getMenu().add(0, MENU_ID_FAVORITE, order++, R.string.message_action_favorite);
                }
                if (!localOnly && isVoice) {
                    menu.getMenu().add(0, MENU_ID_VOICE_TRANSCRIBE, order++, R.string.chat_voice_option_transcribe);
                }
                if (!localOnly && isMine && recallChecker != null && recallChecker.canRecall(msg)) {
                    menu.getMenu().add(0, MENU_ID_RECALL, order, R.string.message_action_revoke);
                }
                if (order <= 0) {
                    return true;
                }

                menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(android.view.MenuItem item) {
                        switch (item.getItemId()) {
                            case MENU_ID_COPY:
                                actionListener.onCopy(payload != null ? payload.text : msg.body);
                                return true;
                            case MENU_ID_QUOTE:
                                actionListener.onQuote(msg);
                                return true;
                            case MENU_ID_FORWARD:
                                actionListener.onForward(msg);
                                return true;
                            case MENU_ID_MULTI_FORWARD:
                                actionListener.onEnterMultiSelect(msg);
                                return true;
                            case MENU_ID_REPORT:
                                MessageReportHelper.reportDirectMessage(context, msg);
                                return true;
                            case MENU_ID_SAVE:
                                saveMediaToDownloads(context, msg, isEmoji);
                                return true;
                            case MENU_ID_SAVE_EMOJI:
                                saveEmojiFromMessage(context, msg);
                                return true;
                            case MENU_ID_FAVORITE:
                                FavoriteHelper.addChatMediaFavorite(context,
                                        msgType,
                                        msg.id,
                                        msg.from_uid,
                                        msg.media_url,
                                        "direct");
                                return true;
                            case MENU_ID_VOICE_TRANSCRIBE:
                                actionListener.onVoiceTranscribe(msg);
                                return true;
                            case MENU_ID_RECALL:
                                actionListener.onRecall(msg);
                                return true;
                            default:
                                return false;
                        }
                    }
                });

                try {
                    menu.show();
                } catch (android.view.WindowManager.BadTokenException e) {
                    // Context/Activity was finishing when trying to show popup
                }
                return true;
            }
        };

        bindLongPress(bubble, listener);
        bindLongPress(text, listener);
        bindLongPress(image, listener);
        bindLongPress(voiceRow, listener);
        bindLongPress(voiceTranscript, listener);
    }

    public static void clear(View bubble,
                             View text,
                             View image,
                             View voiceRow,
                             View voiceTranscript) {
        clearLongPress(bubble);
        clearLongPress(text);
        clearLongPress(image);
        clearLongPress(voiceRow);
        clearLongPress(voiceTranscript);
    }

    private static void bindLongPress(View view, View.OnLongClickListener listener) {
        if (view != null) {
            view.setOnLongClickListener(listener);
        }
    }

    private static void clearLongPress(View view) {
        if (view != null) {
            view.setOnLongClickListener(null);
        }
    }

    private static void saveEmojiFromMessage(final Context context, Message msg) {
        if (msg == null || msg.media_url == null || msg.media_url.length() == 0) {
            return;
        }
        boolean isGif = isGifUrl(msg.media_url);
        EmojiStore.saveFromUrlAsync(context, msg.media_url, isGif, new EmojiStore.SaveCallback() {
            @Override
            public void onResult(boolean success, String message) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void saveMediaToDownloads(final Context context, Message msg, boolean isEmoji) {
        if (msg == null || msg.media_url == null || msg.media_url.length() == 0) {
            return;
        }
        String fallbackExt = isEmoji && isGifUrl(msg.media_url) ? ".gif" : ".jpg";
        DownloadUtil.saveUrlToDownloadsAsync(context, msg.media_url, "oldchat_img_", fallbackExt,
                new DownloadUtil.Callback() {
                    @Override
                    public void onResult(boolean success, String message, java.io.File file) {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private static boolean isGifUrl(String url) {
        return url != null && url.toLowerCase().contains(".gif");
    }
}
