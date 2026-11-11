package data

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func OpenDB(ctx context.Context, dsn string) (*sqlx.DB, error) {
	// SQLite 默认使用 sqlite 驱动名 (modernc.org/sqlite)
	// 将 busy_timeout, WAL 等 pragma 直接写入 DSN，确保连接池中的每一个物理连接均生效
	fullDSN := buildSQLiteDSN(dsn)
	db, err := sqlx.Open("sqlite", fullDSN)
	if err != nil {
		return nil, err
	}

	maxOpen := getEnvInt("SQLITE_MAX_OPEN_CONNS", 16)
	if maxOpen < 1 {
		maxOpen = 16
	}
	db.SetMaxOpenConns(maxOpen)
	db.SetMaxIdleConns(maxOpen)
	db.SetConnMaxLifetime(30 * time.Minute)

	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return nil, err
	}

	applySQLitePragmas(db)

	// 自动初始化数据库表
	if err := initSchema(db); err != nil {
		return nil, err
	}

	return db, nil
}

func buildSQLiteDSN(raw string) string {
	if strings.Contains(raw, "_pragma=") {
		return raw
	}
	clean := strings.TrimPrefix(raw, "file:")
	base := clean
	existingQuery := ""
	if idx := strings.Index(clean, "?"); idx != -1 {
		base = clean[:idx]
		existingQuery = clean[idx+1:]
	}

	busy := getEnvInt("SQLITE_BUSY_TIMEOUT_MS", 10000)
	journal := getEnvString("SQLITE_JOURNAL_MODE", "WAL")
	sync := getEnvString("SQLITE_SYNCHRONOUS", "NORMAL")
	tempStore := getEnvString("SQLITE_TEMP_STORE", "MEMORY")

	params := []string{
		fmt.Sprintf("_pragma=busy_timeout(%d)", busy),
		fmt.Sprintf("_pragma=journal_mode(%s)", journal),
		fmt.Sprintf("_pragma=synchronous(%s)", sync),
		"_pragma=foreign_keys(ON)",
		fmt.Sprintf("_pragma=temp_store(%s)", tempStore),
	}
	if existingQuery != "" {
		return fmt.Sprintf("file:%s?%s&%s", base, strings.Join(params, "&"), existingQuery)
	}
	return fmt.Sprintf("file:%s?%s", base, strings.Join(params, "&"))
}

func applySQLitePragmas(db *sqlx.DB) {
	if db == nil {
		return
	}
	journal := getEnvString("SQLITE_JOURNAL_MODE", "WAL")
	if journal != "" {
		_, _ = db.Exec("PRAGMA journal_mode = " + journal)
	}
	sync := getEnvString("SQLITE_SYNCHRONOUS", "NORMAL")
	if sync != "" {
		_, _ = db.Exec("PRAGMA synchronous = " + sync)
	}
	tempStore := getEnvString("SQLITE_TEMP_STORE", "MEMORY")
	if tempStore != "" {
		_, _ = db.Exec("PRAGMA temp_store = " + tempStore)
	}
	_, _ = db.Exec("PRAGMA foreign_keys = ON")
	if busy := getEnvInt("SQLITE_BUSY_TIMEOUT_MS", 10000); busy > 0 {
		_, _ = db.Exec("PRAGMA busy_timeout = " + strconv.Itoa(busy))
	}
	if cacheKB := getEnvInt("SQLITE_CACHE_SIZE_KB", 0); cacheKB > 0 {
		_, _ = db.Exec("PRAGMA cache_size = -" + strconv.Itoa(cacheKB))
	}
	if mmapMB := getEnvInt("SQLITE_MMAP_SIZE_MB", 0); mmapMB > 0 {
		_, _ = db.Exec("PRAGMA mmap_size = " + strconv.Itoa(mmapMB*1024*1024))
	}
}

func getEnvString(key, def string) string {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return def
	}
	return v
}

func getEnvInt(key string, def int) int {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return def
	}
	val, err := strconv.Atoi(v)
	if err != nil || val <= 0 {
		return def
	}
	return val
}

func initSchema(db *sqlx.DB) error {
	hasUsers, err := hasUsersTable(db)
	if err != nil {
		return err
	}
	if hasUsers {
		ensureMessageColumns(db)
		if err = ensureIdentitySchema(db); err != nil {
			return err
		}
		if err = ensureOAuthSchema(db); err != nil {
			return err
		}
		ensureExtraSchema(db)
		if err = NewPtsStore(db).EnsureSchema(); err != nil {
			return err
		}
		if err = NewGroupEventStore(db).EnsureSchema(); err != nil {
			return err
		}
		if err = NewFileAssetStore(db).EnsureSchema(); err != nil {
			return err
		}
		return ensureGroupMessageSequenceSchema(db)
	}

	schema, err := readSchemaFile()
	if err != nil {
		return err
	}

	if _, err = db.Exec(string(schema)); err != nil {
		return err
	}
	ensureMessageColumns(db)
	if err = ensureIdentitySchema(db); err != nil {
		return err
	}
	if err = ensureOAuthSchema(db); err != nil {
		return err
	}
	ensureExtraSchema(db)
	if err = NewPtsStore(db).EnsureSchema(); err != nil {
		return err
	}
	if err = NewGroupEventStore(db).EnsureSchema(); err != nil {
		return err
	}
	if err = NewFileAssetStore(db).EnsureSchema(); err != nil {
		return err
	}
	return ensureGroupMessageSequenceSchema(db)
}

// ensureIdentitySchema 确保 NCUID 能安全作为第三方身份 subject。
func ensureIdentitySchema(db *sqlx.DB) error {
	var duplicates int
	if err := db.Get(&duplicates, `
SELECT COUNT(1) FROM (
    SELECT ncuid FROM users
    WHERE ncuid IS NOT NULL AND TRIM(ncuid) != ''
    GROUP BY ncuid HAVING COUNT(1) > 1
)`); err != nil {
		return err
	}
	if duplicates > 0 {
		return fmt.Errorf("users.ncuid contains %d duplicate values", duplicates)
	}
	_, err := db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_users_ncuid_unique ON users(ncuid)`)
	return err
}

func ensureOAuthSchema(db *sqlx.DB) error {
	_, err := db.Exec(`
CREATE TABLE IF NOT EXISTS oauth_clients (
    client_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    secret_hash TEXT NOT NULL DEFAULT '',
    redirect_uri TEXT NOT NULL,
    allowed_scopes TEXT NOT NULL DEFAULT 'openid profile',
    public_client INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS oauth_authorization_requests (
    id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
    redirect_uri TEXT NOT NULL,
    scope TEXT NOT NULL,
    state TEXT NOT NULL,
    nonce TEXT NOT NULL DEFAULT '',
    code_challenge TEXT NOT NULL,
    csrf_hash TEXT NOT NULL,
    user_id TEXT REFERENCES users(id) ON DELETE CASCADE,
    auth_time DATETIME,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS oauth_authorization_codes (
    code_hash TEXT PRIMARY KEY,
    client_id TEXT NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    redirect_uri TEXT NOT NULL,
    scope TEXT NOT NULL,
    nonce TEXT NOT NULL DEFAULT '',
    code_challenge TEXT NOT NULL,
    auth_time DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME
);
CREATE TABLE IF NOT EXISTS oauth_grants (
    id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope TEXT NOT NULL,
    revoked_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_oauth_grants_active ON oauth_grants(client_id,user_id) WHERE revoked_at IS NULL;
CREATE TABLE IF NOT EXISTS oauth_access_tokens (
    token_hash TEXT PRIMARY KEY,
    grant_id TEXT NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    scope TEXT NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS oauth_refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    grant_id TEXT NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    scope TEXT NOT NULL,
    family_id TEXT NOT NULL,
    auth_time DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME,
    replaced_by TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_oauth_access_expiry ON oauth_access_tokens(expires_at,revoked_at);
CREATE INDEX IF NOT EXISTS idx_oauth_refresh_grant ON oauth_refresh_tokens(grant_id,family_id);
`)
	if err != nil {
		return err
	}
	// 兼容早期 OAuth 预览版数据库，正式上线前补齐真实认证时间字段。
	if err = addColumnIfMissing(db, "oauth_authorization_requests", "auth_time", "DATETIME NULL"); err != nil {
		return err
	}
	if err = addColumnIfMissing(db, "oauth_authorization_codes", "auth_time", "DATETIME NULL"); err != nil {
		return err
	}
	if err = addColumnIfMissing(db, "oauth_refresh_tokens", "auth_time", "DATETIME NULL"); err != nil {
		return err
	}
	if _, err = db.Exec(`UPDATE oauth_authorization_codes SET auth_time=COALESCE(auth_time,CURRENT_TIMESTAMP) WHERE auth_time IS NULL`); err != nil {
		return err
	}
	if _, err = db.Exec(`UPDATE oauth_refresh_tokens SET auth_time=COALESCE(auth_time,CURRENT_TIMESTAMP) WHERE auth_time IS NULL`); err != nil {
		return err
	}
	return nil
}

// ensureGroupMessageSequenceSchema 为新旧数据库建立每群可靠同步序号。
// 此迁移必须完整成功，否则拒绝启动，避免部分消息有序号、部分消息无序号。
func ensureGroupMessageSequenceSchema(db *sqlx.DB) error {
	if err := addColumnIfMissing(db, "group_messages", "group_seq", "INTEGER NULL"); err != nil {
		return err
	}
	conn, err := db.Connx(context.Background())
	if err != nil {
		return err
	}
	defer conn.Close()
	// 历史库曾在未启用 FK 的版本中保留了已删除用户/群的消息。
	// SQLite 即便只更新 group_seq 也会重新检查旧外键，因此迁移连接需暂时关闭 FK；
	// 新请求连接及迁移完成后的连接仍恢复为开启状态。
	if _, err = conn.ExecContext(context.Background(), `PRAGMA foreign_keys = OFF`); err != nil {
		return err
	}
	defer conn.ExecContext(context.Background(), `PRAGMA foreign_keys = ON`)
	tx, err := conn.BeginTxx(context.Background(), nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`
CREATE TABLE IF NOT EXISTS group_message_sequences (
    group_id VARCHAR(32) PRIMARY KEY,
    last_seq INTEGER NOT NULL DEFAULT 0
)`); err != nil {
		return err
	}
	var missingSeq int
	if err = tx.Get(&missingSeq, `SELECT COUNT(1) FROM group_messages WHERE group_seq IS NULL`); err != nil {
		return err
	}
	// 只有首次升级才执行全表排序；后续启动只做一次有索引可用的空值计数。
	if missingSeq > 0 {
		if _, err = tx.Exec(`
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY group_id ORDER BY created_at ASC, id ASC
    ) AS seq
    FROM group_messages
)
UPDATE group_messages
SET group_seq = (SELECT seq FROM ranked WHERE ranked.id = group_messages.id)
WHERE group_seq IS NULL`); err != nil {
			return err
		}
	}
	if _, err = tx.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_group_messages_group_seq ON group_messages (group_id, group_seq)`); err != nil {
		return err
	}
	// 每次启动都幂等修复“计数器缺失或落后”的单个群；唯一索引可高效完成分组最大值扫描。
	if _, err = tx.Exec(`
INSERT INTO group_message_sequences(group_id, last_seq)
SELECT group_id, COALESCE(MAX(group_seq), 0)
FROM group_messages GROUP BY group_id
ON CONFLICT(group_id) DO UPDATE SET last_seq = MAX(last_seq, excluded.last_seq)`); err != nil {
		return err
	}
	return tx.Commit()
}

func readSchemaFile() ([]byte, error) {
	if schema, err := os.ReadFile("schema.sql"); err == nil {
		return schema, nil
	}

	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}

	return os.ReadFile(filepath.Join(filepath.Dir(exe), "schema.sql"))
}

func hasUsersTable(db *sqlx.DB) (bool, error) {
	var name string
	err := db.Get(&name, `SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'users'`)
	if err == nil {
		return name == "users", nil
	}
	if err == sql.ErrNoRows {
		return false, nil
	}
	return false, err
}

func ensureMessageColumns(db *sqlx.DB) {
	_ = addColumnIfMissing(db, "users", "ncuid", "VARCHAR(32) NOT NULL DEFAULT ''")
	// 为已有用户回填 ncuid = uid
	_, _ = db.Exec(`UPDATE users SET ncuid = uid WHERE ncuid IS NULL OR ncuid = ''`)
	_ = addColumnIfMissing(db, "direct_messages", "delivered_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "direct_messages", "read_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "direct_messages", "msg_type", "TEXT NOT NULL DEFAULT 'text'")
	_ = addColumnIfMissing(db, "direct_messages", "media_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "direct_messages", "thumb_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "direct_messages", "original_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "direct_messages", "duration_ms", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "group_messages", "msg_type", "TEXT NOT NULL DEFAULT 'text'")
	_ = addColumnIfMissing(db, "group_messages", "media_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "group_messages", "thumb_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "group_messages", "original_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "group_messages", "duration_ms", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "group_messages", "sender_ncuid", "VARCHAR(32) NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "direct_messages", "burn_after_seconds", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "direct_messages", "burn_start_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "group_messages", "burn_after_seconds", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "group_messages", "burn_start_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "direct_messages", "burned_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "group_messages", "burned_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "groups", "avatar_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "groups", "announcement", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "groups", "announcement_mode", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "groups", "announcement_updated_at", "DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00'")
	_ = addColumnIfMissing(db, "groups", "member_version", "BIGINT NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "group_members", "announcement_read_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "group_members", "last_read_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "friends", "remark_name", "TEXT NOT NULL DEFAULT ''")
	// 官方数据迁移：占位账号与幂等键。
	_ = addColumnIfMissing(db, "users", "official_uid", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "users", "is_shadow", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "users", "claimed_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "direct_messages", "official_msg_id", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "group_messages", "official_msg_id", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "groups", "official_group_id", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "groups", "imported", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "moments", "official_moment_id", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "moment_comments", "official_comment_id", "TEXT NOT NULL DEFAULT ''")
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_users_official_uid ON users (official_uid) WHERE official_uid != ''`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_direct_msg_official ON direct_messages (official_msg_id) WHERE official_msg_id != ''`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_group_msg_official ON group_messages (official_msg_id) WHERE official_msg_id != ''`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_groups_official ON groups (official_group_id) WHERE official_group_id != ''`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_moments_official ON moments (official_moment_id) WHERE official_moment_id != ''`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_moment_comments_official ON moment_comments (official_comment_id) WHERE official_comment_id != ''`)
	// 覆盖复合游标 WHERE/ORDER BY，避免深页 OFFSET 扫描和额外临时排序。
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_direct_msg_thread_cursor ON direct_messages (thread_id, created_at DESC, id DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_direct_threads_user_a ON direct_threads (user_a_id, id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_direct_threads_user_b ON direct_threads (user_b_id, id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_direct_unread_thread_created ON direct_messages (thread_id, created_at DESC, sender_id) WHERE read_at IS NULL AND burned_at IS NULL AND msg_type != 'recall'`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_direct_messages_burn_scan ON direct_messages (burned_at, burn_start_at, burn_after_seconds)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_members_user_cover ON group_members (user_id, group_id, last_read_at, joined_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_messages_group_cursor ON group_messages (group_id, created_at DESC, id DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_unread_group_created ON group_messages (group_id, created_at DESC, sender_id) WHERE burned_at IS NULL AND msg_type != 'recall'`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_messages_burn_scan ON group_messages (burned_at, burn_start_at, burn_after_seconds)`)
}

func ensureExtraSchema(db *sqlx.DB) {
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS group_invitations (
    id VARCHAR(32) PRIMARY KEY,
    group_id VARCHAR(32) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    inviter_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invitee_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at DATETIME NULL
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_group_invitation_pending
        ON group_invitations(group_id, invitee_id) WHERE status = 0`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_invitation_invitee
        ON group_invitations(invitee_id, status, created_at DESC)`)
	// AI 额度只记录计费数字，不保存用户提示词、模型回答或上游密钥。
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS ai_token_quotas (
    user_id VARCHAR(32) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    token_limit INTEGER NOT NULL DEFAULT 5000000,
    used_tokens INTEGER NOT NULL DEFAULT 0,
    reserved_tokens INTEGER NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (token_limit >= 0),
    CHECK (used_tokens >= 0),
    CHECK (reserved_tokens >= 0)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_ai_token_quotas_updated ON ai_token_quotas (updated_at)`)
	// 将旧版 1 亿免费默认额度迁移为 500 万；其他自定义额度保持不变。
	_, _ = db.Exec(`UPDATE ai_token_quotas SET token_limit = 5000000, updated_at = CURRENT_TIMESTAMP WHERE token_limit = 100000000`)
	// 官方数据迁移任务与过程日志：进度页按 10 秒轮询读取。
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS import_jobs (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    official_uid TEXT NOT NULL DEFAULT '',
    source_host TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'queued',
    stage TEXT NOT NULL DEFAULT '',
    percent INTEGER NOT NULL DEFAULT 0,
    stats TEXT NOT NULL DEFAULT '{}',
    error TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_import_jobs_user ON import_jobs (user_id, created_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS import_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id VARCHAR(32) NOT NULL,
    level TEXT NOT NULL DEFAULT 'info',
    stage TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_import_events_job ON import_events (job_id, id)`)
	// Bug report table is also ensured here so schema migrations can safely run
	// during OpenDB (before http layer bootstraps stores).
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS bug_reports (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32),
    user_uid VARCHAR(32) NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    device_model VARCHAR(128) NOT NULL DEFAULT '',
    android_version VARCHAR(32) NOT NULL DEFAULT '',
    app_version VARCHAR(32) NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'open',
    admin_note TEXT NOT NULL DEFAULT '',
    resolved_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS crash_reports (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(32) REFERENCES users(id) ON DELETE SET NULL,
    crash_log TEXT NOT NULL,
    device_model VARCHAR(128) NOT NULL DEFAULT '',
    android_version VARCHAR(32) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_crash_reports_created ON crash_reports (created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_crash_reports_user ON crash_reports (user_id, created_at)`)
	_ = addColumnIfMissing(db, "bug_reports", "status", "TEXT NOT NULL DEFAULT 'open'")
	_ = addColumnIfMissing(db, "bug_reports", "admin_note", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "bug_reports", "resolved_at", "DATETIME NULL")

	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS system_notifications (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    important INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)`)
	_ = addColumnIfMissing(db, "system_notifications", "target_user_id", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "system_notifications", "important", "INTEGER NOT NULL DEFAULT 0")
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_system_notifications_target_user ON system_notifications (target_user_id, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_system_notifications_created ON system_notifications (created_at DESC)`)
	_ = addColumnIfMissing(db, "users", "signature", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "users", "cover_url", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "users", "token_version", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "users", "user_title", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "users", "user_title_price", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "users", "coin_balance", "INTEGER NOT NULL DEFAULT 0")
	_, _ = db.Exec(`UPDATE users SET coin_balance = 0 WHERE coin_balance IS NULL`)
	_ = addColumnIfMissing(db, "users", "reputation_score", "INTEGER NOT NULL DEFAULT 110")
	_ = addColumnIfMissing(db, "users", "presence_status", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "users", "reject_group_invites", "INTEGER NOT NULL DEFAULT 0")
	_, _ = db.Exec(`UPDATE users SET reputation_score = 110 WHERE reputation_score IS NULL OR reputation_score = 100`)
	_, _ = db.Exec(`UPDATE users SET reputation_score = 200 WHERE reputation_score > 200`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS title_catalog (
    id VARCHAR(32) PRIMARY KEY,
    title TEXT NOT NULL,
    price INTEGER NOT NULL DEFAULT 100,
    active INTEGER NOT NULL DEFAULT 1,
    is_custom INTEGER NOT NULL DEFAULT 0,
    owner_id VARCHAR(32) NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_title_catalog_title ON title_catalog (title)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_title_catalog_owner ON title_catalog (owner_id)`)
	_ = addColumnIfMissing(db, "title_catalog", "owner_id", "VARCHAR(32) NULL REFERENCES users(id) ON DELETE SET NULL")
	_ = addColumnIfMissing(db, "title_catalog", "is_custom", "INTEGER NOT NULL DEFAULT 0")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_daily_checkins (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    checkin_date VARCHAR(16) NOT NULL,
    coin_reward INTEGER NOT NULL DEFAULT 0,
    reputation_reward INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, checkin_date)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_daily_checkins_user_created ON user_daily_checkins (user_id, created_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_daily_scratches (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scratch_date VARCHAR(16) NOT NULL,
    slots VARCHAR(64) NOT NULL DEFAULT '',
    total_reward INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, scratch_date)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_daily_scratches_user_created ON user_daily_scratches (user_id, created_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS deleted_account_cooldowns (
    email VARCHAR(255) PRIMARY KEY,
    deleted_user_id VARCHAR(64) NOT NULL DEFAULT '',
    deleted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cooldown_until DATETIME NOT NULL
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_deleted_email_cooldown ON deleted_account_cooldowns (cooldown_until)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_direct_messages_created ON direct_messages (created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_direct_messages_read ON direct_messages (read_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_messages_created ON group_messages (created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_moments_created ON moments (created_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_devices (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    imei VARCHAR(64) NOT NULL DEFAULT '',
    last_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_user_device_pair ON user_devices (user_id, device_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_devices_user ON user_devices (user_id)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_login_devices (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    device_name VARCHAR(128) NOT NULL DEFAULT '',
    platform VARCHAR(32) NOT NULL DEFAULT '',
    app_version VARCHAR(32) NOT NULL DEFAULT '',
    last_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_user_login_device_pair ON user_login_devices (user_id, device_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_login_devices_user ON user_login_devices (user_id)`)
	_ = addColumnIfMissing(db, "group_members", "last_read_at", "DATETIME NULL")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS banned_devices (
    device_id VARCHAR(128) PRIMARY KEY,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_ = addColumnIfMissing(db, "banned_devices", "banned_until", "DATETIME NULL")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS banned_users (
    user_id VARCHAR(32) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_ = addColumnIfMissing(db, "banned_users", "banned_until", "DATETIME NULL")
	_ = addColumnIfMissing(db, "users", "ban_count", "INTEGER NOT NULL DEFAULT 0")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_ban_logs (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT NOT NULL DEFAULT '',
    duration_hours INTEGER NOT NULL DEFAULT 0,
    banned_until DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_ban_logs_user ON user_ban_logs (user_id, created_at DESC)`)
	_, _ = db.Exec(`UPDATE users SET ban_count = 1 WHERE id IN (SELECT user_id FROM banned_users) AND ban_count = 0`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS ban_appeals (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    uid VARCHAR(32) NOT NULL DEFAULT '',
    username VARCHAR(64) NOT NULL DEFAULT '',
    ban_reason TEXT NOT NULL DEFAULT '',
    banned_until DATETIME NULL,
    appeal_text TEXT NOT NULL DEFAULT '',
    contact TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'pending',
    admin_note TEXT NOT NULL DEFAULT '',
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_ban_appeals_status_created ON ban_appeals (status, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_ban_appeals_user_status ON ban_appeals (user_id, status, created_at DESC)`)
	_ = addColumnIfMissing(db, "ban_appeals", "contact", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "ban_appeals", "admin_note", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "ban_appeals", "handled_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "ban_appeals", "updated_at", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS banned_groups (
    group_id VARCHAR(32) PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_ = addColumnIfMissing(db, "banned_groups", "banned_until", "DATETIME NULL")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS moment_likes (
    moment_id VARCHAR(32) NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (moment_id, user_id)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_moment_likes_moment ON moment_likes (moment_id)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS moment_comments (
    id VARCHAR(32) PRIMARY KEY,
    moment_id VARCHAR(32) NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_moment_comments_moment ON moment_comments (moment_id, created_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS resource_sections (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    owner_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_resource_sections_name ON resource_sections (name COLLATE NOCASE)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_resource_sections_owner ON resource_sections (owner_id, created_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS resource_items (
    id VARCHAR(32) PRIMARY KEY,
    section_id VARCHAR(32) NOT NULL REFERENCES resource_sections(id) ON DELETE CASCADE,
    uploader_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    url VARCHAR(1024) NOT NULL,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_resource_items_section ON resource_items (section_id, created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_resource_items_uploader ON resource_items (uploader_id)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS resource_likes (
    item_id VARCHAR(32) NOT NULL REFERENCES resource_items(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id, user_id)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_resource_likes_item ON resource_likes (item_id)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS resource_comments (
    id VARCHAR(32) PRIMARY KEY,
    item_id VARCHAR(32) NOT NULL REFERENCES resource_items(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_resource_comments_item ON resource_comments (item_id, created_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS emoji_plaza_items (
    id VARCHAR(32) PRIMARY KEY,
    owner_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    media_url VARCHAR(1024) NOT NULL,
    package_url VARCHAR(1024) NOT NULL DEFAULT '',
    cover_url VARCHAR(1024) NOT NULL DEFAULT '',
    item_count INTEGER NOT NULL DEFAULT 1,
    is_gif INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_emoji_plaza_created ON emoji_plaza_items (created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_emoji_plaza_owner ON emoji_plaza_items (owner_id, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_emoji_plaza_name ON emoji_plaza_items (name COLLATE NOCASE)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_emoji_plaza_owner_media ON emoji_plaza_items (owner_id, media_url)`)
	_ = addColumnIfMissing(db, "emoji_plaza_items", "package_url", "VARCHAR(1024) NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "emoji_plaza_items", "cover_url", "VARCHAR(1024) NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "emoji_plaza_items", "item_count", "INTEGER NOT NULL DEFAULT 1")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS music_plaza_items (
    id VARCHAR(32) PRIMARY KEY,
    owner_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    song_url VARCHAR(1024) NOT NULL,
    cover_url VARCHAR(1024) NOT NULL DEFAULT '',
    lyrics_url VARCHAR(1024) NOT NULL DEFAULT '',
    size_bytes INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    play_count INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_music_plaza_created ON music_plaza_items (created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_music_plaza_owner ON music_plaza_items (owner_id, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_music_plaza_name ON music_plaza_items (name COLLATE NOCASE)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_music_plaza_owner_song ON music_plaza_items (owner_id, song_url)`)
	_ = addColumnIfMissing(db, "music_plaza_items", "cover_url", "VARCHAR(1024) NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "music_plaza_items", "lyrics_url", "VARCHAR(1024) NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "music_plaza_items", "duration_ms", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "music_plaza_items", "play_count", "INTEGER NOT NULL DEFAULT 0")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS music_plaza_likes (
    item_id VARCHAR(32) NOT NULL REFERENCES music_plaza_items(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id, user_id)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_music_plaza_likes_item ON music_plaza_likes (item_id)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS music_plaza_comments (
    id VARCHAR(32) PRIMARY KEY,
    item_id VARCHAR(32) NOT NULL REFERENCES music_plaza_items(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_music_plaza_comments_item ON music_plaza_comments (item_id, created_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS music_plaza_play_logs (
    id VARCHAR(32) PRIMARY KEY,
    item_id VARCHAR(32) NOT NULL REFERENCES music_plaza_items(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_music_plaza_play_logs_item_time ON music_plaza_play_logs (item_id, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_music_plaza_play_logs_user_time ON music_plaza_play_logs (user_id, created_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_favorites (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fav_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    subtitle VARCHAR(300) NOT NULL DEFAULT '',
    media_url VARCHAR(1024) NOT NULL DEFAULT '',
    extra_json TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_user_favorites_unique ON user_favorites (user_id, fav_type, target_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_favorites_user_created ON user_favorites (user_id, created_at DESC)`)
	// 歌单云端同步：user_playlists 存歌单元信息，user_playlist_songs 存歌曲完整快照（song_json 与客户端 MusicItem.toJson 字段一致）。
	// 上限：每用户 5 个歌单，每歌单 200 首（handler 校验）。
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_playlists (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    cover_url VARCHAR(1024) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_playlists_user ON user_playlists (user_id, updated_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_playlist_songs (
    playlist_id VARCHAR(32) NOT NULL REFERENCES user_playlists(id) ON DELETE CASCADE,
    song_id VARCHAR(64) NOT NULL,
    song_json TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (playlist_id, song_id)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_playlist_songs_pl ON user_playlist_songs (playlist_id, sort_order)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_playlist_songs_song ON user_playlist_songs (song_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_playlists_updated ON user_playlists (updated_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS external_coin_transfers (
    id VARCHAR(32) PRIMARY KEY,
    client_order_no VARCHAR(64) NOT NULL DEFAULT '',
    payer_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    payee_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount INTEGER NOT NULL,
    remark TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'paid',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at DATETIME NULL
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_ext_coin_payer ON external_coin_transfers (payer_id, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_ext_coin_payee ON external_coin_transfers (payee_id, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_ext_coin_client_order ON external_coin_transfers (client_order_no, created_at DESC)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_ext_coin_payer_client_unique ON external_coin_transfers (payer_id, client_order_no) WHERE client_order_no <> ''`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS resource_reports (
    id VARCHAR(32) PRIMARY KEY,
    item_id VARCHAR(32) NOT NULL REFERENCES resource_items(id) ON DELETE CASCADE,
    reporter_id VARCHAR(32) REFERENCES users(id) ON DELETE SET NULL,
    reporter_uid VARCHAR(32) NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_resource_reports_item ON resource_reports (item_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_resource_reports_created ON resource_reports (created_at)`)
	_ = addColumnIfMissing(db, "resource_reports", "status", "TEXT NOT NULL DEFAULT 'pending'")
	_ = addColumnIfMissing(db, "resource_reports", "result", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "resource_reports", "handled_at", "DATETIME NULL")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS user_reports (
    id VARCHAR(32) PRIMARY KEY,
    reporter_id VARCHAR(32) REFERENCES users(id) ON DELETE SET NULL,
    reporter_uid VARCHAR(32) NOT NULL DEFAULT '',
    target_user_id VARCHAR(32) REFERENCES users(id) ON DELETE SET NULL,
    target_uid VARCHAR(32) NOT NULL DEFAULT '',
    target_device_id VARCHAR(128) NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_user_reports_created ON user_reports (created_at)`)
	_ = addColumnIfMissing(db, "user_reports", "status", "TEXT NOT NULL DEFAULT 'pending'")
	_ = addColumnIfMissing(db, "user_reports", "result", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "user_reports", "handled_at", "DATETIME NULL")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS public_court_cases (
    id VARCHAR(32) PRIMARY KEY,
    report_id VARCHAR(32) NOT NULL DEFAULT '',
    reporter_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reporter_uid VARCHAR(32) NOT NULL DEFAULT '',
    defendant_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    defendant_uid VARCHAR(32) NOT NULL DEFAULT '',
    report_reason TEXT NOT NULL DEFAULT '',
    report_evidence TEXT NOT NULL DEFAULT '',
    defense_reason TEXT NOT NULL DEFAULT '',
    defense_evidence TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'open',
    verdict TEXT NOT NULL DEFAULT '',
    admin_note TEXT NOT NULL DEFAULT '',
    ban_hours INTEGER NOT NULL DEFAULT 0,
    reward_processed INTEGER NOT NULL DEFAULT 0,
    closed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_cases_created ON public_court_cases (created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_cases_status ON public_court_cases (status, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_cases_report ON public_court_cases (report_id)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_public_court_open_pair ON public_court_cases (reporter_id, defendant_id) WHERE status = 'open'`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS public_court_votes (
    case_id VARCHAR(32) NOT NULL REFERENCES public_court_cases(id) ON DELETE CASCADE,
    voter_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vote TEXT NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    evidence TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (case_id, voter_id)
)`)
	_ = addColumnIfMissing(db, "public_court_votes", "evidence", "TEXT NOT NULL DEFAULT ''")
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_votes_case ON public_court_votes (case_id, created_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS public_court_statements (
    case_id VARCHAR(32) NOT NULL REFERENCES public_court_cases(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    evidence TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (case_id, user_id)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_statements_case ON public_court_statements (case_id, updated_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_statements_user ON public_court_statements (user_id, updated_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS public_court_discussions (
    id VARCHAR(32) PRIMARY KEY,
    case_id VARCHAR(32) NOT NULL REFERENCES public_court_cases(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_discussions_case ON public_court_discussions (case_id, created_at DESC)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_public_court_discussions_user ON public_court_discussions (user_id, created_at DESC)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS group_reports (
    id VARCHAR(32) PRIMARY KEY,
    reporter_id VARCHAR(32) REFERENCES users(id) ON DELETE SET NULL,
    reporter_uid VARCHAR(32) NOT NULL DEFAULT '',
    group_id VARCHAR(32) REFERENCES groups(id) ON DELETE SET NULL,
    group_name VARCHAR(64) NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_reports_created ON group_reports (created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_reports_group ON group_reports (group_id, created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_reports_reporter ON group_reports (reporter_id, created_at)`)
	_ = addColumnIfMissing(db, "group_reports", "status", "TEXT NOT NULL DEFAULT 'pending'")
	_ = addColumnIfMissing(db, "group_reports", "result", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "group_reports", "handled_at", "DATETIME NULL")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS red_packets (
    id VARCHAR(32) PRIMARY KEY,
    creator_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    group_id VARCHAR(32) NULL REFERENCES groups(id) ON DELETE CASCADE,
    to_user_id VARCHAR(32) NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL DEFAULT '',
    cover_url VARCHAR(1024) NOT NULL DEFAULT '',
    total_amount INTEGER NOT NULL,
    total_count INTEGER NOT NULL,
    remaining_amount INTEGER NOT NULL,
    remaining_count INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_red_packets_creator ON red_packets (creator_id, created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_red_packets_group ON red_packets (group_id, created_at)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_red_packets_to_user ON red_packets (to_user_id, created_at)`)
	_ = addColumnIfMissing(db, "red_packets", "cover_url", "VARCHAR(1024) NOT NULL DEFAULT ''")
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS red_packet_claims (
    id VARCHAR(32) PRIMARY KEY,
    packet_id VARCHAR(32) NOT NULL REFERENCES red_packets(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount INTEGER NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_red_packet_claim_unique ON red_packet_claims (packet_id, user_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_red_packet_claims_packet ON red_packet_claims (packet_id, created_at)`)

	// Telegram 风格频道：帖子和事件均为频道级单份存储，禁止按订阅者 fan-out 写事件。
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS channels (
    id VARCHAR(32) PRIMARY KEY,
    owner_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(64) NOT NULL,
    handle VARCHAR(64) NOT NULL DEFAULT '',
    avatar_url VARCHAR(1024) NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    visibility TEXT NOT NULL DEFAULT 'private',
    status TEXT NOT NULL DEFAULT 'active',
    subscriber_count INTEGER NOT NULL DEFAULT 0,
    linked_discussion_group_id VARCHAR(32) NULL REFERENCES groups(id) ON DELETE SET NULL,
    sign_messages INTEGER NOT NULL DEFAULT 0,
    reactions_enabled INTEGER NOT NULL DEFAULT 1,
    comments_enabled INTEGER NOT NULL DEFAULT 0,
    content_protection INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_channels_handle ON channels (handle) WHERE handle <> ''`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_channels_owner ON channels (owner_id, created_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS channel_sequences (
    channel_id VARCHAR(32) PRIMARY KEY REFERENCES channels(id) ON DELETE CASCADE,
    post_seq BIGINT NOT NULL DEFAULT 0,
    event_seq BIGINT NOT NULL DEFAULT 0,
    subscriber_version BIGINT NOT NULL DEFAULT 0,
    settings_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS channel_subscriptions (
    channel_id VARCHAR(32) NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'subscriber',
    joined_post_seq BIGINT NOT NULL DEFAULT 0,
    last_read_post_seq BIGINT NOT NULL DEFAULT 0,
    viewed_post_seq BIGINT NOT NULL DEFAULT 0,
    notification_level TEXT NOT NULL DEFAULT 'all',
    muted_until DATETIME NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(channel_id, user_id)
)`)
	_ = addColumnIfMissing(db, "channel_subscriptions", "viewed_post_seq", "BIGINT NOT NULL DEFAULT 0")
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_channel_subscriptions_user ON channel_subscriptions (user_id, joined_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS channel_posts (
    id VARCHAR(32) PRIMARY KEY,
    channel_id VARCHAR(32) NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    post_seq BIGINT NOT NULL,
    author_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reply_to_post_id VARCHAR(32) NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '',
    msg_type VARCHAR(16) NOT NULL DEFAULT 'text',
    media_url VARCHAR(1024) NOT NULL DEFAULT '',
    thumb_url VARCHAR(1024) NOT NULL DEFAULT '',
    view_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    edited_at DATETIME NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(channel_id, post_seq)
)`)
	_ = addColumnIfMissing(db, "channel_posts", "reply_to_post_id", "VARCHAR(32) NOT NULL DEFAULT ''")
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_channel_posts_after ON channel_posts (channel_id, post_seq)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS channel_post_reactions (
    channel_id VARCHAR(32) NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    post_id VARCHAR(32) NOT NULL REFERENCES channel_posts(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(post_id, user_id)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_channel_reactions_aggregate ON channel_post_reactions (post_id, emoji)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS channel_events (
    channel_id VARCHAR(32) NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    event_seq BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    ref_id VARCHAR(32) NOT NULL DEFAULT '',
    payload TEXT NOT NULL DEFAULT '{}',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(channel_id, event_seq)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_channel_events_created ON channel_events (created_at)`)
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS channel_api_permits (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'applied',
    scopes TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME NULL,
    last_used_at DATETIME NULL
)`)
	_ = addColumnIfMissing(db, "channel_api_permits", "review_reason", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "channel_api_permits", "token_delivered_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "channel_api_permits", "contact_email", "TEXT NOT NULL DEFAULT ''")
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_channel_api_permits_user ON channel_api_permits (user_id, status)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_channel_api_permits_token ON channel_api_permits (token_hash) WHERE token_hash <> ''`)

	// 机器人消息许可（bot permits）
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS bot_permits (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'applied',   -- applied / approved / rejected / revoked
    scopes TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    expires_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME,
    last_used_at DATETIME
)`)
	_ = addColumnIfMissing(db, "bot_permits", "review_reason", "TEXT NOT NULL DEFAULT ''")
	_ = addColumnIfMissing(db, "bot_permits", "token_delivered_at", "DATETIME NULL")
	_ = addColumnIfMissing(db, "bot_permits", "contact_email", "TEXT NOT NULL DEFAULT ''")
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_bot_permits_user ON bot_permits (user_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_bot_permits_status ON bot_permits (status)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_bot_permits_token ON bot_permits (token_hash) WHERE token_hash <> ''`)

	// 按钮回调防重放（tid 一次性）
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS button_callbacks (
    tid VARCHAR(128) PRIMARY KEY,
    msg_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    action TEXT NOT NULL DEFAULT '',
    btn_index INTEGER NOT NULL DEFAULT 0,
    form_data TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	_ = addColumnIfMissing(db, "button_callbacks", "btn_index", "INTEGER NOT NULL DEFAULT 0")
	_ = addColumnIfMissing(db, "button_callbacks", "form_data", "TEXT NOT NULL DEFAULT ''")
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_button_callbacks_msg ON button_callbacks (msg_id)`)
	// 表单允许群内每个用户各提交一次；旧表以 tid 为主键，只允许全群第一人提交。
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS button_responses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tid VARCHAR(512) NOT NULL,
    msg_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    action TEXT NOT NULL DEFAULT '',
    btn_index INTEGER NOT NULL DEFAULT 0,
    form_data TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tid, user_id)
)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_button_responses_msg ON button_responses (msg_id)`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_button_responses_created ON button_responses (created_at)`)
	// 审核员账号（独立于主管理员后台）
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS moderators (
    id VARCHAR(32) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    nickname TEXT NOT NULL DEFAULT '',
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at DATETIME
)`)
	_, _ = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_moderators_username ON moderators(username)`)

	// 审核员操作日志
	_, _ = db.Exec(`
CREATE TABLE IF NOT EXISTS moderator_action_logs (
    id VARCHAR(32) PRIMARY KEY,
    moderator_id VARCHAR(32) NOT NULL DEFAULT '',
    moderator_name VARCHAR(64) NOT NULL DEFAULT '',
    action_type VARCHAR(32) NOT NULL DEFAULT '',
    target_type VARCHAR(32) NOT NULL DEFAULT '',
    target_id VARCHAR(64) NOT NULL DEFAULT '',
    action_detail TEXT NOT NULL DEFAULT '',
    ip VARCHAR(64) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`)
	// 群组每日发言限制字段及官方群默认限制 20
	_ = addColumnIfMissing(db, "groups", "daily_msg_limit", "INTEGER NOT NULL DEFAULT 0")
	_, _ = db.Exec(`UPDATE groups SET daily_msg_limit = 20 WHERE id = 'GRP-3TTO6Q' AND daily_msg_limit = 0`)
	_, _ = db.Exec(`DROP INDEX IF EXISTS idx_group_messages_daily_limit`)
	_, _ = db.Exec(`CREATE INDEX IF NOT EXISTS idx_group_msgs_user_daily ON group_messages (sender_id, group_id, created_at)`)
}

func addColumnIfMissing(db *sqlx.DB, table, column, colDef string) error {
	rows, err := db.Queryx("PRAGMA table_info(" + table + ")")
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		if err := rows.Scan(new(interface{}), &name, new(interface{}), new(interface{}), new(interface{}), new(interface{})); err != nil {
			return err
		}
		if name == column {
			return nil
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = db.Exec("ALTER TABLE " + table + " ADD COLUMN " + column + " " + colDef)
	return err
}

// EnsureSystemUser creates the SYSTEM user if it doesn't exist
func EnsureSystemUser(db *sqlx.DB) error {
	_, err := db.Exec(`
INSERT OR IGNORE INTO users (id, uid, username, display_name, email, password_hash, verified, created_at)
VALUES ('SYSTEM', 'SYSTEM', '系统通知', '系统通知', 'system@localhost', '', 1, CURRENT_TIMESTAMP)`)
	return err
}

// IsSQLiteBusy checks whether an error is caused by SQLite lock or busy state.
func IsSQLiteBusy(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "busy") || strings.Contains(s, "locked")
}
