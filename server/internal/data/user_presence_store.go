package data

import (
	"context"
	"strings"

	"github.com/jmoiron/sqlx"
)

// ListPresenceUsersByIDs 批量读取在线好友展示所需字段，避免逐用户查询。
func (s *UserStore) ListPresenceUsersByIDs(ctx context.Context, userIDs []string) ([]User, error) {
	if len(userIDs) == 0 {
		return []User{}, nil
	}
	query, args, err := sqlx.In(`
SELECT id, uid, COALESCE(presence_status, 'online') AS presence_status
FROM users
WHERE id IN (?)`, userIDs)
	if err != nil {
		return nil, err
	}
	query = s.db.Rebind(query)
	users := make([]User, 0, len(userIDs))
	if err := s.db.SelectContext(ctx, &users, query, args...); err != nil {
		return nil, err
	}
	return users, nil
}

func (s *UserStore) UpdatePresenceStatus(ctx context.Context, userID, status string) error {
	normalized := strings.ToLower(strings.TrimSpace(status))
	if normalized != "online" && normalized != "invisible" {
		normalized = "online"
	}
	const q = `UPDATE users SET presence_status = $1 WHERE id = $2`
	_, err := s.db.ExecContext(ctx, q, normalized, userID)
	return err
}
