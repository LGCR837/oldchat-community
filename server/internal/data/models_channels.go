package data

import "time"

type Channel struct {
	ID              string    `db:"id" json:"id"`
	OwnerID         string    `db:"owner_id" json:"-"`
	Name            string    `db:"name" json:"name"`
	Handle          string    `db:"handle" json:"handle"`
	AvatarURL       string    `db:"avatar_url" json:"avatar_url"`
	Description     string    `db:"description" json:"description"`
	Visibility      string    `db:"visibility" json:"visibility"`
	Status          string    `db:"status" json:"status"`
	SubscriberCount int       `db:"subscriber_count" json:"subscriber_count"`
	CreatedAt       time.Time `db:"created_at" json:"created_at"`
}

type ChannelPost struct {
	ID            string     `db:"id" json:"id"`
	ChannelID     string     `db:"channel_id" json:"channel_id"`
	PostSeq       int64      `db:"post_seq" json:"post_seq"`
	AuthorID      string     `db:"author_id" json:"-"`
	AuthorUID     string     `db:"author_uid" json:"author_uid"`
	AuthorName    string     `db:"author_name" json:"author_name"`
	AuthorAvatar  string     `db:"author_avatar" json:"author_avatar"`
	ReplyToPostID string     `db:"reply_to_post_id" json:"reply_to_post_id,omitempty"`
	ReplyAuthor   string     `db:"reply_author" json:"reply_author,omitempty"`
	ReplyBody     string     `db:"reply_body" json:"reply_body,omitempty"`
	Body          string     `db:"body" json:"body"`
	MsgType       string     `db:"msg_type" json:"msg_type"`
	MediaURL      string     `db:"media_url" json:"media_url,omitempty"`
	ThumbURL      string     `db:"thumb_url" json:"thumb_url,omitempty"`
	ViewCount     int64      `db:"view_count" json:"view_count"`
	CommentCount  int64      `db:"comment_count" json:"comment_count"`
	EditedAt      *time.Time `db:"edited_at" json:"edited_at,omitempty"`
	CreatedAt     time.Time  `db:"created_at" json:"created_at"`
}

type ChannelReactionCount struct {
	Emoji string `db:"emoji" json:"emoji"`
	Count int    `db:"count" json:"count"`
	Mine  bool   `db:"-" json:"mine"`
}

type ChannelEvent struct {
	ChannelID string    `db:"channel_id" json:"channel_id"`
	EventSeq  int64     `db:"event_seq" json:"event_seq"`
	EventType string    `db:"event_type" json:"event_type"`
	RefID     string    `db:"ref_id" json:"ref_id"`
	Payload   string    `db:"payload" json:"payload"`
	CreatedAt time.Time `db:"created_at" json:"created_at"`
}

type ChannelState struct {
	ChannelID         string     `db:"channel_id" json:"channel_id"`
	Name              string     `db:"name" json:"name"`
	Handle            string     `db:"handle" json:"handle"`
	AvatarURL         string     `db:"avatar_url" json:"avatar_url"`
	SubscriberCount   int        `db:"subscriber_count" json:"subscriber_count"`
	PostSeq           int64      `db:"post_seq" json:"post_seq"`
	EventSeq          int64      `db:"event_seq" json:"event_seq"`
	SettingsVersion   int64      `db:"settings_version" json:"settings_version"`
	Role              string     `db:"role" json:"role"`
	JoinedPostSeq     int64      `db:"joined_post_seq" json:"joined_post_seq"`
	LastReadPostSeq   int64      `db:"last_read_post_seq" json:"last_read_post_seq"`
	NotificationLevel string     `db:"notification_level" json:"notification_level"`
	MutedUntil        *time.Time `db:"muted_until" json:"muted_until,omitempty"`
	UnreadCount       int        `db:"unread_count" json:"unread_count"`
}
