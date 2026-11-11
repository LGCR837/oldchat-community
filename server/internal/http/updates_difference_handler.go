package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// updatesDifferenceMaxGap 超过该差距阈值即触发 reset 全量兜底。
// 事件表保留窗口（PruneBefore）应与之配合：正常用户离线时长不会攒出
// 超过阈值的差距；长期离线用户直接走 reset，不依赖历史事件。
const updatesDifferenceMaxGap = 10000

// updatesDifferenceResponse /updates/difference 响应。
type updatesDifferenceResponse struct {
	Events     []accountEventEnvelope `json:"events"`
	HasMore    bool                   `json:"has_more,omitempty"`
	NextPts    int64                  `json:"next_pts,omitempty"`
	Reset      bool                   `json:"reset,omitempty"`
	CurrentPts int64                  `json:"current_pts,omitempty"`
}

// handleUpdatesDifference GET /updates/difference?pts=N&limit=200
// 断线重连 / gap 检测时增量补差。
// reset 判定：
//  1. current_pts - request_pts > 阈值
//  2. request_pts 之后的事件已被归档清理（无法增量补齐）
//  3. request_pts > current_pts（客户端本地状态异常）
func (a *API) handleUpdatesDifference(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	afterPts, _ := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("pts")), 10, 64)
	if afterPts < 0 {
		afterPts = 0
	}
	limit := parseLimit(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 200 {
		limit = 200
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	currentPts, err := a.pts.GetCurrentPts(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	// reset 判定 3：客户端 pts 超前（本地状态异常）。
	if afterPts > currentPts {
		writeJSON(w, http.StatusOK, updatesDifferenceResponse{Reset: true, CurrentPts: currentPts})
		return
	}
	// reset 判定 1：差距过大。
	if currentPts-afterPts > updatesDifferenceMaxGap {
		writeJSON(w, http.StatusOK, updatesDifferenceResponse{Reset: true, CurrentPts: currentPts})
		return
	}
	// reset 判定 2：请求位置之后的事件已被归档。
	if afterPts < currentPts {
		has, err := a.pts.HasEventAfter(ctx, claims.Subject, afterPts)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
		if !has {
			writeJSON(w, http.StatusOK, updatesDifferenceResponse{Reset: true, CurrentPts: currentPts})
			return
		}
	}

	events, nextPts, err := a.events.ListDifference(ctx, claims.Subject, afterPts, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	envelopes := make([]accountEventEnvelope, 0, len(events))
	for _, ev := range events {
		envelopes = append(envelopes, accountEventEnvelope{
			Pts:      ev.Pts,
			PtsCount: ev.PtsCount,
			Type:     ev.EventType,
			Date:     ev.CreatedAt.Unix(),
			Payload:  json.RawMessage(ev.Payload),
		})
	}
	hasMore := len(envelopes) >= limit
	resp := updatesDifferenceResponse{
		Events:  envelopes,
		HasMore: hasMore,
		NextPts: nextPts,
	}
	if len(envelopes) == 0 {
		resp.NextPts = currentPts
	}
	if !hasMore {
		// 最后一页：next_pts 对齐到服务端当前值，客户端可直接收敛。
		resp.NextPts = currentPts
	}
	writeJSON(w, http.StatusOK, resp)
}
