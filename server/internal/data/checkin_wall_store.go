package data

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jmoiron/sqlx"
)

var ErrDailyCheckInRequired = errors.New("daily check-in required")
var ErrDailyCheckInPostExists = errors.New("daily check-in post already exists")
var ErrInvalidDailyCheckInPost = errors.New("invalid daily check-in post")

const dailyCheckInWallTextLimit = 120

type CheckInWallStore struct {
	db *sqlx.DB
}

type CheckInWallMessage struct {
	ID             string    `db:"id"`
	UserID         string    `db:"user_id"`
	CheckinDate    string    `db:"checkin_date"`
	ContentText    string    `db:"content_text"`
	ImageURL       string    `db:"image_url"`
	ThumbURL       string    `db:"thumb_url"`
	CreatedAt      time.Time `db:"created_at"`
	LikeCount      int       `db:"-"`
	CommentCount   int       `db:"-"`
	LikedByMe      bool      `db:"-"`
	UID            string    `db:"uid"`
	Username       string    `db:"username"`
	DisplayName    string    `db:"display_name"`
	UserTitle      string    `db:"user_title"`
	AvatarURL      string    `db:"avatar_url"`
	Signature      string    `db:"signature"`
	CoverURL       string    `db:"cover_url"`
	PresenceStatus string    `db:"presence_status"`
}

type DailyCheckInWallState struct {
	CheckinDate      string
	CheckInCount     int
	CheckedIn        bool
	AlreadyPosted    bool
	MyPost           *CheckInWallMessage
	FeaturedMessages []CheckInWallMessage
}

func NewCheckInWallStore(db *sqlx.DB) *CheckInWallStore {
	return &CheckInWallStore{db: db}
}

func (s *CheckInWallStore) GetTodayState(ctx context.Context, userID string, limit int) (*DailyCheckInWallState, error) {
	if userID == "" {
		return nil, ErrNotFound
	}
	if limit <= 0 || limit > 10 {
		limit = 10
	}
	today := dailyCheckInWallToday()
	state := &DailyCheckInWallState{CheckinDate: today}

	if err := s.db.QueryRowContext(ctx, `
SELECT COUNT(1)
FROM user_daily_checkins
WHERE checkin_date = $1
`, today).Scan(&state.CheckInCount); err != nil {
		return nil, err
	}

	checkedIn, err := s.hasDailyCheckIn(ctx, userID, today)
	if err != nil {
		return nil, err
	}
	state.CheckedIn = checkedIn

	alreadyPosted, err := s.hasDailyCheckInPost(ctx, userID, today)
	if err != nil {
		return nil, err
	}
	state.AlreadyPosted = alreadyPosted

	if state.AlreadyPosted {
		post, getErr := s.getUserPostForDate(ctx, userID, today)
		if getErr != nil {
			return nil, getErr
		}
		state.MyPost = post
	}

	featured := make([]CheckInWallMessage, 0, limit)
	if err := s.db.SelectContext(ctx, &featured, `
SELECT p.id, p.user_id, p.checkin_date, p.content_text, p.image_url, p.thumb_url, p.created_at,
       u.uid, u.username, u.display_name, u.user_title, u.avatar_url, u.signature, u.cover_url, u.presence_status
FROM daily_checkin_wall_posts p
JOIN users u ON u.id = p.user_id
WHERE p.checkin_date = $1
ORDER BY RANDOM()
LIMIT $2
`, today, limit); err != nil {
		return nil, err
	}
	state.FeaturedMessages = featured
	return state, nil
}

func (s *CheckInWallStore) CreateTodayPost(ctx context.Context, userID, contentText, imageURL, thumbURL string) (*CheckInWallMessage, error) {
	if userID == "" {
		return nil, ErrNotFound
	}
	contentText = strings.TrimSpace(contentText)
	imageURL = strings.TrimSpace(imageURL)
	thumbURL = strings.TrimSpace(thumbURL)
	if err := validateDailyCheckInPost(contentText, imageURL); err != nil {
		return nil, err
	}
	today := dailyCheckInWallToday()
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer func() {
		if tx != nil {
			_ = tx.Rollback()
		}
	}()

	checkedIn, err := hasDailyCheckInTx(ctx, tx, userID, today)
	if err != nil {
		return nil, err
	}
	if !checkedIn {
		return nil, ErrDailyCheckInRequired
	}

	res, err := tx.ExecContext(ctx, `
INSERT INTO daily_checkin_wall_posts (
    id, user_id, checkin_date, content_text, image_url, thumb_url, created_at
) VALUES (
    $1, $2, $3, $4, $5, $6, CURRENT_TIMESTAMP
)
ON CONFLICT(user_id, checkin_date)
DO NOTHING
`, NewID(), userID, today, contentText, imageURL, thumbURL)
	if err != nil {
		return nil, err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return nil, err
	}
	if rows == 0 {
		return nil, ErrDailyCheckInPostExists
	}

	post, err := getUserPostForDateTx(ctx, tx, userID, today)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	tx = nil
	return post, nil
}

func (s *CheckInWallStore) GetByID(ctx context.Context, postID string) (*CheckInWallMessage, error) {
	if postID == "" {
		return nil, ErrNotFound
	}
	var post CheckInWallMessage
	err := s.db.GetContext(ctx, &post, `
SELECT p.id, p.user_id, p.checkin_date, p.content_text, p.image_url, p.thumb_url, p.created_at,
       u.uid, u.username, u.display_name, u.user_title, u.avatar_url, u.signature, u.cover_url, u.presence_status
FROM daily_checkin_wall_posts p
JOIN users u ON u.id = p.user_id
WHERE p.id = $1
LIMIT 1
`, postID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &post, nil
}

func (s *CheckInWallStore) hasDailyCheckIn(ctx context.Context, userID, checkinDate string) (bool, error) {
	return hasDailyCheckInQuery(ctx, s.db, userID, checkinDate)
}

func (s *CheckInWallStore) hasDailyCheckInPost(ctx context.Context, userID, checkinDate string) (bool, error) {
	count := 0
	err := s.db.QueryRowContext(ctx, `
SELECT COUNT(1)
FROM daily_checkin_wall_posts
WHERE user_id = $1 AND checkin_date = $2
`, userID, checkinDate).Scan(&count)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

func (s *CheckInWallStore) getUserPostForDate(ctx context.Context, userID, checkinDate string) (*CheckInWallMessage, error) {
	return getUserPostForDateQuery(ctx, s.db, userID, checkinDate)
}

func validateDailyCheckInPost(contentText, imageURL string) error {
	hasText := contentText != ""
	hasImage := imageURL != ""
	if hasText == hasImage {
		return ErrInvalidDailyCheckInPost
	}
	if hasText && utf8.RuneCountInString(contentText) > dailyCheckInWallTextLimit {
		return ErrInvalidDailyCheckInPost
	}
	return nil
}

func dailyCheckInWallToday() string {
	return time.Now().Local().Format("2006-01-02")
}

func hasDailyCheckInTx(ctx context.Context, tx *sqlx.Tx, userID, checkinDate string) (bool, error) {
	return hasDailyCheckInQuery(ctx, tx, userID, checkinDate)
}

func hasDailyCheckInQuery(ctx context.Context, queryer sqlx.QueryerContext, userID, checkinDate string) (bool, error) {
	count := 0
	err := sqlx.GetContext(ctx, queryer, &count, `
SELECT COUNT(1)
FROM user_daily_checkins
WHERE user_id = $1 AND checkin_date = $2
`, userID, checkinDate)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

func getUserPostForDateTx(ctx context.Context, tx *sqlx.Tx, userID, checkinDate string) (*CheckInWallMessage, error) {
	return getUserPostForDateQuery(ctx, tx, userID, checkinDate)
}

func getUserPostForDateQuery(ctx context.Context, queryer sqlx.QueryerContext, userID, checkinDate string) (*CheckInWallMessage, error) {
	var post CheckInWallMessage
	err := sqlx.GetContext(ctx, queryer, &post, `
SELECT p.id, p.user_id, p.checkin_date, p.content_text, p.image_url, p.thumb_url, p.created_at,
       u.uid, u.username, u.display_name, u.user_title, u.avatar_url, u.signature, u.cover_url, u.presence_status
FROM daily_checkin_wall_posts p
JOIN users u ON u.id = p.user_id
WHERE p.user_id = $1 AND p.checkin_date = $2
LIMIT 1
`, userID, checkinDate)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &post, nil
}
