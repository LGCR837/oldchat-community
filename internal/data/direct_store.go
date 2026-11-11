package data

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/jmoiron/sqlx"
)

type DirectThread struct {
	ID      string    `db:"id"`
	UserAID string    `db:"user_a_id"`
	UserBID string    `db:"user_b_id"`
	Created time.Time `db:"created_at"`
}

type DirectMessage struct {
	SortSeq          int64      `db:"sort_seq"`
	ID               string     `db:"id"`
	ThreadID         string     `db:"thread_id"`
	SenderID         string     `db:"sender_id"`
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
	DeliveredAt      *time.Time `db:"delivered_at"`
	ReadAt           *time.Time `db:"read_at"`
}

type UnreadDirectMessage struct {
	SortSeq          int64      `db:"sort_seq"`
	ID               string     `db:"id"`
	ThreadID         string     `db:"thread_id"`
	SenderID         string     `db:"sender_id"`
	SenderUID        string     `db:"sender_uid"`
	SenderNcuid      string     `db:"sender_ncuid"`
	PeerUID          string     `db:"peer_uid"`
	PeerNcuid        string     `db:"peer_ncuid"`
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
	DeliveredAt      *time.Time `db:"delivered_at"`
	ReadAt           *time.Time `db:"read_at"`
}

type DirectStore struct {
	db *sqlx.DB
}

func NewDirectStore(db *sqlx.DB) *DirectStore {
	return &DirectStore{db: db}
}

func (s *DirectStore) GetThreadID(ctx context.Context, userID, otherID string) (string, error) {
	a, b := sortPair(userID, otherID)
	const q = `
SELECT id
FROM direct_threads
WHERE user_a_id = $1 AND user_b_id = $2
LIMIT 1`

	var id string
	if err := s.db.GetContext(ctx, &id, q, a, b); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", ErrNotFound
		}
		return "", err
	}
	return id, nil
}

func (s *DirectStore) GetThreadByID(ctx context.Context, threadID string) (*DirectThread, error) {
	var t DirectThread
	err := s.db.GetContext(ctx, &t, `
SELECT id, user_a_id, user_b_id, created_at
FROM direct_threads
WHERE id = $1
LIMIT 1`, threadID)
	if err == nil {
		return &t, nil
	}
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return nil, err
}

func (s *DirectStore) GetOrCreateThread(ctx context.Context, userID, otherID, newID string) (string, error) {
	a, b := sortPair(userID, otherID)
	const q = `
SELECT id
FROM direct_threads
WHERE user_a_id = $1 AND user_b_id = $2
LIMIT 1`

	var id string
	if err := s.db.GetContext(ctx, &id, q, a, b); err == nil {
		return id, nil
	} else if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}

	_, err := s.db.ExecContext(ctx, `
INSERT INTO direct_threads (id, user_a_id, user_b_id, created_at)
VALUES ($1, $2, $3, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING
`, newID, a, b)
	if err != nil {
		return "", err
	}

	if err := s.db.GetContext(ctx, &id, q, a, b); err != nil {
		return "", err
	}
	return id, nil
}

func (s *DirectStore) CreateMessage(ctx context.Context, m *DirectMessage) error {
	const q = `
INSERT INTO direct_messages (id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, created_at, delivered_at, read_at)
VALUES (:id, :thread_id, :sender_id, :body, :msg_type, :media_url, :thumb_url, :original_url, :duration_ms, :burn_after_seconds, CURRENT_TIMESTAMP, NULL, NULL)`

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
		res, err := s.db.NamedExecContext(ctx, q, m)
		if err == nil {
			// 回填 rowid 作为 sort_seq，使同秒消息仍能按插入顺序排列。
			if lastID, err := res.LastInsertId(); err == nil && lastID > 0 {
				m.SortSeq = lastID
			}
			return nil
		}
		lastErr = err
		if !IsSQLiteBusy(err) {
			return err
		}
	}
	return lastErr
}

func (s *DirectStore) GetMessageByID(ctx context.Context, messageID string) (*DirectMessage, error) {
	var m DirectMessage
	err := s.db.GetContext(ctx, &m, `
SELECT rowid AS sort_seq, id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at, delivered_at, read_at
FROM direct_messages
WHERE id = $1
LIMIT 1`, messageID)
	if err == nil {
		return &m, nil
	}
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return nil, err
}

func (s *DirectStore) ListMessages(ctx context.Context, threadID string, limit int, before time.Time) ([]DirectMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if before.IsZero() {
		before = time.Now()
	}

	const q = `
SELECT rowid AS sort_seq, id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at, delivered_at, read_at
FROM direct_messages
WHERE thread_id = $1 AND created_at < $2
ORDER BY created_at DESC, id DESC
LIMIT $3`

	var msgs []DirectMessage
	if err := s.db.SelectContext(ctx, &msgs, q, threadID, before, limit); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *DirectStore) MarkDelivered(ctx context.Context, threadID, readerID string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE direct_messages
SET delivered_at = CURRENT_TIMESTAMP
WHERE thread_id = $1 AND sender_id != $2 AND delivered_at IS NULL
`, threadID, readerID)
	return err
}

func (s *DirectStore) MarkRead(ctx context.Context, threadID, readerID string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE direct_messages
SET delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP),
    read_at = CURRENT_TIMESTAMP
WHERE thread_id = $1 AND sender_id != $2 AND read_at IS NULL
`, threadID, readerID)
	return err
}

func sortPair(a, b string) (string, string) {
	if a < b {
		return a, b
	}
	return b, a
}

func (s *DirectStore) ListUnreadByUser(ctx context.Context, userID string, limit int) ([]UnreadDirectMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
WITH user_threads AS (
    SELECT dt.id AS thread_id, ub.uid AS peer_uid, COALESCE(ub.ncuid, '') AS peer_ncuid
    FROM direct_threads dt JOIN users ub ON ub.id = dt.user_b_id
    WHERE dt.user_a_id = $1
    UNION ALL
    SELECT dt.id AS thread_id, ua.uid AS peer_uid, COALESCE(ua.ncuid, '') AS peer_ncuid
    FROM direct_threads dt JOIN users ua ON ua.id = dt.user_a_id
    WHERE dt.user_b_id = $1
)
SELECT dm.rowid AS sort_seq, dm.id, dm.thread_id, dm.sender_id, su.uid AS sender_uid,
       COALESCE(su.ncuid, '') AS sender_ncuid,
       ut.peer_uid, ut.peer_ncuid, dm.body, dm.msg_type, dm.media_url, dm.thumb_url, dm.duration_ms,
       dm.burn_after_seconds, dm.created_at, dm.delivered_at, dm.read_at,
       dm.burn_start_at, dm.burned_at
FROM user_threads ut
JOIN direct_messages dm INDEXED BY idx_direct_unread_thread_created ON dm.thread_id = ut.thread_id
JOIN users su ON dm.sender_id = su.id
WHERE dm.sender_id != $1
  AND dm.read_at IS NULL
  AND dm.burned_at IS NULL
  AND dm.msg_type != 'recall'
ORDER BY dm.created_at DESC, dm.rowid DESC, dm.id DESC
LIMIT $2`

	var msgs []UnreadDirectMessage
	if err := s.db.SelectContext(ctx, &msgs, q, userID, limit); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *DirectStore) MarkDeliveredByUser(ctx context.Context, userID string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE direct_messages
SET delivered_at = CURRENT_TIMESTAMP
WHERE thread_id IN (
  SELECT id FROM direct_threads WHERE user_a_id = $1
  UNION ALL
  SELECT id FROM direct_threads WHERE user_b_id = $1
)
AND sender_id != $1
AND delivered_at IS NULL
`, userID)
	return err
}

// DeleteMessage deletes a direct message by ID and sender ID
func (s *DirectStore) DeleteMessage(ctx context.Context, messageID, senderID string) error {
	result, err := s.db.ExecContext(ctx, `
DELETE FROM direct_messages
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

// DeleteMessageByID deletes a direct message by ID (admin usage)
func (s *DirectStore) DeleteMessageByID(ctx context.Context, messageID string) error {
	result, err := s.db.ExecContext(ctx, `
DELETE FROM direct_messages
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

// ListMessagesWithCursor 使用 created_at+id 复合游标，避免新消息插入导致 OFFSET 漂移。
func (s *DirectStore) ListMessagesWithCursor(ctx context.Context, threadID string, limit int, beforeUnix int64, beforeID string) ([]DirectMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	const q = `
SELECT rowid AS sort_seq, id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at, delivered_at, read_at
FROM direct_messages
WHERE thread_id = $1
  AND (created_at < datetime($2, 'unixepoch')
       OR (created_at = datetime($2, 'unixepoch') AND id < $3))
ORDER BY created_at DESC, id DESC
LIMIT $4`
	var msgs []DirectMessage
	if err := s.db.SelectContext(ctx, &msgs, q, threadID, beforeUnix, beforeID, limit); err != nil {
		return nil, err
	}
	return msgs, nil
}

// ListMessagesWithOffset returns messages using offset-based pagination
func (s *DirectStore) ListMessagesWithOffset(ctx context.Context, threadID string, limit, offset int) ([]DirectMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	const q = `
SELECT rowid AS sort_seq, id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at, delivered_at, read_at
FROM direct_messages
WHERE thread_id = $1
ORDER BY created_at DESC, id DESC
LIMIT $2 OFFSET $3`

	var msgs []DirectMessage
	if err := s.db.SelectContext(ctx, &msgs, q, threadID, limit, offset); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *DirectStore) SearchMessagesWithOffset(ctx context.Context, threadID, keyword, kind string, limit, offset int) ([]DirectMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	kw := "%" + keyword + "%"

	var msgs []DirectMessage
	if kind == "text" {
		const qText = `
SELECT rowid AS sort_seq, id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at, delivered_at, read_at
FROM direct_messages
WHERE thread_id = $1 AND (body LIKE $2 OR media_url LIKE $2) AND msg_type = 'text'
ORDER BY created_at DESC, id DESC
LIMIT $3 OFFSET $4`
		if err := s.db.SelectContext(ctx, &msgs, qText, threadID, kw, limit, offset); err != nil {
			return nil, err
		}
		return msgs, nil
	}
	if kind == "media" {
		const qMedia = `
SELECT rowid AS sort_seq, id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at, delivered_at, read_at
FROM direct_messages
WHERE thread_id = $1 AND (body LIKE $2 OR media_url LIKE $2)
  AND msg_type IN ('image', 'video', 'voice', 'resource')
ORDER BY created_at DESC, id DESC
LIMIT $3 OFFSET $4`
		if err := s.db.SelectContext(ctx, &msgs, qMedia, threadID, kw, limit, offset); err != nil {
			return nil, err
		}
		return msgs, nil
	}

	const qAll = `
SELECT rowid AS sort_seq, id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, burn_start_at, burned_at, created_at, delivered_at, read_at
FROM direct_messages
WHERE thread_id = $1 AND (body LIKE $2 OR media_url LIKE $2)
ORDER BY created_at DESC, id DESC
LIMIT $3 OFFSET $4`
	if err := s.db.SelectContext(ctx, &msgs, qAll, threadID, kw, limit, offset); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (s *DirectStore) GetMessageOffset(ctx context.Context, threadID, messageID string) (int, error) {
	var exists int
	const qExists = `
SELECT COUNT(1)
FROM direct_messages
WHERE thread_id = $1 AND id = $2`
	if err := s.db.GetContext(ctx, &exists, qExists, threadID, messageID); err != nil {
		return 0, err
	}
	if exists <= 0 {
		return 0, ErrNotFound
	}

	const qOffset = `
SELECT COUNT(1)
FROM direct_messages
WHERE thread_id = $1
  AND (
    created_at > (
      SELECT created_at
      FROM direct_messages
      WHERE thread_id = $1 AND id = $2
      LIMIT 1
    )
    OR (
      created_at = (
        SELECT created_at
        FROM direct_messages
        WHERE thread_id = $1 AND id = $2
        LIMIT 1
      )
      AND id > $2
    )
  )`

	var offset int
	if err := s.db.GetContext(ctx, &offset, qOffset, threadID, messageID); err != nil {
		return 0, err
	}
	if offset < 0 {
		offset = 0
	}
	return offset, nil
}

func (s *DirectStore) ListExpiredBurnMessages(ctx context.Context, limit int) ([]ExpiredDirectBurnMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT dm.rowid AS sort_seq, dm.id, dm.thread_id, dm.sender_id, su.uid AS sender_uid,
       dt.user_a_id, dt.user_b_id
FROM direct_messages dm
JOIN direct_threads dt ON dt.id = dm.thread_id
JOIN users su ON su.id = dm.sender_id
WHERE dm.burn_after_seconds > 0
  AND dm.burn_start_at IS NOT NULL
  AND dm.burn_start_at <= CURRENT_TIMESTAMP
  AND dm.burned_at IS NULL
  AND dm.msg_type != 'recall'
  AND DATETIME(dm.burn_start_at, '+' || dm.burn_after_seconds || ' seconds') <= CURRENT_TIMESTAMP
ORDER BY dm.burn_start_at ASC
LIMIT $1`
	var rows []ExpiredDirectBurnMessage
	if err := s.db.SelectContext(ctx, &rows, q, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DirectStore) MarkMessageBurned(ctx context.Context, messageID string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE direct_messages
SET msg_type = 'recall',
    body = '[消息已焚毁]',
    media_url = '',
    thumb_url = '',
    duration_ms = 0,
    burned_at = CURRENT_TIMESTAMP
WHERE id = $1
  AND burned_at IS NULL
  AND msg_type != 'recall'`, messageID)
	return err
}

func (s *DirectStore) MarkBurnOpened(ctx context.Context, messageID, openerID string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE direct_messages
SET burn_start_at = CASE
        WHEN burn_after_seconds > 0 AND burn_start_at IS NULL THEN CURRENT_TIMESTAMP
        ELSE burn_start_at
    END
WHERE id = $1
  AND sender_id != $2
  AND burn_after_seconds > 0
  AND burned_at IS NULL
  AND msg_type != 'recall'`, messageID, openerID)
	return err
}

func (s *DirectStore) UpdateMessageBody(ctx context.Context, messageID, body string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE direct_messages SET body = $1 WHERE id = $2`, body, messageID)
	return err
}
