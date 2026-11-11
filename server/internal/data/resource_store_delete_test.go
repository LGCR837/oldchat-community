package data

import (
	"context"
	"testing"

	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func TestDeleteSectionAndItemsWithoutForeignKeys(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, statement := range []string{
		`PRAGMA foreign_keys = OFF`,
		`CREATE TABLE resource_sections (id TEXT PRIMARY KEY)`,
		`CREATE TABLE resource_items (id TEXT PRIMARY KEY, section_id TEXT, uploader_id TEXT, name TEXT, url TEXT, size_bytes INTEGER, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)`,
		`CREATE TABLE resource_likes (item_id TEXT, user_id TEXT)`,
		`CREATE TABLE resource_comments (id TEXT, item_id TEXT)`,
		`CREATE TABLE resource_reports (id TEXT, item_id TEXT)`,
		`INSERT INTO resource_sections(id) VALUES ('section-1')`,
		`INSERT INTO resource_items(id,section_id,uploader_id,name,url,size_bytes) VALUES ('item-1','section-1','user-1','文件.zip','/v1/uploads/filewearhouse/a.zip',1024)`,
		`INSERT INTO resource_likes(item_id,user_id) VALUES ('item-1','user-2')`,
		`INSERT INTO resource_comments(id,item_id) VALUES ('comment-1','item-1')`,
		`INSERT INTO resource_reports(id,item_id) VALUES ('report-1','item-1')`,
	} {
		if _, err := db.Exec(statement); err != nil {
			t.Fatal(statement, err)
		}
	}

	store := NewResourceStore(db)
	items, err := store.DeleteSectionAndItems(context.Background(), "section-1")
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].SizeBytes != 1024 {
		t.Fatalf("unexpected deleted items: %#v", items)
	}
	for _, table := range []string{"resource_sections", "resource_items", "resource_likes", "resource_comments", "resource_reports"} {
		var count int
		if err := db.Get(&count, "SELECT COUNT(*) FROM "+table); err != nil {
			t.Fatal(err)
		}
		if count != 0 {
			t.Fatalf("%s still has %d rows", table, count)
		}
	}
}
