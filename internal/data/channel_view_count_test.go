package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func TestIncrementViewsOnReadDeduplicates(t *testing.T) {
	db := mustOpenTestDB(t)
	defer db.Close()

	ctx := context.Background()
	_, err := db.Exec(`
CREATE TABLE channels (id VARCHAR(32) PRIMARY KEY, name VARCHAR(64), status TEXT NOT NULL DEFAULT 'active');
CREATE TABLE channel_sequences (channel_id VARCHAR(32) PRIMARY KEY, post_seq BIGINT NOT NULL DEFAULT 0);
CREATE TABLE channel_subscriptions (
  channel_id VARCHAR(32) NOT NULL,
  user_id VARCHAR(32) NOT NULL,
  role TEXT NOT NULL DEFAULT 'subscriber',
  joined_post_seq BIGINT NOT NULL DEFAULT 0,
  last_read_post_seq BIGINT NOT NULL DEFAULT 0,
  viewed_post_seq BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(channel_id, user_id));
CREATE TABLE channel_posts (
  id VARCHAR(32) PRIMARY KEY, channel_id VARCHAR(32) NOT NULL,
  post_seq BIGINT NOT NULL, author_id VARCHAR(32) NOT NULL,
  body TEXT NOT NULL DEFAULT '', msg_type VARCHAR(16) NOT NULL DEFAULT 'text',
  view_count BIGINT NOT NULL DEFAULT 0, comment_count BIGINT NOT NULL DEFAULT 0,
  deleted_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO channels(id,name) VALUES('CH-1','t');
INSERT INTO channel_sequences(channel_id,post_seq) VALUES('CH-1',3);
INSERT INTO channel_subscriptions(channel_id,user_id) VALUES('CH-1','U-1');
INSERT INTO channel_posts(id,channel_id,post_seq,author_id) VALUES('P1','CH-1',1,'A'),('P2','CH-1',2,'A'),('P3','CH-1',3,'A');
`)
	if err != nil {
		t.Fatal(err)
	}

	store := NewChannelStore(db)
	if err := store.IncrementViewsOnRead(ctx, "CH-1", "U-1", 3); err != nil {
		t.Fatal(err)
	}
	// 重复上报不应重复计数
	if err := store.IncrementViewsOnRead(ctx, "CH-1", "U-1", 3); err != nil {
		t.Fatal(err)
	}
	var total int64
	if err := db.GetContext(ctx, &total, `SELECT SUM(view_count) FROM channel_posts WHERE channel_id='CH-1'`); err != nil {
		t.Fatal(err)
	}
	if total != 3 {
		t.Fatalf("expected 3 views, got %d", total)
	}
	var viewed int64
	if err := db.GetContext(ctx, &viewed, `SELECT viewed_post_seq FROM channel_subscriptions WHERE channel_id='CH-1' AND user_id='U-1'`); err != nil {
		t.Fatal(err)
	}
	if viewed != 3 {
		t.Fatalf("expected viewed_seq 3, got %d", viewed)
	}
	// 新帖子发布后用户再读，只递增新区间
	if _, err := db.Exec(`INSERT INTO channel_posts(id,channel_id,post_seq,author_id) VALUES('P4','CH-1',4,'A')`); err != nil {
		t.Fatal(err)
	}
	if err := store.IncrementViewsOnRead(ctx, "CH-1", "U-1", 4); err != nil {
		t.Fatal(err)
	}
	var p1views, p4views int64
	if err := db.GetContext(ctx, &p1views, `SELECT view_count FROM channel_posts WHERE id='P1'`); err != nil {
		t.Fatal(err)
	}
	if err := db.GetContext(ctx, &p4views, `SELECT view_count FROM channel_posts WHERE id='P4'`); err != nil {
		t.Fatal(err)
	}
	if p1views != 1 || p4views != 1 {
		t.Fatalf("expected P1=1,P4=1 got P1=%d,P4=%d", p1views, p4views)
	}
}

func mustOpenTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	return db
}
