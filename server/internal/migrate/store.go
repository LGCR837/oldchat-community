package migrate

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
)

// Store 只负责迁移相关的读写，不复用业务 store，避免导入走业务校验与事件广播。
type Store struct {
	db *sqlx.DB
}

func NewStore(db *sqlx.DB) *Store { return &Store{db: db} }

type Job struct {
	ID          string     `db:"id"`
	UserID      string     `db:"user_id"`
	OfficialUID string     `db:"official_uid"`
	SourceHost  string     `db:"source_host"`
	Status      string     `db:"status"`
	Stage       string     `db:"stage"`
	Percent     int        `db:"percent"`
	Stats       string     `db:"stats"`
	Error       string     `db:"error"`
	CreatedAt   time.Time  `db:"created_at"`
	FinishedAt  *time.Time `db:"finished_at"`
}

type Event struct {
	ID        int64     `db:"id" json:"id"`
	Level     string    `db:"level" json:"level"`
	Stage     string    `db:"stage" json:"stage"`
	Message   string    `db:"message" json:"message"`
	CreatedAt time.Time `db:"created_at" json:"-"`
	TimeText  string    `db:"time_text" json:"time"`
}

type ShadowProfile struct {
	OfficialUID string
	Username    string
	DisplayName string
	UserTitle   string
	AvatarURL   string
}

// UserIDByOfficialUID 找到已绑定该官方 UID 的账号（含已认领的真实账号）。
func (s *Store) UserIDByOfficialUID(ctx context.Context, officialUID string) (id string, shadow bool, err error) {
	var row struct {
		ID       string `db:"id"`
		IsShadow int    `db:"is_shadow"`
	}
	err = s.db.GetContext(ctx, &row, `SELECT id, is_shadow FROM users WHERE official_uid = ? LIMIT 1`, officialUID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return row.ID, row.IsShadow == 1, nil
}

// UpsertShadowUser 为还没在社区版注册的对方建一条不可登录的占位账号。
func (s *Store) UpsertShadowUser(ctx context.Context, p ShadowProfile) (id string, created bool, err error) {
	if existing, _, err := s.UserIDByOfficialUID(ctx, p.OfficialUID); err != nil {
		return "", false, err
	} else if existing != "" {
		return existing, false, nil
	}

	uid := sanitizeUID(p.OfficialUID)
	if uid == "" {
		uid = "OC" + randomSuffix()
	}
	if taken, err := s.uidTaken(ctx, uid); err != nil {
		return "", false, err
	} else if taken {
		uid = "OC" + sanitizeUID(p.OfficialUID) + randomSuffix()
		if len(uid) > 20 {
			uid = uid[:20]
		}
	}

	username := "u" + strings.ToLower(sanitizeUID(p.OfficialUID))
	if username == "u" || len(username) < 3 {
		username = "u" + randomSuffix()
	}
	if taken, err := s.usernameTaken(ctx, username); err != nil {
		return "", false, err
	} else if taken {
		username = username + randomSuffix()
		if len(username) > 20 {
			username = username[:20]
		}
	}

	id = newID()
	displayName := strings.TrimSpace(p.DisplayName)
	if displayName == "" {
		displayName = strings.TrimSpace(p.Username)
	}
	if displayName == "" {
		displayName = "旧聊用户 " + p.OfficialUID
	}
	_, err = s.db.ExecContext(ctx, `
INSERT INTO users (id, uid, ncuid, email, username, display_name, user_title, avatar_url, signature, cover_url, password_hash, token_version, coin_balance, reputation_score, official_uid, is_shadow)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', '', '!shadow', 0, 0, 100, ?, 1)`,
		id, uid, uid, "import+"+sanitizeUID(p.OfficialUID)+"@shadow.invalid", username, displayName,
		strings.TrimSpace(p.UserTitle), strings.TrimSpace(p.AvatarURL), p.OfficialUID)
	if err != nil {
		return "", false, err
	}
	return id, true, nil
}

func (s *Store) uidTaken(ctx context.Context, uid string) (bool, error) {
	var n int
	if err := s.db.GetContext(ctx, &n, `SELECT COUNT(1) FROM users WHERE uid = ?`, uid); err != nil {
		return false, err
	}
	return n > 0, nil
}

func (s *Store) usernameTaken(ctx context.Context, username string) (bool, error) {
	var n int
	if err := s.db.GetContext(ctx, &n, `SELECT COUNT(1) FROM users WHERE username = ?`, username); err != nil {
		return false, err
	}
	return n > 0, nil
}

// BindOfficialUID 把官方 UID 绑到真实账号（认领或已注册用户发起迁移）。
func (s *Store) BindOfficialUID(ctx context.Context, userID, officialUID string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE users SET official_uid = ? WHERE id = ?`, officialUID, userID)
	return err
}

// ClaimShadow 把占位账号升级为真账号：主键不变，历史消息无需搬迁。
func (s *Store) ClaimShadow(ctx context.Context, userID, email, username, passwordHash, displayName string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE users SET
    email = ?, username = ?, password_hash = ?, display_name = ?,
    is_shadow = 0, claimed_at = CURRENT_TIMESTAMP,
    token_version = token_version + 1
WHERE id = ?`, email, username, passwordHash, displayName, userID)
	return err
}

// OfficialProfile 是从旧站读到的本人资料，空字段表示旧站没给。
type OfficialProfile struct {
	UID             string
	Ncuid           string
	Username        string
	DisplayName     string
	UserTitle       string
	Signature       string
	AvatarURL       string
	CoverURL        string
	CoinBalance     *int
	ReputationScore *int
}

// ApplyOfficialProfile 用旧站资料覆盖社区账号，返回已写入与因唯一键冲突跳过的字段。
// 昵称、称号、签名、头像、封面、金币、声望都按旧站覆盖，UID 与用户名冲突时保留本地值。
func (s *Store) ApplyOfficialProfile(ctx context.Context, userID string, p OfficialProfile) (applied, skipped []string, err error) {
	sets := make([]string, 0, 10)
	args := make([]any, 0, 11)
	add := func(field, column string, value any) {
		sets = append(sets, column+" = ?")
		args = append(args, value)
		applied = append(applied, field)
	}

	if v := strings.TrimSpace(p.DisplayName); v != "" {
		add("昵称", "display_name", v)
	}
	if v := strings.TrimSpace(p.UserTitle); v != "" {
		add("称号", "user_title", v)
	}
	if v := strings.TrimSpace(p.Signature); v != "" {
		add("签名", "signature", v)
	}
	if v := strings.TrimSpace(p.AvatarURL); v != "" {
		add("头像", "avatar_url", v)
	}
	if v := strings.TrimSpace(p.CoverURL); v != "" {
		add("封面", "cover_url", v)
	}
	if p.CoinBalance != nil {
		add("金币", "coin_balance", *p.CoinBalance)
	}
	if p.ReputationScore != nil {
		add("声望", "reputation_score", *p.ReputationScore)
	}
	if v := strings.TrimSpace(p.Ncuid); v != "" {
		add("旧聊号", "ncuid", v)
	}
	for _, item := range []struct {
		field  string
		column string
		value  string
	}{
		{"用户名", "username", p.Username},
		{"账号 ID", "uid", p.UID},
	} {
		v := strings.TrimSpace(item.value)
		if v == "" {
			continue
		}
		taken, e := s.fieldTakenByOther(ctx, item.column, v, userID)
		if e != nil {
			return applied, skipped, e
		}
		if taken {
			skipped = append(skipped, item.field)
			continue
		}
		add(item.field, item.column, v)
	}
	if len(sets) == 0 {
		return applied, skipped, nil
	}
	sets = append(sets, "updated_at = CURRENT_TIMESTAMP")
	args = append(args, userID)
	_, err = s.db.ExecContext(ctx, `UPDATE users SET `+strings.Join(sets, ", ")+` WHERE id = ?`, args...)
	return applied, skipped, err
}

// EnrichShadowUser 给已存在的占位账号补资料并换掉旧的媒体地址。
// 只动 is_shadow = 1 的行，已认领的真账号不受影响。
func (s *Store) EnrichShadowUser(ctx context.Context, officialUID, avatarURL, userTitle string) error {
	sets := make([]string, 0, 3)
	args := make([]any, 0, 4)
	if v := strings.TrimSpace(avatarURL); v != "" {
		sets = append(sets, "avatar_url = ?")
		args = append(args, v)
	}
	if v := strings.TrimSpace(userTitle); v != "" {
		sets = append(sets, "user_title = ?")
		args = append(args, v)
	}
	if len(sets) == 0 {
		return nil
	}
	args = append(args, officialUID)
	_, err := s.db.ExecContext(ctx, `UPDATE users SET `+strings.Join(sets, ", ")+` WHERE official_uid = ? AND is_shadow = 1`, args...)
	return err
}

func (s *Store) fieldTakenByOther(ctx context.Context, column, value, exceptID string) (bool, error) {
	var n int
	if err := s.db.GetContext(ctx, &n, `SELECT COUNT(1) FROM users WHERE `+column+` = ? AND id <> ?`, value, exceptID); err != nil {
		return false, err
	}
	return n > 0, nil
}

func (s *Store) EnsureThread(ctx context.Context, userA, userB string) (string, error) {
	a, b := userA, userB
	if b < a {
		a, b = b, a
	}
	var id string
	err := s.db.GetContext(ctx, &id, `SELECT id FROM direct_threads WHERE user_a_id = ? AND user_b_id = ? LIMIT 1`, a, b)
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	id = newID()
	if _, err := s.db.ExecContext(ctx, `
INSERT INTO direct_threads (id, user_a_id, user_b_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)`, id, a, b); err != nil {
		return "", err
	}
	return id, nil
}

// InsertDirectMessage 幂等写入：同一个官方消息 id 只会成功一次。
func (s *Store) InsertDirectMessage(ctx context.Context, threadID, senderID, officialMsgID, body, msgType, mediaURL, thumbURL, originalURL string, durationMS int, createdAt int64) (bool, error) {
	res, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO direct_messages
    (id, thread_id, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, created_at, delivered_at, read_at, official_msg_id)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, datetime(?, 'unixepoch'), datetime(?, 'unixepoch'), datetime(?, 'unixepoch'), ?)`,
		newID(), threadID, senderID, body, normalizeMsgType(msgType), mediaURL, thumbURL, originalURL, durationMS,
		createdAt, createdAt, createdAt, officialMsgID)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

// InsertMoment 幂等写入一条朋友圈，返回本地 id 与是否新插入。
func (s *Store) InsertMoment(ctx context.Context, userID, officialMomentID, body, imageURL string, createdAt int64) (string, bool, error) {
	var id string
	err := s.db.GetContext(ctx, &id, `SELECT id FROM moments WHERE official_moment_id = ? LIMIT 1`, officialMomentID)
	if err == nil {
		return id, false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", false, err
	}
	id = newID()
	res, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO moments (id, user_id, body, image_url, created_at, official_moment_id)
VALUES (?, ?, ?, ?, datetime(?, 'unixepoch'), ?)`, id, userID, body, imageURL, createdAt, officialMomentID)
	if err != nil {
		return "", false, err
	}
	if n, err := res.RowsAffected(); err == nil && n == 0 {
		if e := s.db.GetContext(ctx, &id, `SELECT id FROM moments WHERE official_moment_id = ? LIMIT 1`, officialMomentID); e != nil {
			return "", false, e
		}
		return id, false, nil
	}
	return id, true, nil
}

// InsertMomentComment 幂等写入一条评论。
func (s *Store) InsertMomentComment(ctx context.Context, momentID, userID, officialCommentID, body string, createdAt int64) (bool, error) {
	res, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO moment_comments (id, moment_id, user_id, body, created_at, official_comment_id)
VALUES (?, ?, ?, ?, datetime(?, 'unixepoch'), ?)`,
		newID(), momentID, userID, body, createdAt, officialCommentID)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

// LikeMoment 补本人当时点过的赞，其他人的点赞旧站没给明细，无法还原。
func (s *Store) LikeMoment(ctx context.Context, momentID, userID string, createdAt int64) error {
	_, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO moment_likes (moment_id, user_id, created_at) VALUES (?, ?, datetime(?, 'unixepoch'))`,
		momentID, userID, createdAt)
	return err
}

func (s *Store) AddFriendPair(ctx context.Context, userID, otherID, remark string) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO friends (user_id, friend_user_id, remark_name, created_at)
VALUES (?, ?, ?, CURRENT_TIMESTAMP), (?, ?, '', CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING`, userID, otherID, remark, otherID, userID)
	return err
}

// EnsureImportedGroup 建立导入用的群记录：owner 记为本人，imported 标记来源。
func (s *Store) EnsureImportedGroup(ctx context.Context, officialGroupID, name, avatarURL, ownerID string) (id string, created bool, err error) {
	err = s.db.GetContext(ctx, &id, `SELECT id FROM groups WHERE official_group_id = ? LIMIT 1`, officialGroupID)
	if err == nil {
		return id, false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", false, err
	}
	id = newID()
	_, err = s.db.ExecContext(ctx, `
INSERT INTO groups (id, official_group_id, imported, name, avatar_url, owner_id, join_approval, global_mute, created_at, updated_at)
VALUES (?, ?, 1, ?, ?, ?, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`,
		id, officialGroupID, name, avatarURL, ownerID)
	if err != nil {
		return "", false, err
	}
	return id, true, nil
}

func (s *Store) AddGroupMember(ctx context.Context, groupID, userID string, role int16) error {
	_, err := s.db.ExecContext(ctx, `
INSERT INTO group_members (group_id, user_id, role, joined_at, last_read_at)
VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING`, groupID, userID, role)
	return err
}

func (s *Store) InsertGroupMessage(ctx context.Context, groupID, senderID, officialMsgID, body, msgType, mediaURL, thumbURL, originalURL string, durationMS int, createdAt int64) (bool, error) {
	res, err := s.db.ExecContext(ctx, `
INSERT OR IGNORE INTO group_messages
    (id, group_id, group_seq, sender_id, body, msg_type, media_url, thumb_url, original_url, duration_ms, burn_after_seconds, created_at, official_msg_id)
VALUES (?, ?, (SELECT COALESCE(MAX(group_seq), 0) + 1 FROM group_messages WHERE group_id = ?), ?, ?, ?, ?, ?, ?, ?, 0, datetime(?, 'unixepoch'), ?)`,
		newID(), groupID, groupID, senderID, body, normalizeMsgType(msgType), mediaURL, thumbURL, originalURL, durationMS,
		createdAt, officialMsgID)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

func (s *Store) RefreshGroupSequence(ctx context.Context, groupID string) {
	_, _ = s.db.ExecContext(ctx, `
INSERT INTO group_message_sequences (group_id, last_seq)
SELECT ?, COALESCE(MAX(group_seq), 0) FROM group_messages WHERE group_id = ?
ON CONFLICT(group_id) DO UPDATE SET last_seq = MAX(last_seq, excluded.last_seq)`, groupID, groupID)
}

func (s *Store) CreateJob(ctx context.Context, userID, officialUID, sourceHost string) (*Job, error) {
	job := &Job{
		ID:          newID(),
		UserID:      userID,
		OfficialUID: officialUID,
		SourceHost:  sourceHost,
		Status:      StatusQueued,
		Stage:       "等待开始",
		Stats:       "{}",
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO import_jobs (id, user_id, official_uid, source_host, status, stage, percent, stats)
VALUES (?, ?, ?, ?, ?, ?, 0, '{}')`, job.ID, userID, officialUID, sourceHost, job.Status, job.Stage)
	if err != nil {
		return nil, err
	}
	return job, nil
}

func (s *Store) GetJob(ctx context.Context, jobID string) (*Job, error) {
	var job Job
	err := s.db.GetContext(ctx, &job, `SELECT * FROM import_jobs WHERE id = ?`, jobID)
	if err != nil {
		return nil, err
	}
	return &job, nil
}

func (s *Store) LatestJobByUser(ctx context.Context, userID string) (*Job, error) {
	var job Job
	err := s.db.GetContext(ctx, &job, `SELECT * FROM import_jobs WHERE user_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 1`, userID)
	if err != nil {
		return nil, err
	}
	return &job, nil
}

func (s *Store) UpdateProgress(ctx context.Context, jobID, stage string, percent int, stats map[string]int64) {
	raw, _ := json.Marshal(stats)
	_, _ = s.db.ExecContext(ctx, `UPDATE import_jobs SET stage = ?, percent = ?, stats = ? WHERE id = ?`, stage, percent, string(raw), jobID)
}

func (s *Store) SetStatus(ctx context.Context, jobID, status, stage, errText string) {
	finished := "NULL"
	if status == StatusDone || status == StatusFailed {
		finished = "CURRENT_TIMESTAMP"
	}
	_, _ = s.db.ExecContext(ctx, `
UPDATE import_jobs SET status = ?, stage = ?, error = ?, finished_at = `+finished+` WHERE id = ?`,
		status, stage, errText, jobID)
}

func (s *Store) AddEvent(ctx context.Context, jobID, level, stage, message string) {
	_, _ = s.db.ExecContext(ctx, `
INSERT INTO import_events (job_id, level, stage, message) VALUES (?, ?, ?, ?)`, jobID, level, stage, message)
}

func (s *Store) ListEvents(ctx context.Context, jobID string, limit int) ([]Event, error) {
	if limit <= 0 || limit > 500 {
		limit = 300
	}
	events := []Event{}
	err := s.db.SelectContext(ctx, &events, `
SELECT id, level, stage, message, created_at, strftime('%H:%M:%S', created_at) AS time_text
FROM import_events WHERE job_id = ? ORDER BY id ASC LIMIT ?`, jobID, limit)
	return events, err
}

func normalizeMsgType(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "":
		return "text"
	case "text", "image", "voice", "video", "file", "sticker", "recall", "system", "redpacket", "card":
		return strings.ToLower(strings.TrimSpace(v))
	default:
		return "text"
	}
}
