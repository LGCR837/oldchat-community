package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"metrochat/internal/auth"
	"metrochat/internal/data"
)

type ctxKey string

const claimsKey ctxKey = "authClaims"
const tokenVersionCacheTTL = 30 * time.Second
const banStatusCacheTTL = 5 * time.Second

var accessTokenParser = jwt.NewParser(jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))

type tokenVersionEntry struct {
	version   int
	expiresAt time.Time
}

type banStatusEntry struct {
	banned    bool
	expiresAt time.Time
}

func (a *API) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		claims, ok := a.authenticateFromHeader(r)
		if !ok {
			w.Header().Set("WWW-Authenticate", "Bearer")
			w.Header().Set("Cache-Control", "private, no-store")
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		if !a.validateTokenVersion(r.Context(), claims.Subject, claims.Version) {
			w.Header().Set("WWW-Authenticate", "Bearer")
			w.Header().Set("Cache-Control", "private, no-store")
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		banned, err := a.isUserBannedCached(r.Context(), claims.Subject)
		if err != nil {
			// 无法确认封禁状态时必须拒绝，不能在数据库故障时绕过安全检查。
			writeError(w, http.StatusServiceUnavailable, "auth_unavailable", "authentication temporarily unavailable")
			return
		}
		if banned {
			writeError(w, http.StatusForbidden, "user_banned", "user banned")
			return
		}

		ctx := context.WithValue(r.Context(), claimsKey, claims)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func claimsFromContext(ctx context.Context) (*auth.AccessClaims, bool) {
	val := ctx.Value(claimsKey)
	claims, ok := val.(*auth.AccessClaims)
	return claims, ok
}

func (a *API) authenticateFromHeader(r *http.Request) (*auth.AccessClaims, bool) {
	header := r.Header.Get("Authorization")
	parts := strings.SplitN(header, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		return nil, false
	}
	tokenStr := strings.TrimSpace(parts[1])
	if tokenStr == "" {
		return nil, false
	}
	return a.authenticateFromToken(tokenStr)
}

func (a *API) authenticateFromToken(tokenStr string) (*auth.AccessClaims, bool) {
	claims := &auth.AccessClaims{}
	token, err := accessTokenParser.ParseWithClaims(tokenStr, claims, func(token *jwt.Token) (interface{}, error) {
		return a.cfg.JWTSecret, nil
	})
	if err != nil || !token.Valid {
		return nil, false
	}
	if claims.Subject == "" {
		return nil, false
	}
	if claims.Issuer != "" && claims.Issuer != a.cfg.JWTIssuer {
		match := false
		for _, legacy := range a.cfg.JWTIssuerLegacy {
			if legacy != "" && claims.Issuer == legacy {
				match = true
				break
			}
		}
		if !match {
			return nil, false
		}
	}
	return claims, true
}

func (a *API) validateTokenVersion(ctx context.Context, userID string, tokenVersion int) bool {
	if userID == "" {
		return false
	}
	if tokenVersion <= 0 {
		return false
	}
	version, err := a.getTokenVersion(ctx, userID)
	if err != nil {
		return false
	}
	// 允许当前版本及最近 9 个版本（共 10 代），给并发请求和客户端刷新令牌留出宽滑动窗口，
	// 与登录时保留最近 10 个会话的策略配套；未来版本和 0 始终拒绝；下界最低为 1。
	minimumVersion := version - 9
	if minimumVersion < 1 {
		minimumVersion = 1
	}
	return tokenVersion >= minimumVersion && tokenVersion <= version
}

func (a *API) getTokenVersion(ctx context.Context, userID string) (int, error) {
	if userID == "" {
		return 0, data.ErrNotFound
	}
	now := time.Now()
	a.tokenVersionMu.Lock()
	entry, ok := a.tokenVersionCache[userID]
	if ok && now.Before(entry.expiresAt) {
		version := entry.version
		a.tokenVersionMu.Unlock()
		return version, nil
	}
	a.tokenVersionMu.Unlock()

	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	version, err := a.users.GetTokenVersion(cctx, userID)
	if err != nil {
		return 0, err
	}
	a.tokenVersionMu.Lock()
	a.tokenVersionCache[userID] = tokenVersionEntry{
		version:   version,
		expiresAt: now.Add(tokenVersionCacheTTL),
	}
	a.tokenVersionMu.Unlock()
	return version, nil
}

func (a *API) setTokenVersionCache(userID string, version int) {
	if userID == "" {
		return
	}
	a.tokenVersionMu.Lock()
	a.tokenVersionCache[userID] = tokenVersionEntry{
		version:   version,
		expiresAt: time.Now().Add(tokenVersionCacheTTL),
	}
	a.tokenVersionMu.Unlock()
}

func (a *API) clearTokenVersionCache(userID string) {
	if userID == "" {
		return
	}
	a.tokenVersionMu.Lock()
	delete(a.tokenVersionCache, userID)
	a.tokenVersionMu.Unlock()
}

func (a *API) isUserBannedCached(ctx context.Context, userID string) (bool, error) {
	if userID == "" {
		return false, data.ErrNotFound
	}
	now := time.Now()
	a.banStatusMu.Lock()
	entry, ok := a.banStatusCache[userID]
	if ok && now.Before(entry.expiresAt) {
		a.banStatusMu.Unlock()
		return entry.banned, nil
	}
	a.banStatusMu.Unlock()

	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	banned, err := a.devices.IsUserBanned(cctx, userID)
	if err != nil {
		return false, err
	}
	a.banStatusMu.Lock()
	a.banStatusCache[userID] = banStatusEntry{
		banned: banned, expiresAt: now.Add(banStatusCacheTTL),
	}
	a.banStatusMu.Unlock()
	return banned, nil
}

func (a *API) clearBanStatusCache(userID string) {
	if userID == "" {
		return
	}
	a.banStatusMu.Lock()
	delete(a.banStatusCache, userID)
	a.banStatusMu.Unlock()
}
