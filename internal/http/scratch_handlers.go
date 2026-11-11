package httpapi

import (
	"context"
	"net/http"
	"time"

	"metrochat/internal/data"
)

// handleMeScratchStatus 查询今日刮刮乐状态（不改变状态）。
func (a *API) handleMeScratchStatus(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	if !a.ipLimiter.Allow("scratch_status:" + clientIP(r)) {
		writeError(w, http.StatusTooManyRequests, "rate_limited", "请求过于频繁，请稍后再试")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	result, err := a.scratch.QueryToday(ctx, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, scratchResultJSON(result))
}

// handleMeScratch 执行今日刮刮乐：首次开奖发金币，已刮过幂等返回。
func (a *API) handleMeScratch(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	if !a.actionIPLimiter.Allow("scratch_action:" + clientIP(r)) || !a.actionIPLimiter.Allow("scratch_user:" + claims.Subject) {
		writeError(w, http.StatusTooManyRequests, "rate_limited", "操作过于频繁，请稍后再试")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	result, err := a.scratch.DailyScratch(ctx, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, scratchResultJSON(result))
}

func scratchResultJSON(result *data.DailyScratchResult) map[string]interface{} {
	slots := result.Slots
	if slots == nil {
		slots = make([]int, 0)
	}
	return map[string]interface{}{
		"scratch_date":  result.ScratchDate,
		"already_done":  result.AlreadyDone,
		"slots":         slots,
		"total_reward":  result.TotalReward,
		"coin_balance":  result.CoinBalance,
	}
}
