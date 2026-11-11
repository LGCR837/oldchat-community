package data

import (
	"context"
	"database/sql"
	"encoding/json"
	"time"

	"github.com/jmoiron/sqlx"
)

// GroupEvent 群级事件流（与群消息共享同一条 group_seq 序号线）。
// 只承载"非新增消息"类事件（撤回/编辑/已读游标/成员元数据变更），
// 消息新增仍走 group_messages 表，客户端通过归并查询获得连续事件流。
type GroupEvent struct {
	ID        int64       `db:"id"`
	GroupID   string      `db:"group_id"`
	GroupSeq  int64       `db:"group_seq"`
	EventType string      `db:"event_type"`
	RefID     string      `db:"ref_id"`
	Payload   PayloadJSON `db:"payload"`
	CreatedAt time.Time   `db:"created_at"`
}

// GroupEventStore 群级事件表操作。群序号分配与 group_message_sequences 共享。
type GroupEventStore struct {
	db *sqlx.DB
}

func NewGroupEventStore(db *sqlx.DB) *GroupEventStore {
	return &GroupEventStore{db: db}
}

// EnsureSchema 幂等创建群事件表。
func (s *GroupEventStore) EnsureSchema() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS group_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id VARCHAR(64) NOT NULL,
    group_seq BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    ref_id VARCHAR(64) NOT NULL DEFAULT '',
    payload TEXT NOT NULL DEFAULT '{}',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_events_seq ON group_events (group_id, group_seq);
CREATE INDEX IF NOT EXISTS idx_group_events_created ON group_events (group_id, created_at);`)
	return err
}

// NextGroupSeq 在事务中分配群级序号（与消息表共享序号线）。
// 事务内完成 seq 分配 + 事件写入，保证严格递增、无并发冲突。
func (s *GroupEventStore) NextGroupSeq(ctx context.Context, tx *sqlx.Tx, groupID string) (int64, error) {
	if _, err := tx.ExecContext(ctx,
		`INSERT OR IGNORE INTO group_message_sequences(group_id, last_seq) VALUES (?, 0)`, groupID); err != nil {
		return 0, err
	}
	var seq int64
	if err := tx.GetContext(ctx, &seq,
		`UPDATE group_message_sequences SET last_seq = last_seq + 1 WHERE group_id = ? RETURNING last_seq`,
		groupID); err != nil {
		return 0, err
	}
	return seq, nil
}

// EmitGroupEvent 在单个事务内：分配 group_seq → 写入 group_events。
func (s *GroupEventStore) EmitGroupEvent(ctx context.Context, groupID, eventType, refID string, payload interface{}) (int64, error) {
	raw, err := json.Marshal(payload)
	if err != nil {
		return 0, err
	}
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	seq, err := s.NextGroupSeq(ctx, tx, groupID)
	if err != nil {
		return 0, err
	}
	if _, err = tx.ExecContext(ctx, `
INSERT INTO group_events (group_id, group_seq, event_type, ref_id, payload, created_at)
VALUES ($1, $2, $3, $4, $5, $6)`,
		groupID, seq, eventType, refID, string(raw), time.Now()); err != nil {
		return 0, err
	}
	return seq, tx.Commit()
}

// ListEventsAfterSeq 分页返回群级事件（group_seq > afterSeq，升序）。
func (s *GroupEventStore) ListEventsAfterSeq(ctx context.Context, groupID string, afterSeq int64, limit int) ([]GroupEvent, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	var events []GroupEvent
	err := s.db.SelectContext(ctx, &events, `
SELECT id, group_id, group_seq, event_type, ref_id, payload, created_at
FROM group_events
WHERE group_id = $1 AND group_seq > $2
ORDER BY group_seq ASC
LIMIT $3`, groupID, afterSeq, limit)
	if err != nil {
		return nil, err
	}
	if events == nil {
		events = []GroupEvent{}
	}
	return events, nil
}

// HasEventAfterSeq 判断 afterSeq 后是否有群事件（归并时判断是否还有数据）。
func (s *GroupEventStore) HasEventAfterSeq(ctx context.Context, groupID string, afterSeq int64) (bool, error) {
	var exists int
	err := s.db.GetContext(ctx, &exists,
		`SELECT COUNT(1) FROM group_events WHERE group_id = $1 AND group_seq > $2 LIMIT 1`,
		groupID, afterSeq)
	if err != nil {
		return false, err
	}
	return exists > 0, nil
}

// GetEventBySeq 按 group_seq 查询单条事件（幂等/占位处理用）。
func (s *GroupEventStore) GetEventBySeq(ctx context.Context, groupID string, seq int64) (*GroupEvent, error) {
	var e GroupEvent
	err := s.db.GetContext(ctx, &e, `
SELECT id, group_id, group_seq, event_type, ref_id, payload, created_at
FROM group_events
WHERE group_id = $1 AND group_seq = $2`, groupID, seq)
	if err == sql.ErrNoRows {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &e, nil
}

// PruneBefore 归档：物理删除早于 cutBefore 的群事件。
func (s *GroupEventStore) PruneBefore(ctx context.Context, cutBefore time.Time) (int64, error) {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM group_events WHERE created_at < $1`, cutBefore)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return n, nil
}
