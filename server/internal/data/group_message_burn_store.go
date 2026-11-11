package data

import "context"

type ExpiredGroupBurnMessage struct {
	ID        string `db:"id"`
	GroupID   string `db:"group_id"`
	SenderID  string `db:"sender_id"`
	SenderUID string `db:"sender_uid"`
}

func (s *GroupMessageStore) ListExpiredBurnMessages(ctx context.Context, limit int) ([]ExpiredGroupBurnMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT gm.id, gm.group_id, gm.sender_id, su.uid AS sender_uid
FROM group_messages gm
JOIN users su ON su.id = gm.sender_id
WHERE gm.burn_after_seconds > 0
  AND gm.burn_start_at IS NOT NULL
  AND gm.burn_start_at <= CURRENT_TIMESTAMP
  AND gm.burned_at IS NULL
  AND gm.msg_type != 'recall'
  AND DATETIME(gm.burn_start_at, '+' || gm.burn_after_seconds || ' seconds') <= CURRENT_TIMESTAMP
ORDER BY gm.burn_start_at ASC
LIMIT $1`
	var rows []ExpiredGroupBurnMessage
	if err := s.db.SelectContext(ctx, &rows, q, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *GroupMessageStore) MarkMessageBurned(ctx context.Context, messageID string) error {
	res, err := s.db.ExecContext(ctx, `
UPDATE group_messages
SET msg_type = 'recall',
    body = '[消息已焚毁]',
    media_url = '',
    thumb_url = '',
    duration_ms = 0,
    burned_at = CURRENT_TIMESTAMP
WHERE id = $1
  AND burned_at IS NULL
  AND msg_type != 'recall'
`, messageID)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *GroupMessageStore) ListReadableBurnMessagesByGroup(ctx context.Context, groupID, readerID string, limit int) ([]ExpiredGroupBurnMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT gm.id, gm.group_id, gm.sender_id, su.uid AS sender_uid
FROM group_messages gm
JOIN users su ON su.id = gm.sender_id
WHERE gm.group_id = $1
  AND gm.sender_id != $2
  AND gm.burn_after_seconds > 0
  AND gm.burned_at IS NULL
  AND gm.msg_type != 'recall'
ORDER BY gm.created_at ASC
LIMIT $3`
	var rows []ExpiredGroupBurnMessage
	if err := s.db.SelectContext(ctx, &rows, q, groupID, readerID, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *GroupMessageStore) MarkBurnOpened(ctx context.Context, messageID, openerID string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE group_messages
SET burn_start_at = CASE
        WHEN burn_after_seconds > 0 AND burn_start_at IS NULL THEN CURRENT_TIMESTAMP
        ELSE burn_start_at
    END
WHERE id = $1
  AND sender_id != $2
  AND burn_after_seconds > 0
  AND burned_at IS NULL
  AND msg_type != 'recall'
`, messageID, openerID)
	return err
}
