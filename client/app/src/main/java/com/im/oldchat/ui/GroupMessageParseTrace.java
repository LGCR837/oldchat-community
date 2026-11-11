package com.im.oldchat.ui;

import com.im.oldchat.util.ChatOpenPerfTracer;

import java.util.HashMap;
import java.util.Map;

final class GroupMessageParseTrace {
    private final ChatOpenPerfTracer perfTracer;
    private final long parseStartedAtMs;
    private final Map<String, String> resolvedUidCache = new HashMap<String, String>();
    private long jsonStartedAtMs;
    private long loopStartedAtMs;
    private long repairCostMs;
    private long metaCostMs;
    private int aliasHits;
    private int aliasMisses;

    GroupMessageParseTrace(ChatOpenPerfTracer perfTracer, long parseStartedAtMs) {
        this.perfTracer = perfTracer;
        this.parseStartedAtMs = parseStartedAtMs;
    }

    void markJsonStart() {
        jsonStartedAtMs = now();
    }

    void markLoopStart() {
        loopStartedAtMs = now();
    }

    void addRepairCost(long costMs) {
        if (costMs > 0L) {
            repairCostMs += costMs;
        }
    }

    void addMetaCost(long costMs) {
        if (costMs > 0L) {
            metaCostMs += costMs;
        }
    }

    String resolveCanonicalUid(android.content.Context context, String rawUid) {
        if (rawUid == null || rawUid.length() == 0 || context == null) {
            return rawUid == null ? "" : rawUid;
        }
        String cached = resolvedUidCache.get(rawUid);
        if (cached != null) {
            aliasHits++;
            return cached;
        }
        String resolved = com.im.oldchat.util.UidAliasStore.resolve(context, rawUid);
        if (resolved == null || resolved.length() == 0) {
            resolved = rawUid;
        }
        resolvedUidCache.put(rawUid, resolved);
        aliasMisses++;
        return resolved;
    }

    void finish(int count) {
        if (perfTracer == null) {
            return;
        }
        long now = now();
        long jsonCost = jsonStartedAtMs > 0L ? loopStartedAtMs - jsonStartedAtMs : 0L;
        long loopCost = loopStartedAtMs > 0L ? now - loopStartedAtMs : 0L;
        perfTracer.traceFrom("remote_parse_breakdown",
                "来源=群聊 条数=" + count
                        + " JSON解析=" + Math.max(0L, jsonCost) + "ms"
                        + " 循环处理=" + Math.max(0L, loopCost) + "ms"
                        + " 字段修正=" + repairCostMs + "ms"
                        + " 成员预热=" + metaCostMs + "ms"
                        + " 别名缓存命中=" + aliasHits
                        + " 别名实际解析=" + aliasMisses,
                parseStartedAtMs);
    }

    private long now() {
        return android.os.SystemClock.uptimeMillis();
    }
}
