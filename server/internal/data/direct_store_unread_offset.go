package data

import "context"

func (s *DirectStore) ListUnreadByUserOffset(ctx context.Context, userID string, limit, offset int) ([]UnreadDirectMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
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
SELECT dm.rowid AS sort_seq, dm.id, dm.thread_id, dm.sender_id,
       su.uid AS sender_uid, COALESCE(su.ncuid, '') AS sender_ncuid,
       ut.peer_uid, ut.peer_ncuid,
       dm.body, dm.msg_type, dm.media_url, dm.thumb_url, dm.duration_ms, dm.burn_after_seconds,
       dm.created_at, dm.delivered_at, dm.read_at, dm.burn_start_at, dm.burned_at
FROM user_threads ut
JOIN direct_messages dm INDEXED BY idx_direct_unread_thread_created ON dm.thread_id = ut.thread_id
JOIN users su ON dm.sender_id = su.id
WHERE dm.sender_id != $1
  AND dm.read_at IS NULL
  AND dm.burned_at IS NULL
  AND dm.msg_type != 'recall'
ORDER BY dm.created_at DESC, dm.rowid DESC, dm.id DESC
LIMIT $2 OFFSET $3`

	var msgs []UnreadDirectMessage
	if err := s.db.SelectContext(ctx, &msgs, q, userID, limit, offset); err != nil {
		return nil, err
	}
	return msgs, nil
}
