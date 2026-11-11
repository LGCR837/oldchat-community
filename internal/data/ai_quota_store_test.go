package data

import (
	"context"
	"errors"
	"testing"

	"github.com/jmoiron/sqlx"
)

func newAIQuotaTestStore(t *testing.T) (*AIQuotaStore, *sqlx.DB) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`CREATE TABLE ai_token_quotas (
		user_id TEXT PRIMARY KEY, token_limit INTEGER NOT NULL DEFAULT 0,
		used_tokens INTEGER NOT NULL DEFAULT 0, reserved_tokens INTEGER NOT NULL DEFAULT 0,
		updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`)
	if err != nil {
		t.Fatal(err)
	}
	// 测试用户显式授予额度（生产默认 0）。
	_, err = db.Exec(`INSERT INTO ai_token_quotas (user_id, token_limit) VALUES ('u1', 5000000)`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return NewAIQuotaStore(db), db
}

func TestAIQuotaReserveAndSettle(t *testing.T) {
	store, _ := newAIQuotaTestStore(t)
	ctx := context.Background()
	if err := store.Reserve(ctx, "u1", 1000); err != nil {
		t.Fatal(err)
	}
	quota, err := store.Get(ctx, "u1")
	if err != nil {
		t.Fatal(err)
	}
	if quota.ReservedTokens != 1000 || quota.Remaining() != 5000000-1000 {
		t.Fatalf("unexpected reserved quota: %+v", quota)
	}
	if err = store.Settle(ctx, "u1", 1000, 123); err != nil {
		t.Fatal(err)
	}
	quota, _ = store.Get(ctx, "u1")
	if quota.ReservedTokens != 0 || quota.UsedTokens != 123 || quota.Remaining() != 5000000-123 {
		t.Fatalf("unexpected settled quota: %+v", quota)
	}
}

func TestAIQuotaRejectsOverLimitAndRefunds(t *testing.T) {
	store, db := newAIQuotaTestStore(t)
	ctx := context.Background()
	if _, err := db.Exec(`INSERT INTO ai_token_quotas(user_id, token_limit) VALUES ('u2', 100)`); err != nil {
		t.Fatal(err)
	}
	if err := store.Reserve(ctx, "u2", 101); !errors.Is(err, ErrAIQuotaExceeded) {
		t.Fatalf("expected quota error, got %v", err)
	}
	if err := store.Reserve(ctx, "u2", 80); err != nil {
		t.Fatal(err)
	}
	if err := store.Settle(ctx, "u2", 80, 0); err != nil {
		t.Fatal(err)
	}
	quota, _ := store.Get(ctx, "u2")
	if quota.Remaining() != 100 || quota.UsedTokens != 0 || quota.ReservedTokens != 0 {
		t.Fatalf("refund failed: %+v", quota)
	}
}
