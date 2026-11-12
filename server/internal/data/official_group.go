package data

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

const (
	// OfficialGroupID 是全站默认群，所有注册账号自动加入。
	// GRP- 前缀同时是「官方/已迁移」群的判定依据。
	OfficialGroupID = "GRP-ADMINS"
	// OfficialGroupName 是官方群的显示名。
	OfficialGroupName = "官方群"
)

// IsOfficialGroupID 判断群 ID 是否属于旧站（GRP- 前缀）。
func IsOfficialGroupID(id string) bool {
	return strings.HasPrefix(strings.ToUpper(strings.TrimSpace(id)), "GRP-")
}

// EnsureOfficialGroup 确保官方群存在。ownerID 为空时用最早注册的真实账号当群主；
// 库里还没有真实账号时返回 false，等第一个账号注册时再建。
func (s *GroupStore) EnsureOfficialGroup(ctx context.Context, groupID, name, ownerID string) (bool, error) {
	var existing string
	err := s.db.GetContext(ctx, &existing, `SELECT id FROM groups WHERE id = ?`, groupID)
	if err == nil {
		return true, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return false, err
	}
	if strings.TrimSpace(ownerID) == "" {
		if e := s.db.GetContext(ctx, &ownerID, `
SELECT id FROM users WHERE is_shadow = 0 ORDER BY created_at LIMIT 1`); e != nil {
			if errors.Is(e, sql.ErrNoRows) {
				return false, nil
			}
			return false, e
		}
	}
	_, err = s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO groups (id, name, avatar_url, owner_id, join_approval, global_mute, created_at, updated_at)
VALUES (?, ?, '', ?, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`, groupID, name, ownerID)
	if err != nil {
		return false, err
	}
	_, err = s.AddMemberIfAbsent(ctx, groupID, ownerID, GroupRoleOwner)
	return true, err
}

// JoinOfficialGroup 让账号加入官方群，群不存在时顺手建好。
func (s *GroupStore) JoinOfficialGroup(ctx context.Context, userID string) error {
	if strings.TrimSpace(userID) == "" {
		return nil
	}
	if _, err := s.EnsureOfficialGroup(ctx, OfficialGroupID, OfficialGroupName, userID); err != nil {
		return err
	}
	_, err := s.AddMemberIfAbsent(ctx, OfficialGroupID, userID, 0)
	return err
}

// BackfillOfficialGroupMembers 把还没进官方群的真实账号一次性补进去，返回新加入的数量。
func (s *GroupStore) BackfillOfficialGroupMembers(ctx context.Context) (int64, error) {
	res, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at, last_read_at)
SELECT ?, id, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users
WHERE is_shadow = 0`, OfficialGroupID)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	if n > 0 {
		_ = s.bumpMemberVersion(ctx, OfficialGroupID)
	}
	return n, nil
}
