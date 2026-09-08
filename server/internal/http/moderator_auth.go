package httpapi

import (
	"context"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"metrochat/internal/auth"
	"metrochat/internal/data"
)

const moderatorCookie = "moderator_session"
const moderatorSessionTTL = 12 * time.Hour

type moderatorSessionEntry struct {
	ModeratorID string
	Username    string
	Nickname    string
	ExpiresAt   time.Time
}

type moderatorSessions struct {
	mu       sync.Mutex
	sessions map[string]moderatorSessionEntry
}

func newModeratorSessions() *moderatorSessions {
	return &moderatorSessions{
		sessions: make(map[string]moderatorSessionEntry),
	}
}

func (s *moderatorSessions) New(mod *data.Moderator, ttl time.Duration) string {
	token := randomToken()
	expires := time.Now().Add(ttl)
	s.mu.Lock()
	s.sessions[token] = moderatorSessionEntry{
		ModeratorID: mod.ID,
		Username:    mod.Username,
		Nickname:    mod.Nickname,
		ExpiresAt:   expires,
	}
	s.mu.Unlock()
	return token
}

func (s *moderatorSessions) Get(token string) (moderatorSessionEntry, bool) {
	if token == "" {
		return moderatorSessionEntry{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.sessions[token]
	if !ok {
		return moderatorSessionEntry{}, false
	}
	if sess.ExpiresAt.Before(time.Now()) {
		delete(s.sessions, token)
		return moderatorSessionEntry{}, false
	}
	return sess, true
}

func (s *moderatorSessions) Delete(token string) {
	if token == "" {
		return
	}
	s.mu.Lock()
	delete(s.sessions, token)
	s.mu.Unlock()
}

func (s *moderatorSessions) PruneExpired() {
	now := time.Now()
	s.mu.Lock()
	for token, sess := range s.sessions {
		if sess.ExpiresAt.Before(now) {
			delete(s.sessions, token)
		}
	}
	s.mu.Unlock()
}

func (a *API) isModerator(r *http.Request) (moderatorSessionEntry, bool) {
	if r == nil {
		return moderatorSessionEntry{}, false
	}
	cookie, err := r.Cookie(moderatorCookie)
	if err != nil || cookie.Value == "" {
		return moderatorSessionEntry{}, false
	}
	a.moderatorSessions.PruneExpired()
	return a.moderatorSessions.Get(cookie.Value)
}

// isModeratorOrAdmin 统一鉴权：超级管理员（admin_session）或审核员（moderator_session）均可访问审核台。
func (a *API) isModeratorOrAdmin(r *http.Request) (displayName string, isSuperAdmin bool, ok bool) {
	if a.isAdmin(r) {
		return "超级管理员", true, true
	}
	if sess, ok := a.isModerator(r); ok {
		name := sess.Nickname
		if name == "" {
			name = sess.Username
		}
		return name, false, true
	}
	return "", false, false
}

func (a *API) ensureModerationPost(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return false
	}
	if _, _, ok := a.isModeratorOrAdmin(r); !ok {
		w.WriteHeader(http.StatusUnauthorized)
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	_ = r.ParseMultipartForm(64 << 10)
	_ = r.ParseForm()
	return true
}

// handleModerationLoginPage 审核台独立登录页
func (a *API) handleModerationLoginPage(w http.ResponseWriter, r *http.Request) {
	if _, _, ok := a.isModeratorOrAdmin(r); ok {
		http.Redirect(w, r, "/admins/moderation", http.StatusSeeOther)
		return
	}
	errMsg := strings.TrimSpace(r.URL.Query().Get("err"))
	renderModerationLogin(w, errMsg)
}

// handleModerationLogin 处理审核员登录提交
func (a *API) handleModerationLogin(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if a.adminLoginLimiter != nil && !a.adminLoginLimiter.Allow("mod_login:"+ip) {
		renderModerationLogin(w, "请求过于频繁，请 3 秒后再试")
		return
	}

	username := strings.TrimSpace(r.FormValue("username"))
	password := strings.TrimSpace(r.FormValue("password"))
	if username == "" || password == "" {
		renderModerationLogin(w, "用户名和密码不能为空")
		return
	}

	// 允许主管理员密码在此直接登录（方便超级管理员单点进入审核台）
	if secureEqual(username, a.cfg.AdminUser) && secureEqual(password, a.cfg.AdminPassword) {
		token := a.adminSessions.New(adminSessionTTL)
		http.SetCookie(w, &http.Cookie{
			Name:     adminCookie,
			Value:    token,
			Path:     "/admins",
			HttpOnly: true,
			SameSite: http.SameSiteLaxMode,
			MaxAge:   int(adminSessionTTL.Seconds()),
		})
		http.Redirect(w, r, "/admins/moderation", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	mod, err := a.moderatorStore.GetByUsername(ctx, username)
	if err != nil || mod == nil {
		renderModerationLogin(w, "审核员账号或密码错误")
		return
	}

	if !mod.IsActive {
		renderModerationLogin(w, "该审核员账号已被停用，请联系超级管理员")
		return
	}

	ok, _ := auth.VerifyPassword(password, mod.PasswordHash)
	if !ok {
		renderModerationLogin(w, "审核员账号或密码错误")
		return
	}

	_ = a.moderatorStore.UpdateLastLogin(ctx, mod.ID)
	token := a.moderatorSessions.New(mod, moderatorSessionTTL)
	http.SetCookie(w, &http.Cookie{
		Name:     moderatorCookie,
		Value:    token,
		Path:     "/admins/moderation",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(moderatorSessionTTL.Seconds()),
	})

	http.Redirect(w, r, "/admins/moderation", http.StatusSeeOther)
}

// handleModerationLogout 审核台登出
func (a *API) handleModerationLogout(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie(moderatorCookie); err == nil && cookie.Value != "" {
		a.moderatorSessions.Delete(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name:     moderatorCookie,
		Value:    "",
		Path:     "/admins/moderation",
		HttpOnly: true,
		MaxAge:   -1,
	})
	http.Redirect(w, r, "/admins/moderation/login", http.StatusSeeOther)
}

// --- 超级管理员在 /admins 管理审核员账号 ---

func (a *API) handleAdminModeratorCreate(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}
	username := strings.TrimSpace(r.FormValue("username"))
	password := strings.TrimSpace(r.FormValue("password"))
	nickname := strings.TrimSpace(r.FormValue("nickname"))

	if username == "" || password == "" {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("用户名和密码不能为空")+"#moderators", http.StatusSeeOther)
		return
	}
	if len(password) < 6 {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("密码长度至少为 6 位")+"#moderators", http.StatusSeeOther)
		return
	}

	hash, err := auth.HashPassword(password)
	if err != nil {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("密码加密失败: "+err.Error())+"#moderators", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	mod, err := a.moderatorStore.Create(ctx, username, hash, nickname)
	if err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("用户名已被占用")+"#moderators", http.StatusSeeOther)
			return
		}
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("创建失败: "+err.Error())+"#moderators", http.StatusSeeOther)
		return
	}

	http.Redirect(w, r, "/admins?section=moderators&ok="+url.QueryEscape("审核员账号 "+mod.Username+" 创建成功")+"#moderators", http.StatusSeeOther)
}

func (a *API) handleAdminModeratorToggle(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	active := r.FormValue("active") == "1" || r.FormValue("active") == "true"
	if id == "" {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("审核员 ID 不能为空")+"#moderators", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	if err := a.moderatorStore.UpdateActive(ctx, id, active); err != nil {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("更新状态失败: "+err.Error())+"#moderators", http.StatusSeeOther)
		return
	}
	statusText := "已启用"
	if !active {
		statusText = "已停用"
	}
	http.Redirect(w, r, "/admins?section=moderators&ok="+url.QueryEscape("审核员状态"+statusText)+"#moderators", http.StatusSeeOther)
}

func (a *API) handleAdminModeratorResetPassword(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	newPassword := strings.TrimSpace(r.FormValue("password"))
	if id == "" || newPassword == "" {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("密码不能为空")+"#moderators", http.StatusSeeOther)
		return
	}
	if len(newPassword) < 6 {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("新密码长度至少为 6 位")+"#moderators", http.StatusSeeOther)
		return
	}

	hash, err := auth.HashPassword(newPassword)
	if err != nil {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("密码加密失败: "+err.Error())+"#moderators", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	if err := a.moderatorStore.UpdatePassword(ctx, id, hash); err != nil {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("重置密码失败: "+err.Error())+"#moderators", http.StatusSeeOther)
		return
	}
	http.Redirect(w, r, "/admins?section=moderators&ok="+url.QueryEscape("密码重置成功")+"#moderators", http.StatusSeeOther)
}

func (a *API) handleAdminModeratorDelete(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	if id == "" {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("审核员 ID 不能为空")+"#moderators", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	if err := a.moderatorStore.Delete(ctx, id); err != nil {
		http.Redirect(w, r, "/admins?section=moderators&err="+url.QueryEscape("删除审核员失败: "+err.Error())+"#moderators", http.StatusSeeOther)
		return
	}
	http.Redirect(w, r, "/admins?section=moderators&ok="+url.QueryEscape("审核员已删除")+"#moderators", http.StatusSeeOther)
}

func renderModerationLogin(w http.ResponseWriter, errMsg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = moderationLoginTpl.Execute(w, map[string]string{"Error": errMsg})
}

var moderationLoginTpl = template.Must(template.New("moderation_login").Parse(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>内容审核台</title>
  <style>
    :root {
      --bg: #0d1117;
      --card-bg: #161b22;
      --border: #30363d;
      --text: #c9d1d9;
      --text-heading: #f0f6fc;
      --primary: #238636;
      --primary-hover: #2ea043;
      --danger-bg: #3d1d24;
      --danger-border: #da3633;
      --danger-text: #f85149;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 20px;
    }
    .login-card {
      width: 100%;
      max-width: 320px;
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 24px;
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    }
    .alert-error {
      background: var(--danger-bg);
      border: 1px solid var(--danger-border);
      color: var(--danger-text);
      padding: 10px 12px;
      border-radius: 6px;
      font-size: 13px;
      margin-bottom: 16px;
      line-height: 1.4;
      text-align: center;
    }
    .form-group {
      margin-bottom: 14px;
    }
    .form-control {
      width: 100%;
      background: #0d1117;
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 10px 12px;
      color: var(--text-heading);
      font-size: 14px;
      outline: none;
      transition: border-color 0.15s ease-in-out;
    }
    .form-control:focus {
      border-color: #58a6ff;
      box-shadow: 0 0 0 3px rgba(56, 139, 253, 0.2);
    }
    .btn-submit {
      width: 100%;
      background: var(--primary);
      color: #ffffff;
      border: none;
      border-radius: 6px;
      padding: 10px 16px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      margin-top: 4px;
      transition: background 0.15s ease;
    }
    .btn-submit:hover {
      background: var(--primary-hover);
    }
  </style>
</head>
<body>
  <div class="login-card">
    {{if .Error}}
    <div class="alert-error">{{.Error}}</div>
    {{end}}

    <form method="POST" action="/admins/moderation/login">
      <div class="form-group">
        <input type="text" id="username" name="username" class="form-control" placeholder="用户名" required autofocus>
      </div>

      <div class="form-group">
        <input type="password" id="password" name="password" class="form-control" placeholder="密码" required>
      </div>

      <button type="submit" class="btn-submit">登录</button>
    </form>
  </div>
</body>
</html>`))
