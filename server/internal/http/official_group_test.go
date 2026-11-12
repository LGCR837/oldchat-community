package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jmoiron/sqlx"

	"metrochat/internal/auth"
	"metrochat/internal/data"
	"metrochat/internal/ws"
)

func newOfficialGroupTestAPI(t *testing.T) (*API, *sqlx.DB) {
	t.Helper()
	t.Chdir(filepath.Join("..", ".."))
	db, err := data.OpenDB(context.Background(), filepath.Join(t.TempDir(), "official-group.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	api := &API{
		db:      db,
		users:   data.NewUserStore(db),
		groups:  data.NewGroupStore(db),
		friends: data.NewFriendStore(db),
		wsHub:   ws.NewHub(),
	}
	return api, db
}

func insertTestUser(t *testing.T, db *sqlx.DB, id, uid, name string, shadow bool) {
	t.Helper()
	shadowFlag := 0
	if shadow {
		shadowFlag = 1
	}
	_, err := db.Exec(`INSERT INTO users (id, uid, ncuid, email, username, display_name, password_hash, is_shadow)
VALUES (?, ?, ?, ?, ?, ?, 'x', ?)`, id, uid, uid, id+"@example.com", id, name, shadowFlag)
	if err != nil {
		t.Fatal(err)
	}
}

func TestBootstrapOfficialGroupJoinsRealUsersOnly(t *testing.T) {
	api, db := newOfficialGroupTestAPI(t)
	insertTestUser(t, db, "u1", "USR1", "老王", false)
	insertTestUser(t, db, "u2", "USR2", "小李", false)
	insertTestUser(t, db, "s1", "USR9", "占位", true)

	api.bootstrapOfficialGroup(context.Background())

	var name string
	if err := db.Get(&name, `SELECT name FROM groups WHERE id = ?`, data.OfficialGroupID); err != nil {
		t.Fatalf("官方群未创建: %v", err)
	}
	if name != data.OfficialGroupName {
		t.Fatalf("官方群名称异常: %q", name)
	}

	var members []string
	if err := db.Select(&members, `SELECT user_id FROM group_members WHERE group_id = ? ORDER BY user_id`, data.OfficialGroupID); err != nil {
		t.Fatal(err)
	}
	if len(members) != 2 || members[0] != "u1" || members[1] != "u2" {
		t.Fatalf("成员应只有两个真实账号，实际 %v", members)
	}

	// 再跑一次不应重复插入，也不应报错。
	api.bootstrapOfficialGroup(context.Background())
	var count int
	if err := db.Get(&count, `SELECT COUNT(1) FROM group_members WHERE group_id = ?`, data.OfficialGroupID); err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatalf("重复初始化后成员数异常: %d", count)
	}
}

func TestJoinOfficialGroupCreatesGroupWhenMissing(t *testing.T) {
	api, db := newOfficialGroupTestAPI(t)
	insertTestUser(t, db, "u1", "USR1", "老王", false)

	api.joinOfficialGroup(context.Background(), "u1")

	var role int16
	if err := db.Get(&role, `SELECT role FROM group_members WHERE group_id = ? AND user_id = 'u1'`, data.OfficialGroupID); err != nil {
		t.Fatalf("首个账号应成为群主: %v", err)
	}
	if role != data.GroupRoleOwner {
		t.Fatalf("群主角色异常: %d", role)
	}

	insertTestUser(t, db, "u2", "USR2", "小李", false)
	api.joinOfficialGroup(context.Background(), "u2")
	var role2 int16
	if err := db.Get(&role2, `SELECT role FROM group_members WHERE group_id = ? AND user_id = 'u2'`, data.OfficialGroupID); err != nil {
		t.Fatal(err)
	}
	if role2 != 0 {
		t.Fatalf("普通成员角色异常: %d", role2)
	}
}

func TestGroupListSplitsMigratedAndPending(t *testing.T) {
	api, db := newOfficialGroupTestAPI(t)
	insertTestUser(t, db, "u1", "USR1", "老王", false)

	// 一个来自旧站的群，一个社区本地新建的群。
	if _, err := db.Exec(`INSERT INTO groups (id, official_group_id, imported, name, owner_id)
VALUES ('imported1', 'GRP-ABC123', 1, '老友群', 'u1')`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO groups (id, name, owner_id)
VALUES ('localnew1', '本地新群', 'u1')`); err != nil {
		t.Fatal(err)
	}
	for _, g := range []string{"imported1", "localnew1"} {
		if _, err := db.Exec(`INSERT INTO group_members (group_id, user_id, role) VALUES (?, 'u1', 2)`, g); err != nil {
			t.Fatal(err)
		}
	}

	req := httptest.NewRequest(http.MethodGet, "/groups/list", nil)
	req = req.WithContext(context.WithValue(req.Context(), claimsKey, &auth.AccessClaims{
		RegisteredClaims: jwt.RegisteredClaims{Subject: "u1"},
	}))
	rec := httptest.NewRecorder()
	api.handleGroupList(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("状态码异常: %d %s", rec.Code, rec.Body.String())
	}

	var resp struct {
		Groups []struct {
			GroupID  string `json:"group_id"`
			Migrated bool   `json:"migrated"`
		} `json:"groups"`
		PendingGroups []struct {
			GroupID  string `json:"group_id"`
			Migrated bool   `json:"migrated"`
		} `json:"pending_groups"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	flags := map[string]bool{}
	for _, g := range resp.Groups {
		flags[g.GroupID] = g.Migrated
	}
	if !flags["imported1"] {
		t.Fatalf("旧站的群应标记为已迁移: %+v", resp.Groups)
	}
	if flags["localnew1"] {
		t.Fatalf("本地新建的群不应标记为已迁移: %+v", resp.Groups)
	}
	if len(resp.PendingGroups) != 1 || resp.PendingGroups[0].GroupID != "localnew1" {
		t.Fatalf("未迁移分区异常: %+v", resp.PendingGroups)
	}
}

func TestFriendListSplitsShadowUsers(t *testing.T) {
	api, db := newOfficialGroupTestAPI(t)
	insertTestUser(t, db, "u1", "USR1", "老王", false)
	insertTestUser(t, db, "u2", "USR2", "小李", false)
	insertTestUser(t, db, "s1", "USR9", "还没注册", true)
	for _, f := range []string{"u2", "s1"} {
		if _, err := db.Exec(`INSERT INTO friends (user_id, friend_user_id) VALUES ('u1', ?)`, f); err != nil {
			t.Fatal(err)
		}
	}
	req := httptest.NewRequest(http.MethodGet, "/friends", nil)
	req = req.WithContext(context.WithValue(req.Context(), claimsKey, &auth.AccessClaims{
		RegisteredClaims: jwt.RegisteredClaims{Subject: "u1"},
	}))
	rec := httptest.NewRecorder()
	api.handleFriendList(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("状态码异常: %d %s", rec.Code, rec.Body.String())
	}

	var resp struct {
		Friends []struct {
			ID       string `json:"id"`
			Migrated bool   `json:"migrated"`
		} `json:"friends"`
		PendingUsers []struct {
			ID string `json:"id"`
		} `json:"pending_users"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(resp.PendingUsers) != 1 || resp.PendingUsers[0].ID != "s1" {
		t.Fatalf("未迁移联系人分区异常: %+v", resp.PendingUsers)
	}
	for _, f := range resp.Friends {
		if f.ID == "u2" && !f.Migrated {
			t.Fatalf("已注册好友应标记为已迁移: %+v", f)
		}
		if f.ID == "s1" && f.Migrated {
			t.Fatalf("占位账号不应标记为已迁移: %+v", f)
		}
	}
}
