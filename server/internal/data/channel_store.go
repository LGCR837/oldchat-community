package data

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	"github.com/jmoiron/sqlx"
)

var ErrChannelNotSubscribed = errors.New("channel not subscribed")
var ErrChannelReadSeqAhead = errors.New("channel read seq ahead")

type ChannelStore struct{ db *sqlx.DB }

func NewChannelStore(db *sqlx.DB) *ChannelStore { return &ChannelStore{db: db} }

func (s *ChannelStore) Create(ctx context.Context, channel *Channel) error {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx, `
INSERT INTO channels(id,owner_id,name,handle,avatar_url,description,visibility,subscriber_count)
VALUES($1,$2,$3,$4,$5,$6,$7,1)`, channel.ID, channel.OwnerID, channel.Name,
		channel.Handle, channel.AvatarURL, channel.Description, channel.Visibility)
	if err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO channel_sequences(channel_id) VALUES($1)`, channel.ID); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `
INSERT INTO channel_subscriptions(channel_id,user_id,role,joined_post_seq,last_read_post_seq)
VALUES($1,$2,'owner',0,0)`, channel.ID, channel.OwnerID)
	if err != nil {
		return err
	}
	return tx.Commit()
}

// UpdateName 更新频道名称（仅所有者/管理员）。
func (s *ChannelStore) UpdateName(ctx context.Context, channelID, name string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE channels SET name=$1, updated_at=CURRENT_TIMESTAMP WHERE id=$2 AND status='active'`,
		name, channelID)
	return err
}

// UpdateAvatar 更新频道头像（仅所有者/管理员）。
func (s *ChannelStore) UpdateAvatar(ctx context.Context, channelID, avatarURL string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE channels SET avatar_url=$1, updated_at=CURRENT_TIMESTAMP WHERE id=$2 AND status='active'`,
		avatarURL, channelID)
	return err
}

// UpdateDescription 更新频道描述（仅所有者/管理员）。
func (s *ChannelStore) UpdateDescription(ctx context.Context, channelID, description string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE channels SET description=$1, updated_at=CURRENT_TIMESTAMP WHERE id=$2 AND status='active'`,
		description, channelID)
	return err
}

func (s *ChannelStore) SearchPublic(ctx context.Context, query string, limit int) ([]Channel, error) {
	channels := make([]Channel, 0)
	query = strings.TrimSpace(query)
	like := "%" + query + "%"
	err := s.db.SelectContext(ctx, &channels, `
SELECT id,owner_id,name,handle,avatar_url,description,visibility,status,subscriber_count,created_at
FROM channels WHERE status='active' AND visibility='public'
  AND ($1='' OR name LIKE $2 OR handle LIKE $2)
ORDER BY subscriber_count DESC,created_at DESC LIMIT $3`, query, like, limit)
	return channels, err
}

func (s *ChannelStore) Get(ctx context.Context, channelID string) (*Channel, error) {
	var channel Channel
	err := s.db.GetContext(ctx, &channel, `
SELECT id,owner_id,name,handle,avatar_url,description,visibility,status,subscriber_count,created_at
FROM channels WHERE id=$1 AND status='active'`, channelID)
	return &channel, err
}

func (s *ChannelStore) Subscribe(ctx context.Context, channelID, userID string) (bool, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var seq int64
	if err = tx.GetContext(ctx, &seq, `SELECT post_seq FROM channel_sequences WHERE channel_id=$1`, channelID); err != nil {
		return false, err
	}
	result, err := tx.ExecContext(ctx, `
INSERT INTO channel_subscriptions(channel_id,user_id,role,joined_post_seq,last_read_post_seq)
VALUES($1,$2,'subscriber',$3,$3) ON CONFLICT(channel_id,user_id) DO NOTHING`, channelID, userID, seq)
	if err != nil {
		return false, err
	}
	inserted, _ := result.RowsAffected()
	if inserted > 0 {
		_, err = tx.ExecContext(ctx, `UPDATE channels SET subscriber_count=subscriber_count+1 WHERE id=$1`, channelID)
		if err == nil {
			_, err = tx.ExecContext(ctx, `UPDATE channel_sequences SET subscriber_version=subscriber_version+1 WHERE channel_id=$1`, channelID)
		}
		if err != nil {
			return false, err
		}
	}
	return inserted > 0, tx.Commit()
}

func (s *ChannelStore) Unsubscribe(ctx context.Context, channelID, userID string) (bool, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `DELETE FROM channel_subscriptions WHERE channel_id=$1 AND user_id=$2 AND role<>'owner'`, channelID, userID)
	if err != nil {
		return false, err
	}
	removed, _ := result.RowsAffected()
	if removed > 0 {
		_, err = tx.ExecContext(ctx, `UPDATE channels SET subscriber_count=MAX(0,subscriber_count-1) WHERE id=$1`, channelID)
		if err == nil {
			_, err = tx.ExecContext(ctx, `UPDATE channel_sequences SET subscriber_version=subscriber_version+1 WHERE channel_id=$1`, channelID)
		}
		if err != nil {
			return false, err
		}
	}
	return removed > 0, tx.Commit()
}

func (s *ChannelStore) CanPost(ctx context.Context, channelID, userID string) (bool, error) {
	var role string
	err := s.db.GetContext(ctx, &role, `SELECT role FROM channel_subscriptions WHERE channel_id=$1 AND user_id=$2`, channelID, userID)
	if err != nil {
		return false, err
	}
	return role == "owner" || role == "admin" || role == "publisher", nil
}

func (s *ChannelStore) CreatePost(ctx context.Context, post *ChannelPost) (int64, int64, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()
	var seqs struct {
		PostSeq  int64 `db:"post_seq"`
		EventSeq int64 `db:"event_seq"`
	}
	err = tx.GetContext(ctx, &seqs, `
UPDATE channel_sequences SET post_seq=post_seq+1,event_seq=event_seq+1,updated_at=CURRENT_TIMESTAMP
WHERE channel_id=$1 RETURNING post_seq,event_seq`, post.ChannelID)
	if err != nil {
		return 0, 0, err
	}
	post.PostSeq = seqs.PostSeq
	if post.ReplyToPostID != "" {
		var replyExists int
		if err = tx.GetContext(ctx, &replyExists, `SELECT COUNT(1) FROM channel_posts WHERE channel_id=$1 AND id=$2 AND deleted_at IS NULL`, post.ChannelID, post.ReplyToPostID); err != nil || replyExists == 0 {
			return 0, 0, sql.ErrNoRows
		}
	}
	_, err = tx.ExecContext(ctx, `
INSERT INTO channel_posts(id,channel_id,post_seq,author_id,reply_to_post_id,body,msg_type,media_url,thumb_url)
VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, post.ID, post.ChannelID, post.PostSeq,
		post.AuthorID, post.ReplyToPostID, post.Body, post.MsgType, post.MediaURL, post.ThumbURL)
	if err != nil {
		return 0, 0, err
	}
	payload, _ := json.Marshal(map[string]interface{}{"post_seq": post.PostSeq})
	_, err = tx.ExecContext(ctx, `
INSERT INTO channel_events(channel_id,event_seq,event_type,ref_id,payload)
VALUES($1,$2,'POST_NEW',$3,$4)`, post.ChannelID, seqs.EventSeq, post.ID, string(payload))
	if err != nil {
		return 0, 0, err
	}
	if err = tx.Commit(); err != nil {
		return 0, 0, err
	}
	return seqs.PostSeq, seqs.EventSeq, nil
}

func (s *ChannelStore) ToggleReaction(ctx context.Context, channelID, postID, userID, emoji string) ([]ChannelReactionCount, int64, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, 0, err
	}
	defer tx.Rollback()
	var allowed int
	err = tx.GetContext(ctx, &allowed, `
SELECT COUNT(1) FROM channel_posts p JOIN channels c ON c.id=p.channel_id
JOIN channel_subscriptions s ON s.channel_id=p.channel_id AND s.user_id=$3
WHERE p.channel_id=$1 AND p.id=$2 AND p.deleted_at IS NULL AND c.reactions_enabled=1`, channelID, postID, userID)
	if err != nil || allowed == 0 {
		return nil, 0, ErrChannelNotSubscribed
	}
	var existing string
	_ = tx.GetContext(ctx, &existing, `SELECT emoji FROM channel_post_reactions WHERE post_id=$1 AND user_id=$2`, postID, userID)
	if existing == emoji {
		_, err = tx.ExecContext(ctx, `DELETE FROM channel_post_reactions WHERE post_id=$1 AND user_id=$2`, postID, userID)
	} else {
		_, err = tx.ExecContext(ctx, `
INSERT INTO channel_post_reactions(channel_id,post_id,user_id,emoji) VALUES($1,$2,$3,$4)
ON CONFLICT(post_id,user_id) DO UPDATE SET emoji=excluded.emoji,created_at=CURRENT_TIMESTAMP`, channelID, postID, userID, emoji)
	}
	if err != nil {
		return nil, 0, err
	}
	counts := make([]ChannelReactionCount, 0)
	if err = tx.SelectContext(ctx, &counts, `SELECT emoji,COUNT(1) AS count FROM channel_post_reactions WHERE post_id=$1 GROUP BY emoji ORDER BY count DESC,emoji`, postID); err != nil {
		return nil, 0, err
	}
	var mine string
	_ = tx.GetContext(ctx, &mine, `SELECT emoji FROM channel_post_reactions WHERE post_id=$1 AND user_id=$2`, postID, userID)
	for i := range counts {
		counts[i].Mine = counts[i].Emoji == mine
	}
	var eventSeq int64
	if err = tx.GetContext(ctx, &eventSeq, `UPDATE channel_sequences SET event_seq=event_seq+1,updated_at=CURRENT_TIMESTAMP WHERE channel_id=$1 RETURNING event_seq`, channelID); err != nil {
		return nil, 0, err
	}
	payload, _ := json.Marshal(map[string]interface{}{"reactions": counts})
	_, err = tx.ExecContext(ctx, `INSERT INTO channel_events(channel_id,event_seq,event_type,ref_id,payload) VALUES($1,$2,'REACTION_CHANGED',$3,$4)`, channelID, eventSeq, postID, string(payload))
	if err != nil {
		return nil, 0, err
	}
	if err = tx.Commit(); err != nil {
		return nil, 0, err
	}
	return counts, eventSeq, nil
}

func (s *ChannelStore) RecentMediaRefs(ctx context.Context, channelID string) (map[string]string, error) {
	rows := []struct {
		ID       string `db:"id"`
		MediaURL string `db:"media_url"`
	}{}
	err := s.db.SelectContext(ctx, &rows, `SELECT id,media_url FROM channel_posts WHERE channel_id=$1 AND deleted_at IS NULL AND media_url<>'' ORDER BY post_seq DESC LIMIT 320`, channelID)
	result := make(map[string]string, len(rows))
	for _, row := range rows {
		result[row.ID] = row.MediaURL
	}
	return result, err
}

func (s *ChannelStore) ReactionSnapshots(ctx context.Context, channelID, userID string) (map[string][]ChannelReactionCount, error) {
	rows := []struct {
		PostID string `db:"post_id"`
		Emoji  string `db:"emoji"`
		Count  int    `db:"count"`
		Mine   int    `db:"mine"`
	}{}
	err := s.db.SelectContext(ctx, &rows, `
SELECT r.post_id,r.emoji,COUNT(1) AS count,
       MAX(CASE WHEN r.user_id=$2 THEN 1 ELSE 0 END) AS mine
FROM channel_post_reactions r WHERE r.channel_id=$1
  AND r.post_id IN (SELECT id FROM channel_posts WHERE channel_id=$1 AND deleted_at IS NULL ORDER BY post_seq DESC LIMIT 320)
GROUP BY r.post_id,r.emoji`, channelID, userID)
	result := make(map[string][]ChannelReactionCount)
	for _, row := range rows {
		result[row.PostID] = append(result[row.PostID], ChannelReactionCount{Emoji: row.Emoji, Count: row.Count, Mine: row.Mine != 0})
	}
	return result, err
}

func (s *ChannelStore) EditPost(ctx context.Context, channelID, postID, body string) (int64, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `UPDATE channel_posts SET body=$3,edited_at=CURRENT_TIMESTAMP WHERE channel_id=$1 AND id=$2 AND deleted_at IS NULL`, channelID, postID, body)
	if err != nil {
		return 0, err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return 0, sql.ErrNoRows
	}
	var eventSeq int64
	if err = tx.GetContext(ctx, &eventSeq, `UPDATE channel_sequences SET event_seq=event_seq+1,updated_at=CURRENT_TIMESTAMP WHERE channel_id=$1 RETURNING event_seq`, channelID); err != nil {
		return 0, err
	}
	payload, _ := json.Marshal(map[string]interface{}{"body": body})
	_, err = tx.ExecContext(ctx, `INSERT INTO channel_events(channel_id,event_seq,event_type,ref_id,payload) VALUES($1,$2,'POST_EDIT',$3,$4)`, channelID, eventSeq, postID, string(payload))
	if err != nil {
		return 0, err
	}
	return eventSeq, tx.Commit()
}

func (s *ChannelStore) DeletePost(ctx context.Context, channelID, postID string) (int64, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `UPDATE channel_posts SET deleted_at=CURRENT_TIMESTAMP WHERE channel_id=$1 AND id=$2 AND deleted_at IS NULL`, channelID, postID)
	if err != nil {
		return 0, err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return 0, sql.ErrNoRows
	}
	var eventSeq int64
	if err = tx.GetContext(ctx, &eventSeq, `UPDATE channel_sequences SET event_seq=event_seq+1,updated_at=CURRENT_TIMESTAMP WHERE channel_id=$1 RETURNING event_seq`, channelID); err != nil {
		return 0, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO channel_events(channel_id,event_seq,event_type,ref_id,payload) VALUES($1,$2,'POST_DELETE',$3,'{}')`, channelID, eventSeq, postID)
	if err != nil {
		return 0, err
	}
	return eventSeq, tx.Commit()
}

func (s *ChannelStore) ListEventsAfter(ctx context.Context, channelID string, after int64, limit int) ([]ChannelEvent, int64, error) {
	events := make([]ChannelEvent, 0)
	err := s.db.SelectContext(ctx, &events, `SELECT channel_id,event_seq,event_type,ref_id,payload,created_at FROM channel_events WHERE channel_id=$1 AND event_seq>$2 ORDER BY event_seq ASC LIMIT $3`, channelID, after, limit)
	if err != nil {
		return nil, 0, err
	}
	var serverSeq int64
	err = s.db.GetContext(ctx, &serverSeq, `SELECT event_seq FROM channel_sequences WHERE channel_id=$1`, channelID)
	return events, serverSeq, err
}

func (s *ChannelStore) ListPostsAfter(ctx context.Context, channelID string, after int64, limit int) ([]ChannelPost, int64, error) {
	posts := make([]ChannelPost, 0)
	err := s.db.SelectContext(ctx, &posts, `
SELECT p.id,p.channel_id,p.post_seq,p.author_id,u.uid AS author_uid,u.username AS author_name,
       u.avatar_url AS author_avatar,p.reply_to_post_id,
       COALESCE(ru.username,'') AS reply_author,COALESCE(rp.body,'') AS reply_body,p.body,p.msg_type,
       p.media_url,p.thumb_url,p.view_count,p.comment_count,p.edited_at,p.created_at
FROM channel_posts p JOIN users u ON u.id=p.author_id
LEFT JOIN channel_posts rp ON rp.id=p.reply_to_post_id LEFT JOIN users ru ON ru.id=rp.author_id
WHERE p.channel_id=$1 AND p.post_seq>$2 AND p.deleted_at IS NULL
ORDER BY p.post_seq ASC LIMIT $3`, channelID, after, limit)
	if err != nil {
		return nil, 0, err
	}
	var serverSeq int64
	err = s.db.GetContext(ctx, &serverSeq, `SELECT post_seq FROM channel_sequences WHERE channel_id=$1`, channelID)
	return posts, serverSeq, err
}

func (s *ChannelStore) SetNotificationLevel(ctx context.Context, channelID, userID, level string) error {
	result, err := s.db.ExecContext(ctx, `UPDATE channel_subscriptions SET notification_level=$3 WHERE channel_id=$1 AND user_id=$2`, channelID, userID, level)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return ErrChannelNotSubscribed
	}
	return nil
}

func (s *ChannelStore) MarkRead(ctx context.Context, channelID, userID string, seq int64) error {
	var serverSeq int64
	if err := s.db.GetContext(ctx, &serverSeq, `SELECT post_seq FROM channel_sequences WHERE channel_id=$1`, channelID); err != nil {
		return err
	}
	if seq > serverSeq {
		return ErrChannelReadSeqAhead
	}
	result, err := s.db.ExecContext(ctx, `
UPDATE channel_subscriptions SET last_read_post_seq=MAX(last_read_post_seq,$3)
WHERE channel_id=$1 AND user_id=$2`, channelID, userID, seq)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return ErrChannelNotSubscribed
	}
	return nil
}

// IncrementViewsOnRead 在用户上报已读到 seq 时，对用户首次读到的帖子递增观看次数。
// 以 viewed_post_seq 为水位，每个用户对每条帖子只计一次，避免重复刷新刷量。
func (s *ChannelStore) IncrementViewsOnRead(ctx context.Context, channelID, userID string, readSeq int64) error {
	if readSeq <= 0 {
		return nil
	}
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var viewedSeq int64
	err = tx.GetContext(ctx, &viewedSeq, `SELECT viewed_post_seq FROM channel_subscriptions WHERE channel_id=$1 AND user_id=$2`, channelID, userID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrChannelNotSubscribed
		}
		return err
	}
	if readSeq <= viewedSeq {
		return tx.Commit()
	}
	// 只对用户首次阅读的区间计数；跳过加入频道前的历史帖（joined_post_seq 之前不补计）。
	from := viewedSeq + 1
	if from < 1 {
		from = 1
	}
	_, err = tx.ExecContext(ctx, `UPDATE channel_posts SET view_count=view_count+1 WHERE channel_id=$1 AND post_seq>=$2 AND post_seq<=$3 AND deleted_at IS NULL`, channelID, from, readSeq)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `UPDATE channel_subscriptions SET viewed_post_seq=$3 WHERE channel_id=$1 AND user_id=$2`, channelID, userID, readSeq)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (s *ChannelStore) State(ctx context.Context, channelID, userID string) (*ChannelState, error) {
	var state ChannelState
	err := s.db.GetContext(ctx, &state, `
SELECT c.id AS channel_id,c.name,c.handle,c.avatar_url,c.subscriber_count,
       q.post_seq,q.event_seq,q.settings_version,s.role,s.joined_post_seq,s.last_read_post_seq,
       s.notification_level,s.muted_until,
       (SELECT COUNT(1) FROM channel_posts p WHERE p.channel_id=c.id
          AND p.post_seq>MAX(s.last_read_post_seq,s.joined_post_seq) AND p.deleted_at IS NULL) AS unread_count
FROM channels c JOIN channel_sequences q ON q.channel_id=c.id
JOIN channel_subscriptions s ON s.channel_id=c.id AND s.user_id=$2
WHERE c.id=$1 AND c.status='active'`, channelID, userID)
	return &state, err
}

func (s *ChannelStore) ListSubscriptionIDs(ctx context.Context, userID string, limit int) ([]string, error) {
	ids := make([]string, 0)
	err := s.db.SelectContext(ctx, &ids, `SELECT channel_id FROM channel_subscriptions WHERE user_id=$1 ORDER BY joined_at DESC LIMIT $2`, userID, limit)
	return ids, err
}

func (s *ChannelStore) ListStates(ctx context.Context, userID string, limit int) ([]ChannelState, error) {
	states := make([]ChannelState, 0)
	err := s.db.SelectContext(ctx, &states, `
SELECT c.id AS channel_id,c.name,c.handle,c.avatar_url,c.subscriber_count,
       q.post_seq,q.event_seq,q.settings_version,s.role,s.joined_post_seq,s.last_read_post_seq,
       s.notification_level,s.muted_until,
       (SELECT COUNT(1) FROM channel_posts p WHERE p.channel_id=c.id
          AND p.post_seq>MAX(s.last_read_post_seq,s.joined_post_seq) AND p.deleted_at IS NULL) AS unread_count
FROM channel_subscriptions s JOIN channels c ON c.id=s.channel_id
JOIN channel_sequences q ON q.channel_id=c.id
WHERE s.user_id=$1 AND c.status='active'
ORDER BY c.updated_at DESC LIMIT $2`, userID, limit)
	return states, err
}

func NormalizeChannelHandle(value string) string {
	return strings.ToLower(strings.TrimSpace(strings.TrimPrefix(value, "@")))
}

func IsNotFound(err error) bool { return errors.Is(err, sql.ErrNoRows) }
