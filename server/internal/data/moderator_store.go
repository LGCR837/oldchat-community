package data

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
)

type Moderator struct {
	ID           string     `db:"id" json:"id"`
	Username     string     `db:"username" json:"username"`
	PasswordHash string     `db:"password_hash" json:"-"`
	Nickname     string     `db:"nickname" json:"nickname"`
	IsActive     bool       `db:"is_active" json:"is_active"`
	CreatedAt    time.Time  `db:"created_at" json:"created_at"`
	LastLoginAt  *time.Time `db:"last_login_at" json:"last_login_at,omitempty"`
}

type ModeratorStore struct {
	db *sqlx.DB
}

func NewModeratorStore(db *sqlx.DB) *ModeratorStore {
	return &ModeratorStore{db: db}
}

func (s *ModeratorStore) Create(ctx context.Context, username, passwordHash, nickname string) (*Moderator, error) {
	username = strings.TrimSpace(username)
	nickname = strings.TrimSpace(nickname)
	if username == "" {
		return nil, errors.New("username is required")
	}
	if passwordHash == "" {
		return nil, errors.New("password_hash is required")
	}
	id := NewID()
	now := time.Now()
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO moderators (id, username, password_hash, nickname, is_active, created_at)
		VALUES (?, ?, ?, ?, 1, ?)`,
		id, username, passwordHash, nickname, now,
	)
	if err != nil {
		return nil, err
	}
	return &Moderator{
		ID:           id,
		Username:     username,
		PasswordHash: passwordHash,
		Nickname:     nickname,
		IsActive:     true,
		CreatedAt:    now,
	}, nil
}

func (s *ModeratorStore) GetByID(ctx context.Context, id string) (*Moderator, error) {
	var m Moderator
	err := s.db.GetContext(ctx, &m, `
		SELECT id, username, password_hash, nickname, is_active, created_at, last_login_at
		FROM moderators WHERE id = ?`, id)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return &m, nil
}

func (s *ModeratorStore) GetByUsername(ctx context.Context, username string) (*Moderator, error) {
	var m Moderator
	err := s.db.GetContext(ctx, &m, `
		SELECT id, username, password_hash, nickname, is_active, created_at, last_login_at
		FROM moderators WHERE username = ? COLLATE NOCASE`, strings.TrimSpace(username))
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return &m, nil
}

func (s *ModeratorStore) List(ctx context.Context) ([]*Moderator, error) {
	var list []*Moderator
	err := s.db.SelectContext(ctx, &list, `
		SELECT id, username, password_hash, nickname, is_active, created_at, last_login_at
		FROM moderators ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	if list == nil {
		list = []*Moderator{}
	}
	return list, nil
}

func (s *ModeratorStore) UpdatePassword(ctx context.Context, id, passwordHash string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE moderators SET password_hash = ? WHERE id = ?`, passwordHash, id)
	return err
}

func (s *ModeratorStore) UpdateActive(ctx context.Context, id string, isActive bool) error {
	activeInt := 0
	if isActive {
		activeInt = 1
	}
	_, err := s.db.ExecContext(ctx, `
		UPDATE moderators SET is_active = ? WHERE id = ?`, activeInt, id)
	return err
}

func (s *ModeratorStore) UpdateLastLogin(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE moderators SET last_login_at = ? WHERE id = ?`, time.Now(), id)
	return err
}

func (s *ModeratorStore) Delete(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM moderators WHERE id = ?`, id)
	return err
}

func (s *ModeratorStore) Count(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(*) FROM moderators`)
	return count, err
}
