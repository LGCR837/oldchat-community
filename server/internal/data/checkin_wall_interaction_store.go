package data

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jmoiron/sqlx"
)

var ErrInvalidDailyCheckInComment = errors.New("invalid daily check-in comment")

const dailyCheckInWallCommentLimit = 180

type CheckInWallInteractionStore struct {
	db *sqlx.DB
}

type CheckInWallComment struct {
	ID          string    `db:"id"`
	PostID      string    `db:"post_id"`
	UserID      string    `db:"user_id"`
	Body        string    `db:"body"`
	CreatedAt   time.Time `db:"created_at"`
	UID         string    `db:"uid"`
	Username    string    `db:"username"`
	DisplayName string    `db:"display_name"`
	UserTitle   string    `db:"user_title"`
	AvatarURL   string    `db:"avatar_url"`
}

type CheckInWallLikeUser struct {
	UserID      string    `db:"user_id"`
	UID         string    `db:"uid"`
	Username    string    `db:"username"`
	DisplayName string    `db:"display_name"`
	UserTitle   string    `db:"user_title"`
	AvatarURL   string    `db:"avatar_url"`
	CreatedAt   time.Time `db:"created_at"`
}

func NewCheckInWallInteractionStore(db *sqlx.DB) *CheckInWallInteractionStore {
	return &CheckInWallInteractionStore{db: db}
}

func (s *CheckInWallInteractionStore) Like(ctx context.Context, postID, userID string) error {
	_, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO daily_checkin_wall_likes (post_id, user_id, created_at)
VALUES ($1, $2, CURRENT_TIMESTAMP)
`, postID, userID)
	return err
}

func (s *CheckInWallInteractionStore) Unlike(ctx context.Context, postID, userID string) error {
	_, err := s.db.ExecContext(ctx, `
DELETE FROM daily_checkin_wall_likes WHERE post_id = $1 AND user_id = $2
`, postID, userID)
	return err
}

func (s *CheckInWallInteractionStore) CountLikes(ctx context.Context, postIDs []string) (map[string]int, error) {
	return countCheckInWallGrouped(ctx, s.db, "daily_checkin_wall_likes", "post_id", postIDs)
}

func (s *CheckInWallInteractionStore) CountComments(ctx context.Context, postIDs []string) (map[string]int, error) {
	return countCheckInWallGrouped(ctx, s.db, "daily_checkin_wall_comments", "post_id", postIDs)
}

func (s *CheckInWallInteractionStore) LikedBy(ctx context.Context, postIDs []string, userID string) (map[string]bool, error) {
	if len(postIDs) == 0 || userID == "" {
		return map[string]bool{}, nil
	}
	query, args, err := sqlx.In(`
SELECT post_id
FROM daily_checkin_wall_likes
WHERE post_id IN (?) AND user_id = ?`, postIDs, userID)
	if err != nil {
		return nil, err
	}
	query = s.db.Rebind(query)
	rows := []struct {
		PostID string `db:"post_id"`
	}{}
	if err := s.db.SelectContext(ctx, &rows, query, args...); err != nil {
		return nil, err
	}
	out := make(map[string]bool, len(rows))
	for _, row := range rows {
		out[row.PostID] = true
	}
	return out, nil
}

func (s *CheckInWallInteractionStore) AddComment(ctx context.Context, comment *CheckInWallComment) error {
	if comment == nil {
		return ErrInvalidDailyCheckInComment
	}
	comment.Body = strings.TrimSpace(comment.Body)
	if comment.ID == "" || comment.PostID == "" || comment.UserID == "" || !isValidDailyCheckInCommentBody(comment.Body) {
		return ErrInvalidDailyCheckInComment
	}
	_, err := s.db.NamedExecContext(ctx, `
INSERT INTO daily_checkin_wall_comments (id, post_id, user_id, body, created_at)
VALUES (:id, :post_id, :user_id, :body, CURRENT_TIMESTAMP)
`, comment)
	return err
}

func (s *CheckInWallInteractionStore) ListComments(ctx context.Context, postID string, limit int, before time.Time) ([]CheckInWallComment, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if before.IsZero() {
		before = time.Now()
	}
	comments := make([]CheckInWallComment, 0, limit)
	err := s.db.SelectContext(ctx, &comments, `
SELECT c.id, c.post_id, c.user_id, c.body, c.created_at,
       u.uid, u.username, u.display_name, u.user_title, u.avatar_url
FROM daily_checkin_wall_comments c
JOIN users u ON u.id = c.user_id
WHERE c.post_id = $1 AND c.created_at < $2
ORDER BY c.created_at DESC
LIMIT $3
`, postID, before, limit)
	if err != nil {
		return nil, err
	}
	return comments, nil
}

func (s *CheckInWallInteractionStore) ListLikes(ctx context.Context, postID string, limit int) ([]CheckInWallLikeUser, error) {
	if limit <= 0 || limit > 100 {
		limit = 40
	}
	likes := make([]CheckInWallLikeUser, 0, limit)
	err := s.db.SelectContext(ctx, &likes, `
SELECT l.user_id, l.created_at, u.uid, u.username, u.display_name, u.user_title, u.avatar_url
FROM daily_checkin_wall_likes l
JOIN users u ON u.id = l.user_id
WHERE l.post_id = $1
ORDER BY l.created_at DESC
LIMIT $2
`, postID, limit)
	if err != nil {
		return nil, err
	}
	return likes, nil
}

func countCheckInWallGrouped(ctx context.Context, db *sqlx.DB, table, column string, postIDs []string) (map[string]int, error) {
	if len(postIDs) == 0 {
		return map[string]int{}, nil
	}
	query, args, err := sqlx.In(`
SELECT `+column+`, COUNT(*) AS cnt
FROM `+table+`
WHERE `+column+` IN (?)
GROUP BY `+column, postIDs)
	if err != nil {
		return nil, err
	}
	query = db.Rebind(query)
	rows := []struct {
		PostID string `db:"post_id"`
		Count  int    `db:"cnt"`
	}{}
	if err := db.SelectContext(ctx, &rows, query, args...); err != nil {
		return nil, err
	}
	out := make(map[string]int, len(rows))
	for _, row := range rows {
		out[row.PostID] = row.Count
	}
	return out, nil
}

func isValidDailyCheckInCommentBody(body string) bool {
	count := utf8.RuneCountInString(strings.TrimSpace(body))
	return count >= 1 && count <= dailyCheckInWallCommentLimit
}
