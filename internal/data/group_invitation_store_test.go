package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
)

func newGroupInvitationTestStore(t *testing.T) (*GroupInvitationStore, *sqlx.DB) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	if _, err = db.Exec(`
CREATE TABLE users(id TEXT PRIMARY KEY,uid TEXT,display_name TEXT,user_title TEXT,avatar_url TEXT);
CREATE TABLE groups(id TEXT PRIMARY KEY,name TEXT,avatar_url TEXT,owner_id TEXT);
CREATE TABLE group_members(group_id TEXT,user_id TEXT,role INTEGER,joined_at DATETIME,last_read_at DATETIME,PRIMARY KEY(group_id,user_id));
CREATE TABLE group_invitations(id TEXT PRIMARY KEY,group_id TEXT,inviter_id TEXT,invitee_id TEXT,status INTEGER,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,responded_at DATETIME);
CREATE UNIQUE INDEX idx_group_invitation_pending ON group_invitations(group_id,invitee_id) WHERE status=0;
INSERT INTO users VALUES('owner','OWNER','群主','','');
INSERT INTO users VALUES('guest','GUEST','受邀人','','');
INSERT INTO users VALUES('other','OTHER','其他人','','');
INSERT INTO groups VALUES('GRP-1','测试群','','owner');
INSERT INTO group_members VALUES('GRP-1','owner',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
`); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return NewGroupInvitationStore(db), db
}

func TestGroupInvitationAcceptAddsInviteeOnlyAfterResponse(t *testing.T) {
	store, db := newGroupInvitationTestStore(t)
	ctx := context.Background()
	inv := &GroupInvitation{ID: "i1", GroupID: "GRP-1", InviterID: "owner", InviteeID: "guest", Status: 0}
	if err := store.Create(ctx, inv); err != nil {
		t.Fatal(err)
	}
	var count int
	_ = db.Get(&count, `SELECT COUNT(1) FROM group_members WHERE user_id='guest'`)
	if count != 0 {
		t.Fatal("邀请发出后不应直接入群")
	}
	if _, err := store.Respond(ctx, "i1", "other", true); err != ErrNotFound {
		t.Fatalf("非受邀人不应处理邀请: %v", err)
	}
	result, err := store.Respond(ctx, "i1", "guest", true)
	if err != nil {
		t.Fatal(err)
	}
	if result.GroupID != "GRP-1" || result.OwnerID != "owner" {
		t.Fatalf("返回群信息错误: %+v", result)
	}
	_ = db.Get(&count, `SELECT COUNT(1) FROM group_members WHERE user_id='guest' AND group_id='GRP-1'`)
	if count != 1 {
		t.Fatal("接受邀请后应加入群聊")
	}
}

func TestGroupInvitationDenyDoesNotAddMember(t *testing.T) {
	store, db := newGroupInvitationTestStore(t)
	ctx := context.Background()
	if err := store.Create(ctx, &GroupInvitation{ID: "i2", GroupID: "GRP-1", InviterID: "owner", InviteeID: "guest"}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Respond(ctx, "i2", "guest", false); err != nil {
		t.Fatal(err)
	}
	var count int
	_ = db.Get(&count, `SELECT COUNT(1) FROM group_members WHERE user_id='guest'`)
	if count != 0 {
		t.Fatal("拒绝邀请后不应加入群聊")
	}
}
