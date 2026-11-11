package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
)

func TestDeleteByIDAndReleaseAssets(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	defer db.Close()
	if _, err = db.Exec(`
CREATE TABLE users (id TEXT PRIMARY KEY, email TEXT UNIQUE, username TEXT UNIQUE, uid TEXT UNIQUE, ncuid TEXT UNIQUE);
CREATE TABLE title_catalog (id TEXT PRIMARY KEY, title TEXT UNIQUE, owner_id TEXT REFERENCES users(id) ON DELETE SET NULL);
CREATE TABLE deleted_account_cooldowns (email TEXT PRIMARY KEY, deleted_user_id TEXT, deleted_at DATETIME, cooldown_until DATETIME);
INSERT INTO users(id,email,username,uid,ncuid) VALUES ('u1','u1@example.com','user1','UID1','NCUID1');
INSERT INTO title_catalog(id,title,owner_id) VALUES ('t1','专属称号','u1');
`); err != nil {
		t.Fatal(err)
	}
	store := NewUserStore(db)
	if err = store.DeleteByIDAndReleaseAssets(context.Background(), "u1"); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = db.Get(&count, `SELECT COUNT(1) FROM users WHERE id='u1'`); err != nil || count != 0 {
		t.Fatalf("用户未删除: count=%d err=%v", count, err)
	}
	if err = db.Get(&count, `SELECT COUNT(1) FROM title_catalog WHERE title='专属称号'`); err != nil || count != 0 {
		t.Fatalf("专属称号未释放: count=%d err=%v", count, err)
	}
	// 验证邮箱进入冷却期
	inCooldown, until, err := store.GetDeletedAccountCooldown(context.Background(), "u1@example.com")
	if err != nil || !inCooldown {
		t.Fatalf("注销邮箱应在冷却期内: inCooldown=%v until=%v err=%v", inCooldown, until, err)
	}
}
