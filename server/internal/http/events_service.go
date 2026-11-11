package httpapi

import (
	"context"
	"encoding/json"
	"time"

	"metrochat/internal/data"
)

// eventsService 账号级 pts 事件流服务。
// 仅保存真正属于单个账号的事件。群消息使用群级 group_seq，严禁按成员
// 写入账号事件，否则一条大群消息会放大成成员数条数据库记录。
type eventsService struct {
	pts   *data.PtsStore
	wsHub wsBroadcaster
	now   func() time.Time
}

// wsBroadcaster 抽象 WS 推送，便于测试替换。
type wsBroadcaster interface {
	BroadcastToUser(userID string, msg []byte)
	BroadcastToUsers(userIDs []string, msg []byte)
}

// accountEventEnvelope WS 帧与 /updates/difference 响应的通用事件包。
type accountEventEnvelope struct {
	Pts      int64           `json:"pts"`
	PtsCount int             `json:"pts_count"`
	Type     string          `json:"type"`
	Date     int64           `json:"date"`
	Payload  json.RawMessage `json:"payload"`
}

func newEventsService(pts *data.PtsStore, hub wsBroadcaster) *eventsService {
	return &eventsService{pts: pts, wsHub: hub, now: time.Now}
}

// Emit 为单个用户写入一条账号级事件并推送 WS（如已连接）。
// 返回分配的 pts。
func (s *eventsService) Emit(ctx context.Context, userID, eventType, refID string, payload interface{}) (int64, error) {
	pts, err := s.pts.Emit(ctx, userID, eventType, refID, payload)
	if err != nil {
		return 0, err
	}
	s.pushToUser(ctx, userID, pts, 1, eventType, payload)
	return pts, nil
}

// buildEnvelope 构造统一事件包。
func (s *eventsService) buildEnvelope(pts int64, ptsCount int, eventType string, payload interface{}) accountEventEnvelope {
	raw, _ := json.Marshal(payload)
	return accountEventEnvelope{
		Pts:      pts,
		PtsCount: ptsCount,
		Type:     eventType,
		Date:     s.now().Unix(),
		Payload:  raw,
	}
}

// pushToUser 构造 wsEnvelope 并推给单个用户。
func (s *eventsService) pushToUser(ctx context.Context, userID string, pts int64, ptsCount int, eventType string, payload interface{}) {
	env := s.buildEnvelope(pts, ptsCount, eventType, payload)
	if raw, err := json.Marshal(wsEnvelope{Type: "account_event", Data: env}); err == nil {
		s.wsHub.BroadcastToUser(userID, raw)
	}
}

// ListDifference 返回 pts > afterPts 的事件列表（升序），供 /updates/difference 使用。
func (s *eventsService) ListDifference(ctx context.Context, userID string, afterPts int64, limit int) ([]data.AccountEvent, int64, error) {
	events, err := s.pts.ListEventsAfter(ctx, userID, afterPts, limit)
	if err != nil {
		return nil, 0, err
	}
	nextPts := afterPts
	if len(events) > 0 {
		nextPts = events[len(events)-1].Pts
	}
	return events, nextPts, nil
}
