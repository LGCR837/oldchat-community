package httpapi

import (
	"context"
	"fmt"
	"html/template"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/auth"
	"metrochat/internal/data"
	"metrochat/internal/mail"
	"metrochat/internal/verify"
)

type oauthAccountPageData struct {
	Title     string
	Mode      string
	RequestID string
	CSRF      string
	Username  string
	Email     string
	CodeSent  bool
	Error     string
	Success   string
}

var oauthAccountTemplate = template.Must(template.New("account").Parse(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{{.Title}} - Oldchat</title><style>
*{box-sizing:border-box}body{margin:0;background:#f3f4f6;color:#171717;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}.shell{max-width:460px;margin:5vh auto;padding:18px}.card{background:#fff;border:1px solid #ddd;padding:26px;box-shadow:8px 8px 0 #171717}.brand{font-size:24px;font-weight:800}.sub{color:#666}.err,.ok{padding:10px;margin:14px 0;border:1px solid}.err{background:#fee2e2;border-color:#dc2626}.ok{background:#dcfce7;border-color:#16a34a}label{display:block;font-weight:700;margin-top:13px}input{width:100%;padding:11px;margin-top:5px;border:1px solid #999;font-size:16px}.captcha{display:flex;gap:8px;align-items:end}.captcha label{flex:1}.captcha img{height:42px;border:1px solid #aaa;background:#f8fafc}button{width:100%;padding:12px;margin-top:17px;border:0;background:#171717;color:#fff;font-size:16px;font-weight:700}.secondary{background:#fff;color:#171717;border:1px solid #171717}a{color:#175cd3;text-decoration:none}</style></head><body><main class="shell"><section class="card"><div class="brand">Oldchat ID</div><p class="sub">{{.Title}}</p>{{if .Error}}<div class="err">{{.Error}}</div>{{end}}{{if .Success}}<div class="ok">{{.Success}}</div>{{end}}
{{if .Success}}<p><a href="/oauth/authorize/resume?request_id={{.RequestID}}">返回登录并继续授权</a></p>{{else}}<form method="post" action="/oauth/{{.Mode}}"><input type="hidden" name="request_id" value="{{.RequestID}}"><input type="hidden" name="csrf" value="{{.CSRF}}"><label>用户名<input name="username" value="{{.Username}}" autocomplete="username" required></label><label>QQ/126/163 邮箱<input type="email" name="email" value="{{.Email}}" autocomplete="email" required></label>{{if eq .Mode "register"}}<label>密码（至少 8 位）<input type="password" name="password" autocomplete="new-password" required></label>{{else}}<label>新密码（至少 8 位）<input type="password" name="password" autocomplete="new-password" required></label>{{end}}
{{if not .CodeSent}}<button name="action" value="send_code">发送邮箱验证码</button>{{else}}<label>邮箱验证码<input name="email_code" inputmode="numeric" required></label><button name="action" value="{{.Mode}}">{{if eq .Mode "register"}}创建账户并继续{{else}}重置密码{{end}}</button>{{end}}</form><p><a href="/oauth/authorize/resume?request_id={{.RequestID}}">返回登录</a></p>{{end}}</section></main></body></html>`))

func (a *API) handleOAuthRegisterGet(w http.ResponseWriter, r *http.Request) {
	a.renderOAuthAccount(w, r, "register", oauthAccountPageData{})
}
func (a *API) handleOAuthResetGet(w http.ResponseWriter, r *http.Request) {
	a.renderOAuthAccount(w, r, "reset", oauthAccountPageData{})
}

func (a *API) handleOAuthAuthorizeResume(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	req, client, csrf, ok := a.loadOAuthPageRequest(ctx, r, r.URL.Query().Get("request_id"), "")
	if !ok {
		writeOAuthError(w, 400, "invalid_request", "authorization request expired")
		return
	}
	setOAuthPageHeaders(w)
	_ = oauthPageTemplate.Execute(w, oauthPageData{Title: "登录", RequestID: req.ID, CSRF: csrf, ClientName: client.Name, RegisterURL: "/oauth/register?request_id=" + url.QueryEscape(req.ID), ResetURL: "/oauth/reset?request_id=" + url.QueryEscape(req.ID)})
}

func (a *API) handleOAuthRegisterPost(w http.ResponseWriter, r *http.Request) {
	a.handleOAuthAccountPost(w, r, "register")
}
func (a *API) handleOAuthResetPost(w http.ResponseWriter, r *http.Request) {
	a.handleOAuthAccountPost(w, r, "reset")
}

func (a *API) handleOAuthAccountPost(w http.ResponseWriter, r *http.Request, mode string) {
	if !parseOAuthForm(w, r) {
		writeOAuthError(w, 400, "invalid_request", "invalid form")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	req, client, csrf, ok := a.loadOAuthPageRequest(ctx, r, r.FormValue("request_id"), r.FormValue("csrf"))
	_ = client
	if !ok {
		writeOAuthError(w, 403, "access_denied", "authorization request expired or CSRF failed")
		return
	}
	username := strings.ToLower(strings.TrimSpace(r.FormValue("username")))
	email := strings.ToLower(strings.TrimSpace(r.FormValue("email")))
	password := r.FormValue("password")
	base := oauthAccountPageData{Mode: mode, RequestID: req.ID, CSRF: csrf, Username: username, Email: email}
	if r.FormValue("action") == "send_code" {
		if !isValidUsername(username) || !isValidEmail(email) {
			base.Error = "用户名或邮箱格式不正确"
			a.renderOAuthAccount(w, r, mode, base)
			return
		}
		if mode == "register" {
			inCooldown, until, err := a.users.GetDeletedAccountCooldown(ctx, email)
			if err == nil && inCooldown {
				daysRemaining := int(math.Ceil(time.Until(until).Hours() / 24.0))
				if daysRemaining < 1 {
					daysRemaining = 1
				}
				base.Error = fmt.Sprintf("该邮箱关联账号已注销，正处于 10 天安全冷却期内（还剩 %d 天），期间暂不可重新注册", daysRemaining)
				a.renderOAuthAccount(w, r, mode, base)
				return
			}
		}
		if allowed, _ := a.sendLimiter.Allow(email+"#"+mode, 120*time.Second); !allowed {
			base.Error = "发送过于频繁，请稍后再试"
			a.renderOAuthAccount(w, r, mode, base)
			return
		}
		accountMatches := true
		if mode == "reset" {
			user, err := a.users.GetByEmailOrUsername(ctx, username)
			accountMatches = err == nil && strings.EqualFold(user.Email, email)
		}
		if accountMatches {
			code := verify.RandomDigits(6)
			if code == "" || mail.SendCode(email, code) != nil {
				base.Error = "验证码发送失败"
				a.renderOAuthAccount(w, r, mode, base)
				return
			}
			a.emailCodes.SetForPurpose(email, mode, code, 10*time.Minute)
		}
		// 无论账号是否匹配都返回相同页面，避免枚举用户。
		base.CodeSent = true
		a.renderOAuthAccount(w, r, mode, base)
		return
	}
	if len(password) < 8 || !a.emailCodes.VerifyForPurpose(email, mode, r.FormValue("email_code")) {
		base.Error = "密码不足 8 位或邮箱验证码无效"
		a.renderOAuthAccount(w, r, mode, base)
		return
	}
	if mode == "register" {
		inCooldown, until, err := a.users.GetDeletedAccountCooldown(ctx, email)
		if err == nil && inCooldown {
			daysRemaining := int(math.Ceil(time.Until(until).Hours() / 24.0))
			if daysRemaining < 1 {
				daysRemaining = 1
			}
			base.Error = fmt.Sprintf("该邮箱关联账号已注销，正处于 10 天安全冷却期内（还剩 %d 天），期间暂不可重新注册", daysRemaining)
			a.renderOAuthAccount(w, r, mode, base)
			return
		}
		if a.cfg.RegistrationLimit > 0 {
			count, _ := a.users.Count(ctx)
			if count >= a.cfg.RegistrationLimit {
				base.Error = "当前已关闭注册"
				a.renderOAuthAccount(w, r, mode, base)
				return
			}
		}
		hash, err := auth.HashPassword(password)
		if err != nil {
			base.Error = "密码处理失败"
			a.renderOAuthAccount(w, r, mode, base)
			return
		}
		uid, err := newPublicID("USR-", 8)
		if err != nil {
			base.Error = "账户创建失败"
			a.renderOAuthAccount(w, r, mode, base)
			return
		}
		user := &data.User{ID: nanoid.New(), UID: uid, Email: email, Username: username, DisplayName: username, PasswordHash: hash, TokenVersion: 1, CoinBalance: 0, ReputationScore: 110}
		if err = a.users.Create(ctx, user); err != nil {
			base.Error = "用户名或邮箱已被使用"
			a.renderOAuthAccount(w, r, mode, base)
			return
		}
		a.setTokenVersionCache(user.ID, user.TokenVersion)
		a.joinOfficialGroup(ctx, user.ID)
		if err = a.oauth.SetAuthorizationUser(ctx, req.ID, user.ID); err != nil {
			writeOAuthError(w, 500, "server_error", "authorization failed")
			return
		}
		setOAuthPageHeaders(w)
		_ = oauthPageTemplate.Execute(w, oauthPageData{Title: "授权确认", RequestID: req.ID, CSRF: csrf, ClientName: client.Name, Scope: req.Scope, Consent: true})
		return
	}
	user, err := a.users.GetByEmailOrUsername(ctx, username)
	if err != nil || !strings.EqualFold(user.Email, email) {
		base.Error = "账号与邮箱不匹配"
		a.renderOAuthAccount(w, r, mode, base)
		return
	}
	hash, err := auth.HashPassword(password)
	if err != nil {
		base.Error = "密码重置失败"
		a.renderOAuthAccount(w, r, mode, base)
		return
	}
	version, err := a.users.ResetPasswordAndRevokeAll(ctx, user.ID, hash)
	if err != nil {
		base.Error = "密码重置失败，请稍后重试"
		a.renderOAuthAccount(w, r, mode, base)
		return
	}
	a.setTokenVersionCache(user.ID, version)
	base.Success = "密码已重置，所有旧会话已撤销。"
	a.renderOAuthAccount(w, r, mode, base)
}

func (a *API) renderOAuthAccount(w http.ResponseWriter, r *http.Request, mode string, data oauthAccountPageData) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	requestID := data.RequestID
	if requestID == "" {
		requestID = r.URL.Query().Get("request_id")
	}
	req, _, csrf, ok := a.loadOAuthPageRequest(ctx, r, requestID, data.CSRF)
	if !ok {
		writeOAuthError(w, 400, "invalid_request", "authorization request expired")
		return
	}
	if data.Mode == "" {
		data.Mode = mode
	}
	data.RequestID = req.ID
	data.CSRF = csrf
	if data.Title == "" {
		if mode == "register" {
			data.Title = "注册 Oldchat 账户"
		} else {
			data.Title = "找回密码"
		}
	}
	if !data.CodeSent && data.Success == "" {
		// 图形验证码已下线；发码由 Turnstile/GeeTest 人机验证保护（页面 JS 处理）。
	}
	setOAuthPageHeaders(w)
	_ = oauthAccountTemplate.Execute(w, data)
}

func (a *API) loadOAuthPageRequest(ctx context.Context, r *http.Request, requestID, submittedCSRF string) (*data.OAuthAuthorizationRequest, *data.OAuthClient, string, bool) {
	req, err := a.oauth.GetAuthorizationRequest(ctx, requestID)
	if err != nil || time.Now().After(req.ExpiresAt) {
		return nil, nil, "", false
	}
	client, err := a.oauth.GetClient(ctx, req.ClientID)
	if err != nil || !client.Active {
		return nil, nil, "", false
	}
	cookie, err := r.Cookie(oauthCSRFCookie)
	if err != nil {
		return nil, nil, "", false
	}
	csrf := cookie.Value
	if submittedCSRF != "" && (!auth.ConstantTimeEqual(csrf, submittedCSRF) || !auth.ConstantTimeEqual(req.CSRFHash, auth.HashToken(submittedCSRF))) {
		return nil, nil, "", false
	}
	if !auth.ConstantTimeEqual(req.CSRFHash, auth.HashToken(csrf)) {
		return nil, nil, "", false
	}
	return req, client, csrf, true
}
