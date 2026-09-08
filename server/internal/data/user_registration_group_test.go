package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
)

func TestCreateAndJoinGroupIsAtomic(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	defer db.Close()
	_, err = db.Exec(`
CREATE TABLE users (
 id TEXT PRIMARY KEY, uid TEXT UNIQUE, ncuid TEXT UNIQUE, uid_changed_at DATETIME,
 email TEXT UNIQUE, username TEXT UNIQUE, display_name TEXT, user_title TEXT,
 user_title_price INTEGER, avatar_url TEXT, signature TEXT, cover_url TEXT,
 password_hash TEXT, token_version INTEGER, coin_balance INTEGER, reputation_score INTEGER, ban_count INTEGER DEFAULT 0);
CREATE TABLE groups (id TEXT PRIMARY KEY);
CREATE TABLE group_members (
 group_id TEXT, user_id TEXT, role INTEGER, joined_at DATETIME, last_read_at DATETIME,
 PRIMARY KEY(group_id,user_id));
INSERT INTO groups(id) VALUES ('GRP-3TTO6Q');`)
	if err != nil {
		t.Fatal(err)
	}
	store := NewUserStore(db)
	user := &User{ID: "u1", UID: "U1", Email: "u1@qq.com", Username: "user1", DisplayName: "user1"}
	if err = store.CreateAndJoinGroup(context.Background(), user, "GRP-3TTO6Q"); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = db.Get(&count, `SELECT COUNT(1) FROM group_members WHERE group_id='GRP-3TTO6Q' AND user_id='u1'`); err != nil || count != 1 {
		t.Fatalf("新用户未加入官方群: count=%d err=%v", count, err)
	}
	if err = store.CreateAndJoinGroup(context.Background(), &User{ID: "u2", UID: "U2", Email: "u2@qq.com", Username: "user2"}, "MISSING"); err != ErrNotFound {
		t.Fatalf("官方群不存在时应回滚注册: %v", err)
	}
	if err = db.Get(&count, `SELECT COUNT(1) FROM users WHERE id='u2'`); err != nil || count != 0 {
		t.Fatalf("失败注册未回滚: count=%d err=%v", count, err)
	}
}
