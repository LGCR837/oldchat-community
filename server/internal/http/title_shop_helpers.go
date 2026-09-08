package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"metrochat/internal/data"
)

const shopTokenCookie = "shop_token"

func extractShopToken(r *http.Request) string {
	if r == nil {
		return ""
	}
	header := strings.TrimSpace(r.Header.Get("Authorization"))
	if header != "" {
		parts := strings.SplitN(header, " ", 2)
		if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
			return strings.TrimSpace(parts[1])
		}
	}
	if token := strings.TrimSpace(r.URL.Query().Get("token")); token != "" {
		return token
	}
	if token := strings.TrimSpace(r.FormValue("token")); token != "" {
		return token
	}
	if cookie, err := r.Cookie(shopTokenCookie); err == nil {
		return strings.TrimSpace(cookie.Value)
	}
	return ""
}

func (a *API) shopUserFromToken(ctx context.Context, token string) (*data.User, string) {
	if token == "" {
		return nil, ""
	}
	claims, ok := a.authenticateFromToken(token)
	if !ok || claims == nil {
		return nil, "登录已失效，请重新登录"
	}
	if !a.validateTokenVersion(ctx, claims.Subject, claims.Version) {
		return nil, "登录已失效，请重新登录"
	}
	banned, err := a.devices.IsUserBanned(ctx, claims.Subject)
	if err == nil && banned {
		return nil, "账号已被封禁"
	}
	user, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		return nil, "账号不存在"
	}
	return user, ""
}

func sanitizeShopTitle(value string) string {
	title := strings.TrimSpace(value)
	if title == "" {
		return ""
	}
	title = strings.ReplaceAll(title, "\r", " ")
	title = strings.ReplaceAll(title, "\n", " ")
	if utf8.RuneCountInString(title) > 20 {
		runes := []rune(title)
		title = string(runes[:20])
	}
	return title
}

func setShopTokenCookie(w http.ResponseWriter, r *http.Request, token string, ttl time.Duration) {
	if w == nil {
		return
	}
	secure := isHTTPS(r)
	cookie := &http.Cookie{
		Name:     shopTokenCookie,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteLaxMode,
	}
	if ttl > 0 {
		cookie.MaxAge = int(ttl.Seconds())
		cookie.Expires = time.Now().Add(ttl)
	}
	http.SetCookie(w, cookie)
}

func clearShopTokenCookie(w http.ResponseWriter, r *http.Request) {
	if w == nil {
		return
	}
	secure := isHTTPS(r)
	http.SetCookie(w, &http.Cookie{
		Name:     shopTokenCookie,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
		Expires:  time.Unix(0, 0),
	})
}
