package data

import (
	"context"
	"database/sql"
	"time"

	"github.com/jmoiron/sqlx"
)

type GroupMessage struct {
	SortSeq          int64      `db:"sort_seq"`
	GroupSeq         int64      `db:"group_seq"`
	ID               string     `db:"id"`
	GroupID          string     `db:"group_id"`
	SenderID         string     `db:"sender_id"`
	SenderUID        string     `db:"sender_uid"`
	SenderNcuid      string     `db:"sender_ncuid"`
	Body             string     `db:"body"`
	MsgType          string     `db:"msg_type"`
	MediaURL         string     `db:"media_url"`
	ThumbURL         string     `db:"thumb_url"`
	OriginalURL      string     `db:"original_url"`
	DurationMS       int        `db:"duration_ms"`
	BurnAfterSeconds int        `db:"burn_after_seconds"`
	BurnStartAt      *time.Time `db:"burn_start_at"`
	BurnedAt         *time.Time `db:"burned_at"`
	Created          time.Time  `db:"created_at"`
}

type UnreadGroupMessage struct {
	SortSeq          int64      `db:"sort_seq"`
	GroupSeq         int64      `db:"group_seq"`
	ID               string     `db:"id"`
	GroupID          string     `db:"group_id"`
	SenderID         string     `db:"sender_id"`
	SenderUID        string     `db:"sender_uid"`
	SenderNcuid      string     `db:"sender_ncuid"`
	Body             string     `db:"body"`
	MsgType          string     `db:"msg_type"`
	MediaURL         string     `db:"media_url"`
	ThumbURL         string     `db:"thumb_url"`
	OriginalURL      string     `db:"original_url"`
	DurationMS       int        `db:"duration_ms"`
	BurnAfterSeconds int        `db:"burn_after_seconds"`
	BurnStartAt      *time.Time `db:"burn_start_at"`
	BurnedAt         *time.Time `db:"burned_at"`
	Created          time.Time  `db:"created_at"`
}

type GroupMessageStore struct {
	db *sqlx.DB
}

func NewGroupMessageStore(db *sqlx.DB) *GroupMessageStore {
	return &GroupMessageStore{db: db}
}

func (s *GroupMessageStore) Create(ctx context.Context, m *GroupMessage) error {
	var lastErr error
	for attempt := 0; attempt < 5; attempt++ {
		if attempt > 0 {
			backoff := time.Duration(40*(1<<attempt)) * time.Millisecond
			if backoff > 600*time.Millisecond {
				backoff = 600 * time.Millisecond
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(backoff):
			}
		}
		err := s.createOnce(ctx, m)
		if err == nil {
			return nil
		}
		lastErr = err
		if !IsSQLiteBusy(err) {
			return err
		}
	}
	return lastErr
}

func (s *GroupMessageStore) createOnce(ctx context.Context, m *GroupMessage) error {
	tx, err := s.db.BeginTxx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `INSERT OR IGNORE INTO group_message_sequences(group_id, last_seq) VALUES (?, 0)`, m.GroupID); err != nil {
		return err
	}
	if err = tx.GetContext(ctx, &m.GroupSeq,
		`UPDATE group_message_sequences SET last_seq = last_seq + 1 WHERE group_id = ? RETURNING last_seq`,
		m.GroupID); err != nil {
		return err
	}
	const q = `
INSERT INTO group_messages (id, group_id, group_seq, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, created_at)
VALUES (:id, :group_id, :group_seq, :sender_id, :body, :msg_type, :media_url, :thumb_url, :original_url, :duration_ms, :burn_after_seconds, CURRENT_TIMESTAMP)`
	res, err := tx.NamedExecContext(ctx, q, m)
	if err != nil {
		return err
	}
	if lastID, lastErr := res.LastInsertId(); lastErr == nil && lastID > 0 {
		m.SortSeq = lastID
	}
	return tx.Commit()
}

func (s *GroupMessageStore) GetByID(ctx context.Context, messageID string) (*GroupMessage, error) {
	var m GroupMessage
	err := s.db.GetContext(ctx, &m, `
SELECT rowid AS sort_seq, group_seq, id, group_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at
FROM group_messages
WHERE id = $1
LIMIT 1`, messageID)
	if err == nil {
		return &m, nil
	}
	if err == sql.ErrNoRows {
		return nil, ErrNotFound
	}
	return nil, err
}

func (s *GroupMessageStore) ListByGroup(ctx context.Context, groupID string, limit int, before time.Time) ([]GroupMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if before.IsZero() {
		before = time.Now()
	}

	const q = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1 AND gm.created_at < $2
ORDER BY gm.created_at DESC, gm.id DESC
LIMIT $3`

	var msgs []GroupMessage
	if err := s.db.SelectContext(ctx, &msgs, q, groupID, before, limit); err != nil {
		return nil, err
	}
	return msgs, nil
}

// DeleteMessage deletes a group message by ID and sender ID
func (s *GroupMessageStore) DeleteMessage(ctx context.Context, messageID, senderID string) error {
	result, err := s.db.ExecContext(ctx, `
DELETE FROM group_messages
WHERE id = $1 AND sender_id = $2
`, messageID, senderID)
	if err != nil {
		return err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteMessageByID deletes a group message by ID regardless of sender.
func (s *GroupMessageStore) DeleteMessageByID(ctx context.Context, messageID string) error {
	result, err := s.db.ExecContext(ctx, `
DELETE FROM group_messages
WHERE id = $1
`, messageID)
	if err != nil {
		return err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

// ListByGroupWithCursor 使用 created_at+id 复合游标，避免实时新消息造成分页漂移。
func (s *GroupMessageStore) ListByGroupWithCursor(ctx context.Context, groupID string, limit int, beforeUnix int64, beforeID string) ([]GroupMessage, error) {
	if limit <= 0 || limit > 300 {
		limit = 50
	}
	const q = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1
  AND (gm.created_at < datetime($2, 'unixepoch')
       OR (gm.created_at = datetime($2, 'unixepoch') AND gm.id < $3))
ORDER BY gm.created_at DESC, gm.id DESC
LIMIT $4`
	var msgs []GroupMessage
	if err := s.db.SelectContext(ctx, &msgs, q, groupID, beforeUnix, beforeID, limit); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *GroupMessageStore) CurrentGroupSeq(ctx context.Context, groupID string) (int64, error) {
	var seq int64
	err := s.db.GetContext(ctx, &seq, `SELECT COALESCE(last_seq, 0) FROM group_message_sequences WHERE group_id = ?`, groupID)
	if err == sql.ErrNoRows {
		return 0, nil
	}
	return seq, err
}

// ListByGroupAfterSeq 按每群单调序号正向补拉，供断线重连可靠恢复使用。
func (s *GroupMessageStore) ListByGroupAfterSeq(ctx context.Context, groupID string, afterSeq int64, limit int) ([]GroupMessage, error) {
	if afterSeq < 0 {
		afterSeq = 0
	}
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	const q = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1 AND gm.group_seq > $2
ORDER BY gm.group_seq ASC
LIMIT $3`
	var msgs []GroupMessage
	if err := s.db.SelectContext(ctx, &msgs, q, groupID, afterSeq, limit); err != nil {
		return nil, err
	}
	return msgs, nil
}

// ListByGroupBeforeSeq Keyset 分页：按 group_seq 倒序翻页（before_seq 为当前列表最旧一条的 seq）。
// 上限 300：覆盖 parseLimit(200) + 1 条探测，保证 hasMore 判断可靠。
func (s *GroupMessageStore) ListByGroupBeforeSeq(ctx context.Context, groupID string, limit int, beforeSeq int64) ([]GroupMessage, error) {
	if limit <= 0 || limit > 300 {
		limit = 100
	}
	if beforeSeq <= 0 {
		return []GroupMessage{}, nil
	}
	const q = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1 AND gm.group_seq < $2
ORDER BY gm.group_seq DESC
LIMIT $3`
	var msgs []GroupMessage
	if err := s.db.SelectContext(ctx, &msgs, q, groupID, beforeSeq, limit); err != nil {
		return nil, err
	}
	if msgs == nil {
		msgs = []GroupMessage{}
	}
	return msgs, nil
}

// ListByGroupWithOffset returns group messages using offset-based pagination
func (s *GroupMessageStore) ListByGroupWithOffset(ctx context.Context, groupID string, limit, offset int) ([]GroupMessage, error) {
	if limit <= 0 || limit > 300 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	const q = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1
ORDER BY gm.created_at DESC, gm.id DESC
LIMIT $2 OFFSET $3`

	var msgs []GroupMessage
	if err := s.db.SelectContext(ctx, &msgs, q, groupID, limit, offset); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *GroupMessageStore) SearchByGroupWithOffset(ctx context.Context, groupID, keyword, kind string, limit, offset int) ([]GroupMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	kw := "%" + keyword + "%"

	var msgs []GroupMessage
	if kind == "text" {
		const qText = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1 AND (gm.body LIKE $2 OR gm.media_url LIKE $2) AND gm.msg_type = 'text'
ORDER BY gm.created_at DESC, gm.id DESC
LIMIT $3 OFFSET $4`
		if err := s.db.SelectContext(ctx, &msgs, qText, groupID, kw, limit, offset); err != nil {
			return nil, err
		}
		return msgs, nil
	}
	if kind == "media" {
		const qMedia = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1 AND (gm.body LIKE $2 OR gm.media_url LIKE $2)
  AND gm.msg_type IN ('image', 'video', 'voice', 'resource')
ORDER BY gm.created_at DESC, gm.id DESC
LIMIT $3 OFFSET $4`
		if err := s.db.SelectContext(ctx, &msgs, qMedia, groupID, kw, limit, offset); err != nil {
			return nil, err
		}
		return msgs, nil
	}

	const qAll = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id, u.uid AS sender_uid,
       COALESCE(u.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms,
       gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_messages gm
JOIN users u ON u.id = gm.sender_id
WHERE gm.group_id = $1 AND (gm.body LIKE $2 OR gm.media_url LIKE $2)
ORDER BY gm.created_at DESC, gm.id DESC
LIMIT $3 OFFSET $4`
	if err := s.db.SelectContext(ctx, &msgs, qAll, groupID, kw, limit, offset); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *GroupMessageStore) GetMessageOffset(ctx context.Context, groupID, messageID string) (int, error) {
	var exists int
	const qExists = `
SELECT COUNT(1)
FROM group_messages
WHERE group_id = $1 AND id = $2`
	if err := s.db.GetContext(ctx, &exists, qExists, groupID, messageID); err != nil {
		return 0, err
	}
	if exists <= 0 {
		return 0, ErrNotFound
	}

	const qOffset = `
SELECT COUNT(1)
FROM group_messages
WHERE group_id = $1
  AND (
    created_at > (
      SELECT created_at
      FROM group_messages
      WHERE group_id = $1 AND id = $2
      LIMIT 1
    )
    OR (
      created_at = (
        SELECT created_at
        FROM group_messages
        WHERE group_id = $1 AND id = $2
        LIMIT 1
      )
      AND id > $2
    )
  )`

	var offset int
	if err := s.db.GetContext(ctx, &offset, qOffset, groupID, messageID); err != nil {
		return 0, err
	}
	if offset < 0 {
		offset = 0
	}
	return offset, nil
}

func (s *GroupMessageStore) ListUnreadByUser(ctx context.Context, userID string, limit int) ([]UnreadGroupMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT gm.rowid AS sort_seq, gm.group_seq, gm.id, gm.group_id, gm.sender_id,
       su.uid AS sender_uid, COALESCE(su.ncuid, '') AS sender_ncuid,
       gm.body, gm.msg_type, gm.media_url, gm.thumb_url, gm.duration_ms, gm.burn_after_seconds, gm.burn_start_at, gm.burned_at, gm.created_at
FROM group_members m
JOIN group_messages gm INDEXED BY idx_group_messages_group ON gm.group_id = m.group_id AND gm.created_at > COALESCE(m.last_read_at, m.joined_at)
JOIN users su ON gm.sender_id = su.id
WHERE m.user_id = $1
  AND gm.sender_id != $1
  AND gm.burned_at IS NULL
  AND gm.msg_type != 'recall'
ORDER BY gm.created_at DESC, gm.group_seq DESC, gm.rowid DESC, gm.id DESC
LIMIT $2`

	var msgs []UnreadGroupMessage
	if err := s.db.SelectContext(ctx, &msgs, q, userID, limit); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *GroupMessageStore) UpdateMessageBody(ctx context.Context, messageID, body string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE group_messages SET body = $1 WHERE id = $2`, body, messageID)
	return err
}

func (s *GroupMessageStore) CountDailyUserMessages(ctx context.Context, groupID, userID string) (int, error) {
	var count int
	const query = `
SELECT COUNT(1)
FROM group_messages
WHERE group_id = $1
  AND sender_id = $2
  AND created_at >= date('now', 'localtime', 'start of day')
  AND msg_type != 'recall'`
	if err := s.db.GetContext(ctx, &count, query, groupID, userID); err != nil {
		return 0, err
	}
	return count, nil
}
