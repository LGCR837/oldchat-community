package data

import (
	"context"
	"fmt"
	"sync"
	"testing"

	"github.com/jmoiron/sqlx"
)

func newGroupSequenceTestStore(t *testing.T) (*GroupMessageStore, *sqlx.DB) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`
CREATE TABLE users (id TEXT PRIMARY KEY, uid TEXT NOT NULL, ncuid TEXT NOT NULL DEFAULT '');
CREATE TABLE group_message_sequences (group_id TEXT PRIMARY KEY, last_seq INTEGER NOT NULL DEFAULT 0);
CREATE TABLE group_members (
 group_id TEXT NOT NULL, user_id TEXT NOT NULL, joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_read_at DATETIME NULL, PRIMARY KEY(group_id, user_id));
CREATE TABLE group_messages (
 id TEXT PRIMARY KEY, group_id TEXT NOT NULL, group_seq INTEGER NOT NULL, sender_id TEXT NOT NULL,
 body TEXT NOT NULL, msg_type TEXT NOT NULL DEFAULT 'text', media_url TEXT NOT NULL DEFAULT '',
 thumb_url TEXT NOT NULL DEFAULT '', original_url TEXT NOT NULL DEFAULT '', duration_ms INTEGER NOT NULL DEFAULT 0,
 burn_after_seconds INTEGER NOT NULL DEFAULT 0, burn_start_at DATETIME NULL, burned_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(group_id, group_seq));
CREATE INDEX idx_group_messages_group ON group_messages (group_id, created_at);
INSERT INTO users(id, uid, ncuid) VALUES ('sender', '10001', '10001'), ('reader', '10002', '10002');
INSERT INTO group_members(group_id, user_id, joined_at) VALUES ('G1', 'reader', datetime('now', '-1 day'));`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return NewGroupMessageStore(db), db
}

func TestGroupMessageSequenceConcurrentAndAfterPaging(t *testing.T) {
	store, _ := newGroupSequenceTestStore(t)
	ctx := context.Background()
	const count = 24
	var wg sync.WaitGroup
	errs := make(chan error, count)
	for i := 0; i < count; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			err := store.Create(ctx, &GroupMessage{
				ID: fmt.Sprintf("m-%02d", index), GroupID: "G1", SenderID: "sender",
				Body: "x", MsgType: "text",
			})
			errs <- err
		}(i)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	first, err := store.ListByGroupAfterSeq(ctx, "G1", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(first) != 10 || first[0].GroupSeq != 1 || first[9].GroupSeq != 10 {
		t.Fatalf("unexpected first page: len=%d first=%d last=%d", len(first), first[0].GroupSeq, first[len(first)-1].GroupSeq)
	}
	second, err := store.ListByGroupAfterSeq(ctx, "G1", first[len(first)-1].GroupSeq, 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(second) != count-10 || second[0].GroupSeq != 11 || second[len(second)-1].GroupSeq != count {
		t.Fatalf("unexpected second page: len=%d first=%d last=%d", len(second), second[0].GroupSeq, second[len(second)-1].GroupSeq)
	}
}

func TestGroupMessageSequenceMigrationRepairsMissingCounter(t *testing.T) {
	store, db := newGroupSequenceTestStore(t)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if err := store.Create(ctx, &GroupMessage{ID: fmt.Sprintf("old-%d", i), GroupID: "G1", SenderID: "sender", Body: "x", MsgType: "text"}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.Exec(`DELETE FROM group_message_sequences WHERE group_id = 'G1'`); err != nil {
		t.Fatal(err)
	}
	if err := ensureGroupMessageSequenceSchema(db); err != nil {
		t.Fatal(err)
	}
	next := &GroupMessage{ID: "next", GroupID: "G1", SenderID: "sender", Body: "x", MsgType: "text"}
	if err := store.Create(ctx, next); err != nil {
		t.Fatal(err)
	}
	if next.GroupSeq != 4 {
		t.Fatalf("counter repair failed: got %d", next.GroupSeq)
	}
}

func TestGroupUnreadExcludesRecalledMessages(t *testing.T) {
	store, _ := newGroupSequenceTestStore(t)
	ctx := context.Background()
	for _, message := range []*GroupMessage{
		{ID: "visible", GroupID: "G1", SenderID: "sender", Body: "hello", MsgType: "text"},
		{ID: "recalled", GroupID: "G1", SenderID: "sender", Body: "gone", MsgType: "recall"},
	} {
		if err := store.Create(ctx, message); err != nil {
			t.Fatal(err)
		}
	}
	messages, err := store.ListUnreadByUser(ctx, "reader", 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(messages) != 1 || messages[0].ID != "visible" || messages[0].SenderNcuid != "10001" {
		t.Fatalf("unexpected unread messages: %+v", messages)
	}
}

func TestGroupMessageSequenceIsIndependentPerGroup(t *testing.T) {
	store, _ := newGroupSequenceTestStore(t)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		for _, groupID := range []string{"G1", "G2"} {
			message := &GroupMessage{ID: fmt.Sprintf("%s-%d", groupID, i), GroupID: groupID, SenderID: "sender", Body: "x", MsgType: "text"}
			if err := store.Create(ctx, message); err != nil {
				t.Fatal(err)
			}
			if message.GroupSeq != int64(i+1) {
				t.Fatalf("group %s got seq %d", groupID, message.GroupSeq)
			}
		}
	}
}
