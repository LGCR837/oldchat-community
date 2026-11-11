package com.im.oldchat.api;

import android.content.Context;
import android.os.Handler;
import com.im.oldchat.util.UiPerformance;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

final class WSUnreadSyncCoordinator {
    // v2 unread 接口：经 gateway 折叠（真实语义在密文里），响应格式与 v1 一致。
    private static final String DIRECT_ENDPOINT = "/v2/unread/direct";
    private static final String GROUP_ENDPOINT = "/v2/unread/groups";
    private static final int PAGE_LIMIT = 100;
    private static final long RETRY_BASE_MS = 3000L;
    private static final long RETRY_MAX_MS = 30000L;
    private static final long SUCCESS_COOLDOWN_MS = 20000L;
    private static final int LOW_MEMORY_SYNC_BUDGET = 300;
    private static final int NORMAL_SYNC_BUDGET = 1000;

    private final Handler mainHandler;
    private final WSUnreadSyncHandler unreadSyncHandler;
    private final WSUnreadSyncHandler.DirectMessageDispatcher directDispatcher;
    private final WSUnreadSyncHandler.GroupMessageDispatcher groupDispatcher;
    private final SyncState directState = new SyncState(DIRECT_ENDPOINT);
    private final SyncState groupState = new SyncState(GROUP_ENDPOINT);

    WSUnreadSyncCoordinator(Handler mainHandler, WSUnreadSyncHandler unreadSyncHandler,
            WSUnreadSyncHandler.DirectMessageDispatcher directDispatcher,
            WSUnreadSyncHandler.GroupMessageDispatcher groupDispatcher) {
        this.mainHandler = mainHandler;
        this.unreadSyncHandler = unreadSyncHandler;
        this.directDispatcher = directDispatcher;
        this.groupDispatcher = groupDispatcher;
    }

    void syncDirect(Context context, String token) {
        requestSync(directState, context, token);
    }

    void syncGroup(Context context, String token) {
        requestSync(groupState, context, token);
    }

    private void requestSync(SyncState state, Context context, String token) {
        if (context == null || token == null || token.length() == 0) {
            return;
        }
        state.context = context.getApplicationContext();
        state.token = token;
        long now = System.currentTimeMillis();
        if (state.running || (state.lastSuccessAt > 0L
                && now - state.lastSuccessAt < SUCCESS_COOLDOWN_MS)) {
            // Splash、MainActivity 和 Fragment 可能连续请求同步；进行中的任务不再排队重跑。
            return;
        }
        state.pending = true;
        clearRetry(state);
        startSync(state, 0, new JSONArray(), new HashSet<String>());
    }

    private void startSync(final SyncState state, final int offset, final JSONArray mergedMessages,
            final Set<String> seenIds) {
        if (state.context == null || state.token == null || state.token.length() == 0) {
            finishSuccess(state);
            return;
        }
        state.running = true;
        try {
            JSONObject json = new JSONObject();
            json.put("limit", PAGE_LIMIT);
            json.put("offset", offset);
            HttpUtil.post(state.endpoint, json, state.token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    handleSuccess(state, offset, response, mergedMessages, seenIds);
                }

                @Override
                public void onError(int code, String error) {
                    scheduleRetry(state);
                }
            });
        } catch (Exception e) {
            scheduleRetry(state);
        }
    }

    private void handleSuccess(SyncState state, int offset, String response,
            JSONArray mergedMessages, Set<String> seenIds) {
        try {
            JSONObject root = new JSONObject(response);
            JSONArray pageMessages = root.optJSONArray("messages");
            int rawCount = pageMessages == null ? 0 : pageMessages.length();
            int budget = UiPerformance.shouldReduceMotion(state.context)
                    ? LOW_MEMORY_SYNC_BUDGET : NORMAL_SYNC_BUDGET;
            int pageCount = appendMessages(pageMessages, mergedMessages, seenIds, budget);
            boolean hasMore = root.has("has_more")
                    ? root.optBoolean("has_more", false) : rawCount >= PAGE_LIMIT;
            boolean withinBudget = mergedMessages.length() < budget;
            if (hasMore && withinBudget && rawCount > 0 && pageCount > 0) {
                startSync(state, offset + rawCount, mergedMessages, seenIds);
                return;
            }
            if (GROUP_ENDPOINT.equals(state.endpoint)) {
                dispatchGroupMerged(state, mergedMessages);
                return;
            }
            dispatchMerged(state, mergedMessages);
        } catch (Exception e) {
            scheduleRetry(state);
        }
    }

    private int appendMessages(JSONArray source, JSONArray target, Set<String> seenIds,
                               int budget) {
        if (source == null) {
            return 0;
        }
        int appended = 0;
        for (int i = 0; i < source.length() && target.length() < budget; i++) {
            JSONObject message = source.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String id = message.optString("id");
            if (id != null && id.length() > 0) {
                if (seenIds.contains(id)) {
                    continue;
                }
                seenIds.add(id);
            }
            target.put(message);
            appended++;
        }
        return appended;
    }

    private void dispatchMerged(final SyncState state, JSONArray mergedMessages) throws Exception {
        JSONObject root = new JSONObject();
        root.put("messages", mergedMessages);
        unreadSyncHandler.handleDirectUnread(state.context, root.toString(), directDispatcher,
                new Runnable() {
                    @Override public void run() { finishSuccess(state); }
                });
    }

    private void dispatchGroupMerged(final SyncState state, JSONArray mergedMessages) throws Exception {
        JSONObject root = new JSONObject();
        root.put("messages", mergedMessages);
        unreadSyncHandler.handleGroupUnread(state.context, root.toString(), groupDispatcher, new Runnable() {
            @Override public void run() { finishSuccess(state); }
        });
    }

    private void finishSuccess(SyncState state) {
        state.running = false;
        state.pending = false;
        state.retryAttempts = 0;
        state.lastSuccessAt = System.currentTimeMillis();
        clearRetry(state);
    }

    private void scheduleRetry(final SyncState state) {
        state.running = false;
        state.pending = true;
        if (state.retryRunnable != null) {
            return;
        }
        int shift = Math.min(state.retryAttempts, 10);
        long baseDelay = Math.min(RETRY_BASE_MS * (1L << shift), RETRY_MAX_MS);
        long spread = Math.max(1L, baseDelay / 5L);
        long delay = baseDelay - spread
                + (long) (Math.random() * (spread * 2L + 1L));
        state.retryAttempts++;
        state.retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (state.retryRunnable != this) {
                    return;
                }
                state.retryRunnable = null;
                if (!state.pending || state.running) {
                    return;
                }
                startSync(state, 0, new JSONArray(), new HashSet<String>());
            }
        };
        mainHandler.postDelayed(state.retryRunnable, delay);
    }

    private void clearRetry(SyncState state) {
        if (state.retryRunnable != null) {
            mainHandler.removeCallbacks(state.retryRunnable);
            state.retryRunnable = null;
        }
    }

    private static final class SyncState {
        private final String endpoint;
        private Context context;
        private String token;
        private boolean running;
        private boolean pending;
        private int retryAttempts;
        private long lastSuccessAt;
        private Runnable retryRunnable;

        private SyncState(String endpoint) {
            this.endpoint = endpoint;
        }
    }
}
