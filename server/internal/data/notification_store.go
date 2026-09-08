package data

import (
	"context"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
)

const SystemNotificationUID = "SYSTEM"

type SystemNotification struct {
	ID           string    `db:"id" json:"id"`
	TargetUserID string    `db:"target_user_id" json:"target_user_id"`
	Title        string    `db:"title" json:"title"`
	Body         string    `db:"body" json:"body"`
	Important    bool      `db:"important" json:"important"`
	CreatedAt    time.Time `db:"created_at" json:"created_at"`
}

type NotificationStore struct {
	db *sqlx.DB
}

func NewNotificationStore(db *sqlx.DB) *NotificationStore {
	return &NotificationStore{db: db}
}

func (s *NotificationStore) EnsureTable(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `
CREATE TABLE IF NOT EXISTS system_notifications (
    id TEXT PRIMARY KEY,
    target_user_id TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    important INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)`)
	if err != nil {
		return err
	}
	_ = addColumnIfMissing(s.db, "system_notifications", "target_user_id", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(s.db, "system_notifications", "important", "INTEGER NOT NULL DEFAULT 0")
	_, _ = s.db.ExecContext(ctx, `CREATE INDEX IF NOT EXISTS idx_system_notifications_target_user ON system_notifications (target_user_id, created_at DESC)`)
	_, _ = s.db.ExecContext(ctx, `CREATE INDEX IF NOT EXISTS idx_system_notifications_created ON system_notifications (created_at DESC)`)
	return nil
}

func (s *NotificationStore) Create(ctx context.Context, n *SystemNotification) error {
	_, err := s.db.NamedExecContext(ctx, `
INSERT INTO system_notifications (id, target_user_id, title, body, important, created_at)
VALUES (:id, :target_user_id, :title, :body, :important, CURRENT_TIMESTAMP)`, n)
	return err
}

func (s *NotificationStore) List(ctx context.Context, limit int, before time.Time) ([]SystemNotification, error) {
	return s.ListForUser(ctx, "", limit, before)
}

func (s *NotificationStore) ListForUser(ctx context.Context, userID string, limit int, before time.Time) ([]SystemNotification, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if before.IsZero() {
		before = time.Now().Add(time.Second)
	}
	// SQLite stores CURRENT_TIMESTAMP as "YYYY-MM-DD HH:MM:SS" text.
	beforeText := before.Format("2006-01-02 15:04:05")

	var notifications []SystemNotification
	var err error
	trimmedUserID := strings.TrimSpace(userID)
	if trimmedUserID == "" {
		err = s.db.SelectContext(ctx, &notifications, `
SELECT id, target_user_id, title, body, important, created_at
FROM system_notifications
WHERE target_user_id = '' AND created_at <= $1
ORDER BY created_at DESC
LIMIT $2`, beforeText, limit)
	} else {
		err = s.db.SelectContext(ctx, &notifications, `
SELECT id, target_user_id, title, body, important, created_at
FROM system_notifications
WHERE (target_user_id = '' OR target_user_id = $1) AND created_at <= $2
ORDER BY created_at DESC
LIMIT $3`, trimmedUserID, beforeText, limit)
	}
	return notifications, err
}

func (s *NotificationStore) Delete(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM system_notifications WHERE id = $1`, id)
	return err
}

func (s *NotificationStore) Count(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(*) FROM system_notifications`)
	return count, err
}

func (s *NotificationStore) ListPage(ctx context.Context, limit, offset int) ([]SystemNotification, error) {
	if limit <= 0 || limit > 200 {
		limit = 20
	}
	if offset < 0 {
		offset = 0
	}
	rows := []SystemNotification{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT id, target_user_id, title, body, important, created_at
FROM system_notifications
ORDER BY created_at DESC, id DESC
LIMIT $1 OFFSET $2`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}
