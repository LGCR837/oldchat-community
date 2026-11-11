package data

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/jmoiron/sqlx"
)

type CipStoreItem struct {
	ID            string   `json:"id" db:"id"`
	Name          string   `json:"name" db:"name"`
	Description   string   `json:"description" db:"description"`
	Version       int      `json:"version" db:"version"`
	AuthorUID     string   `json:"author_uid" db:"author_uid"`
	AuthorName    string   `json:"author_name" db:"author_name"`
	IconURL       string   `json:"icon_url" db:"icon_url"`
	CipFileURL    string   `json:"cip_file_url" db:"cip_file_url"`
	FileSize      int64    `json:"file_size" db:"file_size"`
	SHA256        string   `json:"sha256" db:"sha256"`
	Permissions   string   `json:"permissions" db:"permissions"` // JSON array string
	Status        string   `json:"status" db:"status"`           // pending / approved / rejected
	RejectReason  string   `json:"reject_reason" db:"reject_reason"`
	DownloadCount int      `json:"download_count" db:"download_count"`
	CreatedAt     int64    `json:"created_at" db:"created_at"`
	ReviewedAt    int64    `json:"reviewed_at" db:"reviewed_at"`
	PermList      []string `json:"perm_list,omitempty" db:"-"`
}

type CipStore struct {
	db *sqlx.DB
}

func NewCipStore(db *sqlx.DB) *CipStore {
	return &CipStore{db: db}
}

func (s *CipStore) EnsureTable(ctx context.Context) error {
	schema := `
	CREATE TABLE IF NOT EXISTS cip_store_items (
		id TEXT PRIMARY KEY,
		name TEXT NOT NULL,
		description TEXT NOT NULL DEFAULT '',
		version INTEGER NOT NULL DEFAULT 1,
		author_uid TEXT NOT NULL DEFAULT '',
		author_name TEXT NOT NULL DEFAULT '',
		icon_url TEXT NOT NULL DEFAULT '',
		cip_file_url TEXT NOT NULL,
		file_size INTEGER NOT NULL DEFAULT 0,
		sha256 TEXT NOT NULL DEFAULT '',
		permissions TEXT NOT NULL DEFAULT '[]',
		status TEXT NOT NULL DEFAULT 'pending',
		reject_reason TEXT NOT NULL DEFAULT '',
		download_count INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL DEFAULT 0,
		reviewed_at INTEGER NOT NULL DEFAULT 0
	);
	CREATE INDEX IF NOT EXISTS idx_cip_store_status ON cip_store_items(status, created_at DESC);
	`
	_, err := s.db.ExecContext(ctx, schema)
	return err
}

func (s *CipStore) SubmitCIP(ctx context.Context, item CipStoreItem) error {
	now := time.Now().Unix()
	if item.CreatedAt == 0 {
		item.CreatedAt = now
	}
	query := `
	INSERT INTO cip_store_items (
		id, name, description, version, author_uid, author_name, icon_url,
		cip_file_url, file_size, sha256, permissions, status, reject_reason,
		download_count, created_at, reviewed_at
	) VALUES (
		:id, :name, :description, :version, :author_uid, :author_name, :icon_url,
		:cip_file_url, :file_size, :sha256, :permissions, :status, :reject_reason,
		:download_count, :created_at, :reviewed_at
	)
	ON CONFLICT(id) DO UPDATE SET
		name = excluded.name,
		description = excluded.description,
		version = excluded.version,
		author_uid = excluded.author_uid,
		author_name = excluded.author_name,
		icon_url = excluded.icon_url,
		cip_file_url = excluded.cip_file_url,
		file_size = excluded.file_size,
		sha256 = excluded.sha256,
		permissions = excluded.permissions,
		status = 'pending',
		reject_reason = '',
		created_at = excluded.created_at,
		reviewed_at = 0
	`
	_, err := s.db.NamedExecContext(ctx, query, item)
	return err
}

func (s *CipStore) ListApproved(ctx context.Context, limit, offset int) ([]CipStoreItem, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	query := `
	SELECT id, name, description, version, author_uid, author_name, icon_url,
	       cip_file_url, file_size, sha256, permissions, status, reject_reason,
	       download_count, created_at, reviewed_at
	FROM cip_store_items
	WHERE status = 'approved'
	ORDER BY download_count DESC, created_at DESC
	LIMIT $1 OFFSET $2
	`
	var items []CipStoreItem
	err := s.db.SelectContext(ctx, &items, query, limit, offset)
	if err != nil && err != sql.ErrNoRows {
		return nil, err
	}
	for i := range items {
		var perms []string
		if err := json.Unmarshal([]byte(items[i].Permissions), &perms); err == nil {
			items[i].PermList = perms
		} else {
			items[i].PermList = []string{}
		}
	}
	return items, nil
}

func (s *CipStore) ListSubmissions(ctx context.Context, status string, limit, offset int) ([]CipStoreItem, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	var query string
	var args []interface{}
	if status != "" && status != "all" {
		query = `
		SELECT id, name, description, version, author_uid, author_name, icon_url,
		       cip_file_url, file_size, sha256, permissions, status, reject_reason,
		       download_count, created_at, reviewed_at
		FROM cip_store_items
		WHERE status = $1
		ORDER BY created_at DESC
		LIMIT $2 OFFSET $3
		`
		args = []interface{}{status, limit, offset}
	} else {
		query = `
		SELECT id, name, description, version, author_uid, author_name, icon_url,
		       cip_file_url, file_size, sha256, permissions, status, reject_reason,
		       download_count, created_at, reviewed_at
		FROM cip_store_items
		ORDER BY CASE WHEN status = 'pending' THEN 0 ELSE 1 END, created_at DESC
		LIMIT $1 OFFSET $2
		`
		args = []interface{}{limit, offset}
	}
	var items []CipStoreItem
	err := s.db.SelectContext(ctx, &items, query, args...)
	if err != nil && err != sql.ErrNoRows {
		return nil, err
	}
	for i := range items {
		var perms []string
		if err := json.Unmarshal([]byte(items[i].Permissions), &perms); err == nil {
			items[i].PermList = perms
		} else {
			items[i].PermList = []string{}
		}
	}
	return items, nil
}

func (s *CipStore) GetByID(ctx context.Context, id string) (*CipStoreItem, error) {
	query := `
	SELECT id, name, description, version, author_uid, author_name, icon_url,
	       cip_file_url, file_size, sha256, permissions, status, reject_reason,
	       download_count, created_at, reviewed_at
	FROM cip_store_items
	WHERE id = $1
	`
	var item CipStoreItem
	err := s.db.GetContext(ctx, &item, query, id)
	if err != nil {
		return nil, err
	}
	var perms []string
	if err := json.Unmarshal([]byte(item.Permissions), &perms); err == nil {
		item.PermList = perms
	}
	return &item, nil
}

func (s *CipStore) ReviewSubmission(ctx context.Context, id, status, reason string) error {
	if status != "approved" && status != "rejected" {
		return fmt.Errorf("invalid status: %s", status)
	}
	now := time.Now().Unix()
	query := `
	UPDATE cip_store_items
	SET status = $1, reject_reason = $2, reviewed_at = $3
	WHERE id = $4
	`
	_, err := s.db.ExecContext(ctx, query, status, reason, now, id)
	return err
}

func (s *CipStore) IncrementDownloadCount(ctx context.Context, id string) error {
	query := `UPDATE cip_store_items SET download_count = download_count + 1 WHERE id = $1`
	_, err := s.db.ExecContext(ctx, query, id)
	return err
}

func (s *CipStore) Delete(ctx context.Context, id string) error {
	query := `DELETE FROM cip_store_items WHERE id = $1`
	_, err := s.db.ExecContext(ctx, query, id)
	return err
}
