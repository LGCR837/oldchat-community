package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/auth"
	"metrochat/internal/data"
	"metrochat/internal/migrate"
	"metrochat/internal/official"
)

type migrateStartRequest struct {
	Mode             string `json:"mode"`
	OfficialUsername string `json:"official_username"`
	OfficialPassword string `json:"official_password"`
	Identifier       string `json:"identifier"`
	Username         string `json:"username"`
	Email            string `json:"email"`
	Password         string `json:"password"`
	DeviceID         string `json:"device_id"`

	captchaInput
}

func (a *API) handleMigratePage(w http.ResponseWriter, r *http.Request) {
	if !a.cfg.MigrateEnabled {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte(renderMigratePage(a.cfg)))
}

func (a *API) handleMigrateStart(w http.ResponseWriter, r *http.Request) {
	if !a.cfg.MigrateEnabled || a.migrate == nil {
		http.NotFound(w, r)
		return
	}
	if !requireJSON(w, r) {
		return
	}
	ip := clientIP(r)
	if !a.ipLimiter.Allow("migrate:" + ip) {
		writeError(w, http.StatusTooManyRequests, "rate_limited", "操作太频繁，请稍后再试")
		return
	}

	var req migrateStartRequest
	if err := decodeJSONLenient(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "请求格式不正确")
		return
	}
	if err := a.verifyCaptcha(ip, req.captchaInput); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_captcha", "人机验证未通过")
		return
	}

	officialUser := strings.TrimSpace(req.OfficialUsername)
	officialPass := req.OfficialPassword
	if officialUser == "" || officialPass == "" {
		writeError(w, http.StatusBadRequest, "invalid_input", "请填写旧服务器账号与密码")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
	defer cancel()

	client := official.New(a.cfg.MigrateSourceHost)
	profile, err := client.Login(ctx, officialUser, officialPass, "community-migrate-"+sanitizeDeviceToken(req.DeviceID))
	if err != nil {
		if err == official.ErrUnauthorized {
			writeError(w, http.StatusBadRequest, "official_rejected", "旧服务器拒绝了这组账号密码")
			return
		}
		writeError(w, http.StatusBadGateway, "official_unreachable", "连不上旧服务器："+err.Error())
		return
	}
	officialUID := strings.TrimSpace(profile.UID)
	if officialUID == "" {
		officialUID = strings.TrimSpace(profile.Ncuid)
	}
	if officialUID == "" {
		writeError(w, http.StatusBadGateway, "official_no_uid", "旧服务器没有返回账号 UID")
		return
	}

	store := a.migrate.Store()
	displayName := firstNonEmpty(profile.DisplayName, profile.Username, req.Username)
	userID, state, errKey, errMsg := a.resolveMigrateAccount(ctx, req, store, officialUID, displayName)
	if errMsg != "" {
		writeError(w, http.StatusConflict, errKey, errMsg)
		return
	}

	if latest, err := store.LatestJobByUser(ctx, userID); err == nil && latest != nil && latest.Status == migrate.StatusRunning {
		writeError(w, http.StatusConflict, "job_running", "这个账号已有迁移任务在运行，请等待它结束")
		return
	}

	job, err := store.CreateJob(ctx, userID, officialUID, a.cfg.MigrateSourceHost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "创建迁移任务失败")
		return
	}
	a.migrate.Start(job.ID, migrate.Credentials{Username: officialUser, Password: officialPass})

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":      true,
		"job":     job.ID,
		"account": state,
	})
}

// resolveMigrateAccount 处理三种情况：认领占位账号、创建新账号、绑定已注册账号。
func (a *API) resolveMigrateAccount(ctx context.Context, req migrateStartRequest, store *migrate.Store, officialUID, officialDisplayName string) (userID, state, errKey, errMsg string) {
	mode := strings.ToLower(strings.TrimSpace(req.Mode))
	if mode == "existing" {
		identifier := strings.TrimSpace(req.Identifier)
		if identifier == "" || req.Password == "" {
			return "", "", "invalid_input", "请填写社区账号与密码"
		}
		user, err := a.users.GetByEmailOrUsername(ctx, strings.ToLower(identifier))
		if err != nil {
			return "", "", "account_not_found", "社区账号不存在或者密码不正确"
		}
		ok, err := auth.VerifyPassword(req.Password, user.PasswordHash)
		if err != nil || !ok {
			return "", "", "bad_password", "社区账号不存在或者密码不正确"
		}
		bound, _, err := store.UserIDByOfficialUID(ctx, officialUID)
		if err != nil {
			return "", "", "db_error", "数据库错误"
		}
		if bound != "" && bound != user.ID {
			return "", "", "official_taken", "这个旧账号已被另一个社区账号绑定"
		}
		if err := store.BindOfficialUID(ctx, user.ID, officialUID); err != nil {
			return "", "", "db_error", "绑定旧账号失败"
		}
		return user.ID, "bound", "", ""
	}

	// 默认模式：迁移即注册。
	email := strings.ToLower(strings.TrimSpace(req.Email))
	username := strings.ToLower(strings.TrimSpace(req.Username))
	password := strings.TrimSpace(req.Password)
	if !isValidEmail(email) {
		return "", "", "invalid_email", "邮箱格式不正确"
	}
	if !isValidUsername(username) {
		return "", "", "invalid_username", "用户名需为 3-20 位小写字母/数字/下划线"
	}
	if len(password) < 8 {
		return "", "", "invalid_password", "密码至少 8 位"
	}
	hash, err := auth.HashPassword(password)
	if err != nil {
		return "", "", "hash_failed", "密码处理失败"
	}

	existingID, isShadow, err := store.UserIDByOfficialUID(ctx, officialUID)
	if err != nil {
		return "", "", "db_error", "数据库错误"
	}
	if existingID != "" {
		if !isShadow {
			return "", "", "official_taken", "这个旧账号已经迁移过，请直接用原有社区账号登录"
		}
		if !a.cfg.MigrateAllowClaim {
			return "", "", "claim_disabled", "该账号已被他人迁移过并包含聊天记录，管理员关闭了直接认领"
		}
		if err := store.ClaimShadow(ctx, existingID, email, username, hash, username); err != nil {
			if isUniqueViolation(err, "users_email_key") {
				return "", "", "email_taken", "该邮箱已被注册"
			}
			if isUniqueViolation(err, "users_username_key") {
				return "", "", "username_taken", "该用户名已被占用"
			}
			return "", "", "db_error", "认领账号失败"
		}
		a.setTokenVersionCache(existingID, 1)
		return existingID, "claimed", "", ""
	}

	uid := strings.ToUpper(strings.TrimSpace(officialUID))
	if uid == "" || len(uid) > 20 {
		uid = ""
	}
	if uid != "" {
		if _, err := a.users.GetByUID(ctx, uid); err == nil {
			uid = ""
		}
	}
	if uid == "" {
		generated, genErr := newPublicID("USR-", 8)
		if genErr != nil {
			return "", "", "id_failed", "生成账号标识失败"
		}
		uid = generated
	}

	user := &data.User{
		ID:              nanoid.New(),
		UID:             uid,
		Ncuid:           uid,
		Email:           email,
		Username:        username,
		DisplayName:     firstNonEmpty(officialDisplayName, username),
		PasswordHash:    hash,
		TokenVersion:    1,
		CoinBalance:     0,
		ReputationScore: 110,
	}
	if err := a.users.Create(ctx, user); err != nil {
		if isUniqueViolation(err, "users_email_key") {
			return "", "", "email_taken", "该邮箱已被注册"
		}
		if isUniqueViolation(err, "users_username_key") {
			return "", "", "username_taken", "该用户名已被占用"
		}
		return "", "", "db_error", "创建账号失败"
	}
	a.setTokenVersionCache(user.ID, user.TokenVersion)
	a.joinOfficialGroup(ctx, user.ID)
	if err := store.BindOfficialUID(ctx, user.ID, officialUID); err != nil {
		return "", "", "db_error", "绑定旧账号失败"
	}
	return user.ID, "created", "", ""
}

func (a *API) handleMigrateStatus(w http.ResponseWriter, r *http.Request) {
	if !a.cfg.MigrateEnabled || a.migrate == nil {
		http.NotFound(w, r)
		return
	}
	jobID := strings.TrimSpace(r.URL.Query().Get("job"))
	if jobID == "" {
		writeError(w, http.StatusBadRequest, "invalid_job", "缺少任务编号")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	store := a.migrate.Store()
	job, err := store.GetJob(ctx, jobID)
	if err != nil {
		writeError(w, http.StatusNotFound, "job_not_found", "任务不存在")
		return
	}
	events, _ := store.ListEvents(ctx, jobID, 300)
	stats := map[string]int64{}
	if strings.TrimSpace(job.Stats) != "" {
		_ = json.Unmarshal([]byte(job.Stats), &stats)
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok": true,
		"job": map[string]interface{}{
			"id":      job.ID,
			"status":  job.Status,
			"stage":   job.Stage,
			"percent": job.Percent,
			"error":   job.Error,
			"stats":   stats,
			"events":  events,
		},
	})
}

func sanitizeDeviceToken(v string) string {
	v = strings.TrimSpace(v)
	if v == "" {
		return "web"
	}
	var b strings.Builder
	for _, r := range v {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' {
			b.WriteRune(r)
		}
		if b.Len() >= 40 {
			break
		}
	}
	if b.Len() == 0 {
		return "web"
	}
	return b.String()
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
