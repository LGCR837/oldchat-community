package data

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"time"

	"github.com/jmoiron/sqlx"
)

// ModeratorActionLog 审核员操作日志实体
type ModeratorActionLog struct {
	ID            string    `db:"id" json:"id"`
	ModeratorID   string    `db:"moderator_id" json:"moderator_id"`
	ModeratorName string    `db:"moderator_name" json:"moderator_name"`
	ActionType    string    `db:"action_type" json:"action_type"`
	TargetType    string    `db:"target_type" json:"target_type"`
	TargetID      string    `db:"target_id" json:"target_id"`
	ActionDetail  string    `db:"action_detail" json:"action_detail"`
	IP            string    `db:"ip" json:"ip"`
	CreatedAt     time.Time `db:"created_at" json:"created_at"`
}

type ModeratorLogStore struct {
	db *sqlx.DB
}

func NewModeratorLogStore(db *sqlx.DB) *ModeratorLogStore {
	return &ModeratorLogStore{db: db}
}

func (s *ModeratorLogStore) Log(ctx context.Context, modID, modName, actionType, targetType, targetID, detail, ip string) error {
	id := generateLogID()
	query := `INSERT INTO moderator_action_logs (id, moderator_id, moderator_name, action_type, target_type, target_id, action_detail, ip, created_at)
	          VALUES ($1, $2, $3, $4, $5, $6, $7, $8, CURRENT_TIMESTAMP)`
	_, err := s.db.ExecContext(ctx, query, id, modID, modName, actionType, targetType, targetID, detail, ip)
	return err
}

func (s *ModeratorLogStore) ListRecentPage(ctx context.Context, limit, offset int) ([]ModeratorActionLog, error) {
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	query := `SELECT id, moderator_id, moderator_name, action_type, target_type, target_id, action_detail, ip, created_at
	          FROM moderator_action_logs
	          ORDER BY created_at DESC, rowid DESC
	          LIMIT $1 OFFSET $2`
	var rows []ModeratorActionLog
	err := s.db.SelectContext(ctx, &rows, query, limit, offset)
	return rows, err
}

func (s *ModeratorLogStore) Count(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM moderator_action_logs`)
	return count, err
}

func generateLogID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "mlog-" + hex.EncodeToString(b)
}
