-- MetroChat core schema (SQLite)

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(32) PRIMARY KEY,
    uid VARCHAR(32) UNIQUE NOT NULL,
    ncuid VARCHAR(32) NOT NULL DEFAULT '',
    uid_changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(32) UNIQUE NOT NULL,
    display_name VARCHAR(64) NOT NULL DEFAULT '',
    user_title VARCHAR(64) NOT NULL DEFAULT '',
    user_title_price INTEGER NOT NULL DEFAULT 0,
    avatar_url VARCHAR(512) NOT NULL DEFAULT '',
    signature TEXT NOT NULL DEFAULT '',
    cover_url VARCHAR(512) NOT NULL DEFAULT '',
    password_hash TEXT NOT NULL,
    official_uid TEXT NOT NULL DEFAULT '',
    is_shadow INTEGER NOT NULL DEFAULT 0,
    claimed_at DATETIME NULL,
    token_version INTEGER NOT NULL DEFAULT 0,
    coin_balance INTEGER NOT NULL DEFAULT 10,
    reputation_score INTEGER NOT NULL DEFAULT 110,
    presence_status TEXT NOT NULL DEFAULT '',
    reject_group_invites INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);
CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_ncuid_unique ON users (ncuid);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) UNIQUE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    replaced_by VARCHAR(32) NULL
);

CREATE INDEX IF NOT EXISTS idx_refresh_user_id ON refresh_tokens (user_id);

CREATE TABLE IF NOT EXISTS friend_requests (
    id VARCHAR(32) PRIMARY KEY,
    from_user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status SMALLINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at DATETIME NULL
);

-- SQLite partially supports partial indexes in newer versions
CREATE UNIQUE INDEX IF NOT EXISTS idx_friend_req_pending
ON friend_requests (from_user_id, to_user_id)
WHERE status = 0;

CREATE INDEX IF NOT EXISTS idx_friend_req_to ON friend_requests (to_user_id);

CREATE TABLE IF NOT EXISTS friends (
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    remark_name VARCHAR(64) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, friend_user_id)
);

CREATE TABLE IF NOT EXISTS direct_threads (
    id VARCHAR(32) PRIMARY KEY,
    user_a_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_direct_thread_pair
ON direct_threads (user_a_id, user_b_id);
CREATE INDEX IF NOT EXISTS idx_direct_threads_user_a ON direct_threads (user_a_id, id);
CREATE INDEX IF NOT EXISTS idx_direct_threads_user_b ON direct_threads (user_b_id, id);

CREATE TABLE IF NOT EXISTS direct_messages (
    id VARCHAR(32) PRIMARY KEY,
    thread_id VARCHAR(32) NOT NULL REFERENCES direct_threads(id) ON DELETE CASCADE,
    sender_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    official_msg_id TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    msg_type TEXT NOT NULL DEFAULT 'text',
    media_url TEXT NOT NULL DEFAULT '',
    thumb_url TEXT NOT NULL DEFAULT '',
    original_url TEXT NOT NULL DEFAULT '',
    duration_ms INTEGER NOT NULL DEFAULT 0,
    burn_after_seconds INTEGER NOT NULL DEFAULT 0,
    burn_start_at DATETIME NULL,
    burned_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at DATETIME NULL,
    read_at DATETIME NULL
);

CREATE INDEX IF NOT EXISTS idx_direct_msg_thread ON direct_messages (thread_id, created_at);
CREATE INDEX IF NOT EXISTS idx_direct_msg_thread_cursor ON direct_messages (thread_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_direct_unread_thread_created
ON direct_messages (thread_id, created_at DESC, sender_id)
WHERE read_at IS NULL AND burned_at IS NULL AND msg_type != 'recall';
CREATE INDEX IF NOT EXISTS idx_direct_messages_burn_scan
ON direct_messages (burned_at, burn_start_at, burn_after_seconds);

CREATE TABLE IF NOT EXISTS groups (
    id VARCHAR(32) PRIMARY KEY,
    official_group_id TEXT NOT NULL DEFAULT '',
    imported INTEGER NOT NULL DEFAULT 0,
    name VARCHAR(64) NOT NULL,
    avatar_url VARCHAR(512) NOT NULL DEFAULT '',
    owner_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    join_approval BOOLEAN NOT NULL DEFAULT 0,
    global_mute BOOLEAN NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id VARCHAR(32) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role SMALLINT NOT NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_read_at DATETIME NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members (user_id);
CREATE INDEX IF NOT EXISTS idx_group_members_user_cover
ON group_members (user_id, group_id, last_read_at, joined_at);

CREATE TABLE IF NOT EXISTS group_invitations (
    id VARCHAR(32) PRIMARY KEY,
    group_id VARCHAR(32) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    inviter_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invitee_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at DATETIME NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_group_invitation_pending
ON group_invitations (group_id, invitee_id) WHERE status = 0;
CREATE INDEX IF NOT EXISTS idx_group_invitation_invitee
ON group_invitations (invitee_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS group_join_requests (
    id VARCHAR(32) PRIMARY KEY,
    group_id VARCHAR(32) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status SMALLINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at DATETIME NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_group_join_pending
ON group_join_requests (group_id, user_id)
WHERE status = 0;

CREATE TABLE IF NOT EXISTS group_messages (
    id VARCHAR(32) PRIMARY KEY,
    group_id VARCHAR(32) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    sender_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    official_msg_id TEXT NOT NULL DEFAULT '',
    group_seq INTEGER NULL,
    body TEXT NOT NULL,
    msg_type TEXT NOT NULL DEFAULT 'text',
    media_url TEXT NOT NULL DEFAULT '',
    thumb_url TEXT NOT NULL DEFAULT '',
    original_url TEXT NOT NULL DEFAULT '',
    duration_ms INTEGER NOT NULL DEFAULT 0,
    burn_after_seconds INTEGER NOT NULL DEFAULT 0,
    burn_start_at DATETIME NULL,
    burned_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_group_messages_group ON group_messages (group_id, created_at);
CREATE INDEX IF NOT EXISTS idx_group_messages_group_cursor ON group_messages (group_id, created_at DESC, id DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_messages_group_seq ON group_messages (group_id, group_seq);
CREATE INDEX IF NOT EXISTS idx_group_unread_group_created
ON group_messages (group_id, created_at DESC, sender_id)
WHERE burned_at IS NULL AND msg_type != 'recall';
CREATE INDEX IF NOT EXISTS idx_group_messages_burn_scan
ON group_messages (burned_at, burn_start_at, burn_after_seconds);

CREATE TABLE IF NOT EXISTS group_message_sequences (
    group_id VARCHAR(32) PRIMARY KEY,
    last_seq INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS moments (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    official_moment_id TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    image_url VARCHAR(512) NOT NULL DEFAULT '',
    like_extra INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_moments_user ON moments (user_id, created_at);

CREATE TABLE IF NOT EXISTS moment_likes (
    moment_id VARCHAR(32) NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (moment_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_moment_likes_moment ON moment_likes (moment_id);

CREATE TABLE IF NOT EXISTS moment_comments (
    id VARCHAR(32) PRIMARY KEY,
    moment_id VARCHAR(32) NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    official_comment_id TEXT NOT NULL DEFAULT '',
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_moment_comments_moment ON moment_comments (moment_id, created_at);

CREATE TABLE IF NOT EXISTS user_devices (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    imei VARCHAR(64) NOT NULL DEFAULT '',
    last_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_device_pair
ON user_devices (user_id, device_id);

CREATE INDEX IF NOT EXISTS idx_user_devices_user ON user_devices (user_id);

CREATE TABLE IF NOT EXISTS banned_devices (
    device_id VARCHAR(128) PRIMARY KEY,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS banned_users (
    user_id VARCHAR(32) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS crash_reports (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(32) REFERENCES users(id) ON DELETE SET NULL,
    crash_log TEXT NOT NULL,
    device_model VARCHAR(128) NOT NULL DEFAULT '',
    android_version VARCHAR(32) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_crash_reports_created ON crash_reports (created_at);
CREATE INDEX IF NOT EXISTS idx_crash_reports_user ON crash_reports (user_id, created_at);

-- 每个账号的 AI token 总额度；提示词和回复不会写入数据库。
CREATE TABLE IF NOT EXISTS ai_token_quotas (
    user_id VARCHAR(32) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    token_limit INTEGER NOT NULL DEFAULT 5000000,
    used_tokens INTEGER NOT NULL DEFAULT 0,
    reserved_tokens INTEGER NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (token_limit >= 0),
    CHECK (used_tokens >= 0),
    CHECK (reserved_tokens >= 0)
);
CREATE INDEX IF NOT EXISTS idx_ai_token_quotas_updated ON ai_token_quotas (updated_at);

-- 第三方登录 OAuth 2.1 / OpenID Connect
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

CREATE TABLE IF NOT EXISTS file_assets (
    id TEXT PRIMARY KEY,
    sha256 TEXT NOT NULL UNIQUE,
    relative_path TEXT NOT NULL UNIQUE,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    original_name TEXT NOT NULL DEFAULT '',
    content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    created_by TEXT NOT NULL DEFAULT '',
    reference_count INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_file_assets_sha256 ON file_assets(sha256);
CREATE INDEX IF NOT EXISTS idx_file_assets_created_by ON file_assets(created_by, created_at DESC);

CREATE TABLE IF NOT EXISTS channel_api_permits (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'applied',
    scopes TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    review_reason TEXT NOT NULL DEFAULT '',
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME NULL,
    token_delivered_at DATETIME NULL,
    contact_email TEXT NOT NULL DEFAULT '',
    last_used_at DATETIME NULL
);
CREATE INDEX IF NOT EXISTS idx_channel_api_permits_user ON channel_api_permits(user_id,status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_channel_api_permits_token ON channel_api_permits(token_hash) WHERE token_hash <> '';

CREATE TABLE IF NOT EXISTS bot_permits (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'applied',
    scopes TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    review_reason TEXT NOT NULL DEFAULT '',
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME NULL,
    token_delivered_at DATETIME NULL,
    contact_email TEXT NOT NULL DEFAULT '',
    last_used_at DATETIME NULL
);
CREATE INDEX IF NOT EXISTS idx_bot_permits_user ON bot_permits(user_id);
CREATE INDEX IF NOT EXISTS idx_bot_permits_status ON bot_permits(status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_bot_permits_token ON bot_permits(token_hash) WHERE token_hash <> '';

-- 官方数据迁移：任务与过程日志
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
);

CREATE INDEX IF NOT EXISTS idx_import_jobs_user ON import_jobs (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS import_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id VARCHAR(32) NOT NULL,
    level TEXT NOT NULL DEFAULT 'info',
    stage TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_import_events_job ON import_events (job_id, id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_official_uid ON users (official_uid) WHERE official_uid != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_direct_msg_official ON direct_messages (official_msg_id) WHERE official_msg_id != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_msg_official ON group_messages (official_msg_id) WHERE official_msg_id != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_groups_official ON groups (official_group_id) WHERE official_group_id != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_moments_official ON moments (official_moment_id) WHERE official_moment_id != '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_moment_comments_official ON moment_comments (official_comment_id) WHERE official_comment_id != '';
