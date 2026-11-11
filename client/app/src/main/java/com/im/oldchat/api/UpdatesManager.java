package com.im.oldchat.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.im.oldchat.data.OldChatDbHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UpdatesManager：账号级 pts 同步体系客户端入口。
 *
 * 职责：
 *  - 维护本地 local_pts（SQLite 单值）。
 *  - WS 实时事件到达时做连续性校验（localPts + ptsCount == pts），
 *    命中 gap 立即触发 /updates/difference 补差。
 *  - 断线重连 / 冷启动时主动 fetchDifference。
 *  - reset 时降级全量同步（复用旧逻辑）。
 *
 * 兼容 Android 1.6+（API 4），仅使用 JSON/Handler/SQLite 基础 API。
 */
public final class UpdatesManager {
    private static final String DIFFERENCE_ENDPOINT = "/v2/updates/difference";
    private static final int PAGE_LIMIT = 200;
    private static final long RETRY_BASE_MS = 3000L;
    private static final long RETRY_MAX_MS = 30000L;
    private static final long SUCCESS_COOLDOWN_MS = 5000L;

    private static volatile UpdatesManager instance;

    public interface EventListener {
        /** 应用一条账号级事件（DIRECT_MESSAGE_NEW / RECALL / GROUP_POINTER_UPDATE 等）。 */
        void onAccountEvent(String type, JSONObject payload, long pts);

        /** reset 时触发全量兜底（由调用方复用旧同步逻辑）。 */
        void onReset(long serverCurrentPts);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EventListener listener;
    private Context appContext;
    private volatile long localPts;
    private volatile boolean running;
    private volatile boolean pending;
    private int retryAttempts;
    private long lastSuccessAt;
    private Runnable retryRunnable;

    public static UpdatesManager getInstance() {
        if (instance == null) {
            synchronized (UpdatesManager.class) {
                if (instance == null) {
                    instance = new UpdatesManager();
                }
            }
        }
        return instance;
    }

    private UpdatesManager() {
    }

    /** 初始化：恢复 local_pts 并注册事件监听。 */
    public void init(Context context, EventListener listener) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.listener = listener;
        if (appContext != null) {
            long saved = OldChatDbHelper.getLocalPts(appContext);
            this.localPts = OldChatDbHelper.isValidLocalPts(saved) ? saved : 0L;
        }
    }

    public long getLocalPts() {
        return localPts;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * WS 账号级事件到达：连续性校验。
     *  - pts <= localPts：重复推送，丢弃。
     *  - localPts + ptsCount == pts：连续，应用事件并前进。
     *  - 其他：gap，触发补差。
     */
    public void onWsAccountEvent(final long pts, final int ptsCount, final String type, final JSONObject payload) {
        if (appContext == null || listener == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (UpdatesManager.this) {
                    if (pts <= localPts) {
                        return; // 重复
                    }
                    if (localPts + ptsCount == pts) {
                        listener.onAccountEvent(type, payload, pts);
                        advanceLocalPts(pts);
                        return;
                    }
                    // gap 或异常：补差
                    fetchDifference();
                }
            }
        });
    }

    /** 断线重连 / 冷启动 / 主动触发：从 localPts 补差。 */
    public void fetchDifference() {
        if (appContext == null) {
            return;
        }
        synchronized (this) {
            if (running) {
                pending = true;
                return;
            }
            long now = System.currentTimeMillis();
            if (lastSuccessAt > 0L && now - lastSuccessAt < SUCCESS_COOLDOWN_MS) {
                return;
            }
            running = true;
            pending = false;
            clearRetry();
        }
        requestPage(localPts);
    }

    private void requestPage(final long fromPts) {
        String url = DIFFERENCE_ENDPOINT + "?pts=" + fromPts + "&limit=" + PAGE_LIMIT;
        HttpUtil.get(url, getToken(), new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                handleDifferenceResponse(fromPts, response);
            }

            @Override
            public void onError(int code, String error) {
                scheduleRetry();
            }
        });
    }

    private void handleDifferenceResponse(final long fromPts, String response) {
        try {
            JSONObject root = new JSONObject(response);
            if (root.optBoolean("reset", false)) {
                long serverPts = root.optLong("current_pts", 0L);
                finishSuccess();
                if (listener != null) {
                    listener.onReset(serverPts);
                }
                return;
            }
            JSONArray events = root.optJSONArray("events");
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    JSONObject ev = events.optJSONObject(i);
                    if (ev == null) continue;
                    long pts = ev.optLong("pts", 0L);
                    int ptsCount = ev.optInt("pts_count", 1);
                    String type = ev.optString("type", "");
                    JSONObject payload = ev.optJSONObject("payload");
                    if (pts > localPts) {
                        // 补差事件按序应用（pts 严格递增）。
                        if (localPts + ptsCount == pts) {
                            if (listener != null) {
                                listener.onAccountEvent(type, payload, pts);
                            }
                            advanceLocalPts(pts);
                        } else {
                            // 仍不连续：继续请求（服务端已按序，不应发生；兜底重拉）
                        }
                    }
                }
            }
            long nextPts = root.optLong("next_pts", fromPts);
            boolean hasMore = root.optBoolean("has_more", false);
            if (hasMore && nextPts > localPts) {
                requestPage(nextPts);
            } else {
                finishSuccess();
            }
        } catch (Exception e) {
            scheduleRetry();
        }
    }

    private void advanceLocalPts(long pts) {
        this.localPts = pts;
        if (appContext != null) {
            OldChatDbHelper.saveLocalPts(appContext, pts);
        }
    }

    private void finishSuccess() {
        running = false;
        pending = false;
        retryAttempts = 0;
        lastSuccessAt = System.currentTimeMillis();
        clearRetry();
        // 有 pending 请求则继续
        if (pending) {
            pending = false;
            fetchDifference();
        }
    }

    private void scheduleRetry() {
        running = false;
        if (retryRunnable != null) {
            return;
        }
        int shift = Math.min(retryAttempts, 10);
        long baseDelay = Math.min(RETRY_BASE_MS * (1L << shift), RETRY_MAX_MS);
        long spread = Math.max(1L, baseDelay / 5L);
        long delay = baseDelay - spread + (long) (Math.random() * (spread * 2L + 1L));
        retryAttempts++;
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (retryRunnable != this) return;
                retryRunnable = null;
                if (pending && !running) {
                    pending = false;
                    fetchDifference();
                }
            }
        };
        handler.postDelayed(retryRunnable, delay);
    }

    private void clearRetry() {
        if (retryRunnable != null) {
            handler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    private String getToken() {
        if (appContext == null) return "";
        return WSManagerSupport.getToken(appContext);
    }
}
