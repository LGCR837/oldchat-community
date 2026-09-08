package data

import (
	"context"
	"errors"
	"testing"

	"github.com/jmoiron/sqlx"
)

func newMusicPlazaTestStore(t *testing.T) (*MusicPlazaStore, *sqlx.DB) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	if _, err = db.Exec(`CREATE TABLE music_plaza_items (
		id TEXT PRIMARY KEY,
		owner_id TEXT NOT NULL,
		name TEXT NOT NULL,
		song_url TEXT NOT NULL,
		cover_url TEXT NOT NULL DEFAULT '',
		lyrics_url TEXT NOT NULL DEFAULT '',
		size_bytes INTEGER NOT NULL DEFAULT 0,
		duration_ms INTEGER NOT NULL DEFAULT 0,
		play_count INTEGER NOT NULL DEFAULT 0,
		created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
	)`); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return NewMusicPlazaStore(db), db
}

func TestMusicPlazaUpdateMetadataByOwner(t *testing.T) {
	store, db := newMusicPlazaTestStore(t)
	if _, err := db.Exec(`INSERT INTO music_plaza_items(id, owner_id, name, song_url, cover_url)
		VALUES ('m1', 'u1', '旧名称', '/song.mp3', '/old.jpg')`); err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	if err := store.UpdateMetadataByOwner(ctx, "m1", "u1", "新名称", ""); err != nil {
		t.Fatal(err)
	}
	var name, cover string
	if err := db.QueryRow(`SELECT name, cover_url FROM music_plaza_items WHERE id = 'm1'`).Scan(&name, &cover); err != nil {
		t.Fatal(err)
	}
	if name != "新名称" || cover != "/old.jpg" {
		t.Fatalf("只更新名称结果异常：name=%q cover=%q", name, cover)
	}

	if err := store.UpdateMetadataByOwner(ctx, "m1", "u1", "最终名称", "/new.jpg"); err != nil {
		t.Fatal(err)
	}
	if err := db.QueryRow(`SELECT name, cover_url FROM music_plaza_items WHERE id = 'm1'`).Scan(&name, &cover); err != nil {
		t.Fatal(err)
	}
	if name != "最终名称" || cover != "/new.jpg" {
		t.Fatalf("名称与封面更新结果异常：name=%q cover=%q", name, cover)
	}
}

func TestMusicPlazaUpdateMetadataRejectsOtherOwner(t *testing.T) {
	store, db := newMusicPlazaTestStore(t)
	if _, err := db.Exec(`INSERT INTO music_plaza_items(id, owner_id, name, song_url, cover_url)
		VALUES ('m1', 'u1', '原名称', '/song.mp3', '/old.jpg')`); err != nil {
		t.Fatal(err)
	}

	err := store.UpdateMetadataByOwner(context.Background(), "m1", "u2", "越权名称", "/bad.jpg")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("预期拒绝非所有者更新，实际错误：%v", err)
	}
	var name, cover string
	if err := db.QueryRow(`SELECT name, cover_url FROM music_plaza_items WHERE id = 'm1'`).Scan(&name, &cover); err != nil {
		t.Fatal(err)
	}
	if name != "原名称" || cover != "/old.jpg" {
		t.Fatalf("越权更新修改了数据：name=%q cover=%q", name, cover)
	}
}
