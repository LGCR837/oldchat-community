package data

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
)

type FileAsset struct {
	ID             string    `db:"id"`
	SHA256         string    `db:"sha256"`
	RelativePath   string    `db:"relative_path"`
	SizeBytes      int64     `db:"size_bytes"`
	OriginalName   string    `db:"original_name"`
	ContentType    string    `db:"content_type"`
	CreatedBy      string    `db:"created_by"`
	ReferenceCount int64     `db:"reference_count"`
	CreatedAt      time.Time `db:"created_at"`
	LastUsedAt     time.Time `db:"last_used_at"`
}

type FileAssetStore struct {
	db *sqlx.DB
}

func NewFileAssetStore(db *sqlx.DB) *FileAssetStore {
	return &FileAssetStore{db: db}
}

func (s *FileAssetStore) EnsureSchema() error {
	if s == nil || s.db == nil {
		return errors.New("file asset store is not configured")
	}
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS file_assets (
    id TEXT PRIMARY KEY,
    sha256 TEXT NOT NULL UNIQUE,
    relative_path TEXT NOT NULL UNIQUE,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    original_name TEXT NOT NULL DEFAULT '',
    content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    created_by TEXT NOT NULL DEFAULT '',
    reference_count INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_file_assets_sha256 ON file_assets(sha256);
CREATE INDEX IF NOT EXISTS idx_file_assets_created_by ON file_assets(created_by, created_at DESC);
`)
	return err
}

func (s *FileAssetStore) GetByID(ctx context.Context, id string) (*FileAsset, error) {
	if s == nil || s.db == nil {
		return nil, errors.New("file asset store is not configured")
	}
	var asset FileAsset
	err := s.db.GetContext(ctx, &asset, `
SELECT id, sha256, relative_path, size_bytes, original_name, content_type,
       created_by, reference_count, created_at, last_used_at
FROM file_assets WHERE id = ? LIMIT 1`, strings.TrimSpace(id))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &asset, nil
}

func (s *FileAssetStore) GetBySHA256(ctx context.Context, sha256 string) (*FileAsset, error) {
	if s == nil || s.db == nil {
		return nil, errors.New("file asset store is not configured")
	}
	var asset FileAsset
	err := s.db.GetContext(ctx, &asset, `
SELECT id, sha256, relative_path, size_bytes, original_name, content_type,
       created_by, reference_count, created_at, last_used_at
FROM file_assets WHERE sha256 = ? LIMIT 1`, strings.ToLower(strings.TrimSpace(sha256)))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &asset, nil
}

// PutIfAbsent atomically creates an asset or returns the existing row for the same hash.
func (s *FileAssetStore) PutIfAbsent(ctx context.Context, asset *FileAsset) (*FileAsset, bool, error) {
	if s == nil || s.db == nil || asset == nil {
		return nil, false, errors.New("invalid file asset")
	}
	asset.SHA256 = strings.ToLower(strings.TrimSpace(asset.SHA256))
	result, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO file_assets
(id, sha256, relative_path, size_bytes, original_name, content_type, created_by, reference_count)
VALUES (?, ?, ?, ?, ?, ?, ?, 1)`, asset.ID, asset.SHA256, asset.RelativePath, asset.SizeBytes,
		asset.OriginalName, asset.ContentType, asset.CreatedBy)
	if err != nil {
		return nil, false, err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return nil, false, err
	}
	stored, err := s.GetBySHA256(ctx, asset.SHA256)
	if err != nil {
		return nil, false, err
	}
	return stored, rows > 0, nil
}

func (s *FileAssetStore) MarkUsed(ctx context.Context, sha256 string) error {
	if s == nil || s.db == nil {
		return errors.New("file asset store is not configured")
	}
	_, err := s.db.ExecContext(ctx, `
UPDATE file_assets
SET reference_count = reference_count + 1, last_used_at = CURRENT_TIMESTAMP
WHERE sha256 = ?`, strings.ToLower(strings.TrimSpace(sha256)))
	return err
}
