package data

import (
	"context"
	"fmt"
	"testing"

	"github.com/jmoiron/sqlx"
)

func newChannelStoreTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	schema := []string{
		`CREATE TABLE users(id TEXT PRIMARY KEY,uid TEXT NOT NULL,username TEXT DEFAULT '',avatar_url TEXT DEFAULT '')`,
		`CREATE TABLE channels(id TEXT PRIMARY KEY,owner_id TEXT,name TEXT,handle TEXT DEFAULT '',avatar_url TEXT DEFAULT '',description TEXT DEFAULT '',visibility TEXT,status TEXT DEFAULT 'active',subscriber_count INTEGER DEFAULT 0,reactions_enabled INTEGER DEFAULT 1,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)`,
		`CREATE TABLE channel_sequences(channel_id TEXT PRIMARY KEY,post_seq INTEGER DEFAULT 0,event_seq INTEGER DEFAULT 0,subscriber_version INTEGER DEFAULT 0,settings_version INTEGER DEFAULT 0,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)`,
		`CREATE TABLE channel_subscriptions(channel_id TEXT,user_id TEXT,role TEXT,joined_post_seq INTEGER,last_read_post_seq INTEGER,notification_level TEXT DEFAULT 'all',muted_until DATETIME,joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(channel_id,user_id))`,
		`CREATE TABLE channel_posts(id TEXT PRIMARY KEY,channel_id TEXT,post_seq INTEGER,author_id TEXT,reply_to_post_id TEXT DEFAULT '',body TEXT,msg_type TEXT,media_url TEXT,thumb_url TEXT,view_count INTEGER DEFAULT 0,comment_count INTEGER DEFAULT 0,edited_at DATETIME,deleted_at DATETIME,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,UNIQUE(channel_id,post_seq))`,
		`CREATE TABLE channel_events(channel_id TEXT,event_seq INTEGER,event_type TEXT,ref_id TEXT,payload TEXT,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(channel_id,event_seq))`,
		`CREATE TABLE channel_post_reactions(channel_id TEXT,post_id TEXT,user_id TEXT,emoji TEXT,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(post_id,user_id))`,
		`CREATE TABLE account_events(id INTEGER PRIMARY KEY,user_id TEXT,pts INTEGER,event_type TEXT)`,
	}
	for _, statement := range schema {
		if _, err = db.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func TestChannelPublishDoesNotFanOutToTenThousandSubscribers(t *testing.T) {
	db := newChannelStoreTestDB(t)
	ctx := context.Background()
	if _, err := db.Exec(`INSERT INTO users(id,uid) VALUES('owner','OWNER')`); err != nil {
		t.Fatal(err)
	}
	store := NewChannelStore(db)
	if err := store.Create(ctx, &Channel{ID: "CH-1", OwnerID: "owner", Name: "Test", Visibility: "public"}); err != nil {
		t.Fatal(err)
	}
	tx := db.MustBegin()
	for i := 0; i < 10000; i++ {
		id := fmt.Sprintf("u-%05d", i)
		tx.MustExec(`INSERT INTO users(id,uid) VALUES(?,?)`, id, id)
		tx.MustExec(`INSERT INTO channel_subscriptions(channel_id,user_id,role,joined_post_seq,last_read_post_seq) VALUES('CH-1',?,'subscriber',0,0)`, id)
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}

	postSeq, eventSeq, err := store.CreatePost(ctx, &ChannelPost{ID: "P-1", ChannelID: "CH-1", AuthorID: "owner", Body: "hello", MsgType: "text"})
	if err != nil {
		t.Fatal(err)
	}
	if postSeq != 1 || eventSeq != 1 {
		t.Fatalf("sequences=%d/%d", postSeq, eventSeq)
	}
	var subscriptions, posts, events, accountEvents int
	db.Get(&subscriptions, `SELECT COUNT(*) FROM channel_subscriptions`)
	db.Get(&posts, `SELECT COUNT(*) FROM channel_posts`)
	db.Get(&events, `SELECT COUNT(*) FROM channel_events`)
	db.Get(&accountEvents, `SELECT COUNT(*) FROM account_events`)
	if subscriptions != 10001 || posts != 1 || events != 1 || accountEvents != 0 {
		t.Fatalf("unexpected rows subscriptions=%d posts=%d events=%d account_events=%d", subscriptions, posts, events, accountEvents)
	}
}

func TestChannelUnreadUsesReadAndJoinWatermarks(t *testing.T) {
	db := newChannelStoreTestDB(t)
	ctx := context.Background()
	db.MustExec(`INSERT INTO users(id,uid) VALUES('owner','OWNER'),('reader','READER'),('late','LATE')`)
	store := NewChannelStore(db)
	if err := store.Create(ctx, &Channel{ID: "CH-1", OwnerID: "owner", Name: "Test", Visibility: "public"}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Subscribe(ctx, "CH-1", "reader"); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.CreatePost(ctx, &ChannelPost{ID: "P-1", ChannelID: "CH-1", AuthorID: "owner", Body: "one", MsgType: "text"}); err != nil {
		t.Fatal(err)
	}
	state, err := store.State(ctx, "CH-1", "reader")
	if err != nil || state.UnreadCount != 1 {
		t.Fatalf("reader state=%+v err=%v", state, err)
	}
	if err = store.MarkRead(ctx, "CH-1", "reader", 1); err != nil {
		t.Fatal(err)
	}
	state, _ = store.State(ctx, "CH-1", "reader")
	if state.UnreadCount != 0 || state.LastReadPostSeq != 1 {
		t.Fatalf("read state=%+v", state)
	}
	if _, err = store.Subscribe(ctx, "CH-1", "late"); err != nil {
		t.Fatal(err)
	}
	late, _ := store.State(ctx, "CH-1", "late")
	if late.UnreadCount != 0 || late.JoinedPostSeq != 1 {
		t.Fatalf("late state=%+v", late)
	}
}
