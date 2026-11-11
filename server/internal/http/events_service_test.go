package httpapi

import (
	"context"
	"sync"
	"testing"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/data"
)

// recordingHub 记录推送内容，便于断言。
type recordingHub struct {
	mu     sync.Mutex
	sent   [][]byte
	sentTo map[string][][]byte
}

func (h *recordingHub) BroadcastToUser(userID string, msg []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.sentTo == nil {
		h.sentTo = make(map[string][][]byte)
	}
	h.sentTo[userID] = append(h.sentTo[userID], msg)
	h.sent = append(h.sent, msg)
}

func (h *recordingHub) BroadcastToUsers(userIDs []string, msg []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.sentTo == nil {
		h.sentTo = make(map[string][][]byte)
	}
	for _, id := range userIDs {
		h.sentTo[id] = append(h.sentTo[id], msg)
	}
}

func TestEventsServiceEmitAndPush(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { _ = db.Close() })
	pts := data.NewPtsStore(db)
	if err := pts.EnsureSchema(); err != nil {
		t.Fatal(err)
	}
	hub := &recordingHub{}
	svc := newEventsService(pts, hub)
	ctx := context.Background()

	ptsAssigned, err := svc.Emit(ctx, "user-a", "DIRECT_MESSAGE_NEW", "m1",
		map[string]interface{}{"message": map[string]interface{}{"id": "m1", "body": "hello"}})
	if err != nil {
		t.Fatal(err)
	}
	if ptsAssigned != 1 {
		t.Fatalf("pts = %d, want 1", ptsAssigned)
	}
	// WS 推送应包含 account_event 帧。
	hub.mu.Lock()
	pushed := len(hub.sent)
	hub.mu.Unlock()
	if pushed != 1 {
		t.Fatalf("pushes = %d, want 1", pushed)
	}
	// difference 可查回。
	events, next, err := svc.ListDifference(ctx, "user-a", 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || next != 1 {
		t.Fatalf("events=%d next=%d", len(events), next)
	}
	if events[0].EventType != "DIRECT_MESSAGE_NEW" {
		t.Fatalf("type = %s", events[0].EventType)
	}
}
