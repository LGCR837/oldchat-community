package data

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/jmoiron/sqlx"
)

// PayloadJSON 兼容 modernc sqlite 驱动返回的 string → json.RawMessage。
// 提供 Scan 方法，支持直接扫描为字节。
type PayloadJSON []byte

func (p *PayloadJSON) Scan(value interface{}) error {
	switch v := value.(type) {
	case nil:
		*p = nil
	case []byte:
		*p = append((*p)[:0], v...)
	case string:
		*p = []byte(v)
	default:
		return fmt.Errorf("unsupported payload type %T", value)
	}
	return nil
}

// AccountEvent 账号级 pts 事件流中的一条事件。
type AccountEvent struct {
	ID        int64       `db:"id"`
	UserID    string      `db:"user_id"`
	Pts       int64       `db:"pts"`
	PtsCount  int         `db:"pts_count"`
	EventType string      `db:"event_type"`
	RefID     string      `db:"ref_id"`
	Payload   PayloadJSON `db:"payload"`
	CreatedAt time.Time   `db:"created_at"`
}

// PtsStore 账号级 pts 序号 + 事件流水表操作。
// 采用 SQLite 事务内 UPDATE+RETURNING 分配序号，保证强一致；
// 序号允许空洞（事件可能因各种原因缺失），客户端连续性校验只依赖
// pts + pts_count 的数学关系，不依赖序号严格无空洞。
type PtsStore struct {
	db *sqlx.DB
}

func NewPtsStore(db *sqlx.DB) *PtsStore {
	return &PtsStore{db: db}
}

// EnsureSchema 幂等创建账号级事件流相关表。
func (s *PtsStore) EnsureSchema() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS user_pts_cursor (
    user_id VARCHAR(64) PRIMARY KEY,
    current_pts BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS account_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id VARCHAR(64) NOT NULL,
    pts BIGINT NOT NULL,
    pts_count INTEGER NOT NULL DEFAULT 1,
    event_type VARCHAR(32) NOT NULL,
    ref_id VARCHAR(64) NOT NULL DEFAULT '',
    payload TEXT NOT NULL DEFAULT '{}',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_account_events_user_pts ON account_events (user_id, pts);
CREATE INDEX IF NOT EXISTS idx_account_events_user_created ON account_events (user_id, created_at);`)
	return err
}

// GetCurrentPts 返回用户当前 pts。
func (s *PtsStore) GetCurrentPts(ctx context.Context, userID string) (int64, error) {
	var pts int64
	err := s.db.GetContext(ctx, &pts,
		`SELECT COALESCE(current_pts, 0) FROM user_pts_cursor WHERE user_id = $1`, userID)
	if err == sql.ErrNoRows {
		return 0, nil
	}
	return pts, err
}

// Emit 在单个事务内：pts+1 → 插入事件 → 返回分配到的 pts。
// eventType 与 refID 用于排查与去重（业务层自行保证幂等）。
func (s *PtsStore) Emit(ctx context.Context, userID, eventType, refID string, payload interface{}) (int64, error) {
	return s.EmitMulti(ctx, userID, eventType, refID, payload, 1)
}

// EmitMulti 支持一次事件让 pts 前进 ptsCount 步（如批量推送合并）。
func (s *PtsStore) EmitMulti(ctx context.Context, userID, eventType, refID string, payload interface{}, ptsCount int) (int64, error) {
	if ptsCount < 1 {
		ptsCount = 1
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return 0, err
	}
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	if _, err = tx.ExecContext(ctx,
		`INSERT INTO user_pts_cursor(user_id, current_pts, updated_at) VALUES ($1, 0, $2)
		 ON CONFLICT(user_id) DO NOTHING`,
		userID, time.Now().Unix()); err != nil {
		return 0, err
	}
	var pts int64
	if err = tx.GetContext(ctx, &pts,
		`UPDATE user_pts_cursor SET current_pts = current_pts + $2, updated_at = $3
		 WHERE user_id = $1 RETURNING current_pts`,
		userID, int64(ptsCount), time.Now().Unix()); err != nil {
		return 0, err
	}
	if _, err = tx.ExecContext(ctx, `
INSERT INTO account_events (user_id, pts, pts_count, event_type, ref_id, payload, created_at)
VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		userID, pts, ptsCount, eventType, refID, string(raw), time.Now()); err != nil {
		return 0, err
	}
	return pts, tx.Commit()
}

// EmitBatchForUsers 批量写账号事件。仅保留给非群聊的账号级批处理；
// 群消息禁止调用，必须使用每群唯一的 group_seq。
func (s *PtsStore) EmitBatchForUsers(ctx context.Context, userIDs []string, eventType, refID string, payload interface{}) (map[string]int64, error) {
	result := make(map[string]int64, len(userIDs))
	if len(userIDs) == 0 {
		return result, nil
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	now := time.Now().Unix()
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	for _, userID := range userIDs {
		if userID == "" {
			continue
		}
		if _, err = tx.ExecContext(ctx, `INSERT INTO user_pts_cursor(user_id, current_pts, updated_at) VALUES ($1, 0, $2) ON CONFLICT(user_id) DO NOTHING`, userID, now); err != nil {
			return nil, err
		}
		var pts int64
		if err = tx.GetContext(ctx, &pts, `UPDATE user_pts_cursor SET current_pts = current_pts + 1, updated_at = $2 WHERE user_id = $1 RETURNING current_pts`, userID, now); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `INSERT INTO account_events (user_id, pts, pts_count, event_type, ref_id, payload, created_at) VALUES ($1, $2, 1, $3, $4, $5, $6)`, userID, pts, eventType, refID, string(raw), time.Now()); err != nil {
			return nil, err
		}
		result[userID] = pts
	}
	return result, tx.Commit()
}

// ListEventsAfter 分页返回 pts > afterPts 的事件（升序）。
func (s *PtsStore) ListEventsAfter(ctx context.Context, userID string, afterPts int64, limit int) ([]AccountEvent, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	var events []AccountEvent
	err := s.db.SelectContext(ctx, &events, `
SELECT id, user_id, pts, pts_count, event_type, ref_id, payload, created_at
FROM account_events
WHERE user_id = $1 AND pts > $2
ORDER BY pts ASC
LIMIT $3`, userID, afterPts, limit)
	if err != nil {
		return nil, err
	}
	if events == nil {
		events = []AccountEvent{}
	}
	return events, nil
}

// HasEventAfter 判断 afterPts 之后是否仍有事件（用于 reset 判定：数据已被归档则 false）。
func (s *PtsStore) HasEventAfter(ctx context.Context, userID string, afterPts int64) (bool, error) {
	var exists int
	err := s.db.GetContext(ctx, &exists,
		`SELECT COUNT(1) FROM account_events WHERE user_id = $1 AND pts > $2 LIMIT 1`,
		userID, afterPts)
	if err != nil {
		return false, err
	}
	return exists > 0, nil
}

// ResetCursor 服务端事故回退后使用：强制把用户 pts 跳到明显的新值，
// 使客户端下次连接因差距过大触发全量 reset。
func (s *PtsStore) ResetCursor(ctx context.Context, userID string, jumpTo int64) error {
	if jumpTo <= 0 {
		jumpTo = 100000
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO user_pts_cursor(user_id, current_pts, updated_at) VALUES ($1, $2, $3)
		 ON CONFLICT(user_id) DO UPDATE SET current_pts = MAX(current_pts, excluded.current_pts), updated_at = excluded.updated_at`,
		userID, jumpTo, time.Now().Unix())
	return err
}

// PruneBefore 归档：物理删除早于 cutBefore 的账号事件。
func (s *PtsStore) PruneBefore(ctx context.Context, cutBefore time.Time) (int64, error) {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM account_events WHERE created_at < $1`, cutBefore)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return n, nil
}
