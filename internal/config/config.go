package config

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Port                      string
	DatabaseURL               string
	UploadDir                 string
	UpdateDir                 string
	PublicBaseURL             string
	FilesBaseURL              string
	DataServerBaseURL         string
	DataServerSyncToken       string
	AdminUser                 string
	AdminPassword             string
	JWTSecret                 []byte
	JWTIssuer                 string
	JWTIssuerLegacy           []string
	AccessTokenTTL            time.Duration
	RefreshTokenTTL           time.Duration
	OAuthIssuer               string
	OAuthSigningKeyPath       string
	OAuthAccessTokenTTL       time.Duration
	OAuthRefreshTokenTTL      time.Duration
	OAuthAuthorizationCodeTTL time.Duration
	ResourceQuota             int64
	RegistrationLimit         int
	VideoEnabled              bool
	MediaRateBytes            int64
	UpdateRateBytes           int64
	VideoRateBytes            int64
	MusicRateBytes            int64
	MediaDownloadConcurrency  int
	UpdateDownloadConcurrency int
	VideoDownloadConcurrency  int
	MusicDownloadConcurrency  int
	DownloadSources           []string
	OpenCodeGoAPIKey          string
	OpenCodeGoEndpoint        string
	OpenCodeGoModel           string
	TurnstileSecretKey        string
	// V2 路由防护开关：V2RequireSignature=true 时 /v2 无签名拒绝；
	// V2RequireDevice=true 时 /v2 无 X-Device-Id 拒绝。默认关闭（灰度放行）。
	V2RequireSignature    bool
	V2RequireDevice       bool
	ResourceSquareEnabled bool
	MusicPlazaEnabled     bool
	// OssEnabled 控制阿里云 OSS 与雨云 S3 同步。社区版默认关闭，
	// 仅在 settings.json 的 oss_enabled 或环境变量 OSS_ENABLED 显式开启时启用。
	OssEnabled bool
}

func Load() (Config, error) {
	cfg := Config{}

	cfg.Port = getEnv("PORT", "8080")
	dbURL, dbURLSet := getEnvRaw("DATABASE_URL")
	if dbURL == "" {
		dbURL = "metrochat.db"
	}
	cfg.DatabaseURL = resolveDatabaseURL(dbURL, dbURLSet)
	cfg.UploadDir = getEnv("UPLOAD_DIR", "uploads")
	cfg.UpdateDir = getEnv("UPDATE_DIR", "update")
	cfg.PublicBaseURL = strings.TrimSpace(getEnv("PUBLIC_BASE_URL", ""))
	// 社区版不接对象存储，文件默认由源服务器自身提供；留空表示同源相对路径。
	cfg.FilesBaseURL = strings.TrimRight(strings.TrimSpace(getEnv("FILES_BASE_URL", "")), "/")
	cfg.OAuthIssuer = strings.TrimRight(strings.TrimSpace(
		getEnv("OAUTH_ISSUER", "https://oc.mcl0.dpdns.org")), "/")
	cfg.OAuthSigningKeyPath = strings.TrimSpace(getEnv("OAUTH_SIGNING_KEY_PATH", "oauth_ed25519.key"))
	cfg.OAuthAccessTokenTTL = getDurationEnv("OAUTH_ACCESS_TOKEN_TTL", 10*time.Minute)
	cfg.OAuthRefreshTokenTTL = getDurationEnv("OAUTH_REFRESH_TOKEN_TTL", 30*24*time.Hour)
	cfg.OAuthAuthorizationCodeTTL = getDurationEnv("OAUTH_CODE_TTL", 90*time.Second)
	issuerURL, issuerErr := url.Parse(cfg.OAuthIssuer)
	if issuerErr != nil || issuerURL.Scheme != "https" || issuerURL.Host == "" || issuerURL.User != nil || issuerURL.RawQuery != "" || issuerURL.Fragment != "" {
		return cfg, errors.New("OAUTH_ISSUER must be an absolute HTTPS URL without userinfo, query or fragment")
	}
	cfg.DataServerBaseURL = strings.TrimRight(strings.TrimSpace(getEnv("DATA_SERVER_BASE_URL", "")), "/")
	cfg.DataServerSyncToken = strings.TrimSpace(getEnv("DATA_SERVER_SYNC_TOKEN", ""))
	cfg.AdminUser = getEnv("ADMIN_USER", "admin")
	cfg.AdminPassword = getEnv("ADMIN_PASSWORD", "admin123456")
	cfg.ResourceQuota = 10 * 1024 * 1024 * 1024 // default 10GB
	cfg.MediaRateBytes = 5 << 20
	cfg.UpdateRateBytes = 5 << 20
	cfg.VideoRateBytes = 5 << 20
	cfg.MusicRateBytes = 5 << 20
	cfg.MediaDownloadConcurrency = 12
	cfg.UpdateDownloadConcurrency = 4
	cfg.VideoDownloadConcurrency = 2
	cfg.MusicDownloadConcurrency = 8
	// 上游地址和模型固定为 OpenCode Go 套餐端点；密钥只允许从进程环境读取。
	cfg.OpenCodeGoAPIKey = strings.TrimSpace(getEnv("OPENCODE_GO_API_KEY", ""))
	cfg.OpenCodeGoEndpoint = "https://opencode.ai/zen/go/v1/chat/completions"
	cfg.OpenCodeGoModel = "deepseek-v4-flash"
	// Cloudflare Turnstile Secret Key（仅从环境变量 TURNSTILE_SECRET 读取，源码不写死）。
	// 未配置时 siteverify 无法通过（fail-closed），注册页的人机验证会被拒绝。
	cfg.TurnstileSecretKey = strings.TrimSpace(getEnv("TURNSTILE_SECRET", ""))
	// /v2 防护开关（灰度默认关闭，等带签名的客户端覆盖后再开）。
	cfg.V2RequireSignature = parseBoolEnv("V2_REQUIRE_SIGNATURE", false)
	cfg.V2RequireDevice = parseBoolEnv("V2_REQUIRE_DEVICE", false)
	// 对象存储默认关闭：社区版以本地 uploads/ 为权威存储，
	// 需要 CDN 加速时再通过 settings.json 或环境变量开启。
	cfg.OssEnabled = parseBoolEnv("OSS_ENABLED", false)

	settingsPath := getEnv("SETTINGS_JSON", "settings.json")
	settings, settingsOk := loadSettingsFromJSON(settingsPath)
	if settingsOk {
		// 部署方可把管理员账号与对象存储开关放在 settings.json（该文件不入库）。
		// 环境变量显式设置时优先级更高。
		if _, ok := os.LookupEnv("OSS_ENABLED"); !ok && settings.OssEnabled != nil {
			cfg.OssEnabled = *settings.OssEnabled
		}
		if _, ok := os.LookupEnv("ADMIN_USER"); !ok {
			if v := strings.TrimSpace(settings.AdminUser); v != "" {
				cfg.AdminUser = v
			}
		}
		if _, ok := os.LookupEnv("ADMIN_PASSWORD"); !ok {
			if v := settings.AdminPassword; strings.TrimSpace(v) != "" {
				cfg.AdminPassword = v
			}
		}
		if v := strings.TrimSpace(settings.PublicBaseURL); v != "" {
			cfg.PublicBaseURL = v
		}
		if v := strings.TrimRight(strings.TrimSpace(settings.DataServerBaseURL), "/"); v != "" {
			cfg.DataServerBaseURL = v
		}
		if v := strings.TrimSpace(settings.DataServerSyncToken); v != "" {
			cfg.DataServerSyncToken = v
		}
		if settings.ResourceQuotaBytes > 0 {
			cfg.ResourceQuota = settings.ResourceQuotaBytes
		}
		if settings.RegistrationLimit > 0 {
			cfg.RegistrationLimit = settings.RegistrationLimit
		}
		if settings.MediaRateBytes != nil && *settings.MediaRateBytes >= 0 {
			cfg.MediaRateBytes = *settings.MediaRateBytes
		}
		if settings.UpdateRateBytes != nil && *settings.UpdateRateBytes >= 0 {
			cfg.UpdateRateBytes = *settings.UpdateRateBytes
		}
		if settings.VideoRateBytes != nil && *settings.VideoRateBytes >= 0 {
			cfg.VideoRateBytes = *settings.VideoRateBytes
		}
		if settings.MusicRateBytes != nil && *settings.MusicRateBytes >= 0 {
			cfg.MusicRateBytes = *settings.MusicRateBytes
		}
		if settings.MediaDownloadConcurrency != nil && *settings.MediaDownloadConcurrency > 0 {
			cfg.MediaDownloadConcurrency = *settings.MediaDownloadConcurrency
		}
		if settings.UpdateDownloadConcurrency != nil && *settings.UpdateDownloadConcurrency > 0 {
			cfg.UpdateDownloadConcurrency = *settings.UpdateDownloadConcurrency
		}
		if settings.VideoDownloadConcurrency != nil && *settings.VideoDownloadConcurrency > 0 {
			cfg.VideoDownloadConcurrency = *settings.VideoDownloadConcurrency
		}
		if settings.MusicDownloadConcurrency != nil && *settings.MusicDownloadConcurrency > 0 {
			cfg.MusicDownloadConcurrency = *settings.MusicDownloadConcurrency
		}
		cfg.DownloadSources = cleanDownloadSources(settings.DownloadSources)
	}

	// 未单独配置文件域名时跟随公开访问域名，保证媒体地址指向源服务器自身。
	if cfg.FilesBaseURL == "" {
		cfg.FilesBaseURL = strings.TrimRight(strings.TrimSpace(cfg.PublicBaseURL), "/")
	}

	if envQuota := getInt64Env("RESOURCE_QUOTA_BYTES", 0); envQuota > 0 {
		cfg.ResourceQuota = envQuota
	}
	if envLimit := getIntEnv("REGISTRATION_LIMIT", 0); envLimit > 0 {
		cfg.RegistrationLimit = envLimit
	}
	if v, ok := getInt64EnvAllowZero("MEDIA_TRANSFER_RATE_BYTES"); ok && v >= 0 {
		cfg.MediaRateBytes = v
	}
	if v, ok := getInt64EnvAllowZero("UPDATE_TRANSFER_RATE_BYTES"); ok && v >= 0 {
		cfg.UpdateRateBytes = v
	}
	if v, ok := getInt64EnvAllowZero("VIDEO_TRANSFER_RATE_BYTES"); ok && v >= 0 {
		cfg.VideoRateBytes = v
	}
	if v, ok := getInt64EnvAllowZero("MUSIC_TRANSFER_RATE_BYTES"); ok && v >= 0 {
		cfg.MusicRateBytes = v
	}
	if envSources := os.Getenv("DOWNLOAD_SOURCES"); strings.TrimSpace(envSources) != "" {
		cfg.DownloadSources = cleanDownloadSources(strings.Split(envSources, ","))
	}
	if len(cfg.DownloadSources) == 0 {
		// 社区版只有源服务器一个下载源，不再把官方 CDN 与对象存储作为默认回退。
		if cfg.FilesBaseURL != "" {
			cfg.DownloadSources = []string{cfg.FilesBaseURL}
		}
	}
	if v, ok := getIntEnvAllowZero("MEDIA_DOWNLOAD_CONCURRENCY"); ok && v > 0 {
		cfg.MediaDownloadConcurrency = v
	}
	if v, ok := getIntEnvAllowZero("UPDATE_DOWNLOAD_CONCURRENCY"); ok && v > 0 {
		cfg.UpdateDownloadConcurrency = v
	}
	if v, ok := getIntEnvAllowZero("VIDEO_DOWNLOAD_CONCURRENCY"); ok && v > 0 {
		cfg.VideoDownloadConcurrency = v
	}
	if v, ok := getIntEnvAllowZero("MUSIC_DOWNLOAD_CONCURRENCY"); ok && v > 0 {
		cfg.MusicDownloadConcurrency = v
	}
	cfg.VideoEnabled = false
	if settingsOk {
		cfg.VideoEnabled = settings.VideoEnabled
	}
	if raw, ok := getEnvRaw("VIDEO_ENABLED"); ok {
		cfg.VideoEnabled = parseBool(raw, cfg.VideoEnabled)
	}
	cfg.ResourceSquareEnabled = false
	if raw, ok := getEnvRaw("RESOURCE_SQUARE_ENABLED"); ok {
		cfg.ResourceSquareEnabled = parseBool(raw, false)
	}
	cfg.MusicPlazaEnabled = true
	if raw, ok := getEnvRaw("MUSIC_PLAZA_ENABLED"); ok {
		cfg.MusicPlazaEnabled = parseBool(raw, true)
	}
	if raw, ok := getEnvRaw("PUBLIC_BASE_URL"); ok {
		cfg.PublicBaseURL = strings.TrimSpace(raw)
	}
	if raw, ok := getEnvRaw("DATA_SERVER_BASE_URL"); ok {
		cfg.DataServerBaseURL = strings.TrimRight(strings.TrimSpace(raw), "/")
	}
	if raw, ok := getEnvRaw("DATA_SERVER_SYNC_TOKEN"); ok {
		cfg.DataServerSyncToken = strings.TrimSpace(raw)
	}

	secret, secretSet := getEnvRaw("JWT_SECRET")
	if secret == "" {
		if settingsOk && strings.TrimSpace(settings.JWTSecret) != "" {
			secret = strings.TrimSpace(settings.JWTSecret)
		} else {
			// 未配置时生成随机密钥并写回 settings.json：
			// 避免所有社区部署共用同一个固定默认值，导致 token 可被跨站伪造。
			generated, err := randomSecret()
			if err != nil {
				return cfg, errors.New("JWT_SECRET must be at least 32 bytes")
			}
			secret = generated
		}
	}
	if len(secret) < 32 {
		return cfg, errors.New("JWT_SECRET must be at least 32 bytes")
	}
	cfg.JWTSecret = []byte(secret)
	if !secretSet && (settings.JWTSecret == "") && settingsPath != "" {
		persistJWTSecret(settingsPath, settings, secret)
	}

	cfg.JWTIssuer = getEnv("JWT_ISSUER", "metrochat")
	if legacy := strings.TrimSpace(getEnv("JWT_ISSUER_LEGACY", "")); legacy != "" {
		for _, item := range strings.Split(legacy, ",") {
			trimmed := strings.TrimSpace(item)
			if trimmed != "" {
				cfg.JWTIssuerLegacy = append(cfg.JWTIssuerLegacy, trimmed)
			}
		}
	}
	// Access Token 必须短期有效；旧的无 exp Token 仍由兼容校验和 token_version 管理。
	cfg.AccessTokenTTL = getDurationEnv("ACCESS_TOKEN_TTL", time.Hour)
	cfg.RefreshTokenTTL = getDurationEnv("REFRESH_TOKEN_TTL", 30*24*time.Hour)

	return cfg, nil
}

// cleanDownloadSources 过滤空值并统一去掉尾部斜杠；返回 nil 表示未配置（走默认）。
func cleanDownloadSources(input []string) []string {
	var out []string
	for _, raw := range input {
		v := strings.TrimSpace(raw)
		if v == "" {
			continue
		}
		v = strings.TrimRight(v, "/")
		if !strings.HasPrefix(v, "http://") && !strings.HasPrefix(v, "https://") {
			continue
		}
		dup := false
		for _, existing := range out {
			if existing == v {
				dup = true
				break
			}
		}
		if !dup {
			out = append(out, v)
		}
	}
	return out
}

// randomSecret 生成 32 字节随机密钥的 base64url 编码。
func randomSecret() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func getEnv(key, def string) string {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	return v
}

// parseBoolEnv 解析布尔环境变量（1/true/yes 为 true）。
func parseBoolEnv(key string, def bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	}
	return def
}

func getEnvRaw(key string) (string, bool) {
	v, ok := os.LookupEnv(key)
	return v, ok
}

func resolveDatabaseURL(value string, explicit bool) string {
	if value == "" {
		return value
	}
	if explicit {
		return value
	}
	// If default relative path doesn't exist, try common locations.
	if looksLikeURL(value) || filepath.IsAbs(value) {
		return value
	}
	if fileExists(value) {
		return value
	}
	if fileExists(filepath.Join("server", value)) {
		return filepath.Join("server", value)
	}
	if exePath, err := os.Executable(); err == nil {
		exeDir := filepath.Dir(exePath)
		candidate := filepath.Join(exeDir, value)
		if fileExists(candidate) {
			return candidate
		}
	}
	return value
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func looksLikeURL(value string) bool {
	return strings.Contains(value, "://")
}

func getInt64Env(key string, def int64) int64 {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	val, err := strconv.ParseInt(v, 10, 64)
	if err != nil || val <= 0 {
		return def
	}
	return val
}

func getInt64EnvAllowZero(key string) (int64, bool) {
	v, ok := os.LookupEnv(key)
	if !ok {
		return 0, false
	}
	v = strings.TrimSpace(v)
	if v == "" {
		return 0, false
	}
	val, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return 0, false
	}
	return val, true
}

func getIntEnvAllowZero(key string) (int, bool) {
	v, ok := os.LookupEnv(key)
	if !ok {
		return 0, false
	}
	v = strings.TrimSpace(v)
	if v == "" {
		return 0, false
	}
	val, err := strconv.Atoi(v)
	if err != nil {
		return 0, false
	}
	return val, true
}

func getIntEnv(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	val, err := strconv.Atoi(v)
	if err != nil || val <= 0 {
		return def
	}
	return val
}

func parseBool(raw string, def bool) bool {
	v := strings.ToLower(strings.TrimSpace(raw))
	if v == "1" || v == "true" || v == "yes" || v == "on" {
		return true
	}
	if v == "0" || v == "false" || v == "no" || v == "off" {
		return false
	}
	return def
}

type quotaSettings struct {
	ResourceQuotaBytes int64 `json:"resource_quota_bytes"`
	RegistrationLimit  int   `json:"registration_limit"`
}

type settingsFile struct {
	ResourceQuotaBytes        int64    `json:"resource_quota_bytes"`
	RegistrationLimit         int      `json:"registration_limit"`
	AdminUser                 string   `json:"admin_user"`
	AdminPassword             string   `json:"admin_password"`
	OssEnabled                *bool    `json:"oss_enabled"`
	PublicBaseURL             string   `json:"public_base_url"`
	DataServerBaseURL         string   `json:"data_server_base_url"`
	DataServerSyncToken       string   `json:"data_server_sync_token"`
	JWTSecret                 string   `json:"jwt_secret"`
	VideoEnabled              bool     `json:"video_enabled"`
	MediaRateBytes            *int64   `json:"media_transfer_rate_bytes"`
	UpdateRateBytes           *int64   `json:"update_transfer_rate_bytes"`
	VideoRateBytes            *int64   `json:"video_transfer_rate_bytes"`
	MusicRateBytes            *int64   `json:"music_transfer_rate_bytes"`
	MediaDownloadConcurrency  *int     `json:"media_download_concurrency"`
	UpdateDownloadConcurrency *int     `json:"update_download_concurrency"`
	VideoDownloadConcurrency  *int     `json:"video_download_concurrency"`
	MusicDownloadConcurrency  *int     `json:"music_download_concurrency"`
	DownloadSources           []string `json:"download_sources"`
}

func loadSettingsFromJSON(path string) (settingsFile, bool) {
	if path == "" {
		return settingsFile{}, false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return settingsFile{}, false
	}
	var cfg settingsFile
	if err := json.Unmarshal(data, &cfg); err != nil {
		return settingsFile{}, false
	}
	return cfg, true
}

func persistJWTSecret(path string, settings settingsFile, secret string) {
	if path == "" || secret == "" {
		return
	}
	if settings.JWTSecret != "" {
		return
	}
	settings.JWTSecret = secret
	data, err := json.MarshalIndent(settings, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(path, data, 0644)
}

func loadQuotaFromJSON(path string) int64 {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	var cfg quotaSettings
	if err := json.Unmarshal(data, &cfg); err != nil {
		return 0
	}
	if cfg.ResourceQuotaBytes > 0 {
		return cfg.ResourceQuotaBytes
	}
	return 0
}

func loadRegistrationLimitFromJSON(path string) int {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	var cfg quotaSettings
	if err := json.Unmarshal(data, &cfg); err != nil {
		return 0
	}
	if cfg.RegistrationLimit > 0 {
		return cfg.RegistrationLimit
	}
	return 0
}

func getDurationEnv(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	secs, err := strconv.Atoi(v)
	if err != nil || secs <= 0 {
		return def
	}
	return time.Duration(secs) * time.Second
}
