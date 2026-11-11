package data

import "context"

type ExpiredDirectBurnMessage struct {
	ID        string `db:"id"`
	ThreadID  string `db:"thread_id"`
	SenderID  string `db:"sender_id"`
	SenderUID string `db:"sender_uid"`
	UserAID   string `db:"user_a_id"`
	UserBID   string `db:"user_b_id"`
}

func (s *DirectStore) ListReadableBurnMessagesByThread(ctx context.Context, threadID, readerID string, limit int) ([]ExpiredDirectBurnMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT dm.id, dm.thread_id, dm.sender_id, su.uid AS sender_uid, dt.user_a_id, dt.user_b_id
FROM direct_messages dm
JOIN direct_threads dt ON dt.id = dm.thread_id
JOIN users su ON su.id = dm.sender_id
WHERE dm.thread_id = $1
  AND dm.sender_id != $2
  AND dm.read_at IS NOT NULL
  AND dm.burn_after_seconds > 0
  AND dm.burned_at IS NULL
  AND dm.msg_type != 'recall'
ORDER BY dm.read_at ASC
LIMIT $3`
	var rows []ExpiredDirectBurnMessage
	if err := s.db.SelectContext(ctx, &rows, q, threadID, readerID, limit); err != nil {
		return nil, err
	}
	return rows, nil
}
