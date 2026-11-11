package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

type adminLoginReq struct {
	Secret string `json:"secret"`
}

func (a *API) handleAdminAPILogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
		return
	}
	var req adminLoginReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.Secret) == "" {
		writeError(w, http.StatusBadRequest, "invalid_request", "secret required")
		return
	}
	if !secureEqual(req.Secret, a.cfg.AdminPassword) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "invalid secret")
		return
	}

	token := a.adminSessions.New(adminSessionTTL)
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().Add(adminSessionTTL),
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"code":    200,
		"message": "success",
	})
}

func (a *API) handleAdminAPILogout(w http.ResponseWriter, r *http.Request) {
	cookie, err := r.Cookie(adminCookie)
	if err == nil && cookie != nil {
		a.adminSessions.Delete(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "logged out"})
}

func (a *API) adminAPIMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.isAdmin(r) {
			writeError(w, http.StatusUnauthorized, "unauthorized", "admin session required")
			return
		}
		next(w, r)
	}
}
