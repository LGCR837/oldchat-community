package data

import (
	"context"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"
)

func newBugReportTestStore(t *testing.T) (*BugReportStore, *sqlx.DB) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	store := NewBugReportStore(db)
	if err := store.EnsureTable(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return store, db
}

func TestBugReportCountAndDeleteAll(t *testing.T) {
	store, _ := newBugReportTestStore(t)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if err := store.Create(ctx, &BugReport{UserUID: "10001", Content: "测试反馈"}); err != nil {
			t.Fatal(err)
		}
	}
	total, err := store.Count(ctx)
	if err != nil || total != 3 {
		t.Fatalf("反馈总数异常：total=%d err=%v", total, err)
	}
	deleted, err := store.DeleteAll(ctx)
	if err != nil || deleted != 3 {
		t.Fatalf("清空结果异常：deleted=%d err=%v", deleted, err)
	}
	total, err = store.Count(ctx)
	if err != nil || total != 0 {
		t.Fatalf("清空后总数异常：total=%d err=%v", total, err)
	}
}

func TestBugReportReopenClearsResolvedTime(t *testing.T) {
	store, db := newBugReportTestStore(t)
	ctx := context.Background()
	report := &BugReport{UserUID: "10001", Content: "测试反馈"}
	if err := store.Create(ctx, report); err != nil {
		t.Fatal(err)
	}
	if err := store.SetStatus(ctx, report.ID, "resolved", "已修复"); err != nil {
		t.Fatal(err)
	}
	var resolvedAt interface{}
	if err := db.QueryRow(`SELECT resolved_at FROM bug_reports WHERE id = ?`, report.ID).Scan(&resolvedAt); err != nil {
		t.Fatal(err)
	}
	if resolvedAt == nil {
		t.Fatal("标记已解决后缺少解决时间")
	}
	// 确保时间写入和重新打开不是同一个数据库操作时刻。
	time.Sleep(time.Millisecond)
	if err := store.SetStatus(ctx, report.ID, "open", "重新检查"); err != nil {
		t.Fatal(err)
	}
	if err := db.QueryRow(`SELECT resolved_at FROM bug_reports WHERE id = ?`, report.ID).Scan(&resolvedAt); err != nil {
		t.Fatal(err)
	}
	if resolvedAt != nil {
		t.Fatalf("重新打开后解决时间未清空：%v", resolvedAt)
	}
}
