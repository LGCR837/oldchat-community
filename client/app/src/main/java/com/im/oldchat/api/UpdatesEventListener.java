package com.im.oldchat.api;

import android.content.Context;

import org.json.JSONObject;

/**
 * UpdatesEventListener：UpdatesManager 事件分发落地。
 * 把账号级事件翻译为现有 UI/缓存操作：
 *  - DIRECT_MESSAGE_NEW      → 直接交给 ChatWsListener 逻辑（复用旧路径）
 *  - DIRECT_MESSAGE_RECALL   → 私聊撤回
 *  - DIRECT_READ             → 已读游标
 *  - GROUP_POINTER_UPDATE    → 群聊增量拉取 / 未读标记
 *  - GROUP_MEMBERSHIP_CHANGE → 群成员变更
 *  - reset                   → 全量兜底（复用旧 unread 同步）
 *
 * 兼容 Android 1.6+。
 */
public final class UpdatesEventListener implements UpdatesManager.EventListener {
    private static volatile UpdatesEventListener instance;

    private volatile Context appContext;

    public static UpdatesEventListener getInstance() {
        if (instance == null) {
            synchronized (UpdatesEventListener.class) {
                if (instance == null) {
                    instance = new UpdatesEventListener();
                }
            }
        }
        return instance;
    }

    private UpdatesEventListener() {
    }

    /** 初始化（登录成功后调用），并把 UpdatesManager 与本监听绑定。 */
    public void init(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
        if (appContext != null) {
            UpdatesManager.getInstance().init(appContext, this);
        }
    }

    @Override
    public void onAccountEvent(String type, JSONObject payload, long pts) {
        if (payload == null) {
            return;
        }
        try {
            if ("DIRECT_MESSAGE_NEW".equals(type)) {
                JSONObject message = payload.optJSONObject("message");
                if (message != null) {
                    UpdatesDispatchHelper.dispatchDirectNew(appContext, message);
                }
            } else if ("DIRECT_MESSAGE_RECALL".equals(type)) {
                String messageId = payload.optString("message_id", "");
                String peerUid = payload.optString("thread_peer_uid", "");
                UpdatesDispatchHelper.dispatchDirectRecall(appContext, messageId, peerUid);
            } else if ("DIRECT_READ".equals(type)) {
                String peerUid = payload.optString("peer_uid", "");
                String readUpTo = payload.optString("read_up_to_message_id", "");
                UpdatesDispatchHelper.dispatchDirectRead(appContext, peerUid, readUpTo);
            } else if ("GROUP_POINTER_UPDATE".equals(type)) {
                String groupId = payload.optString("group_id", "");
                long groupSeq = payload.optLong("group_seq", 0L);
                String hintType = payload.optString("hint_type", "message");
                UpdatesDispatchHelper.dispatchGroupPointer(appContext, groupId, groupSeq, hintType);
            } else if ("GROUP_MEMBERSHIP_CHANGE".equals(type)) {
                String groupId = payload.optString("group_id", "");
                UpdatesDispatchHelper.dispatchGroupMembershipChange(appContext, groupId);
            }
        } catch (Exception ignored) {
            // 事件应用失败不阻塞 pts 前进；下次补差由服务端重发（pts 未推进则重拉）。
        }
    }

    @Override
    public void onReset(long serverCurrentPts) {
        // 全量兜底：复用旧 unread 同步路径。
        if (appContext == null) {
            return;
        }
        UpdatesDispatchHelper.dispatchFullSync(appContext, serverCurrentPts);
    }
}
