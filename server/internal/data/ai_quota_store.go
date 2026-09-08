package data

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/jmoiron/sqlx"
)

const DefaultAITokenLimit int64 = 0

var ErrAIQuotaExceeded = errors.New("AI token quota exceeded")

type AIQuota struct {
	UserID         string `db:"user_id" json:"-"`
	TokenLimit     int64  `db:"token_limit" json:"token_limit"`
	UsedTokens     int64  `db:"used_tokens" json:"used_tokens"`
	ReservedTokens int64  `db:"reserved_tokens" json:"reserved_tokens"`
}

func (q AIQuota) Remaining() int64 {
	remaining := q.TokenLimit - q.UsedTokens - q.ReservedTokens
	if remaining < 0 {
		return 0
	}
	return remaining
}

type AIQuotaStore struct{ db *sqlx.DB }

func NewAIQuotaStore(db *sqlx.DB) *AIQuotaStore { return &AIQuotaStore{db: db} }

func (s *AIQuotaStore) Get(ctx context.Context, userID string) (AIQuota, error) {
	if _, err := s.db.ExecContext(ctx, `INSERT OR IGNORE INTO ai_token_quotas (user_id, token_limit) VALUES (?, ?)`, userID, DefaultAITokenLimit); err != nil {
		return AIQuota{}, err
	}
	var quota AIQuota
	err := s.db.GetContext(ctx, &quota, `SELECT user_id, token_limit, used_tokens, reserved_tokens FROM ai_token_quotas WHERE user_id = ?`, userID)
	return quota, err
}

// Reserve 在请求上游前原子冻结额度，防止同一账号的并发请求透支。
func (s *AIQuotaStore) Reserve(ctx context.Context, userID string, tokens int64) error {
	if tokens <= 0 {
		return errors.New("invalid reservation")
	}
	tx, err := s.db.BeginTxx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `INSERT OR IGNORE INTO ai_token_quotas (user_id, token_limit) VALUES (?, ?)`, userID, DefaultAITokenLimit); err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, `UPDATE ai_token_quotas SET reserved_tokens = reserved_tokens + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND used_tokens + reserved_tokens + ? <= token_limit`, tokens, userID, tokens)
	if err != nil {
		return err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rows != 1 {
		return ErrAIQuotaExceeded
	}
	return tx.Commit()
}

// Settle 释放冻结额度，并按上游 usage 的实际总 token 结算；失败请求传 actual=0 即可全额退回。
func (s *AIQuotaStore) Settle(ctx context.Context, userID string, reserved, actual int64) error {
	if reserved < 0 {
		reserved = 0
	}
	if actual < 0 {
		actual = 0
	}
	_, err := s.db.ExecContext(ctx, `UPDATE ai_token_quotas SET reserved_tokens = MAX(0, reserved_tokens - ?), used_tokens = used_tokens + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?`, reserved, actual, userID)
	return err
}

func (s *AIQuotaStore) CleanupStaleReservations(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `UPDATE ai_token_quotas SET reserved_tokens = 0, updated_at = CURRENT_TIMESTAMP WHERE reserved_tokens > 0 AND updated_at < ?`, time.Now().UTC().Add(-30*time.Minute))
	return err
}
