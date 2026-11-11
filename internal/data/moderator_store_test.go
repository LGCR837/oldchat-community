package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func newModeratorTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`
CREATE TABLE moderators (
    id VARCHAR(32) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    nickname TEXT NOT NULL DEFAULT '',
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at DATETIME
)`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func TestModeratorStoreCRUD(t *testing.T) {
	db := newModeratorTestDB(t)
	store := NewModeratorStore(db)
	ctx := context.Background()

	// 1. Create
	mod, err := store.Create(ctx, "audit_user1", "hash123", "张审核")
	if err != nil {
		t.Fatalf("create moderator failed: %v", err)
	}
	if mod.ID == "" || mod.Username != "audit_user1" || mod.Nickname != "张审核" || !mod.IsActive {
		t.Fatalf("unexpected mod: %+v", mod)
	}

	// 2. Duplicate username error
	_, err = store.Create(ctx, "audit_user1", "hash456", "李审核")
	if err == nil {
		t.Fatalf("expected error on duplicate username, got nil")
	}

	// 3. GetByID
	got, err := store.GetByID(ctx, mod.ID)
	if err != nil || got == nil {
		t.Fatalf("GetByID failed: %v, got: %+v", err, got)
	}
	if got.Username != "audit_user1" {
		t.Fatalf("expected audit_user1, got %s", got.Username)
	}

	// 4. GetByUsername (case insensitive)
	gotByU, err := store.GetByUsername(ctx, "AUDIT_USER1")
	if err != nil || gotByU == nil {
		t.Fatalf("GetByUsername failed: %v, got: %+v", err, gotByU)
	}
	if gotByU.ID != mod.ID {
		t.Fatalf("expected ID %s, got %s", mod.ID, gotByU.ID)
	}

	// 5. Update Password
	if err := store.UpdatePassword(ctx, mod.ID, "newhash999"); err != nil {
		t.Fatalf("UpdatePassword failed: %v", err)
	}
	got, _ = store.GetByID(ctx, mod.ID)
	if got.PasswordHash != "newhash999" {
		t.Fatalf("expected newhash999, got %s", got.PasswordHash)
	}

	// 6. Update Active
	if err := store.UpdateActive(ctx, mod.ID, false); err != nil {
		t.Fatalf("UpdateActive false failed: %v", err)
	}
	got, _ = store.GetByID(ctx, mod.ID)
	if got.IsActive {
		t.Fatalf("expected inactive, got active")
	}

	// 7. Update LastLogin
	if err := store.UpdateLastLogin(ctx, mod.ID); err != nil {
		t.Fatalf("UpdateLastLogin failed: %v", err)
	}
	got, _ = store.GetByID(ctx, mod.ID)
	if got.LastLoginAt == nil {
		t.Fatalf("expected non-nil last_login_at")
	}

	// 8. List & Count
	count, err := store.Count(ctx)
	if err != nil || count != 1 {
		t.Fatalf("expected count 1, got %d, err: %v", count, err)
	}
	list, err := store.List(ctx)
	if err != nil || len(list) != 1 {
		t.Fatalf("expected list len 1, got %d, err: %v", len(list), err)
	}

	// 9. Delete
	if err := store.Delete(ctx, mod.ID); err != nil {
		t.Fatalf("Delete failed: %v", err)
	}
	count, _ = store.Count(ctx)
	if count != 0 {
		t.Fatalf("expected count 0 after delete, got %d", count)
	}
}
