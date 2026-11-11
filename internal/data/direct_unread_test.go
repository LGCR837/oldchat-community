package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
)

func TestDirectUnreadOffsetMapsAllSelectedColumns(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	defer db.Close()
	_, err = db.Exec(`
CREATE TABLE users (id TEXT PRIMARY KEY, uid TEXT NOT NULL, ncuid TEXT NOT NULL DEFAULT '');
CREATE TABLE direct_threads (id TEXT PRIMARY KEY, user_a_id TEXT NOT NULL, user_b_id TEXT NOT NULL);
CREATE TABLE direct_messages (
 id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, sender_id TEXT NOT NULL, body TEXT NOT NULL,
 msg_type TEXT NOT NULL DEFAULT 'text', media_url TEXT NOT NULL DEFAULT '', thumb_url TEXT NOT NULL DEFAULT '',
 duration_ms INTEGER NOT NULL DEFAULT 0, burn_after_seconds INTEGER NOT NULL DEFAULT 0,
 burn_start_at DATETIME NULL, burned_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 delivered_at DATETIME NULL, read_at DATETIME NULL);
CREATE INDEX idx_direct_threads_user_a ON direct_threads(user_a_id, id);
CREATE INDEX idx_direct_threads_user_b ON direct_threads(user_b_id, id);
CREATE INDEX idx_direct_unread_thread_created ON direct_messages(thread_id, created_at DESC, sender_id)
 WHERE read_at IS NULL AND burned_at IS NULL AND msg_type != 'recall';
INSERT INTO users(id, uid, ncuid) VALUES ('a','10001','N10001'),('b','10002','N10002');
INSERT INTO direct_threads(id,user_a_id,user_b_id) VALUES ('t','a','b');
INSERT INTO direct_messages(id,thread_id,sender_id,body,msg_type,burn_after_seconds,burn_start_at)
 VALUES ('m','t','b','hello','text',30,CURRENT_TIMESTAMP);`)
	if err != nil {
		t.Fatal(err)
	}

	messages, err := NewDirectStore(db).ListUnreadByUserOffset(context.Background(), "a", 50, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(messages) != 1 {
		t.Fatalf("unexpected count: %d", len(messages))
	}
	message := messages[0]
	if message.SortSeq <= 0 || message.SenderNcuid != "N10002" || message.BurnStartAt == nil {
		t.Fatalf("unexpected mapped message: %+v", message)
	}
}
