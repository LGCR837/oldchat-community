package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
)

func newPtsTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	if err := NewPtsStore(db).EnsureSchema(); err != nil {
		t.Fatal(err)
	}
	if err := NewGroupEventStore(db).EnsureSchema(); err != nil {
		t.Fatal(err)
	}
	// group_message_sequences 由 ensureGroupMessageSequenceSchema 建，测试手动建。
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_message_sequences (
    group_id VARCHAR(32) PRIMARY KEY,
    last_seq INTEGER NOT NULL DEFAULT 0
)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_messages (
    id VARCHAR(32) PRIMARY KEY,
    group_id VARCHAR(32) NOT NULL,
    group_seq INTEGER NULL,
    sender_id VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    msg_type TEXT NOT NULL DEFAULT 'text',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db
}

func TestPtsStoreEmitAndList(t *testing.T) {
	db := newPtsTestDB(t)
	store := NewPtsStore(db)
	ctx := context.Background()

	pts1, err := store.Emit(ctx, "user-1", "DIRECT_MESSAGE_NEW", "msg-1", map[string]interface{}{"id": "msg-1"})
	if err != nil {
		t.Fatal(err)
	}
	if pts1 != 1 {
		t.Fatalf("first pts = %d, want 1", pts1)
	}
	pts2, err := store.Emit(ctx, "user-1", "DIRECT_MESSAGE_RECALL", "msg-2", map[string]interface{}{"message_id": "msg-2"})
	if err != nil {
		t.Fatal(err)
	}
	if pts2 != 2 {
		t.Fatalf("second pts = %d, want 2", pts2)
	}
	cur, err := store.GetCurrentPts(ctx, "user-1")
	if err != nil || cur != 2 {
		t.Fatalf("current pts = %d, err=%v; want 2", cur, err)
	}

	// 用户隔离
	ptsOther, _ := store.Emit(ctx, "user-2", "DIRECT_MESSAGE_NEW", "x", map[string]interface{}{})
	if ptsOther != 1 {
		t.Fatalf("user-2 pts = %d, want 1 (隔离)", ptsOther)
	}

	events, err := store.ListEventsAfter(ctx, "user-1", 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 2 {
		t.Fatalf("events len = %d, want 2", len(events))
	}
	if events[0].Pts != 1 || events[1].Pts != 2 {
		t.Fatalf("events pts order wrong: %d %d", events[0].Pts, events[1].Pts)
	}
	if events[0].EventType != "DIRECT_MESSAGE_NEW" {
		t.Fatalf("event type = %s", events[0].EventType)
	}
	if events[0].RefID != "msg-1" {
		t.Fatalf("ref_id = %s", events[0].RefID)
	}

	// 增量
	after1, _ := store.ListEventsAfter(ctx, "user-1", 1, 100)
	if len(after1) != 1 || after1[0].Pts != 2 {
		t.Fatalf("after pts=1 should return 1 event (pts=2)")
	}
}

func TestPtsStoreEmitMulti(t *testing.T) {
	db := newPtsTestDB(t)
	store := NewPtsStore(db)
	ctx := context.Background()

	pts, err := store.EmitMulti(ctx, "user-1", "GROUP_POINTER_UPDATE", "g1", map[string]interface{}{}, 5)
	if err != nil {
		t.Fatal(err)
	}
	if pts != 5 {
		t.Fatalf("EmitMulti pts = %d, want 5", pts)
	}
	events, _ := store.ListEventsAfter(ctx, "user-1", 0, 100)
	if len(events) != 1 || events[0].PtsCount != 5 {
		t.Fatalf("events = %+v", events)
	}
}

func TestGroupEventStoreSharedSeq(t *testing.T) {
	db := newPtsTestDB(t)
	store := NewGroupEventStore(db)
	ctx := context.Background()

	seq1, err := store.EmitGroupEvent(ctx, "g1", "GROUP_MESSAGE_RECALL", "m1", map[string]interface{}{"message_id": "m1"})
	if err != nil {
		t.Fatal(err)
	}
	if seq1 != 1 {
		t.Fatalf("first group seq = %d, want 1", seq1)
	}
	seq2, err := store.EmitGroupEvent(ctx, "g1", "GROUP_READ_CURSOR_UPDATE", "m2", map[string]interface{}{})
	if err != nil {
		t.Fatal(err)
	}
	if seq2 != 2 {
		t.Fatalf("second group seq = %d, want 2 (共享序号线)", seq2)
	}

	// 群隔离
	seqOther, _ := store.EmitGroupEvent(ctx, "g2", "GROUP_MESSAGE_RECALL", "x", map[string]interface{}{})
	if seqOther != 1 {
		t.Fatalf("g2 seq = %d, want 1 (群隔离)", seqOther)
	}

	events, err := store.ListEventsAfterSeq(ctx, "g1", 0, 100)
	if err != nil || len(events) != 2 {
		t.Fatalf("events len=%d err=%v", len(events), err)
	}
	if events[0].GroupSeq != 1 || events[0].EventType != "GROUP_MESSAGE_RECALL" {
		t.Fatalf("event[0] = %+v", events[0])
	}
	has, _ := store.HasEventAfterSeq(ctx, "g1", 1)
	if !has {
		t.Fatal("should have event after seq 1")
	}
	has, _ = store.HasEventAfterSeq(ctx, "g1", 2)
	if has {
		t.Fatal("should not have event after seq 2")
	}
}

func TestPtsStoreEmitBatchForUsers(t *testing.T) {
	db := newPtsTestDB(t)
	store := NewPtsStore(db)
	ctx := context.Background()

	// 批量：3 个用户各分配连续 pts
	userIDs := []string{"u-batch-1", "u-batch-2", "u-batch-3"}
	ptsMap, err := store.EmitBatchForUsers(ctx, userIDs, "GROUP_POINTER_UPDATE", "g1", map[string]interface{}{"group_id": "g1"})
	if err != nil {
		t.Fatal(err)
	}
	for _, uid := range userIDs {
		if ptsMap[uid] != 1 {
			t.Fatalf("first batch pts[%s] = %d, want 1", uid, ptsMap[uid])
		}
	}
	// 二次批量：pts 应连续递增到 2
	ptsMap2, err := store.EmitBatchForUsers(ctx, userIDs, "GROUP_POINTER_UPDATE", "g1", map[string]interface{}{"group_id": "g1"})
	if err != nil {
		t.Fatal(err)
	}
	for _, uid := range userIDs {
		if ptsMap2[uid] != 2 {
			t.Fatalf("second batch pts[%s] = %d, want 2", uid, ptsMap2[uid])
		}
		cur, _ := store.GetCurrentPts(ctx, uid)
		if cur != 2 {
			t.Fatalf("current pts[%s] = %d, want 2", uid, cur)
		}
	}
	// 每个用户事件可独立列出
	for _, uid := range userIDs {
		events, _ := store.ListEventsAfter(ctx, uid, 0, 100)
		if len(events) != 2 {
			t.Fatalf("events[%s] len = %d, want 2", uid, len(events))
		}
		if events[0].Pts != 1 || events[1].Pts != 2 {
			t.Fatalf("events[%s] pts order wrong", uid)
		}
		if events[0].RefID != "g1" {
			t.Fatalf("events[%s].RefID = %s, want g1", uid, events[0].RefID)
		}
	}
}

func TestPtsStoreEmitBatchEmptyAndDup(t *testing.T) {
	db := newPtsTestDB(t)
	store := NewPtsStore(db)
	ctx := context.Background()

	// 空列表：返回空 map 无错误
	m, err := store.EmitBatchForUsers(ctx, nil, "GROUP_POINTER_UPDATE", "g1", map[string]interface{}{})
	if err != nil || len(m) != 0 {
		t.Fatalf("empty batch: map=%v err=%v", m, err)
	}
	// 含空 userID 被跳过
	m2, err := store.EmitBatchForUsers(ctx, []string{"", "u-ok"}, "GROUP_POINTER_UPDATE", "g1", map[string]interface{}{})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m2[""]; ok {
		t.Fatal("empty userID should be skipped")
	}
	if m2["u-ok"] != 1 {
		t.Fatalf("u-ok pts = %d, want 1", m2["u-ok"])
	}
}
