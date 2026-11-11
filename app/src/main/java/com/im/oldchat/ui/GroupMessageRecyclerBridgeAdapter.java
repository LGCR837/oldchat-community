package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.DataSetObserver;
import android.os.SystemClock;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.im.oldchat.R;
import com.im.oldchat.util.ScreenPerfTracer;

class GroupMessageRecyclerBridgeAdapter
        extends RecyclerView.Adapter<GroupMessageRecyclerBridgeAdapter.BridgeHolder> {

    private static final int VIEW_TYPE_HEADER = -100;
    private static final long FIRST_BIND_TRACE_WINDOW_MS = 1200L;
    private static final long SLOW_BIND_THRESHOLD_MS = 12L;
    private static final int MAX_SLOW_BIND_LOGS = 3;

    private final GroupMessageAdapter legacyAdapter;
    private final DataSetObserver observer = new DataSetObserver() {
        @Override
        public void onChanged() {
            notifyDataSetChanged();
            if (recyclerView != null) {
                recyclerView.handleLegacyDataChanged();
            }
        }

        @Override
        public void onInvalidated() {
            onChanged();
        }
    };
    private GroupMessageRecyclerView recyclerView;
    private View headerView;
    private long firstBindTraceStartMs = -1L;
    private int firstBindCount = 0;
    private int firstBindNormalCount = 0;
    private int firstBindTypingCount = 0;
    private long firstBindTotalCostMs = 0L;
    private long firstBindMaxCostMs = 0L;
    private int slowBindLogCount = 0;
    private boolean firstBindSummaryLogged = false;

    GroupMessageRecyclerBridgeAdapter(GroupMessageAdapter legacyAdapter, View headerView) {
        this.legacyAdapter = legacyAdapter;
        this.headerView = headerView;
        // 桥接层自行按消息身份生成稳定 ID，不再使用 legacy adapter 的位置 ID。
        // 历史前插时 RecyclerView 可保留现有 ViewHolder，减少重绑和视觉位移。
        setHasStableIds(true);
        if (legacyAdapter != null) {
            legacyAdapter.registerDataSetObserver(observer);
        }
    }

    void setRecyclerView(GroupMessageRecyclerView recyclerView) {
        this.recyclerView = recyclerView;
    }

    void setHeaderView(View headerView) {
        this.headerView = headerView;
        notifyDataSetChanged();
    }

    void notifyVisibleRangeChanged(int firstMessagePosition, int lastMessagePosition) {
        if (legacyAdapter == null) {
            return;
        }
        if (firstMessagePosition < 0) {
            firstMessagePosition = 0;
        }
        if (lastMessagePosition < firstMessagePosition) {
            return;
        }
        int count = legacyAdapter.getCount();
        if (count <= 0 || firstMessagePosition >= count) {
            return;
        }
        if (lastMessagePosition >= count) {
            lastMessagePosition = count - 1;
        }
        notifyItemRangeChanged(getHeaderCount() + firstMessagePosition,
                lastMessagePosition - firstMessagePosition + 1);
    }

    void notifyMessageRangeInserted(int firstMessagePosition, int count) {
        if (legacyAdapter == null || count <= 0) {
            return;
        }
        int itemCount = legacyAdapter.getCount();
        if (itemCount <= 0) {
            return;
        }
        if (firstMessagePosition < 0) {
            firstMessagePosition = 0;
        }
        if (firstMessagePosition > itemCount) {
            firstMessagePosition = itemCount;
        }
        // 通知数量绝不能超过实际新增量，否则 RecyclerView 内部计数与数据不符，
        // 下一次布局会以越界的 holder 位置抛 "Inconsistency detected"。
        int maxCount = itemCount - firstMessagePosition;
        if (count > maxCount) {
            count = maxCount;
        }
        if (count <= 0) {
            return;
        }
        notifyItemRangeInserted(getHeaderCount() + firstMessagePosition, count);
    }

    void notifyMessageRemoved(int messagePosition) {
        if (legacyAdapter == null || messagePosition < 0) {
            return;
        }
        if (messagePosition >= legacyAdapter.getCount()) {
            return;
        }
        notifyItemRemoved(getHeaderCount() + messagePosition);
    }

    int getHeaderCount() {
        return headerView == null ? 0 : 1;
    }

    int toMessagePosition(int adapterPosition) {
        return adapterPosition - getHeaderCount();
    }

    @Override
    public int getItemCount() {
        return getHeaderCount() + (legacyAdapter == null ? 0 : legacyAdapter.getCount());
    }

    @Override
    public long getItemId(int position) {
        if (position < getHeaderCount()) {
            return Long.MIN_VALUE;
        }
        int messagePosition = toMessagePosition(position);
        Object item = legacyAdapter == null ? null : legacyAdapter.getItem(messagePosition);
        if (!(item instanceof com.im.oldchat.models.GroupMessage)) {
            return Long.MIN_VALUE + 1L;
        }
        com.im.oldchat.models.GroupMessage message = (com.im.oldchat.models.GroupMessage) item;
        String key = message.id;
        if (key == null || key.length() == 0) {
            key = message.localRequestId;
        }
        if (key == null || key.length() == 0) {
            // 本地图片可能同时创建、正文为空；身份键必须包含媒体地址，
            // 并以对象身份兜底，避免两条相邻图片获得相同 stable ID。
            key = String.valueOf(message.created_at) + ':'
                    + String.valueOf(message.from_uid) + ':'
                    + String.valueOf(message.msg_type) + ':'
                    + String.valueOf(message.media_url) + ':'
                    + String.valueOf(message.thumb_url) + ':'
                    + String.valueOf(message.localPreviewUri) + ':'
                    + String.valueOf(message.body) + ':'
                    + System.identityHashCode(message);
        }
        long hash = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) {
            hash = 31L * hash + key.charAt(i);
        }
        return hash == Long.MIN_VALUE ? Long.MIN_VALUE + 2L : hash;
    }

    @Override
    public int getItemViewType(int position) {
        if (position < getHeaderCount()) {
            return VIEW_TYPE_HEADER;
        }
        return legacyAdapter.getItemViewType(toMessagePosition(position));
    }

    @Override
    public BridgeHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View view = headerView;
            if (view == null) {
                view = new View(parent.getContext());
            }
            detachFromParent(view);
            return new BridgeHolder(view);
        }
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view;
        if (viewType == GroupMessageAdapterSupport3.VIEW_TYPE_TYPING) {
            view = inflater.inflate(R.layout.item_typing_indicator, parent, false);
            view.setTag(new GroupMessageAdapterSupport3.TypingViewHolder(view));
        } else {
            view = inflater.inflate(R.layout.item_group_message, parent, false);
            view.setTag(new GroupMessageAdapterSupport3.ViewHolder(view));
        }
        return new BridgeHolder(view);
    }

    @Override
    public void onBindViewHolder(BridgeHolder holder, int position) {
        if (position < getHeaderCount()) {
            return;
        }
        long bindStartedAtMs = SystemClock.uptimeMillis();
        legacyAdapter.getView(toMessagePosition(position), holder.itemView, null);
        long bindCostMs = SystemClock.uptimeMillis() - bindStartedAtMs;
        recordBindTrace(position, bindCostMs, getItemViewType(position));
    }

    @Override
    public void onViewRecycled(BridgeHolder holder) {
        Object tag = holder.itemView.getTag();
        if (tag instanceof GroupMessageAdapterSupport3.TypingViewHolder) {
            ((GroupMessageAdapterSupport3.TypingViewHolder) tag).reset();
        }
        super.onViewRecycled(holder);
    }

    private static void detachFromParent(View view) {
        if (view == null) {
            return;
        }
        android.view.ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }

    private void recordBindTrace(int adapterPosition, long bindCostMs, int viewType) {
        if (firstBindSummaryLogged) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (firstBindTraceStartMs < 0L) {
            firstBindTraceStartMs = now;
        }
        if (now - firstBindTraceStartMs > FIRST_BIND_TRACE_WINDOW_MS) {
            flushFirstBindSummary(now);
            return;
        }
        firstBindCount++;
        firstBindTotalCostMs += bindCostMs;
        if (bindCostMs > firstBindMaxCostMs) {
            firstBindMaxCostMs = bindCostMs;
        }
        if (viewType == GroupMessageAdapterSupport3.VIEW_TYPE_TYPING) {
            firstBindTypingCount++;
        } else {
            firstBindNormalCount++;
        }
        if (bindCostMs >= SLOW_BIND_THRESHOLD_MS && slowBindLogCount < MAX_SLOW_BIND_LOGS) {
            slowBindLogCount++;
            traceBind("first_screen_bind_slow", firstBindTraceStartMs,
                    "来源=群聊 消息位置=" + toMessagePosition(adapterPosition)
                            + " 绑定耗时=" + bindCostMs + "ms"
                            + " 类型=" + describeViewType(viewType));
        }
        if (firstBindCount >= 12) {
            flushFirstBindSummary(now);
        }
    }

    private void flushFirstBindSummary(long now) {
        if (firstBindSummaryLogged || firstBindTraceStartMs < 0L) {
            return;
        }
        firstBindSummaryLogged = true;
        traceBind("first_screen_bind_summary", firstBindTraceStartMs,
                "来源=群聊 首屏绑定数=" + firstBindCount
                        + " 普通消息=" + firstBindNormalCount
                        + " 正在输入=" + firstBindTypingCount
                        + " 总耗时=" + firstBindTotalCostMs + "ms"
                        + " 最慢单条=" + firstBindMaxCostMs + "ms"
                        + " 窗口=" + (now - firstBindTraceStartMs) + "ms");
    }

    private void traceBind(String stage, long startedAtMs, String detail) {
        if (recyclerView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String groupId = resolveGroupId();
        if (groupId.length() > 0) {
            sb.append("target=").append(groupId);
        }
        if (detail != null && detail.length() > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(detail);
        }
        ScreenPerfTracer.trace(recyclerView.getContext(), "GroupChatOpen", stage,
                startedAtMs, sb.toString());
    }

    private String resolveGroupId() {
        if (!(recyclerView.getContext() instanceof Activity)) {
            return "";
        }
        Intent intent = ((Activity) recyclerView.getContext()).getIntent();
        if (intent == null) {
            return "";
        }
        String groupId = intent.getStringExtra("group_id");
        return groupId == null ? "" : groupId;
    }

    private String describeViewType(int viewType) {
        return viewType == GroupMessageAdapterSupport3.VIEW_TYPE_TYPING ? "输入中" : "消息";
    }

    static class BridgeHolder extends RecyclerView.ViewHolder {
        BridgeHolder(View itemView) {
            super(itemView);
        }
    }
}
