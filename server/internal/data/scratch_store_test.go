package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
)

func newScratchTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`CREATE TABLE users (
		id VARCHAR(32) PRIMARY KEY,
		uid VARCHAR(32) NOT NULL DEFAULT '',
		coin_balance INTEGER NOT NULL DEFAULT 0,
		reputation_score INTEGER NOT NULL DEFAULT 0,
		created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
		updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`CREATE TABLE user_daily_scratches (
		id VARCHAR(32) PRIMARY KEY,
		user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		scratch_date VARCHAR(16) NOT NULL,
		slots VARCHAR(64) NOT NULL DEFAULT '',
		total_reward INTEGER NOT NULL DEFAULT 0,
		created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
		UNIQUE(user_id, scratch_date))`)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO users (id, uid, coin_balance) VALUES ('user-1', 'U1', 100)`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func TestRollScratchSlots(t *testing.T) {
	for i := 0; i < 200; i++ {
		slots := rollScratchSlots()
		if len(slots) != ScratchSlotCount {
			t.Fatalf("expected %d slots, got %d", ScratchSlotCount, len(slots))
		}
		for _, v := range slots {
			if v != 0 && v != 1 && v != 5 && v != 10 && v != 20 {
				t.Fatalf("unexpected slot value %d", v)
			}
		}
	}
}

func TestParseJoinSlotsRoundTrip(t *testing.T) {
	slots := []int{0, 1, 5, 10, 20}
	joined := joinSlots(slots)
	parsed, err := parseScratchSlots(joined)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(parsed) != len(slots) {
		t.Fatalf("length mismatch")
	}
	for i := range slots {
		if parsed[i] != slots[i] {
			t.Fatalf("mismatch at %d: %d != %d", i, parsed[i], slots[i])
		}
	}
}

func TestDailyScratchIdempotent(t *testing.T) {
	db := newScratchTestDB(t)
	store := NewScratchStore(db)

	ctx := context.Background()
	first, err := store.DailyScratch(ctx, "user-1")
	if err != nil {
		t.Fatalf("first scratch error: %v", err)
	}
	if first.AlreadyDone {
		t.Fatalf("first scratch should not be already_done")
	}
	if len(first.Slots) != ScratchSlotCount {
		t.Fatalf("expected %d slots", ScratchSlotCount)
	}
	if first.CoinBalance != 100+first.TotalReward {
		t.Fatalf("balance should be 100+%d, got %d", first.TotalReward, first.CoinBalance)
	}

	second, err := store.DailyScratch(ctx, "user-1")
	if err != nil {
		t.Fatalf("second scratch error: %v", err)
	}
	if !second.AlreadyDone {
		t.Fatalf("second scratch should be already_done")
	}
	if second.TotalReward != first.TotalReward {
		t.Fatalf("idempotent reward mismatch: %d != %d", second.TotalReward, first.TotalReward)
	}
	if second.CoinBalance != first.CoinBalance {
		t.Fatalf("balance should not change on repeat: %d != %d", second.CoinBalance, first.CoinBalance)
	}

	status, err := store.QueryToday(ctx, "user-1")
	if err != nil {
		t.Fatalf("query error: %v", err)
	}
	if !status.AlreadyDone || status.TotalReward != first.TotalReward {
		t.Fatalf("query mismatch")
	}
}

func TestQueryTodayNotDone(t *testing.T) {
	db := newScratchTestDB(t)
	store := NewScratchStore(db)

	status, err := store.QueryToday(context.Background(), "user-1")
	if err != nil {
		t.Fatalf("query error: %v", err)
	}
	if status.AlreadyDone {
		t.Fatalf("should not be done before first scratch")
	}
	if status.TotalReward != 0 {
		t.Fatalf("reward should be 0 before scratch")
	}
}
