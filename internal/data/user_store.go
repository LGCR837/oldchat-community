package data

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
)

var ErrNotFound = errors.New("not found")
var ErrUIDTooSoon = errors.New("uid change too soon")

type UserStore struct {
	db *sqlx.DB
}

type UserAdminRow struct {
	ID              string    `db:"id"`
	UID             string    `db:"uid"`
	Username        string    `db:"username"`
	Email           string    `db:"email"`
	UserTitle       string    `db:"user_title"`
	CoinBalance     int       `db:"coin_balance"`
	ReputationScore int       `db:"reputation_score"`
	BanCount        int       `db:"ban_count"`
	CreatedAt       time.Time `db:"created_at"`
	Banned          int       `db:"banned"`
}

type UserActiveRow struct {
	ID           string    `db:"id"`
	UID          string    `db:"uid"`
	Username     string    `db:"username"`
	Email        string    `db:"email"`
	LastActivity time.Time `db:"last_activity"`
	DirectCount  int       `db:"direct_count"`
	GroupCount   int       `db:"group_count"`
	MomentCount  int       `db:"moment_count"`
	MessageCount int       `db:"message_count"`
}

type UserDailyCheckInResult struct {
	CheckinDate      string
	AlreadyChecked   bool
	CoinReward       int
	ReputationReward int
	CoinBalance      int
	ReputationScore  int
}

func NewUserStore(db *sqlx.DB) *UserStore {
	return &UserStore{db: db}
}

func (s *UserStore) Create(ctx context.Context, u *User) error {
	if u != nil {
		if u.CoinBalance < 0 {
			u.CoinBalance = 0
		}
		if u.ReputationScore > 200 {
			u.ReputationScore = 200
		}
	}
	if u.Ncuid == "" {
		u.Ncuid = u.UID
	}
	const q = `
INSERT INTO users (
	id, uid, ncuid, uid_changed_at, email, username, display_name, user_title, user_title_price,
	avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score, ban_count
) VALUES (
	:id, :uid, :ncuid, CURRENT_TIMESTAMP, :email, :username, :display_name, :user_title, :user_title_price,
	:avatar_url, :signature, :cover_url, :password_hash, :token_version, :coin_balance, :reputation_score, :ban_count
)`

	_, err := s.db.NamedExecContext(ctx, q, u)
	return err
}

// CreateAndJoinGroup 原子创建用户并加入指定官方群聊。
func (s *UserStore) CreateAndJoinGroup(ctx context.Context, u *User, groupID string) error {
	if u != nil {
		if u.CoinBalance < 0 {
			u.CoinBalance = 0
		}
		if u.ReputationScore > 200 {
			u.ReputationScore = 200
		}
	}
	if u == nil || groupID == "" {
		return ErrNotFound
	}
	if u.Ncuid == "" {
		u.Ncuid = u.UID
	}
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, err = tx.NamedExecContext(ctx, `
INSERT INTO users (
	id, uid, ncuid, uid_changed_at, email, username, display_name, user_title, user_title_price,
	avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score, ban_count
) VALUES (
	:id, :uid, :ncuid, CURRENT_TIMESTAMP, :email, :username, :display_name, :user_title, :user_title_price,
	:avatar_url, :signature, :cover_url, :password_hash, :token_version, :coin_balance, :reputation_score, :ban_count
)`, u)
	if err != nil {
		return err
	}
	var exists int
	if err = tx.GetContext(ctx, &exists, `SELECT 1 FROM groups WHERE id=$1`, groupID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	_, err = tx.ExecContext(ctx, `
INSERT INTO group_members (group_id, user_id, role, joined_at, last_read_at)
VALUES ($1, $2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING`, groupID, u.ID)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (s *UserStore) GetByEmailOrUsername(ctx context.Context, identifier string) (*User, error) {
	trimmed := strings.TrimSpace(identifier)
	if trimmed == "" {
		return nil, ErrNotFound
	}
	const q = `
SELECT id, uid, ncuid, uid_changed_at, email, username, display_name, user_title, user_title_price,
       avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score,
       presence_status, ban_count, created_at, updated_at
FROM users
WHERE LOWER(email) = LOWER($1) OR LOWER(username) = LOWER($1) OR uid = $1 OR ncuid = $1
LIMIT 1`

	var u User
	if err := s.db.GetContext(ctx, &u, q, trimmed); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}

	return &u, nil
}

func (s *UserStore) GetByID(ctx context.Context, id string) (*User, error) {
	const q = `
SELECT id, uid, ncuid, uid_changed_at, email, username, display_name, user_title, user_title_price, avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score, presence_status, ban_count, created_at, updated_at
FROM users
WHERE id = $1
LIMIT 1`

	var u User
	if err := s.db.GetContext(ctx, &u, q, id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}

	return &u, nil
}

// GetDisplayNameByUID 按公开 UID 查询显示名（群消息填充 from_name 用）。
func (s *UserStore) GetDisplayNameByUID(ctx context.Context, uid string) (string, error) {
	if uid == "" {
		return "", nil
	}
	var name string
	err := s.db.GetContext(ctx, &name,
		`SELECT display_name FROM users WHERE uid = $1 LIMIT 1`, uid)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", nil
		}
		return "", err
	}
	return name, nil
}

func (s *UserStore) GetByUID(ctx context.Context, uid string) (*User, error) {
	const q = `
SELECT id, uid, ncuid, uid_changed_at, email, username, display_name, user_title, user_title_price, avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score, presence_status, ban_count, created_at, updated_at
FROM users
WHERE uid = $1
LIMIT 1`

	var u User
	if err := s.db.GetContext(ctx, &u, q, uid); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}

	return &u, nil
}

// GetByNcuid 使用不可变的 NCUID 查询用户，供客户端身份主链路使用。
func (s *UserStore) GetByNcuid(ctx context.Context, ncuid string) (*User, error) {
	const q = `
SELECT id, uid, ncuid, uid_changed_at, email, username, display_name, user_title, user_title_price, avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score, presence_status, ban_count, created_at, updated_at
FROM users
WHERE ncuid = $1
LIMIT 1`

	var u User
	if err := s.db.GetContext(ctx, &u, q, ncuid); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &u, nil
}

func (s *UserStore) UpdateUID(ctx context.Context, id, newUID string, cutoff time.Time) error {
	res, err := s.db.ExecContext(ctx, `
UPDATE users
SET uid = $1, uid_changed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
WHERE id = $2 AND (uid_changed_at <= $3 OR uid_changed_at = created_at)
`, newUID, id, cutoff)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrUIDTooSoon
	}
	return nil
}

// UpdateUIDForce updates UID without enforcing cooldown.
func (s *UserStore) UpdateUIDForce(ctx context.Context, id, newUID string) error {
	if id == "" || newUID == "" {
		return ErrNotFound
	}
	res, err := s.db.ExecContext(ctx, `
UPDATE users
SET uid = $1, uid_changed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
WHERE id = $2
`, newUID, id)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *UserStore) UpdateProfile(ctx context.Context, id, displayName, avatarURL, signature, coverURL string) error {
	res, err := s.db.ExecContext(ctx, `
UPDATE users
SET display_name = $1, avatar_url = $2, signature = $3, cover_url = $4, updated_at = CURRENT_TIMESTAMP
WHERE id = $5
`, displayName, avatarURL, signature, coverURL, id)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *UserStore) UpdateTitleByUID(ctx context.Context, uid, title string) error {
	res, err := s.db.ExecContext(ctx, `
UPDATE users
SET user_title = $1, updated_at = CURRENT_TIMESTAMP
WHERE uid = $2
`, title, uid)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *UserStore) UpdateTitleWithPriceByUID(ctx context.Context, uid, title string, price int) error {
	if title == "" {
		price = 0
	}
	res, err := s.db.ExecContext(ctx, `
UPDATE users
SET user_title = $1, user_title_price = $2, updated_at = CURRENT_TIMESTAMP
WHERE uid = $3
`, title, price, uid)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *UserStore) UpdateCoinByUID(ctx context.Context, uid string, balance int) error {
	if balance < 0 {
		balance = 0
	}
	res, err := s.db.ExecContext(ctx, `
UPDATE users
SET coin_balance = $1, updated_at = CURRENT_TIMESTAMP
WHERE uid = $2
`, balance, uid)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *UserStore) DailyCheckIn(ctx context.Context, userID string, coinReward, reputationReward int) (*UserDailyCheckInResult, error) {
	if userID == "" {
		return nil, ErrNotFound
	}
	if coinReward < 0 {
		coinReward = 0
	}
	today := time.Now().Local().Format("2006-01-02")
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer func() {
		if tx != nil {
			_ = tx.Rollback()
		}
	}()

	checkinID := NewID()
	res, err := tx.ExecContext(ctx, `
INSERT INTO user_daily_checkins (
    id, user_id, checkin_date, coin_reward, reputation_reward, created_at
) VALUES (
    $1, $2, $3, $4, $5, CURRENT_TIMESTAMP
)
ON CONFLICT(user_id, checkin_date)
DO NOTHING
`, checkinID, userID, today, coinReward, reputationReward)
	if err != nil {
		return nil, err
	}

	rows, err := res.RowsAffected()
	if err != nil {
		return nil, err
	}

	alreadyChecked := rows == 0
	appliedCoinReward := 0
	appliedReputationReward := 0
	if !alreadyChecked {
		appliedCoinReward = coinReward
		var curRep int
		_ = tx.GetContext(ctx, &curRep, `SELECT reputation_score FROM users WHERE id = $1`, userID)
		appliedReputationReward = reputationReward
		if curRep+appliedReputationReward > 200 {
			appliedReputationReward = 200 - curRep
			if appliedReputationReward < 0 {
				appliedReputationReward = 0
			}
		}
		_, err = tx.ExecContext(ctx, `
UPDATE users
SET coin_balance = coin_balance + $1,
    reputation_score = CASE WHEN reputation_score + $2 > 200 THEN 200 ELSE reputation_score + $2 END,
    updated_at = CURRENT_TIMESTAMP
WHERE id = $3
`, appliedCoinReward, reputationReward, userID)
		if err != nil {
			return nil, err
		}
	}

	var balance int
	var reputation int
	err = tx.QueryRowContext(ctx, `
SELECT coin_balance, reputation_score
FROM users
WHERE id = $1
`, userID).Scan(&balance, &reputation)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}

	if err = tx.Commit(); err != nil {
		return nil, err
	}
	tx = nil

	return &UserDailyCheckInResult{
		CheckinDate:      today,
		AlreadyChecked:   alreadyChecked,
		CoinReward:       appliedCoinReward,
		ReputationReward: appliedReputationReward,
		CoinBalance:      balance,
		ReputationScore:  reputation,
	}, nil
}

// ResetPasswordAndRevokeAll 原子更新密码并撤销 Oldchat 与 OAuth 全部会话。
func (s *UserStore) ResetPasswordAndRevokeAll(ctx context.Context, id, passwordHash string) (int, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	// legacy 中间件为旧客户端保留最近两个版本，因此安全重置需一次推进 3。
	res, err := tx.ExecContext(ctx, `UPDATE users SET password_hash=$1,token_version=token_version+3,updated_at=CURRENT_TIMESTAMP WHERE id=$2`, passwordHash, id)
	if err != nil {
		return 0, err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	if rows != 1 {
		return 0, ErrNotFound
	}
	if _, err = tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE user_id=$1`, id); err != nil {
		return 0, err
	}
	var grants []string
	if err = tx.SelectContext(ctx, &grants, `SELECT id FROM oauth_grants WHERE user_id=$1 AND revoked_at IS NULL`, id); err != nil {
		return 0, err
	}
	for _, grantID := range grants {
		if _, err = tx.ExecContext(ctx, `UPDATE oauth_grants SET revoked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=$1`, grantID); err != nil {
			return 0, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE oauth_access_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE grant_id=$1`, grantID); err != nil {
			return 0, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE oauth_refresh_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE grant_id=$1`, grantID); err != nil {
			return 0, err
		}
	}
	var version int
	if err = tx.GetContext(ctx, &version, `SELECT token_version FROM users WHERE id=$1`, id); err != nil {
		return 0, err
	}
	return version, tx.Commit()
}

func (s *UserStore) UpdatePassword(ctx context.Context, id, passwordHash string) error {
	res, err := s.db.ExecContext(ctx, `
UPDATE users
SET password_hash = $1, updated_at = CURRENT_TIMESTAMP
WHERE id = $2
`, passwordHash, id)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *UserStore) DeleteByID(ctx context.Context, id string) error {
	if id == "" {
		return ErrNotFound
	}
	res, err := s.db.ExecContext(ctx, `DELETE FROM users WHERE id = $1`, id)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteByIDAndReleaseAssets 在同一事务内释放账号独占资源并删除用户。
// 用户行删除后，email/username/uid/ncuid 的唯一索引占用自动释放；
// 同时将用户绑定的邮箱写入 deleted_account_cooldowns 表，设置 10 天注册冷却期。
func (s *UserStore) DeleteByIDAndReleaseAssets(ctx context.Context, id string) error {
	if id == "" {
		return ErrNotFound
	}
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var email string
	_ = tx.GetContext(ctx, &email, `SELECT email FROM users WHERE id = $1`, id)
	email = strings.ToLower(strings.TrimSpace(email))

	// 自定义/专属称号在 title_catalog 中有 owner_id；先删除才能释放唯一称号名。
	if _, err = tx.ExecContext(ctx, `DELETE FROM title_catalog WHERE owner_id = $1`, id); err != nil {
		return err
	}
	res, err := tx.ExecContext(ctx, `DELETE FROM users WHERE id = $1`, id)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}

	if email != "" {
		now := time.Now().UTC()
		until := now.Add(10 * 24 * time.Hour)
		_, err = tx.ExecContext(ctx, `
			INSERT INTO deleted_account_cooldowns (email, deleted_user_id, deleted_at, cooldown_until)
			VALUES ($1, $2, $3, $4)
			ON CONFLICT(email) DO UPDATE SET
				deleted_user_id = excluded.deleted_user_id,
				deleted_at = excluded.deleted_at,
				cooldown_until = excluded.cooldown_until
		`, email, id, now, until)
		if err != nil {
			return err
		}
	}

	return tx.Commit()
}

// GetDeletedAccountCooldown 检查邮箱是否处于注销账号 10 天冷却期内
func (s *UserStore) GetDeletedAccountCooldown(ctx context.Context, email string) (bool, time.Time, error) {
	email = strings.ToLower(strings.TrimSpace(email))
	if email == "" {
		return false, time.Time{}, nil
	}
	var cdUntil time.Time
	err := s.db.GetContext(ctx, &cdUntil, `
		SELECT cooldown_until FROM deleted_account_cooldowns
		WHERE email = $1 AND cooldown_until > CURRENT_TIMESTAMP
		LIMIT 1
	`, email)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, time.Time{}, nil
		}
		return false, time.Time{}, err
	}
	return true, cdUntil, nil
}

// RecordDeletedAccountCooldown 手动记录注销邮箱冷却期（便于测试与管理操作）
func (s *UserStore) RecordDeletedAccountCooldown(ctx context.Context, email, userID string, duration time.Duration) error {
	email = strings.ToLower(strings.TrimSpace(email))
	if email == "" {
		return nil
	}
	if duration <= 0 {
		duration = 10 * 24 * time.Hour
	}
	now := time.Now().UTC()
	until := now.Add(duration)
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO deleted_account_cooldowns (email, deleted_user_id, deleted_at, cooldown_until)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT(email) DO UPDATE SET
			deleted_user_id = excluded.deleted_user_id,
			deleted_at = excluded.deleted_at,
			cooldown_until = excluded.cooldown_until
	`, email, userID, now, until)
	return err
}

func (s *UserStore) RejectGroupInvites(ctx context.Context, id string) (bool, error) {
	var enabled int
	err := s.db.GetContext(ctx, &enabled, `SELECT reject_group_invites FROM users WHERE id=$1`, id)
	if errors.Is(err, sql.ErrNoRows) {
		return false, ErrNotFound
	}
	return enabled != 0, err
}

func (s *UserStore) SetRejectGroupInvites(ctx context.Context, id string, enabled bool) error {
	value := 0
	if enabled {
		value = 1
	}
	res, err := s.db.ExecContext(ctx, `UPDATE users SET reject_group_invites=$1,
        updated_at=CURRENT_TIMESTAMP WHERE id=$2`, value, id)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *UserStore) ListActiveUsers(ctx context.Context, start, end time.Time, limit int) ([]UserActiveRow, error) {
	if limit <= 0 {
		limit = 200
	}
	if start.After(end) {
		start, end = end, start
	}
	startUTC := start.UTC().Format("2006-01-02 15:04:05")
	endUTC := end.UTC().Format("2006-01-02 15:04:05")
	const q = `
WITH activity AS (
	SELECT sender_id AS user_id, created_at AS ts, 'direct' AS src
	FROM direct_messages
	WHERE created_at >= $1 AND created_at <= $2
	UNION ALL
	SELECT sender_id AS user_id, created_at AS ts, 'group' AS src
	FROM group_messages
	WHERE created_at >= $1 AND created_at <= $2
)
SELECT u.id, u.uid, u.username, u.email,
       MAX(activity.ts) AS last_activity,
       SUM(CASE WHEN activity.src = 'direct' THEN 1 ELSE 0 END) AS direct_count,
       SUM(CASE WHEN activity.src = 'group' THEN 1 ELSE 0 END) AS group_count,
       0 AS moment_count,
       COUNT(1) AS message_count
FROM activity
JOIN users u ON u.id = activity.user_id
GROUP BY u.id, u.uid, u.username, u.email
ORDER BY message_count DESC, last_activity DESC
LIMIT $3`
	rows := []UserActiveRow{}
	if err := s.db.SelectContext(ctx, &rows, q, startUTC, endUTC, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *UserStore) IncrementTokenVersion(ctx context.Context, id string) (int, error) {
	if id == "" {
		return 0, ErrNotFound
	}
	_, err := s.db.ExecContext(ctx, `
UPDATE users
SET token_version = token_version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = $1
`, id)
	if err != nil {
		return 0, err
	}
	var v int
	if err := s.db.GetContext(ctx, &v, `SELECT token_version FROM users WHERE id = $1`, id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, ErrNotFound
		}
		return 0, err
	}
	return v, nil
}

func (s *UserStore) GetTokenVersion(ctx context.Context, id string) (int, error) {
	if id == "" {
		return 0, ErrNotFound
	}
	var v int
	if err := s.db.GetContext(ctx, &v, `SELECT token_version FROM users WHERE id = $1`, id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, ErrNotFound
		}
		return 0, err
	}
	return v, nil
}

func (s *UserStore) Count(ctx context.Context) (int, error) {
	var count int
	if err := s.db.GetContext(ctx, &count, `SELECT COUNT(*) FROM users WHERE id != 'SYSTEM'`); err != nil {
		return 0, err
	}
	return count, nil
}

func (s *UserStore) ListRecent(ctx context.Context, limit int) ([]UserAdminRow, error) {
	return s.ListRecentPage(ctx, limit, 0)
}

func (s *UserStore) ListRecentPage(ctx context.Context, limit, offset int) ([]UserAdminRow, error) {
	if limit <= 0 || limit > 1000 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	const q = `
SELECT u.id, u.uid, u.username, u.email, u.user_title, u.coin_balance, u.reputation_score, u.ban_count, u.created_at,
CASE WHEN b.user_id IS NULL THEN 0 ELSE 1 END AS banned
FROM users u
LEFT JOIN banned_users b ON b.user_id = u.id
WHERE u.id != 'SYSTEM'
ORDER BY u.created_at DESC, u.id DESC
LIMIT $1 OFFSET $2`
	rows := []UserAdminRow{}
	if err := s.db.SelectContext(ctx, &rows, q, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *UserStore) ListAll(ctx context.Context) ([]UserAdminRow, error) {
	const q = `
SELECT u.id, u.uid, u.username, u.email, u.user_title, u.coin_balance, u.reputation_score, u.ban_count, u.created_at,
CASE WHEN b.user_id IS NULL THEN 0 ELSE 1 END AS banned
FROM users u
LEFT JOIN banned_users b ON b.user_id = u.id
ORDER BY u.created_at DESC`
	rows := []UserAdminRow{}
	if err := s.db.SelectContext(ctx, &rows, q); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *UserStore) SearchUsersForInspector(ctx context.Context, keyword string, limit int) ([]User, error) {
	if limit <= 0 || limit > 50 {
		limit = 20
	}
	trimmed := keyword
	if trimmed == "" {
		return []User{}, nil
	}
	const q = `
SELECT id, uid, ncuid, uid_changed_at, email, username, display_name, user_title, user_title_price,
       avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score,
       presence_status, ban_count, created_at, updated_at
FROM users
WHERE id = $1 OR uid = $1 OR ncuid = $1 OR username LIKE '%' || $1 || '%' OR display_name LIKE '%' || $1 || '%'
ORDER BY CASE 
    WHEN uid = $1 OR ncuid = $1 OR id = $1 THEN 1
    WHEN username = $1 OR display_name = $1 THEN 2
    ELSE 3
END, created_at DESC
LIMIT $2`
	var users []User
	if err := s.db.SelectContext(ctx, &users, q, trimmed, limit); err != nil {
		return nil, err
	}
	return users, nil
}

func (s *UserStore) CountFriends(ctx context.Context, userID string) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(*) FROM friendships WHERE user_a_id = $1 OR user_b_id = $1`, userID)
	return count, err
}

func (s *UserStore) CountJoinedGroups(ctx context.Context, userID string) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(*) FROM group_members WHERE user_id = $1`, userID)
	return count, err
}

func (s *UserStore) IsUserBanned(ctx context.Context, userID string) (bool, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(*) FROM banned_users WHERE user_id = $1`, userID)
	return count > 0, err
}

func (s *UserStore) SearchAdminPage(ctx context.Context, keyword string, limit, offset int) ([]UserAdminRow, int, error) {
	if limit <= 0 || limit > 1000 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	keyword = strings.TrimSpace(keyword)
	if keyword == "" {
		rows, err := s.ListRecentPage(ctx, limit, offset)
		if err != nil {
			return nil, 0, err
		}
		total, _ := s.Count(ctx)
		return rows, total, nil
	}

	pattern := "%" + keyword + "%"
	const countQ = `
SELECT COUNT(*) FROM users u
WHERE u.id != 'SYSTEM' AND (u.username LIKE $1 OR u.uid LIKE $1 OR u.email LIKE $1 OR u.id LIKE $1)`
	var total int
	if err := s.db.GetContext(ctx, &total, countQ, pattern); err != nil {
		return nil, 0, err
	}

	const q = `
SELECT u.id, u.uid, u.username, u.email, u.user_title, u.coin_balance, u.reputation_score, u.ban_count, u.created_at,
CASE WHEN b.user_id IS NULL THEN 0 ELSE 1 END AS banned
FROM users u
LEFT JOIN banned_users b ON b.user_id = u.id
WHERE u.id != 'SYSTEM' AND (u.username LIKE $1 OR u.uid LIKE $1 OR u.email LIKE $1 OR u.id LIKE $1)
ORDER BY u.created_at DESC, u.id DESC
LIMIT $2 OFFSET $3`
	rows := []UserAdminRow{}
	if err := s.db.SelectContext(ctx, &rows, q, pattern, limit, offset); err != nil {
		return nil, 0, err
	}
	return rows, total, nil
}


