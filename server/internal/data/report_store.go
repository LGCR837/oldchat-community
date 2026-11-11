package data

import (
	"context"
	"database/sql"
	"time"

	"github.com/jmoiron/sqlx"
)

type UserReport struct {
	ID                  string       `db:"id"`
	ReporterID          string       `db:"reporter_id"`
	ReporterUID         string       `db:"reporter_uid"`
	TargetUserID        string       `db:"target_user_id"`
	TargetUID           string       `db:"target_uid"`
	TargetDevice        string       `db:"target_device_id"`
	Reason              string       `db:"reason"`
	Status              string       `db:"status"`
	Result              string       `db:"result"`
	HandledAt           sql.NullTime `db:"handled_at"`
	CreatedAt           time.Time    `db:"created_at"`
	TargetReportedCount int          `db:"target_reported_count"`
	TargetBanCount      int          `db:"target_ban_count"`
}

type ReportStore struct {
	db *sqlx.DB
}

func NewReportStore(db *sqlx.DB) *ReportStore {
	return &ReportStore{db: db}
}

func (s *ReportStore) CreateUserReport(ctx context.Context, report *UserReport) error {
	if report == nil {
		return nil
	}
	if report.ID == "" {
		report.ID = NewID()
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO user_reports (id, reporter_id, reporter_uid, target_user_id, target_uid, target_device_id, reason, created_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, CURRENT_TIMESTAMP)
`, report.ID, report.ReporterID, report.ReporterUID, report.TargetUserID, report.TargetUID, report.TargetDevice, report.Reason)
	return err
}

func (s *ReportStore) ListRecentUserReports(ctx context.Context, limit int) ([]UserReport, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	rows := []UserReport{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT r.id, r.reporter_id, r.reporter_uid, r.target_user_id, r.target_uid, r.target_device_id,
       r.reason, r.status, r.result, r.handled_at, r.created_at,
       (SELECT COUNT(1) FROM user_reports r2 WHERE (r2.target_user_id != '' AND r2.target_user_id = r.target_user_id) OR (r2.target_uid != '' AND r2.target_uid = r.target_uid)) AS target_reported_count,
       COALESCE((SELECT ban_count FROM users u WHERE (u.id = r.target_user_id OR u.uid = r.target_uid) LIMIT 1), 0) AS target_ban_count
FROM user_reports r
ORDER BY r.created_at DESC
LIMIT $1`, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *ReportStore) ListUserReportsByReporter(ctx context.Context, reporterID string, limit int) ([]UserReport, error) {
	if reporterID == "" {
		return []UserReport{}, nil
	}
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	rows := []UserReport{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT r.id, r.reporter_id, r.reporter_uid, r.target_user_id, r.target_uid, r.target_device_id,
       r.reason, r.status, r.result, r.handled_at, r.created_at,
       (SELECT COUNT(1) FROM user_reports r2 WHERE (r2.target_user_id != '' AND r2.target_user_id = r.target_user_id) OR (r2.target_uid != '' AND r2.target_uid = r.target_uid)) AS target_reported_count,
       COALESCE((SELECT ban_count FROM users u WHERE (u.id = r.target_user_id OR u.uid = r.target_uid) LIMIT 1), 0) AS target_ban_count
FROM user_reports r
WHERE r.reporter_id = $1
ORDER BY r.created_at DESC
LIMIT $2`, reporterID, limit); err != nil {
		return nil, err
	}
	return rows, nil
}

func (s *ReportStore) SetUserReportStatus(ctx context.Context, id, status, result string) error {
	if id == "" {
		return nil
	}
	if status == "" {
		status = "pending"
	}
	handledAt := interface{}(nil)
	if status != "pending" {
		handledAt = time.Now()
	}
	_, err := s.db.ExecContext(ctx, `
UPDATE user_reports
SET status = $1,
    result = $2,
    handled_at = CASE WHEN $3 IS NULL THEN handled_at ELSE $3 END
WHERE id = $4
`, status, result, handledAt, id)
	return err
}

func (s *ReportStore) CountUserReports(ctx context.Context) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM user_reports`)
	return count, err
}

// userReportHandledSQL 返回已处理/未处理的状态集合片段（仅两个固定值，无注入风险）。
func userReportHandledSQL(handled bool) string {
	if handled {
		return "('resolved','rejected')"
	}
	return "('pending','processing')"
}

// ListUserReportsByHandledPage 按处理状态分页查询用户举报。
// handled=true 查已处理（resolved/rejected），false 查待处理（pending/processing）。
func (s *ReportStore) ListUserReportsByHandledPage(ctx context.Context, handled bool, limit, offset int) ([]UserReport, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	rows := []UserReport{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT r.id, r.reporter_id, r.reporter_uid, r.target_user_id, r.target_uid, r.target_device_id,
       r.reason, r.status, r.result, r.handled_at, r.created_at,
       (SELECT COUNT(1) FROM user_reports r2 WHERE (r2.target_user_id != '' AND r2.target_user_id = r.target_user_id) OR (r2.target_uid != '' AND r2.target_uid = r.target_uid)) AS target_reported_count,
       COALESCE((SELECT ban_count FROM users u WHERE (u.id = r.target_user_id OR u.uid = r.target_uid) LIMIT 1), 0) AS target_ban_count
FROM user_reports r
WHERE r.status IN `+userReportHandledSQL(handled)+`
ORDER BY r.created_at DESC, r.id DESC
LIMIT $1 OFFSET $2`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}

// CountUserReportsByHandled 统计已处理/未处理用户举报数量。
func (s *ReportStore) CountUserReportsByHandled(ctx context.Context, handled bool) (int, error) {
	var count int
	err := s.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM user_reports WHERE status IN `+userReportHandledSQL(handled))
	return count, err
}

// GetUserReportByID 按 ID 查询用户举报。
func (s *ReportStore) GetUserReportByID(ctx context.Context, id string) (*UserReport, error) {
	if id == "" {
		return nil, ErrNotFound
	}
	var row UserReport
	err := s.db.GetContext(ctx, &row, `
SELECT r.id, r.reporter_id, r.reporter_uid, r.target_user_id, r.target_uid, r.target_device_id,
       r.reason, r.status, r.result, r.handled_at, r.created_at,
       (SELECT COUNT(1) FROM user_reports r2 WHERE (r2.target_user_id != '' AND r2.target_user_id = r.target_user_id) OR (r2.target_uid != '' AND r2.target_uid = r.target_uid)) AS target_reported_count,
       COALESCE((SELECT ban_count FROM users u WHERE (u.id = r.target_user_id OR u.uid = r.target_uid) LIMIT 1), 0) AS target_ban_count
FROM user_reports r
WHERE r.id = $1`, id)
	if err != nil {
		return nil, err
	}
	return &row, nil
}

func (s *ReportStore) ListUserReportsPage(ctx context.Context, limit, offset int) ([]UserReport, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	rows := []UserReport{}
	if err := s.db.SelectContext(ctx, &rows, `
SELECT r.id, r.reporter_id, r.reporter_uid, r.target_user_id, r.target_uid, r.target_device_id,
       r.reason, r.status, r.result, r.handled_at, r.created_at,
       (SELECT COUNT(1) FROM user_reports r2 WHERE (r2.target_user_id != '' AND r2.target_user_id = r.target_user_id) OR (r2.target_uid != '' AND r2.target_uid = r.target_uid)) AS target_reported_count,
       COALESCE((SELECT ban_count FROM users u WHERE (u.id = r.target_user_id OR u.uid = r.target_uid) LIMIT 1), 0) AS target_ban_count
FROM user_reports r
ORDER BY r.created_at DESC, r.id DESC
LIMIT $1 OFFSET $2`, limit, offset); err != nil {
		return nil, err
	}
	return rows, nil
}
