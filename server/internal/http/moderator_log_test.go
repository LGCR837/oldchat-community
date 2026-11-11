package httpapi

import (
	"context"
	"testing"
	"time"

	"metrochat/internal/data"

	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func TestModeratorLogStore(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatalf("打开内存数据库失败: %v", err)
	}
	defer db.Close()

	if _, err := db.Exec(`
	CREATE TABLE moderator_action_logs (
		id VARCHAR(32) PRIMARY KEY,
		moderator_id VARCHAR(32) NOT NULL DEFAULT '',
		moderator_name VARCHAR(64) NOT NULL DEFAULT '',
		action_type VARCHAR(32) NOT NULL DEFAULT '',
		target_type VARCHAR(32) NOT NULL DEFAULT '',
		target_id VARCHAR(64) NOT NULL DEFAULT '',
		action_detail TEXT NOT NULL DEFAULT '',
		ip VARCHAR(64) NOT NULL DEFAULT '',
		created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
	);
	`); err != nil {
		t.Fatalf("创建表失败: %v", err)
	}

	store := data.NewModeratorLogStore(db)
	ctx := context.Background()

	// 记录日志
	err = store.Log(ctx, "mod-1", "审核员小王", "report_ban", "report", "rep-101", "封禁用户 USR-1 24h", "127.0.0.1")
	if err != nil {
		t.Fatalf("记录日志失败: %v", err)
	}

	time.Sleep(10 * time.Millisecond)

	err = store.Log(ctx, "admin", "admin (超级管理员)", "public_court_review", "case", "case-202", "裁决案件为 ban，下架歌曲", "127.0.0.1")
	if err != nil {
		t.Fatalf("记录日志失败: %v", err)
	}

	// 统计总数
	count, err := store.Count(ctx)
	if err != nil {
		t.Fatalf("统计日志总数失败: %v", err)
	}
	if count != 2 {
		t.Fatalf("期望日志总数为 2，实际为 %d", count)
	}

	// 分页查询
	logs, err := store.ListRecentPage(ctx, 10, 0)
	if err != nil {
		t.Fatalf("查询日志分页失败: %v", err)
	}
	if len(logs) != 2 {
		t.Fatalf("期望返回 2 条日志，实际返回 %d", len(logs))
	}
	if logs[0].ModeratorID != "admin" {
		t.Fatalf("最新日志操作人期望为 admin，实际为 %s", logs[0].ModeratorID)
	}
	if logs[1].ModeratorID != "mod-1" {
		t.Fatalf("第二条日志操作人期望为 mod-1，实际为 %s", logs[1].ModeratorID)
	}
}
