package data

import (
	"context"
	"database/sql"
	"time"

	"github.com/jmoiron/sqlx"
)

type DeviceStore struct {
	db *sqlx.DB
}

type DeviceRow struct {
	DeviceID string    `db:"device_id"`
	IMEI     string    `db:"imei"`
	UserID   string    `db:"user_id"`
	UID      string    `db:"uid"`
	Username string    `db:"username"`
	LastSeen time.Time `db:"last_seen"`
	Banned   int       `db:"banned"`
}

type UserLoginDevice struct {
	DeviceID   string    `db:"device_id"`
	DeviceName string    `db:"device_name"`
	Platform   string    `db:"platform"`
	AppVersion string    `db:"app_version"`
	LastSeen   time.Time `db:"last_seen"`
	CreatedAt  time.Time `db:"created_at"`
}

type BannedDeviceRow struct {
	DeviceID    string       `db:"device_id"`
	Reason      string       `db:"reason"`
	CreatedAt   time.Time    `db:"created_at"`
	BannedUntil sql.NullTime `db:"banned_until"`
}

type BannedUserRow struct {
	UserID      string       `db:"user_id"`
	UID         string       `db:"uid"`
	Username    string       `db:"username"`
	Reason      string       `db:"reason"`
	CreatedAt   time.Time    `db:"created_at"`
	BannedUntil sql.NullTime `db:"banned_until"`
}

type UserBanLogRow struct {
	ID            string       `db:"id" json:"id"`
	UserID        string       `db:"user_id" json:"user_id"`
	Reason        string       `db:"reason" json:"reason"`
	DurationHours int          `db:"duration_hours" json:"duration_hours"`
	BannedUntil   sql.NullTime `db:"banned_until" json:"banned_until"`
	CreatedAt     time.Time    `db:"created_at" json:"created_at"`
}

func NewDeviceStore(db *sqlx.DB) *DeviceStore {
	return &DeviceStore{db: db}
}

func (s *DeviceStore) UpsertUserDevice(ctx context.Context, userID, deviceID, imei string) error {
	if deviceID == "" {
		return nil
	}
	const q = `
INSERT INTO user_devices (id, user_id, device_id, imei, last_seen)
VALUES ($1, $2, $3, $4, CURRENT_TIMESTAMP)
ON CONFLICT(user_id, device_id)
DO UPDATE SET imei = excluded.imei, last_seen = CURRENT_TIMESTAMP
`
	_, err := s.db.ExecContext(ctx, q, NewID(), userID, deviceID, imei)
	return err
}

func (s *DeviceStore) UpsertLoginDevice(ctx context.Context, userID, deviceID, deviceName, platform, appVersion string) error {
	if userID == "" || deviceID == "" {
		return nil
	}
	const q = `
INSERT INTO user_login_devices (id, user_id, device_id, device_name, platform, app_version, last_seen, created_at)
VALUES ($1, $2, $3, $4, $5, $6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT(user_id, device_id)
DO UPDATE SET device_name = excluded.device_name, platform = excluded.platform,
app_version = excluded.app_version, last_seen = CURRENT_TIMESTAMP
`
	_, err := s.db.ExecContext(ctx, q, NewID(), userID, deviceID, deviceName, platform, appVersion)
	return err
}

// IsDeviceOfUser 校验设备是否属于该用户（user_devices 表，1 次索引查询）。
func (s *DeviceStore) IsDeviceOfUser(ctx context.Context, userID, deviceID string) bool {
	if userID == "" || deviceID == "" {
		return false
	}
	var one int
	err := s.db.GetContext(ctx, &one,
		`SELECT 1 FROM user_devices WHERE user_id = $1 AND device_id = $2 LIMIT 1`,
		userID, deviceID)
	return err == nil && one == 1
}

func (s *DeviceStore) ListUserLoginDevices(ctx context.Context, userID string, limit int) ([]UserLoginDevice, error) {
	if userID == "" {
		return []UserLoginDevice{}, nil
	}
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	const q = `
SELECT device_id, device_name, platform, app_version, last_seen, created_at
FROM user_login_devices
WHERE user_id = $1
ORDER BY last_seen DESC
LIMIT $2`
	rows := []UserLoginDevice{}
	if err := s.db.SelectContext(ctx, &rows, q, userID, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DeviceStore) IsDeviceBanned(ctx context.Context, deviceID string) (bool, error) {
	if deviceID == "" {
		return false, nil
	}
	var found string
	err := s.db.GetContext(ctx, &found, `SELECT device_id FROM banned_devices WHERE device_id = $1 AND (banned_until IS NULL OR banned_until > CURRENT_TIMESTAMP)`, deviceID)
	if err == nil {
		return true, nil
	}
	if err == sql.ErrNoRows {
		return false, nil
	}
	return false, err
}

func (s *DeviceStore) IsUserBanned(ctx context.Context, userID string) (bool, error) {
	if userID == "" {
		return false, nil
	}
	var found string
	err := s.db.GetContext(ctx, &found, `SELECT user_id FROM banned_users WHERE user_id = $1 AND (banned_until IS NULL OR banned_until > CURRENT_TIMESTAMP)`, userID)
	if err == nil {
		return true, nil
	}
	if err == sql.ErrNoRows {
		return false, nil
	}
	return false, err
}

func (s *DeviceStore) GetBannedUserByUserID(ctx context.Context, userID string) (*BannedUserRow, error) {
	if userID == "" {
		return nil, ErrNotFound
	}
	var row BannedUserRow
	err := s.db.GetContext(ctx, &row, `
SELECT b.user_id, u.uid, u.username, b.reason, b.created_at, b.banned_until
FROM banned_users b
JOIN users u ON u.id = b.user_id
WHERE b.user_id = $1 AND (b.banned_until IS NULL OR b.banned_until > CURRENT_TIMESTAMP)
LIMIT 1
`, userID)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &row, nil
}

func (s *DeviceStore) BanDevice(ctx context.Context, deviceID, reason string, durationHours int) error {
	if deviceID == "" {
		return nil
	}
	var bannedUntil interface{} = nil
	if durationHours > 0 {
		bannedUntil = time.Now().Add(time.Duration(durationHours) * time.Hour)
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO banned_devices (device_id, reason, created_at, banned_until)
VALUES ($1, $2, CURRENT_TIMESTAMP, $3)
ON CONFLICT(device_id) DO UPDATE SET reason = excluded.reason, created_at = CURRENT_TIMESTAMP, banned_until = excluded.banned_until
`, deviceID, reason, bannedUntil)
	return err
}

func (s *DeviceStore) UnbanDevice(ctx context.Context, deviceID string) error {
	if deviceID == "" {
		return nil
	}
	_, err := s.db.ExecContext(ctx, `DELETE FROM banned_devices WHERE device_id = $1`, deviceID)
	return err
}

func (s *DeviceStore) BanUser(ctx context.Context, userID, reason string, durationHours int) error {
	if userID == "" {
		return nil
	}
	var bannedUntil interface{} = nil
	if durationHours > 0 {
		bannedUntil = time.Now().Add(time.Duration(durationHours) * time.Hour)
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO banned_users (user_id, reason, created_at, banned_until)
VALUES ($1, $2, CURRENT_TIMESTAMP, $3)
ON CONFLICT(user_id) DO UPDATE SET reason = excluded.reason, created_at = CURRENT_TIMESTAMP, banned_until = excluded.banned_until
`, userID, reason, bannedUntil)
	if err != nil {
		return err
	}

	// 记录历史封禁日志并递增封禁计数
	logID := NewID()
	_, _ = s.db.ExecContext(ctx, `
INSERT INTO user_ban_logs (id, user_id, reason, duration_hours, banned_until, created_at)
VALUES ($1, $2, $3, $4, $5, CURRENT_TIMESTAMP)
`, logID, userID, reason, durationHours, bannedUntil)

	_, _ = s.db.ExecContext(ctx, `UPDATE users SET ban_count = ban_count + 1 WHERE id = $1`, userID)

	return nil
}

func (s *DeviceStore) GetUserBanCount(ctx context.Context, userID string) (int, error) {
	if userID == "" {
		return 0, nil
	}
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM user_ban_logs WHERE user_id = $1`, userID)
	if err != nil {
		return 0, err
	}
	return count, nil
}

func (s *DeviceStore) ListUserBanLogs(ctx context.Context, userID string, limit int) ([]UserBanLogRow, error) {
	if userID == "" {
		return []UserBanLogRow{}, nil
	}
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows := []UserBanLogRow{}
	err := s.db.SelectContext(ctx, &rows, `
SELECT id, user_id, reason, duration_hours, banned_until, created_at
FROM user_ban_logs
WHERE user_id = $1
ORDER BY created_at DESC
LIMIT $2
`, userID, limit)
	if err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DeviceStore) UnbanUser(ctx context.Context, userID string) error {
	if userID == "" {
		return nil
	}
	_, err := s.db.ExecContext(ctx, `DELETE FROM banned_users WHERE user_id = $1`, userID)
	return err
}

func (s *DeviceStore) ListRecentDevices(ctx context.Context, limit int) ([]DeviceRow, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT d.device_id, d.imei, d.user_id, u.uid, u.username, d.last_seen,
CASE WHEN b.device_id IS NULL THEN 0 ELSE 1 END AS banned
FROM user_devices d
JOIN users u ON u.id = d.user_id
LEFT JOIN banned_devices b ON b.device_id = d.device_id
ORDER BY d.last_seen DESC
LIMIT $1`
	rows := []DeviceRow{}
	if err := s.db.SelectContext(ctx, &rows, q, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DeviceStore) ListBannedDevices(ctx context.Context, limit int) ([]BannedDeviceRow, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT device_id, reason, created_at, banned_until
FROM banned_devices
ORDER BY created_at DESC
LIMIT $1`
	rows := []BannedDeviceRow{}
	if err := s.db.SelectContext(ctx, &rows, q, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DeviceStore) ListBannedUsers(ctx context.Context, limit int) ([]BannedUserRow, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	const q = `
SELECT b.user_id, u.uid, u.username, b.reason, b.created_at, b.banned_until
FROM banned_users b
JOIN users u ON u.id = b.user_id
ORDER BY b.created_at DESC
LIMIT $1`
	rows := []BannedUserRow{}
	if err := s.db.SelectContext(ctx, &rows, q, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DeviceStore) LatestDeviceForUser(ctx context.Context, userID string) (string, string, error) {
	if userID == "" {
		return "", "", nil
	}
	var row struct {
		DeviceID string `db:"device_id"`
		IMEI     string `db:"imei"`
	}
	err := s.db.GetContext(ctx, &row, `SELECT device_id, imei FROM user_devices WHERE user_id = $1 ORDER BY last_seen DESC LIMIT 1`, userID)
	if err == sql.ErrNoRows {
		return "", "", nil
	}
	if err != nil {
		return "", "", err
	}
	return row.DeviceID, row.IMEI, nil
}

func (s *DeviceStore) DeleteOtherUserDevices(ctx context.Context, userID, excludeDeviceID string) (int, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM user_devices WHERE user_id = $1 AND device_id != $2`, userID, excludeDeviceID)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

func (s *DeviceStore) CountDevices(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM user_devices`)
	return count, err
}

func (s *DeviceStore) CountBannedDevices(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM banned_devices`)
	return count, err
}

func (s *DeviceStore) CountBannedUsers(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM banned_users`)
	return count, err
}

func (s *DeviceStore) ListRecentDevicesPage(ctx context.Context, limit, offset int) ([]DeviceRow, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	rows := []DeviceRow{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT d.device_id, d.imei, d.user_id, u.uid, u.username, d.last_seen,
CASE WHEN b.device_id IS NULL THEN 0 ELSE 1 END AS banned
FROM user_devices d
JOIN users u ON u.id = d.user_id
LEFT JOIN banned_devices b ON b.device_id = d.device_id
ORDER BY d.last_seen DESC, d.device_id DESC
LIMIT $1 OFFSET $2`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DeviceStore) ListBannedDevicesPage(ctx context.Context, limit, offset int) ([]BannedDeviceRow, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	rows := []BannedDeviceRow{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT device_id, reason, created_at, banned_until
FROM banned_devices
ORDER BY created_at DESC, device_id DESC
LIMIT $1 OFFSET $2`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *DeviceStore) ListBannedUsersPage(ctx context.Context, limit, offset int) ([]BannedUserRow, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	rows := []BannedUserRow{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT b.user_id, u.uid, u.username, b.reason, b.created_at, b.banned_until
FROM banned_users b
JOIN users u ON u.id = b.user_id
ORDER BY b.created_at DESC, b.user_id DESC
LIMIT $1 OFFSET $2`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}
