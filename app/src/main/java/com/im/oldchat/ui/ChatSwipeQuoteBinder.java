package com.im.oldchat.ui;

import android.content.Context;
import android.view.View;

import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.MessagePayload;

final class ChatSwipeQuoteBinder {
    private ChatSwipeQuoteBinder() {
    }

    static void bindDirect(Context context,
                           boolean selectionMode,
                           final MessageAdapterSupport1.ViewHolder holder,
                           final Message message,
                           MessagePayload payload,
                           final MessageAdapter.MessageActionListener actionListener) {
        if (holder == null) {
            return;
        }
        if (selectionMode || message == null || actionListener == null) {
            clearTargets(holder.bubble, holder.text, holder.image, holder.voiceRow,
                    holder.voiceTranscript, holder.resourceCard, holder.resourceAction,
                    holder.redPacketContainer, holder.quoteContainer);
            return;
        }
        bindTargets(context, holder.bubble,
                new Runnable() {
                    @Override
                    public void run() {
                        actionListener.onQuote(message);
                    }
                },
                holder.bubble, holder.text, holder.image, holder.voiceRow,
                holder.voiceTranscript, holder.resourceCard, holder.resourceAction,
                holder.redPacketContainer, holder.quoteContainer);
    }

    static void bindGroup(Context context,
                          boolean selectionMode,
                          final GroupMessageAdapterSupport3.ViewHolder holder,
                          final GroupMessage message,
                          MessagePayload payload,
                          final String displayName,
                          final GroupMessageAdapter.GroupMessageActionListener actionListener) {
        if (holder == null) {
            return;
        }
        if (selectionMode || message == null || actionListener == null) {
            clearTargets(holder.bubble, holder.text, holder.image, holder.voiceRow,
                    holder.voiceTranscript, holder.resourceCard, holder.resourceAction,
                    holder.redPacketContainer, holder.quoteContainer);
            return;
        }
        bindTargets(context, holder.bubble,
                new Runnable() {
                    @Override
                    public void run() {
                        actionListener.onQuote(message, displayName);
                    }
                },
                holder.bubble, holder.text, holder.image, holder.voiceRow,
                holder.voiceTranscript, holder.resourceCard, holder.resourceAction,
                holder.redPacketContainer, holder.quoteContainer);
    }

    private static void bindTargets(Context context,
                                    View translateView,
                                    final Runnable quoteAction,
                                    View... targets) {
        float density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
        ChatSwipeQuoteTouchListener listener = new ChatSwipeQuoteTouchListener(density, translateView,
                new ChatSwipeQuoteTouchListener.Callback() {
                    @Override
                    public void onSwipeProgress(float progress) {
                    }

                    @Override
                    public void onSwipeRelease() {
                    }

                    @Override
                    public void onSwipeQuote() {
                        if (quoteAction != null) {
                            quoteAction.run();
                        }
                    }
                });
        for (int i = 0; i < targets.length; i++) {
            View target = targets[i];
            if (target != null) {
                target.setOnTouchListener(listener);
            }
        }
    }

    private static void clearTargets(View... targets) {
        for (int i = 0; i < targets.length; i++) {
            View target = targets[i];
            if (target != null) {
                target.setOnTouchListener(null);
            }
        }
    }
}
