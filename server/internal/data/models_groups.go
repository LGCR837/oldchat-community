package data

import (
	"database/sql"
	"time"
)

type Group struct {
	ID                    string    `db:"id"`
	Name                  string    `db:"name"`
	AvatarURL             string    `db:"avatar_url"`
	OwnerID               string    `db:"owner_id"`
	JoinApproval          bool      `db:"join_approval"`
	GlobalMute            bool      `db:"global_mute"`
	Announcement          string    `db:"announcement"`
	AnnouncementMode      int16     `db:"announcement_mode"`
	AnnouncementUpdatedAt time.Time `db:"announcement_updated_at"`
	MemberVersion         int64     `db:"member_version"`
	DailyMsgLimit         int       `db:"daily_msg_limit"`
	CreatedAt             time.Time `db:"created_at"`
	UpdatedAt             time.Time `db:"updated_at"`
}

type GroupMember struct {
	GroupID string    `db:"group_id"`
	UserID  string    `db:"user_id"`
	Role    int16     `db:"role"`
	Joined  time.Time `db:"joined_at"`
}

type GroupJoinRequest struct {
	ID        string    `db:"id"`
	GroupID   string    `db:"group_id"`
	UserID    string    `db:"user_id"`
	Status    int16     `db:"status"`
	CreatedAt time.Time `db:"created_at"`
}

type GroupSummary struct {
	ID                    string       `db:"id"`
	OfficialGroupID       string       `db:"official_group_id"`
	Name                  string       `db:"name"`
	AvatarURL             string       `db:"avatar_url"`
	OwnerID               string       `db:"owner_id"`
	JoinApproval          bool         `db:"join_approval"`
	GlobalMute            bool         `db:"global_mute"`
	Announcement          string       `db:"announcement"`
	AnnouncementMode      int16        `db:"announcement_mode"`
	AnnouncementUpdatedAt time.Time    `db:"announcement_updated_at"`
	AnnouncementReadAt    sql.NullTime `db:"announcement_read_at"`
	MemberCount           int          `db:"member_count"`
	MemberVersion         int64        `db:"member_version"`
	MessageSeq            int64        `db:"message_seq"`
	Role                  int16        `db:"role"`
}

type GroupMemberEntry struct {
	ID          string    `db:"id"`
	UID         string    `db:"uid"`
	Ncuid       string    `db:"ncuid"`
	Username    string    `db:"username"`
	DisplayName string    `db:"display_name"`
	UserTitle   string    `db:"user_title"`
	AvatarURL   string    `db:"avatar_url"`
	Role        int16     `db:"role"`
	JoinedAt    time.Time `db:"joined_at"`
}

type GroupJoinRequestEntry struct {
	ID          string    `db:"id"`
	UserID      string    `db:"user_id"`
	UID         string    `db:"uid"`
	Ncuid       string    `db:"ncuid"`
	Username    string    `db:"username"`
	DisplayName string    `db:"display_name"`
	UserTitle   string    `db:"user_title"`
	AvatarURL   string    `db:"avatar_url"`
	CreatedAt   time.Time `db:"created_at"`
}

type GroupInvitation struct {
	ID        string    `db:"id"`
	GroupID   string    `db:"group_id"`
	InviterID string    `db:"inviter_id"`
	InviteeID string    `db:"invitee_id"`
	Status    int16     `db:"status"`
	CreatedAt time.Time `db:"created_at"`
}

type GroupInvitationEntry struct {
	ID                 string    `db:"id"`
	GroupID            string    `db:"group_id"`
	GroupName          string    `db:"group_name"`
	GroupAvatarURL     string    `db:"group_avatar_url"`
	InviterUID         string    `db:"inviter_uid"`
	InviterDisplayName string    `db:"inviter_display_name"`
	InviterTitle       string    `db:"inviter_title"`
	InviterAvatarURL   string    `db:"inviter_avatar_url"`
	CreatedAt          time.Time `db:"created_at"`
}

type GroupInvitationResult struct {
	GroupID string
	OwnerID string
}
