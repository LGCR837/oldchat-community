package httpapi

import (
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"metrochat/internal/data"
	"metrochat/internal/mail"
	"metrochat/internal/verify"
)

type emailCodeRequest struct {
	Email          string `json:"email"`
	Username       string `json:"username"`
	TurnstileToken string `json:"turnstile_token"`
	// GeeTest 4.0 验证参数
	GeeTestLotNumber     string         `json:"geetest_lot_number"`
	GeeTestCaptchaOutput string         `json:"geetest_captcha_output"`
	GeeTestPassToken     string         `json:"geetest_pass_token"`
	GeeTestGenTime       geetestGenTime `json:"geetest_gen_time"`
}

func (a *API) handleEmailCode(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	ip := clientIP(r)
	if !a.ipLimiter.Allow("email:" + ip) {
		writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests")
		return
	}

	var req emailCodeRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	email := strings.ToLower(strings.TrimSpace(req.Email))
	username := strings.ToLower(strings.TrimSpace(req.Username))
	purpose := "register"
	if username != "" {
		purpose = "reset"
	}
	if !isValidEmail(email) {
		writeError(w, http.StatusBadRequest, "invalid_email", "邮箱格式不正确，请检查后重试")
		return
	}
	if username == "" && !isSupportedRegistrationEmail(email) {
		writeError(w, http.StatusBadRequest, "invalid_email_domain", "仅支持 QQ 邮箱注册")
		return
	}
	// 注册用途：检查注册名额上限、注销冷却期与是否已注册
	if username == "" {
		if a.cfg.RegistrationLimit > 0 {
			count, err := a.users.Count(r.Context())
			if err == nil && count >= a.cfg.RegistrationLimit {
				writeError(w, http.StatusForbidden, "registration_closed", "注册名额已满，暂时无法注册")
				return
			}
		}
		inCooldown, until, err := a.users.GetDeletedAccountCooldown(r.Context(), email)
		if err == nil && inCooldown {
			daysRemaining := int(math.Ceil(time.Until(until).Hours() / 24.0))
			if daysRemaining < 1 {
				daysRemaining = 1
			}
			msg := fmt.Sprintf("该邮箱关联账号已注销，正处于 10 天安全冷却期内（还剩 %d 天），期间暂不可重新注册", daysRemaining)
			writeError(w, http.StatusForbidden, "email_in_cooldown", msg)
			return
		}

		existing, err := a.users.GetByEmailOrUsername(r.Context(), email)
		if err == nil && existing != nil {
			writeError(w, http.StatusConflict, "email_registered", "该邮箱已注册")
			return
		}
	}
	if username != "" {
		user, err := a.users.GetByEmailOrUsername(r.Context(), username)
		if err != nil {
			if err == data.ErrNotFound {
				writeError(w, http.StatusBadRequest, "invalid_account", "该账号不存在，请检查用户名或邮箱")
				return
			}
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
		if !strings.EqualFold(user.Email, email) {
			writeError(w, http.StatusBadRequest, "invalid_account", "该邮箱与账号不匹配，请检查后重试")
			return
		}
		if !strings.EqualFold(user.Username, username) {
			writeError(w, http.StatusBadRequest, "invalid_account", "该用户名与邮箱不匹配，请检查后重试")
			return
		}
	}
	// 人机验证：GeeTest 或 Turnstile，两者至少通过其一（图形验证码已下线）。
	turnstile := strings.TrimSpace(req.TurnstileToken)
	geetestGiven := strings.TrimSpace(req.GeeTestLotNumber) != "" ||
		strings.TrimSpace(req.GeeTestCaptchaOutput) != "" ||
		strings.TrimSpace(req.GeeTestPassToken) != ""
	geetestOK := false
	if geetestGiven {
		if err := verify.VerifyGeeTest(req.GeeTestLotNumber, req.GeeTestCaptchaOutput,
			req.GeeTestPassToken, string(req.GeeTestGenTime), ip); err == nil {
			geetestOK = true
		}
	}
	if !geetestOK {
		if turnstile == "" {
			writeError(w, http.StatusBadRequest, "invalid_turnstile", "人机验证未通过")
			return
		}
		if err := verify.VerifyTurnstile(a.cfg.TurnstileSecretKey, turnstile, ip); err != nil {
			writeError(w, http.StatusBadRequest, "invalid_turnstile", "invalid turnstile")
			return
		}
	}

	a.sendLimiter.PruneOlderThan(10 * time.Minute)
	ok, wait := a.sendLimiter.Allow(email+"#"+purpose, 120*time.Second)
	if !ok {
		if wait > 0 {
			w.Header().Set("Retry-After", strconv.Itoa(int(wait.Seconds())))
		}
		writeError(w, http.StatusTooManyRequests, "email_cooldown", "cooldown")
		return
	}

	code := verify.RandomDigits(6)
	if err := mail.SendCode(email, code); err != nil {
		writeError(w, http.StatusInternalServerError, "mail_failed", "send mail failed")
		return
	}
	a.emailCodes.PruneExpired()
	a.emailCodes.SetForPurpose(email, purpose, code, 10*time.Minute)
	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}
