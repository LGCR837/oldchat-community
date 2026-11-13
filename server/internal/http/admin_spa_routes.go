package httpapi

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func (a *API) handleAdminSPAPage(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		a.renderAdminLoginPage(w, r, "")
		return
	}

	spaPaths := []string{
		filepath.Join(a.webAppDir, "admin", "index.html"),
		filepath.Join("webapp", "admin", "index.html"),
		filepath.Join("server", "webapp", "admin", "index.html"),
		filepath.Join("/A-chat", "webapp", "admin", "index.html"),
	}
	for _, p := range spaPaths {
		if p == "" {
			continue
		}
		b, err := os.ReadFile(p)
		if err == nil && len(b) > 0 {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(b)
			return
		}
	}
	// 找不到静态文件时回退到旧版 handleAdminIndex
	a.handleAdminIndex(w, r)
}

func (a *API) handleAdminLoginPage(w http.ResponseWriter, r *http.Request) {
	if a.isAdmin(r) {
		http.Redirect(w, r, "/admins", http.StatusSeeOther)
		return
	}
	a.renderAdminLoginPage(w, r, "")
}

func (a *API) handleAdminLoginSubmit(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Redirect(w, r, "/admins", http.StatusSeeOther)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 32<<10)
	if err := r.ParseForm(); err != nil {
		a.renderAdminLoginPage(w, r, "请求解析失败")
		return
	}
	secret := strings.TrimSpace(r.FormValue("secret"))
	username := strings.TrimSpace(r.FormValue("username"))
	password := strings.TrimSpace(r.FormValue("password"))

	authOK := false
	if secret != "" && secureEqual(secret, a.cfg.AdminPassword) {
		authOK = true
	} else if username != "" && password != "" && secureEqual(username, a.cfg.AdminUser) && secureEqual(password, a.cfg.AdminPassword) {
		authOK = true
	}

	if !authOK {
		a.renderAdminLoginPage(w, r, "管理密钥错误")
		return
	}

	token := a.adminSessions.New(adminSessionTTL)
	// 清理旧路径残留的 Cookie
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    "",
		Path:     "/admins",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
	// 设置全局根路径 Cookie
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().Add(adminSessionTTL),
	})

	http.Redirect(w, r, "/admins", http.StatusSeeOther)
}

func (a *API) handleAdminLogoutSubmit(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie(adminCookie); err == nil && cookie != nil {
		a.adminSessions.Delete(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    "",
		Path:     "/admins",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
	http.Redirect(w, r, "/admins/login", http.StatusSeeOther)
}

func (a *API) renderAdminLoginPage(w http.ResponseWriter, r *http.Request, errMsg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = adminSeparateLoginTpl.Execute(w, adminLoginData{Error: errMsg})
}

func (a *API) handleAdminStatic(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/admin/")
	if name == "" || name == "/" || strings.Contains(name, "..") {
		a.handleAdminSPAPage(w, r)
		return
	}
	candidates := []string{
		filepath.Join(a.webAppDir, "admin", filepath.FromSlash(name)),
		filepath.Join("webapp", "admin", filepath.FromSlash(name)),
		filepath.Join("server", "webapp", "admin", filepath.FromSlash(name)),
		filepath.Join("/A-chat", "webapp", "admin", filepath.FromSlash(name)),
	}
	for _, target := range candidates {
		if target == "" {
			continue
		}
		if info, err := os.Stat(target); err == nil && !info.IsDir() {
			http.ServeFile(w, r, target)
			return
		}
	}
	a.handleAdminSPAPage(w, r)
}

func (a *API) registerAdminAPIRoutes(r interface {
	Get(pattern string, h http.HandlerFunc)
	Post(pattern string, h http.HandlerFunc)
	Handle(pattern string, h http.Handler)
}) {
	// SPA 页面与独立登录登出路由
	r.Get("/admins", a.handleAdminSPAPage)
	r.Get("/admin", a.handleAdminSPAPage)
	r.Get("/admins/login", a.handleAdminLoginPage)
	r.Post("/admins/login", a.handleAdminLoginSubmit)
	r.Get("/admins/logout", a.handleAdminLogoutSubmit)
	r.Post("/admins/logout", a.handleAdminLogoutSubmit)
	r.Get("/admin/*", a.handleAdminStatic)

	// Admin REST API
	r.Post("/v1/admin/login", a.handleAdminAPILogin)
	r.Post("/v1/admin/logout", a.handleAdminAPILogout)

	// 以下接口挂载 adminAPIMiddleware
	r.Get("/v1/admin/stats", a.adminAPIMiddleware(a.handleAdminAPIStats))
	r.Get("/v1/admin/metrics/overview", a.adminAPIMiddleware(a.handleAdminAPIMetricsOverview))
	r.Get("/v1/admin/metrics/logs", a.adminAPIMiddleware(a.handleAdminAPIMetricsLogs))
	r.Get("/v1/admin/users", a.adminAPIMiddleware(a.handleAdminAPIUsers))
	r.Post("/v1/admin/users/create", a.adminAPIMiddleware(a.handleAdminAPICreateUser))
	r.Post("/v1/admin/users/reset-password", a.adminAPIMiddleware(a.handleAdminAPIResetPassword))
	r.Post("/v1/admin/users/update-profile", a.adminAPIMiddleware(a.handleAdminAPIUpdateProfile))
	r.Post("/v1/admin/users/adjust-coins", a.adminAPIMiddleware(a.handleAdminAPIAdjustCoins))
	r.Post("/v1/admin/users/set-title", a.adminAPIMiddleware(a.handleAdminAPISetTitle))
	r.Post("/v1/admin/users/kick", a.adminAPIMiddleware(a.handleAdminAPIKickUser))
	r.Post("/v1/admin/users/ban", a.adminAPIMiddleware(a.handleAdminAPIUserBan))
	r.Post("/v1/admin/users/unban", a.adminAPIMiddleware(a.handleAdminAPIUserUnban))
	r.Get("/v1/admin/devices/banned", a.adminAPIMiddleware(a.handleAdminAPIBannedDevices))
	r.Post("/v1/admin/devices/ban", a.adminAPIMiddleware(a.handleAdminAPIBanDevice))
	r.Post("/v1/admin/devices/unban", a.adminAPIMiddleware(a.handleAdminAPIUnbanDevice))
	r.Get("/v1/admin/groups", a.adminAPIMiddleware(a.handleAdminAPIGroups))
	r.Post("/v1/admin/groups/dismiss", a.adminAPIMiddleware(a.handleAdminAPIGroupDismiss))
	r.Get("/v1/admin/reports", a.adminAPIMiddleware(a.handleAdminAPIReports))
	r.Post("/v1/admin/reports/resolve", a.adminAPIMiddleware(a.handleAdminAPIReportResolve))
	r.Get("/v1/admin/court", a.adminAPIMiddleware(a.handleAdminAPICourt))
	r.Post("/v1/admin/court/resolve", a.adminAPIMiddleware(a.handleAdminAPICourtResolve))
	r.Get("/v1/admin/resources", a.adminAPIMiddleware(a.handleAdminAPIResources))
	r.Post("/v1/admin/resources", a.adminAPIMiddleware(a.handleAdminAPIResources))
	r.Get("/v1/admin/logs", a.adminAPIMiddleware(a.handleAdminAPILogs))
	r.Get("/v1/admin/routes", a.adminAPIMiddleware(a.handleAdminAPIRoutesGet))
	r.Post("/v1/admin/routes/toggle", a.adminAPIMiddleware(a.handleAdminAPIRoutesToggle))
	r.Get("/v1/admin/notifications", a.adminAPIMiddleware(a.handleAdminAPINotificationsList))
	r.Post("/v1/admin/notifications/send", a.adminAPIMiddleware(a.handleAdminAPINotificationsSend))
	r.Post("/v1/admin/notifications/delete", a.adminAPIMiddleware(a.handleAdminAPINotificationsDelete))
	r.Get("/v1/admin/appeals", a.adminAPIMiddleware(a.handleAdminAPIAppealsList))
	r.Post("/v1/admin/appeals/respond", a.adminAPIMiddleware(a.handleAdminAPIAppealRespond))
	r.Get("/v1/admin/bugs", a.adminAPIMiddleware(a.handleAdminAPIBugsList))
	r.Post("/v1/admin/bugs/status", a.adminAPIMiddleware(a.handleAdminAPIBugStatus))
	r.Get("/v1/admin/titles/catalog", a.adminAPIMiddleware(a.handleAdminAPITitlesCatalog))
	r.Post("/v1/admin/titles/catalog/add", a.adminAPIMiddleware(a.handleAdminAPITitleAdd))
	r.Post("/v1/admin/titles/catalog/update", a.adminAPIMiddleware(a.handleAdminAPITitleUpdate))
	r.Post("/v1/admin/titles/catalog/delete", a.adminAPIMiddleware(a.handleAdminAPITitleDelete))
	r.Get("/v1/admin/emoji-plaza", a.adminAPIMiddleware(a.handleAdminAPIEmojiPlazaList))
	r.Post("/v1/admin/emoji-plaza/delete", a.adminAPIMiddleware(a.handleAdminAPIEmojiPlazaDelete))
	r.Get("/v1/admin/lua-apps", a.adminAPIMiddleware(a.handleAdminLuaApps))
	r.Post("/v1/admin/lua-apps/toggle", a.adminAPIMiddleware(a.handleAdminLuaAppToggle))
	r.Post("/v1/admin/lua-apps/delete", a.adminAPIMiddleware(a.handleAdminLuaAppDelete))
	r.Get("/v1/admin/settings", a.adminAPIMiddleware(a.handleAdminAPISettingsGet))
	r.Post("/v1/admin/settings", a.adminAPIMiddleware(a.handleAdminAPISettingsSave))

	// 安装包发版与客户端清单
	r.Get("/v1/admin/release", a.adminAPIMiddleware(a.handleAdminAPIReleaseGet))
	r.Post("/v1/admin/release/save", a.adminAPIMiddleware(a.handleAdminAPIReleaseSave))
	r.Post("/v1/admin/release/upload", a.adminAPIMiddleware(a.handleAdminAPIReleaseUpload))
	r.Post("/v1/admin/release/delete", a.adminAPIMiddleware(a.handleAdminAPIReleaseDelete))
	r.Get("/v1/admin/clients", a.adminAPIMiddleware(a.handleAdminAPIClientsGet))
	r.Post("/v1/admin/clients", a.adminAPIMiddleware(a.handleAdminAPIClientsSave))
}
