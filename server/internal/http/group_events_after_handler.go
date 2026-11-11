package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	"metrochat/internal/data"
)

// groupEventsAfterLimit 群级事件单页上限。
const groupEventsAfterLimit = 100

// groupEventEnvelope 群级归并事件条目（消息 or 事件）。
type groupEventEnvelope struct {
	GroupSeq  int64           `json:"group_seq"`
	EventType string          `json:"event_type"` // MESSAGE_NEW / MESSAGE_RECALL / MESSAGE_EDIT / READ_CURSOR / MEMBER_META
	Payload   json.RawMessage `json:"payload"`
}

// groupEventsAfterResponse 归并响应。
type groupEventsAfterResponse struct {
	Events         []groupEventEnvelope `json:"events"`
	ServerGroupSeq int64                `json:"server_group_seq"`
	NextGroupSeq   int64                `json:"next_group_seq"`
	HasMore        bool                 `json:"has_more"`
}

// handleGroupEventsAfter GET /groups/events/after?group_id=..&after_seq=N&limit=100
// 群级事件流归并查询：group_messages（新增）+ group_events（撤回/编辑/已读/成员变更）
// 按 group_seq 严格升序归并返回，客户端视角为一条连续事件流。
func (a *API) handleGroupEventsAfter(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	groupID := strings.TrimSpace(r.URL.Query().Get("group_id"))
	afterSeq, _ := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("after_seq")), 10, 64)
	limit := parseLimit(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > groupEventsAfterLimit {
		limit = groupEventsAfterLimit
	}
	if groupID == "" {
		writeError(w, http.StatusBadRequest, "invalid_group", "group_id required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	// 成员校验
	role, err := a.groups.GetRole(ctx, groupID, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	_ = role

	serverSeq, err := a.groupMsgs.CurrentGroupSeq(ctx, groupID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	// 服务端回退：水位失效，让客户端重置（对应客户端 GroupReliableSync 的 legacy 兜底）。
	if serverSeq < afterSeq {
		writeJSON(w, http.StatusOK, groupEventsAfterResponse{
			Events:         []groupEventEnvelope{},
			ServerGroupSeq: serverSeq,
			NextGroupSeq:   afterSeq,
			HasMore:        false,
		})
		return
	}

	// 归并：消息表 + 事件表各取 limit 条，按 group_seq 合并排序。
	messages, err := a.groupMsgs.ListByGroupAfterSeq(ctx, groupID, afterSeq, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	events, err := a.groupEvents.ListEventsAfterSeq(ctx, groupID, afterSeq, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	merged := mergeGroupEvents(messages, events, limit)
	next := afterSeq
	if len(merged) > 0 {
		next = merged[len(merged)-1].GroupSeq
	}
	hasMore := len(messages) >= limit || len(events) >= limit

	// 转换为统一信封
	envelopes := make([]groupEventEnvelope, 0, len(merged))
	for _, item := range merged {
		envelopes = append(envelopes, groupEventEnvelope{
			GroupSeq:  item.GroupSeq,
			EventType: item.EventType,
			Payload:   item.Payload,
		})
	}
	writeJSON(w, http.StatusOK, groupEventsAfterResponse{
		Events:         envelopes,
		ServerGroupSeq: serverSeq,
		NextGroupSeq:   next,
		HasMore:        hasMore,
	})
}

// mergedGroupEvent 归并过程中的中间项。
type mergedGroupEvent struct {
	GroupSeq  int64
	EventType string
	Payload   json.RawMessage
}

// mergeGroupEvents 将消息表（MESSAGE_NEW）与事件表按 group_seq 归并，取前 limit 条。
func mergeGroupEvents(messages []data.GroupMessage, events []data.GroupEvent, limit int) []mergedGroupEvent {
	result := make([]mergedGroupEvent, 0, len(messages)+len(events))
	i, j := 0, 0
	for i < len(messages) && j < len(events) {
		if messages[i].GroupSeq <= events[j].GroupSeq {
			payload, _ := json.Marshal(messages[i])
			result = append(result, mergedGroupEvent{GroupSeq: messages[i].GroupSeq, EventType: "MESSAGE_NEW", Payload: payload})
			i++
		} else {
			payload, _ := json.Marshal(map[string]interface{}{
				"event_type": events[j].EventType,
				"ref_id":     events[j].RefID,
				"payload":    json.RawMessage(events[j].Payload),
			})
			result = append(result, mergedGroupEvent{GroupSeq: events[j].GroupSeq, EventType: events[j].EventType, Payload: payload})
			j++
		}
	}
	for i < len(messages) {
		payload, _ := json.Marshal(messages[i])
		result = append(result, mergedGroupEvent{GroupSeq: messages[i].GroupSeq, EventType: "MESSAGE_NEW", Payload: payload})
		i++
	}
	for j < len(events) {
		payload, _ := json.Marshal(map[string]interface{}{
			"event_type": events[j].EventType,
			"ref_id":     events[j].RefID,
			"payload":    json.RawMessage(events[j].Payload),
		})
		result = append(result, mergedGroupEvent{GroupSeq: events[j].GroupSeq, EventType: events[j].EventType, Payload: payload})
		j++
	}
	if len(result) > limit {
		result = result[:limit]
	}
	return result
}
