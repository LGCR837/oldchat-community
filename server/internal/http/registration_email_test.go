package httpapi

import (
	"context"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/data"
)

func TestSupportedRegistrationEmailDomains(t *testing.T) {
	for _, email := range []string{"user@qq.com", "user@vip.qq.com", "TEST.123@QQ.COM"} {
		if !isSupportedRegistrationEmail(email) {
			t.Errorf("应支持注册邮箱: %s", email)
		}
	}
	for _, email := range []string{"user@gmail.com", "user@126.com", "user@163.com", "user@126.com.evil", "user@qq.com.evil"} {
		if isSupportedRegistrationEmail(email) {
			t.Errorf("不应支持注册邮箱: %s", email)
		}
	}
}

func TestDeletedAccountEmailCooldownStore(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	defer db.Close()

	if _, err := db.Exec(`
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(32) PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(64) UNIQUE NOT NULL,
    uid VARCHAR(32) UNIQUE NOT NULL,
    ncuid VARCHAR(32) UNIQUE NOT NULL
);
CREATE TABLE IF NOT EXISTS title_catalog (
    id VARCHAR(32) PRIMARY KEY,
    title VARCHAR(64) UNIQUE NOT NULL,
    owner_id VARCHAR(32)
);
CREATE TABLE IF NOT EXISTS deleted_account_cooldowns (
    email VARCHAR(255) PRIMARY KEY,
    deleted_user_id VARCHAR(64) NOT NULL DEFAULT '',
    deleted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cooldown_until DATETIME NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_deleted_email_cooldown ON deleted_account_cooldowns (cooldown_until);
`); err != nil {
		t.Fatal(err)
	}

	userStore := data.NewUserStore(db)
	ctx := context.Background()

	// 插入用户
	if _, err := db.Exec(`INSERT INTO users (id, email, username, uid, ncuid) VALUES ('u100', 'deleted@qq.com', 'deluser', 'UID-DEL', 'NCUID-DEL')`); err != nil {
		t.Fatal(err)
	}

	// 初始状态下无冷却
	inCd, _, err := userStore.GetDeletedAccountCooldown(ctx, "deleted@qq.com")
	if err != nil || inCd {
		t.Fatalf("初始状态不应在冷却期内: inCd=%v err=%v", inCd, err)
	}

	// 执行注销
	if err := userStore.DeleteByIDAndReleaseAssets(ctx, "u100"); err != nil {
		t.Fatalf("注销失败: %v", err)
	}

	// 注销后应在冷却期内
	inCd, until, err := userStore.GetDeletedAccountCooldown(ctx, "deleted@qq.com")
	if err != nil || !inCd {
		t.Fatalf("注销后应在 10 天冷却期内: inCd=%v err=%v", inCd, err)
	}
	if time.Until(until) < 9*24*time.Hour {
		t.Fatalf("冷却到期时间异常: until=%v", until)
	}
}
