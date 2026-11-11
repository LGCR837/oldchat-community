package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
)

func TestLookupMembersSQL(t *testing.T) {
	// 用内存 sqlite 模拟群成员查询，验证 sqlx.In 用法（全 ? 占位符）。
	db, err := sqlx.Open("sqlite3", ":memory:")
	if err != nil {
		t.Skip("sqlite3 driver unavailable: " + err.Error())
	}
	_, err = db.Exec(`CREATE TABLE users (id TEXT PRIMARY KEY, uid TEXT, ncuid TEXT, username TEXT, display_name TEXT, user_title TEXT, avatar_url TEXT)`)
	if err != nil {
		t.Skip("create users failed: " + err.Error())
	}
	_, err = db.Exec(`CREATE TABLE group_members (group_id TEXT, user_id TEXT, role INTEGER, joined_at DATETIME)`)
	if err != nil {
		t.Skip("create group_members failed: " + err.Error())
	}
	_, _ = db.Exec(`INSERT INTO users VALUES ('u1','U1','NC1','alice','Alice','','')`)
	_, _ = db.Exec(`INSERT INTO group_members VALUES ('G1','u1',1,CURRENT_TIMESTAMP)`)

	store := &GroupStore{db: db}
	members, err := store.LookupMembersByNcuids(context.Background(), "G1", []string{"NC1"})
	if err != nil {
		t.Fatalf("lookup failed: %v", err)
	}
	if len(members) != 1 {
		t.Fatalf("expected 1 member, got %d", len(members))
	}
	if members[0].DisplayName != "Alice" {
		t.Fatalf("unexpected display name: %s", members[0].DisplayName)
	}
}
