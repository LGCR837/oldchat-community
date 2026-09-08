package data

import (
	"context"
	"database/sql"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
)

type BanAppeal struct {
	ID          string       `db:"id"`
	UserID      string       `db:"user_id"`
	UID         string       `db:"uid"`
	Username    string       `db:"username"`
	BanReason   string       `db:"ban_reason"`
	BannedUntil sql.NullTime `db:"banned_until"`
	AppealText  string       `db:"appeal_text"`
	Contact     string       `db:"contact"`
	Status      string       `db:"status"`
	AdminNote   string       `db:"admin_note"`
	HandledAt   sql.NullTime `db:"handled_at"`
	CreatedAt   time.Time    `db:"created_at"`
	UpdatedAt   time.Time    `db:"updated_at"`
}

type BanAppealStore struct {
	db *sqlx.DB
}

func NewBanAppealStore(db *sqlx.DB) *BanAppealStore {
	return &BanAppealStore{db: db}
}

func (s *BanAppealStore) Create(ctx context.Context, item *BanAppeal) error {
	if item == nil || item.UserID == "" {
		return ErrNotFound
	}
	if item.ID == "" {
		item.ID = NewID()
	}
	status := normalizeBanAppealStatus(item.Status)
	if status == "" {
		status = "pending"
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO ban_appeals (
    id, user_id, uid, username, ban_reason, banned_until,
    appeal_text, contact, status, admin_note,
    handled_at, created_at, updated_at
) VALUES (
    $1, $2, $3, $4, $5, $6,
    $7, $8, $9, $10,
    $11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
`, item.ID, item.UserID, item.UID, item.Username, item.BanReason, item.BannedUntil,
		item.AppealText, item.Contact, status, item.AdminNote, item.HandledAt)
	return err
}

func (s *BanAppealStore) GetByID(ctx context.Context, id string) (*BanAppeal, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, ErrNotFound
	}
	var item BanAppeal
	err := s.db.GetContext(ctx, &item, `
SELECT id, user_id, uid, username, ban_reason, banned_until,
       appeal_text, contact, status, admin_note,
       handled_at, created_at, updated_at
FROM ban_appeals
WHERE id = $1
`, id)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &item, nil
}

func (s *BanAppealStore) GetLatestByUser(ctx context.Context, userID string) (*BanAppeal, error) {
	userID = strings.TrimSpace(userID)
	if userID == "" {
		return nil, nil
	}
	var item BanAppeal
	err := s.db.GetContext(ctx, &item, `
SELECT id, user_id, uid, username, ban_reason, banned_until,
       appeal_text, contact, status, admin_note,
       handled_at, created_at, updated_at
FROM ban_appeals
WHERE user_id = $1
ORDER BY created_at DESC
LIMIT 1
`, userID)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &item, nil
}

func (s *BanAppealStore) HasPendingByUser(ctx context.Context, userID string) (bool, error) {
	userID = strings.TrimSpace(userID)
	if userID == "" {
		return false, nil
	}
	var found string
	err := s.db.GetContext(ctx, &found, `
SELECT id
FROM ban_appeals
WHERE user_id = $1 AND status = 'pending'
ORDER BY created_at DESC
LIMIT 1
`, userID)
	if err == nil {
		return true, nil
	}
	if err == sql.ErrNoRows {
		return false, nil
	}
	return false, err
}

func (s *BanAppealStore) CountPending(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM ban_appeals WHERE status = 'pending'`)
	if err != nil {
		return 0, err
	}
	return count, nil
}

func (s *BanAppealStore) ListRecent(ctx context.Context, status string, limit int) ([]BanAppeal, error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	status = normalizeBanAppealStatus(status)
	rows := make([]BanAppeal, 0)
	if status == "pending" || status == "approved" || status == "rejected" {
		if err := s.db.SelectContext(ctx, &rows, `
SELECT id, user_id, uid, username, ban_reason, banned_until,
       appeal_text, contact, status, admin_note,
       handled_at, created_at, updated_at
FROM ban_appeals
WHERE status = $1
ORDER BY CASE WHEN status = 'pending' THEN 0 ELSE 1 END, created_at DESC
LIMIT $2
`, status, limit); err != nil {
			return nil, err
		}
		return rows, nil
	}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT id, user_id, uid, username, ban_reason, banned_until,
       appeal_text, contact, status, admin_note,
       handled_at, created_at, updated_at
FROM ban_appeals
ORDER BY CASE WHEN status = 'pending' THEN 0 ELSE 1 END, created_at DESC
LIMIT $1
`, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

// banAppealHandledSQL 返回已处理/未处理的状态集合片段（仅两个固定值，无注入风险）。
func banAppealHandledSQL(handled bool) string {
	if handled {
		return "('approved','rejected')"
	}
	return "('pending','processing')"
}

// ListByHandledPage 按处理状态分页查询封禁申诉。
// handled=true 查已处理（approved/rejected），false 查待处理（pending/processing）。
func (s *BanAppealStore) ListByHandledPage(ctx context.Context, handled bool, limit, offset int) ([]BanAppeal, error) {
	if limit <= 0 || limit > 1000 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	rows := make([]BanAppeal, 0)
	if err := s.db.SelectContext(ctx, &rows, `
SELECT id, user_id, uid, username, ban_reason, banned_until,
       appeal_text, contact, status, admin_note,
       handled_at, created_at, updated_at
FROM ban_appeals
WHERE status IN `+banAppealHandledSQL(handled)+`
ORDER BY created_at DESC, id DESC
LIMIT $1 OFFSET $2
`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}

// CountByHandled 统计已处理/未处理封禁申诉数量。
func (s *BanAppealStore) CountByHandled(ctx context.Context, handled bool) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM ban_appeals WHERE status IN `+banAppealHandledSQL(handled))
	return count, err
}

func (s *BanAppealStore) SetStatus(ctx context.Context, id, status, adminNote string) error {
	id = strings.TrimSpace(id)
	if id == "" {
		return ErrNotFound
	}
	status = normalizeBanAppealStatus(status)
	if status == "" {
		status = "pending"
	}
	adminNote = strings.TrimSpace(adminNote)
	_, err := s.db.ExecContext(ctx, `
UPDATE ban_appeals
SET status = $1,
    admin_note = $2,
    handled_at = CASE WHEN $1 = 'pending' THEN NULL ELSE CURRENT_TIMESTAMP END,
    updated_at = CURRENT_TIMESTAMP
WHERE id = $3
`, status, adminNote, id)
	return err
}

func normalizeBanAppealStatus(status string) string {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "pending", "open", "todo":
		return "pending"
	case "approved", "approve", "pass", "accepted":
		return "approved"
	case "rejected", "reject", "deny", "declined":
		return "rejected"
	case "all":
		return "all"
	default:
		return ""
	}
}

func (s *BanAppealStore) Count(ctx context.Context, status string) (int, error) {
	status = normalizeBanAppealStatus(status)
	if status == "pending" || status == "approved" || status == "rejected" {
		var count int
		err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM ban_appeals WHERE status = $1`, status)
		return count, err
	}
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM ban_appeals`)
	return count, err
}

func (s *BanAppealStore) ListRecentPage(ctx context.Context, status string, limit, offset int) ([]BanAppeal, error) {
	if limit <= 0 || limit > 1000 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	status = normalizeBanAppealStatus(status)
	rows := make([]BanAppeal, 0)
	if status == "pending" || status == "approved" || status == "rejected" {
		if err := s.db.SelectContext(ctx, &rows, `
SELECT id, user_id, uid, username, ban_reason, banned_until,
       appeal_text, contact, status, admin_note,
       handled_at, created_at, updated_at
FROM ban_appeals
WHERE status = $1
ORDER BY CASE WHEN status = 'pending' THEN 0 ELSE 1 END, created_at DESC, id DESC
LIMIT $2 OFFSET $3
`, status, limit, offset); err != nil {
			return nil, err
		}
		return rows, nil
	}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT id, user_id, uid, username, ban_reason, banned_until,
       appeal_text, contact, status, admin_note,
       handled_at, created_at, updated_at
FROM ban_appeals
ORDER BY CASE WHEN status = 'pending' THEN 0 ELSE 1 END, created_at DESC, id DESC
LIMIT $1 OFFSET $2
`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}
