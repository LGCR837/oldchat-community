package data

import (
	"context"
	"database/sql"
	"errors"

	"github.com/jmoiron/sqlx"
)

const (
	GroupInvitationPending  int16 = 0
	GroupInvitationAccepted int16 = 1
	GroupInvitationDenied   int16 = 2
)

type GroupInvitationStore struct {
	db *sqlx.DB
}

func NewGroupInvitationStore(db *sqlx.DB) *GroupInvitationStore {
	return &GroupInvitationStore{db: db}
}

func (s *GroupInvitationStore) Create(ctx context.Context, invitation *GroupInvitation) error {
	_, err := s.db.NamedExecContext(ctx, `
INSERT INTO group_invitations(id,group_id,inviter_id,invitee_id,status,created_at)
VALUES (:id,:group_id,:inviter_id,:invitee_id,:status,CURRENT_TIMESTAMP)`, invitation)
	return err
}

func (s *GroupInvitationStore) Pending(ctx context.Context, groupID, inviteeID string) (bool, error) {
	var value int
	err := s.db.GetContext(ctx, &value, `SELECT 1 FROM group_invitations
        WHERE group_id=$1 AND invitee_id=$2 AND status=0 LIMIT 1`, groupID, inviteeID)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func (s *GroupInvitationStore) ListIncoming(ctx context.Context, inviteeID string) ([]GroupInvitationEntry, error) {
	items := []GroupInvitationEntry{}
	err := s.db.SelectContext(ctx, &items, `
SELECT gi.id, gi.group_id, g.name AS group_name, g.avatar_url AS group_avatar_url,
       u.uid AS inviter_uid, u.display_name AS inviter_display_name,
       u.user_title AS inviter_title, u.avatar_url AS inviter_avatar_url, gi.created_at
FROM group_invitations gi
JOIN groups g ON g.id=gi.group_id
JOIN users u ON u.id=gi.inviter_id
WHERE gi.invitee_id=$1 AND gi.status=0
ORDER BY gi.created_at DESC`, inviteeID)
	return items, err
}

func (s *GroupInvitationStore) Respond(ctx context.Context, id, inviteeID string,
	accept bool) (*GroupInvitationResult, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	status := GroupInvitationDenied
	if accept {
		status = GroupInvitationAccepted
	}
	var result GroupInvitationResult
	err = tx.QueryRowContext(ctx, `
UPDATE group_invitations SET status=$1, responded_at=CURRENT_TIMESTAMP
WHERE id=$2 AND invitee_id=$3 AND status=0
RETURNING group_id, (SELECT owner_id FROM groups WHERE id=group_id)`,
		status, id, inviteeID).Scan(&result.GroupID, &result.OwnerID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if accept {
		if _, err = tx.ExecContext(ctx, `INSERT INTO group_members(group_id,user_id,role,joined_at,last_read_at)
            VALUES($1,$2,$3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING`,
			result.GroupID, inviteeID, GroupRoleMember); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return &result, nil
}
